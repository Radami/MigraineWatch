package com.radami.migrainewatch.ui.screens.calendar

import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.domain.Severity
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

const val OTHER_TRIGGER = "Other"

/** The canonical order every screen shows triggers in. */
val TRIGGER_OPTIONS = listOf(
    "Poor sleep", "Stress", "Food / skipped meal", "Hormonal", "Screen time", OTHER_TRIGGER
)

private val TRIGGER_RANKS = TRIGGER_OPTIONS.withIndex()
    .associate { (rank, trigger) -> trigger to rank }

/**
 * Sorts triggers into [TRIGGER_OPTIONS] order with [OTHER_TRIGGER] last, so a saved entry
 * reads the same regardless of tap order. Unrecognized (e.g. old) values sort after known ones.
 */
fun Iterable<String>.inTriggerOrder(): List<String> = sortedWith(
    compareBy<String>(
        // "Other" always sorts last, ahead of any other comparison.
        { it == OTHER_TRIGGER },
        // Elvis matters: null would otherwise sort an unranked trigger first, not last.
        { TRIGGER_RANKS[it] ?: Int.MAX_VALUE },
        // Tiebreaker for unranked triggers, which all share MAX_VALUE above.
        { it }
    )
)

val DURATION_OPTIONS = listOf("<2h", "2-6h", "6-12h", ">12h")

/**
 * How much the medication helped, in quarters. Offered as a short list rather than a
 * continuous scale: nobody recalls relief to the percentage point hours later, and a scale
 * that invites 63% only makes the entry harder to give.
 */
val RELIEF_OPTIONS = listOf(0, 25, 50, 75, 100)

/** The form's steps, in the order it walks through them. */
const val SEVERITY_STEP = 1
const val TRIGGERS_STEP = 2
const val DETAILS_STEP = 3

/** The log-entry form for one day, as filled in so far. */
data class LogEntryState(
    val date: LocalDate,
    val step: Int = SEVERITY_STEP,
    val severity: Severity? = null,
    val triggers: Set<String> = emptySet(),
    val durationBucket: String? = null,
    val reliefPercent: Int? = null,
    val medication: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false
) {
    /** Starts an edit from what the day already has logged. */
    fun prefilledFrom(entry: SymptomEntry) = copy(
        severity = entry.severity,
        triggers = entry.triggers.toSet(),
        durationBucket = entry.durationBucket,
        reliefPercent = entry.reliefPercent,
        medication = entry.medication.orEmpty(),
        notes = entry.notes.orEmpty()
    )

    /** Severity is the one required answer, so the form can't move past it without one. */
    fun continuedToTriggers(): LogEntryState {
        if (severity == null) return this

        return copy(step = TRIGGERS_STEP)
    }

    fun continuedToDetails() = copy(step = DETAILS_STEP)

    /** One step back; the first step has nowhere further to go. */
    fun steppedBack(): LogEntryState {
        if (step == SEVERITY_STEP) return this

        return copy(step = step - 1)
    }

    fun withTriggerToggled(trigger: String) = copy(
        triggers = if (trigger in triggers) triggers - trigger else triggers + trigger
    )

    /**
     * The entry to save. Triggers are stored in picker order and blank text as nothing, so an
     * entry reads the same however it was filled in.
     *
     * @param createdAt when the day was first logged, kept across edits; null for a new entry.
     */
    fun toEntry(createdAt: Instant?, now: Instant): SymptomEntry {
        val severity = checkNotNull(severity) { "An entry needs a severity" }

        return SymptomEntry(
            date = date,
            severity = severity,
            triggers = triggers.inTriggerOrder(),
            durationBucket = durationBucket,
            reliefPercent = reliefPercent,
            medication = medication.takeIf { it.isNotBlank() },
            notes = notes.takeIf { it.isNotBlank() },
            createdAt = createdAt ?: now,
            updatedAt = now
        )
    }
}
