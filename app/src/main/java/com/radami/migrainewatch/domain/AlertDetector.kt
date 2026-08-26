package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.model.PressureReading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One pressure event.
 *
 * @param start when the pressure last turned, and [end] when it finished turning — the real
 *   extremes, so the shading on the chart covers the whole rise or drop however long it took.
 * @param delta the largest swing inside any *24-hour* window of the event, which is not the
 *   swing from [start] to [end]: an event can run longer than a day, and this one is the
 *   figure the threshold was actually tested against. Reporting the start-to-end swing instead
 *   meant showing a number that no 24-hour period ever reached, so the same event could be
 *   labelled 11.1 hPa and then vanish when the threshold was raised to 10.
 */
data class AlertWindow(
    val start: Instant,
    val end: Instant,
    val delta: Float,
    val direction: PressureDirection
)

object AlertDetector {
    fun detect(readings: List<PressureReading>, thresholdHpa: Float): List<AlertWindow> {
        if (readings.size < 2) return emptyList()
        val windowMillis = 24L * 60 * 60 * 1000

        // Step 1: collect every qualifying 24-hour sliding window.
        data class RawWindow(
            val startMillis: Long,
            val endMillis: Long,
            val delta: Float,
            val direction: PressureDirection
        )
        val raw = mutableListOf<RawWindow>()
        for (i in readings.indices) {
            val startMillis = readings[i].dateTime.toEpochMilli()
            val endMillis = startMillis + windowMillis
            val window = readings.filter { it.dateTime.toEpochMilli() in startMillis..endMillis }
            if (window.size < 2) continue
            val maxReading = window.maxByOrNull { it.pressureMsl }!!
            val minReading = window.minByOrNull { it.pressureMsl }!!
            val delta = maxReading.pressureMsl - minReading.pressureMsl
            if (delta < thresholdHpa) continue
            // Direction comes from the position of the extremes (peak before trough = drop),
            // matching how step 3 labels the final event. Comparing the window's first and
            // last reading instead is fragile with noisy data: neighbouring windows over the
            // same event can flip label, escape the merge in step 2, and end up pinned to the
            // same extremes — i.e. duplicate alerts.
            val direction =
                if (maxReading.dateTime <= minReading.dateTime) PressureDirection.DROP
                else PressureDirection.RISE
            raw.add(RawWindow(startMillis, endMillis, delta, direction))
        }
        if (raw.isEmpty()) return emptyList()

        // Step 2: merge overlapping windows that share the same direction so one continuous
        // pressure event produces one alert. Windows with opposite directions (e.g. a drop
        // immediately followed by a rise) are kept separate — they are distinct physiological events.
        val mergedRaw = mutableListOf<RawWindow>()
        var current = raw.first()
        for (next in raw.drop(1)) {
            if (next.startMillis <= current.endMillis && next.direction == current.direction) {
                current = current.copy(
                    endMillis = maxOf(current.endMillis, next.endMillis),
                    delta = maxOf(current.delta, next.delta)
                )
            } else {
                mergedRaw.add(current)
                current = next
            }
        }
        mergedRaw.add(current)

        // Step 3: pin each event's start/end to the actual pressure extremes within the merged
        // window so the displayed times reflect when pressure peaked and troughed, not the
        // sliding-window boundaries.
        //
        // The extremes set the times only. The swing between them is deliberately *not* used as
        // the event's delta: the merged window can be far wider than a day — 50 hours, on data
        // that produced this comment — so that figure answers a question nobody asked and no
        // threshold tested. What carries through instead is `w.delta`, the largest qualifying
        // 24-hour swing found in step 1, which is what the user's sensitivity is set against.
        val pinned = mergedRaw.map { w ->
            val window = readings.filter { it.dateTime.toEpochMilli() in w.startMillis..w.endMillis }
            val maxReading = window.maxByOrNull { it.pressureMsl }!!
            val minReading = window.minByOrNull { it.pressureMsl }!!
            val (start, end, direction) = if (maxReading.dateTime <= minReading.dateTime) {
                Triple(maxReading.dateTime, minReading.dateTime, PressureDirection.DROP)
            } else {
                Triple(minReading.dateTime, maxReading.dateTime, PressureDirection.RISE)
            }
            AlertWindow(start, end, w.delta, direction)
        }.sortedBy { it.start }

        // Step 4: pinning can land two windows on overlapping (or identical) extremes when the
        // data is irregular, which would report the same physical event twice. Collapse
        // overlapping same-direction events into one.
        val result = mutableListOf<AlertWindow>()
        for (alert in pinned) {
            val prev = result.lastOrNull()
            if (prev != null && alert.direction == prev.direction && !alert.start.isAfter(prev.end)) {
                result[result.lastIndex] = prev.copy(
                    end = maxOf(prev.end, alert.end),
                    delta = maxOf(prev.delta, alert.delta)
                )
            } else {
                result.add(alert)
            }
        }
        return result
    }

    /**
     * The span of days [alert] spends any time in, first to last. Callers that only need to ask
     * whether one day is touched test it with `day in daysTouched(alert, zone)`.
     */
    fun daysTouched(alert: AlertWindow, zone: ZoneId): ClosedRange<LocalDate> {
        val firstDay = alert.start.atZone(zone).toLocalDate()
        val endDay = alert.end.atZone(zone).toLocalDate()

        // An alert ending exactly at midnight spends no time in the day it lands on, so that
        // day is not one to watch. Only a window spanning at least two days can end this way
        // without disappearing entirely.
        val endsAtMidnight = alert.end == endDay.atStartOfDay(zone).toInstant()
        val lastDay = if (endsAtMidnight && endDay.isAfter(firstDay)) endDay.minusDays(1) else endDay

        return firstDay..lastDay
    }

    /**
     * Every day an alert touches, in any direction. The calendar marks a day as high risk or
     * not, so which way the pressure moved — and how long each direction held the day — carries
     * no information here; a day touched by any qualifying event is a day to watch.
     */
    fun eventDays(alerts: List<AlertWindow>, zone: ZoneId): Set<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        alerts.forEach { alert ->
            val span = daysTouched(alert, zone)
            var day = span.start
            while (!day.isAfter(span.endInclusive)) {
                days.add(day)
                day = day.plusDays(1)
            }
        }

        return days
    }
}
