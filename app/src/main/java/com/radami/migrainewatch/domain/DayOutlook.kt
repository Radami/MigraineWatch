package com.radami.migrainewatch.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the forecast lets us say about one day. */
enum class OutlookRisk {

    /** The forecast covers the day and no qualifying pressure event touches it. */
    Clear,

    /** At least one qualifying pressure event touches the day. */
    Elevated,

    /**
     * The forecast does not reach the end of the day, so the day cannot be called clear —
     * an event could still be hiding in the hours we have no readings for.
     */
    Unknown
}

/**
 * One day of the outlook.
 *
 * @param peakDelta the largest swing among the events touching the day, and [direction] the way
 *   that event moved. Both are null unless [risk] is [OutlookRisk.Elevated]: the biggest event
 *   is what the day is worth summarising by, and on a clear day there is none.
 */
data class DayOutlook(
    val date: LocalDate,
    val risk: OutlookRisk,
    val peakDelta: Float?,
    val direction: PressureDirection?
) {
    companion object {

        /** Days the outlook spans, today included. */
        const val DAYS = 7

        /**
         * The next [DAYS] days from [today], each marked with the events in [alerts] that touch
         * it.
         *
         * @param forecastEnd the last instant the readings reach, or null when there are none.
         *   A day is only called clear once the forecast covers all of it; past that point the
         *   days are [OutlookRisk.Unknown] rather than quietly reported as safe.
         */
        fun forecast(
            alerts: List<AlertWindow>,
            forecastEnd: Instant?,
            today: LocalDate,
            zone: ZoneId
        ): List<DayOutlook> = (0 until DAYS).map { offset ->
            val date = today.plusDays(offset.toLong())
            val touching = alerts.filter { date in AlertDetector.daysTouched(it, zone) }

            // The day is covered only if the readings run past its final midnight.
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            val covered = forecastEnd != null && !forecastEnd.isBefore(dayEnd)

            // A touched day is elevated whether or not the forecast reaches its end: the event
            // is already known, and nothing later in the day can un-know it.
            val peak = touching.maxByOrNull { it.delta }
            when {
                peak != null -> DayOutlook(date, OutlookRisk.Elevated, peak.delta, peak.direction)
                covered -> DayOutlook(date, OutlookRisk.Clear, null, null)
                else -> DayOutlook(date, OutlookRisk.Unknown, null, null)
            }
        }
    }
}
