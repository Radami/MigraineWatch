package com.radami.migrainewatch.ui.screens.calendar

import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.domain.Severity
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The log-entry form's rules, run on every platform. The Android ViewModel tests drive the same
 * state through the repository; these pin it directly.
 */
class LogEntryStateTest {

    private companion object {
        val DATE = LocalDate(2026, 8, 23)

        val NOW: Instant = Instant.parse("2026-08-23T20:00:00Z")
    }

    private val blank = LogEntryState(date = DATE)

    @Test
    fun theFormCannotMovePastSeverityWithoutOne() {
        assertEquals(SEVERITY_STEP, blank.continuedToTriggers().step)
    }

    @Test
    fun aChosenSeverityMovesTheFormOnToTriggers() {
        assertEquals(TRIGGERS_STEP, blank.copy(severity = Severity.MILD).continuedToTriggers().step)
    }

    @Test
    fun steppingBackStopsAtTheFirstStep() {
        val details = blank.copy(severity = Severity.MILD, step = DETAILS_STEP)

        assertEquals(TRIGGERS_STEP, details.steppedBack().step)
        assertEquals(SEVERITY_STEP, details.steppedBack().steppedBack().step)
        assertEquals(SEVERITY_STEP, details.steppedBack().steppedBack().steppedBack().step)
    }

    @Test
    fun togglingATriggerTwiceLeavesItUnselected() {
        val once = blank.withTriggerToggled("Stress")

        assertEquals(setOf("Stress"), once.triggers)
        assertEquals(emptySet(), once.withTriggerToggled("Stress").triggers)
    }

    @Test
    fun anEditStartsFromWhatTheDayHasLogged() {
        val logged = SymptomEntry(
            date = DATE,
            severity = Severity.AURA,
            triggers = listOf("Stress", OTHER_TRIGGER),
            durationBucket = "2-6h",
            reliefPercent = 0,
            medication = null,
            notes = "Bright light",
            createdAt = NOW - 1.days,
            updatedAt = NOW - 1.days
        )

        val edit = blank.prefilledFrom(logged)

        assertEquals(Severity.AURA, edit.severity)
        assertEquals(setOf("Stress", OTHER_TRIGGER), edit.triggers)
        assertEquals("2-6h", edit.durationBucket)
        assertEquals(0, edit.reliefPercent)
        assertEquals("", edit.medication)
        assertEquals("Bright light", edit.notes)
        assertEquals(SEVERITY_STEP, edit.step)
    }

    @Test
    fun aSavedEntryListsTriggersInPickerOrder() {
        val form = blank.copy(severity = Severity.MIGRAINE)
            .withTriggerToggled(OTHER_TRIGGER)
            .withTriggerToggled("Screen time")
            .withTriggerToggled("Poor sleep")

        assertEquals(listOf("Poor sleep", "Screen time", OTHER_TRIGGER), form.toEntry(null, NOW).triggers)
    }

    @Test
    fun blankTextIsSavedAsNothing() {
        val entry = blank.copy(severity = Severity.MILD, medication = "  ", notes = "").toEntry(null, NOW)

        assertNull(entry.medication)
        assertNull(entry.notes)
    }

    @Test
    fun noReliefAtAllIsStillAnAnswer() {
        assertEquals(0, blank.copy(severity = Severity.MILD, reliefPercent = 0).toEntry(null, NOW).reliefPercent)
    }

    @Test
    fun aNewEntryIsCreatedNow() {
        val entry = blank.copy(severity = Severity.MILD).toEntry(createdAt = null, now = NOW)

        assertEquals(NOW, entry.createdAt)
        assertEquals(NOW, entry.updatedAt)
    }

    @Test
    fun anEditKeepsWhenTheDayWasFirstLogged() {
        val firstLogged = NOW - 3.days

        val entry = blank.copy(severity = Severity.MILD).toEntry(createdAt = firstLogged, now = NOW)

        assertEquals(firstLogged, entry.createdAt)
        assertEquals(NOW, entry.updatedAt)
    }

    @Test
    fun anEntryCannotBeBuiltWithoutASeverity() {
        assertFailsWith<IllegalStateException> { blank.toEntry(null, NOW) }
    }
}
