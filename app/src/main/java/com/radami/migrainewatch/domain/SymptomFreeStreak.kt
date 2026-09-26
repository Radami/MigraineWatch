package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.data.model.SymptomEntry
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How long the user has gone without a symptom event — right now, and at their best.
 *
 * A "streak" is the run of days strictly between two events (consecutive-day events give a
 * streak of zero). Unlogged days count towards a streak, since logging is opt-in.
 */
data class SymptomFreeStreak(
    val currentDays: Long,
    val lastEvent: LastEvent,
    /** Null when there are not yet two events to measure a completed streak between. */
    val longest: Run?
) {

    data class LastEvent(val date: LocalDate, val severity: Severity)

    /**
     * A single symptom-free run. [from]/[to] are the first/last day inside it; a zero-day run
     * has [from] after [to].
     */
    data class Run(val days: Long, val from: LocalDate, val to: LocalDate)

    companion object {

        /** A gap only exists between two events, so one event is not enough to take a max over. */
        private const val MIN_EVENTS_FOR_LONGEST = 2

        /**
         * Returns null when no event has ever been logged, which leaves nothing to count from.
         */
        fun from(entries: List<SymptomEntry>, today: LocalDate): SymptomFreeStreak? {
            // Only mild/aura/migraine break a streak; a clear day is part of one.
            val events = entries.filter { it.severity.isSymptomEvent }.sortedBy { it.date }
            val lastEvent = events.lastOrNull() ?: return null

            // Days elapsed since that event is the streak still running.
            val currentDays = ChronoUnit.DAYS.between(lastEvent.date, today).coerceAtLeast(0)

            return SymptomFreeStreak(
                currentDays = currentDays,
                lastEvent = LastEvent(lastEvent.date, lastEvent.severity),
                longest = longestRun(events, currentDays)
            )
        }

        private fun longestRun(events: List<SymptomEntry>, currentDays: Long): Run? {
            if (events.size < MIN_EVENTS_FOR_LONGEST) return null

            val completed = events.zipWithNext().map { (earlier, later) ->
                runBetween(earlier.date, later.date)
            }

            // The in-progress run competes too, so a record being set right now isn't invisible
            // until the next event ends it.
            val lastEventDate = events.last().date
            val running = Run(
                days = currentDays,
                from = lastEventDate.plusDays(1),
                to = lastEventDate.plusDays(currentDays)
            )

            // maxByOrNull keeps the first tie, crediting the run that first set the record.
            return (completed + running).maxByOrNull { it.days }
        }

        /** The symptom-free days bounded by two event days, excluding the events themselves. */
        private fun runBetween(afterEvent: LocalDate, beforeEvent: LocalDate) = Run(
            days = ChronoUnit.DAYS.between(afterEvent, beforeEvent) - 1,
            from = afterEvent.plusDays(1),
            to = beforeEvent.minusDays(1)
        )
    }
}
