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
        val alerts = mutableListOf<AlertWindow>()
        val windowMillis = 24L * 60 * 60 * 1000

        for (i in readings.indices) {
            val windowEnd = readings[i].dateTime.toEpochMilli() + windowMillis
            val windowReadings = readings.filter { r ->
                r.dateTime.toEpochMilli() >= readings[i].dateTime.toEpochMilli() &&
                    r.dateTime.toEpochMilli() <= windowEnd
            }
            if (windowReadings.size < 2) continue
            val maxP = windowReadings.maxOf { it.pressureMsl }
            val minP = windowReadings.minOf { it.pressureMsl }
            val delta = maxP - minP
            if (delta >= thresholdHpa) {
                val direction = if (windowReadings.last().pressureMsl < windowReadings.first().pressureMsl) "drop" else "rise"
                alerts.add(AlertWindow(readings[i].dateTime, Instant.ofEpochMilli(windowEnd), delta, direction))
                break
            }
        }
        return alerts
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
            
            // Calculate biggest drop within this 24h window (peak must precede trough)
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
