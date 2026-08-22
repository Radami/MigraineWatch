package com.radami.migrainewatch.domain

import java.time.Instant
import java.time.ZoneId

private const val SECONDS_PER_HOUR = 3600L

/** Daily points sit at local noon, so a day's label lands in the middle of its own data. */
private const val DAILY_ANCHOR_HOUR = 12

/**
 * How much time one point of the pressure chart covers, which also fixes how far the whole
 * chart reaches: eight points either side of the anchor, so the step is the only dial.
 */
enum class ChartStep(val hours: Int) {
    ThreeHours(3),
    SixHours(6),
    OneDay(24);

    val seconds: Long get() = hours * SECONDS_PER_HOUR
}

/**
 * The span the pressure chart draws, and the single definition of where a moment in time
 * lands on it.
 *
 * The chart is anchored on "now" snapped to the step: three steps of history sit before the
 * anchor and four ahead of it, always at the same eight indices whatever the step. Callers
 * need the same maths the chart draws with — the Pressure screen has to say which alerts its
 * chart can actually show — so it lives here rather than inside the composable, where it
 * would have to be re-derived from a step count and be untestable besides. It sits in the
 * domain and not beside the chart for the same reason: a ViewModel deciding what its chart
 * can reach should not have to reach into the view layer to find out.
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
         * snaps to local noon instead, which keeps the "now" line near the current day's
         * label rather than halfway to the next one during the morning.
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

    /** Points up to and including the anchor — measured pressure. */
    val historyIndices: IntRange = POINT_INDICES.first..ANCHOR_INDEX

    /** Points from the anchor on — forecast. The anchor is in both, so the lines join up. */
    val forecastIndices: IntRange = ANCHOR_INDEX..POINT_INDICES.last

    /**
     * How far the plot area reaches past the first and last point. The daily step is drawn
     * segmented — one cell per day with its point at the centre — so the plot begins half a
     * step before the first point. The hourly steps put their points on the edges themselves.
     */
    private val edgeMarginSeconds: Long = when (step) {
        ChartStep.OneDay -> step.seconds / 2
        else -> 0L
    }

    /**
     * The instant point [index] is sampled at.
     *
     * Exact multiples of the step, so a daily window crossing a DST boundary has its later
     * points an hour off local noon. That cannot move a point onto another date, so the day
     * labels stay right; only the segment edges stop landing exactly on midnight.
     */
    fun epochSecondAt(index: Int): Long =
        anchorEpochSecond + (index - ANCHOR_INDEX) * step.seconds

    /**
     * [instant] as a chart x-value, fractional between points. Values outside
     * [POINT_INDICES] are still returned; drawing clips them to the plot area.
     */
    fun xOf(instant: Instant): Float =
        ANCHOR_INDEX + (instant.epochSecond - anchorEpochSecond).toFloat() / step.seconds

    /** Left edge of the plot area. */
    val firstVisible: Instant =
        Instant.ofEpochSecond(epochSecondAt(POINT_INDICES.first) - edgeMarginSeconds)

    /** Right edge of the plot area. */
    val lastVisible: Instant =
        Instant.ofEpochSecond(epochSecondAt(POINT_INDICES.last) + edgeMarginSeconds)

    /**
     * Whether any part of [alert] falls inside the plot area. An alert that merely touches an
     * edge does not count: it would draw a band of no width, which reads as a missing one.
     */
    fun covers(alert: AlertWindow): Boolean =
        alert.end.isAfter(firstVisible) && alert.start.isBefore(lastVisible)
}
