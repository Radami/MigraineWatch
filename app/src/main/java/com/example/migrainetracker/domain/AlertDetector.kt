package com.example.migrainetracker.domain

import com.example.migrainetracker.data.model.PressureReading
import java.time.Instant

data class AlertWindow(
    val start: Instant,
    val end: Instant,
    val delta: Float,
    val direction: String  // "drop" | "rise"
)

object AlertDetector {
    fun detect(readings: List<PressureReading>, thresholdHpa: Float): List<AlertWindow> {
        if (readings.size < 2) return emptyList()
        val windowMillis = 24L * 60 * 60 * 1000

        // Step 1: collect every qualifying 24-hour sliding window.
        data class RawWindow(val startMillis: Long, val endMillis: Long, val delta: Float, val direction: String)
        val raw = mutableListOf<RawWindow>()
        for (i in readings.indices) {
            val startMillis = readings[i].dateTime.toEpochMilli()
            val endMillis = startMillis + windowMillis
            val window = readings.filter { it.dateTime.toEpochMilli() in startMillis..endMillis }
            if (window.size < 2) continue
            val delta = window.maxOf { it.pressureMsl } - window.minOf { it.pressureMsl }
            if (delta < thresholdHpa) continue
            val direction = if (window.last().pressureMsl < window.first().pressureMsl) "drop" else "rise"
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
        return mergedRaw.map { w ->
            val window = readings.filter { it.dateTime.toEpochMilli() in w.startMillis..w.endMillis }
            val maxReading = window.maxByOrNull { it.pressureMsl }!!
            val minReading = window.minByOrNull { it.pressureMsl }!!
            val delta = maxReading.pressureMsl - minReading.pressureMsl
            val (start, end, direction) = if (maxReading.dateTime <= minReading.dateTime) {
                Triple(maxReading.dateTime, minReading.dateTime, "drop")
            } else {
                Triple(minReading.dateTime, maxReading.dateTime, "rise")
            }
            AlertWindow(start, end, delta, direction)
        }
    }

    fun maxForecastDrop(forecast: List<PressureReading>): Float {
        if (forecast.size < 2) return 0f
        val windowMillis = 24L * 60 * 60 * 1000
        var maxDrop = 0f
        for (i in forecast.indices) {
            val windowEnd = forecast[i].dateTime.toEpochMilli() + windowMillis
            val window = forecast.filter { r ->
                r.dateTime.toEpochMilli() >= forecast[i].dateTime.toEpochMilli() &&
                    r.dateTime.toEpochMilli() <= windowEnd
            }
            if (window.size < 2) continue
            val maxP = window.maxOf { it.pressureMsl }
            val maxIdx = window.indexOfFirst { it.pressureMsl == maxP }
            val subsequent = window.drop(maxIdx)
            if (subsequent.isNotEmpty()) {
                val minP = subsequent.minOf { it.pressureMsl }
                val drop = maxP - minP
                if (drop > maxDrop) maxDrop = drop
            }
        }
        return maxDrop
    }
}
