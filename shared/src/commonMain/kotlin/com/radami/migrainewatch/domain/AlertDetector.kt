package com.radami.migrainewatch.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateRange
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One pressure event.
 *
 * @param start actual pressure peak/trough, so shading covers the whole rise or drop.
 * @param delta the largest swing in any 24-hour window of the event (not start-to-end), since
 *   that is what the threshold is tested against.
 */
data class AlertWindow(
    val start: Instant,
    val end: Instant,
    val delta: Float,
    val direction: PressureDirection
)

object AlertDetector {
    fun detect(samples: List<PressureSample>, thresholdHpa: Float): List<AlertWindow> {
        if (samples.size < 2) return emptyList()
        val windowMillis = 24L * 60 * 60 * 1000

        // Step 1: collect every qualifying 24-hour sliding window.
        data class RawWindow(
            val startMillis: Long,
            val endMillis: Long,
            val delta: Float,
            val direction: PressureDirection
        )
        val raw = mutableListOf<RawWindow>()
        for (i in samples.indices) {
            val startMillis = samples[i].time.toEpochMilliseconds()
            val endMillis = startMillis + windowMillis
            val window = samples.filter { it.time.toEpochMilliseconds() in startMillis..endMillis }
            if (window.size < 2) continue
            val maxSample = window.maxByOrNull { it.pressureMsl }!!
            val minSample = window.minByOrNull { it.pressureMsl }!!
            val delta = maxSample.pressureMsl - minSample.pressureMsl
            if (delta < thresholdHpa) continue
            // Direction comes from the position of the extremes (peak before trough = drop),
            // matching step 3. Using first/last reading instead is fragile with noisy data
            // and can produce duplicate alerts.
            val direction =
                if (maxSample.time <= minSample.time) PressureDirection.DROP
                else PressureDirection.RISE
            raw.add(RawWindow(startMillis, endMillis, delta, direction))
        }
        if (raw.isEmpty()) return emptyList()

        // Step 2: merge overlapping windows with the same direction into one event. Opposite
        // directions stay separate as distinct events.
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

        // Step 3: pin start/end to the actual pressure extremes in the merged window, not the
        // sliding-window boundaries. Delta still comes from `w.delta` (step 1's 24h swing), not
        // the extremes' own swing, since the merged window can span well over a day.
        val pinned = mergedRaw.map { w ->
            val window = samples.filter { it.time.toEpochMilliseconds() in w.startMillis..w.endMillis }
            val maxSample = window.maxByOrNull { it.pressureMsl }!!
            val minSample = window.minByOrNull { it.pressureMsl }!!
            val (start, end, direction) = if (maxSample.time <= minSample.time) {
                Triple(maxSample.time, minSample.time, PressureDirection.DROP)
            } else {
                Triple(minSample.time, maxSample.time, PressureDirection.RISE)
            }
            AlertWindow(start, end, w.delta, direction)
        }.sortedBy { it.start }

        // Step 4: pinning can land two windows on overlapping extremes with irregular data,
        // double-reporting one event. Collapse overlapping same-direction events into one.
        val result = mutableListOf<AlertWindow>()
        for (alert in pinned) {
            val prev = result.lastOrNull()
            if (prev != null && alert.direction == prev.direction && alert.start <= prev.end) {
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
     * The span of days [alert] spends any time in, first to last. Test whether a single day is
     * touched with `day in daysTouched(alert, zone)`.
     */
    fun daysTouched(alert: AlertWindow, zone: TimeZone): LocalDateRange {
        val firstDay = alert.start.toLocalDateTime(zone).date
        val endDay = alert.end.toLocalDateTime(zone).date

        // An alert ending exactly at midnight spends no time in that day, so it doesn't count
        // unless the window spans at least two days.
        val endsAtMidnight = alert.end == endDay.atStartOfDayIn(zone)
        val lastDay =
            if (endsAtMidnight && endDay > firstDay) endDay.minus(1, DateTimeUnit.DAY) else endDay

        return firstDay..lastDay
    }

    /**
     * Every day any alert touches, in either direction. The calendar only marks a day as risky
     * or not, so direction and duration don't matter here.
     */
    fun eventDays(alerts: List<AlertWindow>, zone: TimeZone): Set<LocalDate> =
        alerts.flatMapTo(mutableSetOf()) { daysTouched(it, zone) }
}
