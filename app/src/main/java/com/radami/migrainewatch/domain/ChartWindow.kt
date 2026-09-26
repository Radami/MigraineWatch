package com.radami.migrainewatch.domain

import java.time.Instant
import java.time.ZoneId

private const val SECONDS_PER_HOUR = 3600L

/** Daily points sit at local noon, so a day's label lands in the middle of its own data. */
private const val DAILY_ANCHOR_HOUR = 12

/** How much time one chart point covers; also fixes how far the chart reaches overall. */
enum class ChartStep(val hours: Int) {
    ThreeHours(3),
    SixHours(6),
    OneDay(24);

    val seconds: Long get() = hours * SECONDS_PER_HOUR
}

/**
 * The span the pressure chart draws, and the single definition of where a moment lands on it.
 * Anchored on "now" snapped to the step: three steps of history, four ahead. Lives in the
 * domain, not the composable, so callers like the ViewModel can use the same math.
 */
data class ChartWindow(
    val anchorEpochSecond: Long,
    val step: ChartStep
) {
    companion object {
        /** Chart index of the anchor point: the snapped "now". */
        const val ANCHOR_INDEX = 3

        /** Every drawn point, from three steps back to four steps ahead of the anchor. */
        val POINT_INDICES = 0..7

        /**
         * The window around [now]. Sub-day steps floor to the step boundary; the daily step
         * snaps to local noon so the "now" line stays near the current day's label.
         */
        fun around(
            now: Instant,
            step: ChartStep,
            zone: ZoneId = ZoneId.systemDefault()
        ): ChartWindow {
            val anchor = when (step) {
                ChartStep.OneDay -> now.atZone(zone)
                    .toLocalDate()
                    .atTime(DAILY_ANCHOR_HOUR, 0)
                    .atZone(zone)
                    .toEpochSecond()

                else -> (now.epochSecond / step.seconds) * step.seconds
            }

            return ChartWindow(anchorEpochSecond = anchor, step = step)
        }
    }

    /**
     * How far the plot area reaches past the first/last point. The daily step draws segmented
     * cells with the point centred, so it needs a half-step margin; hourly steps don't.
     */
    private val edgeMarginSeconds: Long = when (step) {
        ChartStep.OneDay -> step.seconds / 2
        else -> 0L
    }

    /**
     * The instant point [index] is sampled at, in exact multiples of the step. A daily window
     * crossing DST can drift later points an hour off local noon, but never onto another date.
     */
    fun epochSecondAt(index: Int): Long =
        anchorEpochSecond + (index - ANCHOR_INDEX) * step.seconds

    /**
     * [instant] as a chart x-value, fractional between points. Values outside
     * [POINT_INDICES] are still returned; drawing clips them to the plot area.
     */
    fun xOf(instant: Instant): Float =
        ANCHOR_INDEX + (instant.epochSecond - anchorEpochSecond).toFloat() / step.seconds

    /**
     * The instant chart x-value [x] falls on: the inverse of [xOf], defined between points too.
     * Needed for the strip of plot past the last point.
     */
    fun instantAt(x: Float): Instant =
        Instant.ofEpochSecond(anchorEpochSecond + ((x - ANCHOR_INDEX) * step.seconds).toLong())

    /** Left edge of the plot area. */
    val firstVisible: Instant =
        Instant.ofEpochSecond(epochSecondAt(POINT_INDICES.first) - edgeMarginSeconds)

    /** Right edge of the plot area. */
    val lastVisible: Instant =
        Instant.ofEpochSecond(epochSecondAt(POINT_INDICES.last) + edgeMarginSeconds)

    /** Whether any part of [alert] falls inside the plot area (merely touching an edge doesn't count). */
    fun covers(alert: AlertWindow): Boolean =
        alert.end.isAfter(firstVisible) && alert.start.isBefore(lastVisible)
}
