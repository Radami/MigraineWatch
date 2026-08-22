package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.data.model.SymptomEntry
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How long the user has gone without a symptom event — right now, and at their best.
 *
 * A "streak" here is the run of days strictly between two events, so two events on consecutive
 * days leave a streak of zero and an event yesterday leaves a streak of one. Days with no entry
 * at all count towards a streak: the log is opt-in, and treating a day the user never opened the
 * app as a symptom day would punish them for not logging.
 */
data class SymptomFreeStreak(
    val currentDays: Long,
    val lastEvent: LastEvent,
    /** Null when there are not yet two events to measure a completed streak between. */
    val longest: Run?
) {

    data class LastEvent(val date: LocalDate, val severity: Severity)

    /**
     * A single symptom-free run. [from] and [to] are the first and last day *inside* it, so a run
     * of zero days has no days to name and [from] lands after [to].
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

            // The days elapsed since that event are exactly the streak still running.
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

            // The run still in progress competes too, otherwise a record being set right now would
            // stay invisible until the next event ended it. It is measured from the already
            // clamped current count rather than from today, so a future-dated entry cannot put a
            // negative run into the running.
            val lastEventDate = events.last().date
            val running = Run(
                days = currentDays,
                from = lastEventDate.plusDays(1),
                to = lastEventDate.plusDays(currentDays)
            )

            // maxByOrNull keeps the first of any tie, so a record stays credited to the run that
            // first set it rather than jumping to a later run that merely matched it.
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
