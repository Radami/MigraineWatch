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

    /** The forecast doesn't reach the end of the day, so it can't be called clear yet. */
    Unknown
}

/**
 * One day of the outlook.
 *
 * @param peakDelta the largest swing among events touching the day; [direction] is that event's
 *   direction. Both null unless [risk] is [OutlookRisk.Elevated].
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
         * @param coveredThrough instant the readings stop describing, or null. See
         *   [PressureAlertUseCase.coverageEnd]. A day is only "clear" once coverage reaches its
         *   final midnight; otherwise it's [OutlookRisk.Unknown].
         */
        fun forecast(
            alerts: List<AlertWindow>,
            coveredThrough: Instant?,
            today: LocalDate,
            zone: ZoneId
        ): List<DayOutlook> = (0 until DAYS).map { offset ->
            val date = today.plusDays(offset.toLong())
            val touching = alerts.filter { date in AlertDetector.daysTouched(it, zone) }

            // Covered only once readings describe the day through to its final midnight.
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            val covered = coveredThrough != null && !coveredThrough.isBefore(dayEnd)

            // A touched day is elevated regardless of coverage; the event is already known.
            val peak = touching.maxByOrNull { it.delta }
            when {
                peak != null -> DayOutlook(date, OutlookRisk.Elevated, peak.delta, peak.direction)
                covered -> DayOutlook(date, OutlookRisk.Clear, null, null)
                else -> DayOutlook(date, OutlookRisk.Unknown, null, null)
            }
        }
    }
}
