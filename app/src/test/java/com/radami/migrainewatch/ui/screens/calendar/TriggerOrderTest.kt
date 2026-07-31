package com.radami.migrainewatch.ui.screens.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerOrderTest {

    @Test
    fun `triggers come back in the order the picker offers them`() {
        val tapped = TRIGGER_OPTIONS.reversed()

        assertEquals(TRIGGER_OPTIONS, tapped.inTriggerOrder())
    }

    @Test
    fun `other is last whatever else is selected`() {
        val selected = listOf(OTHER_TRIGGER, "Stress", "Poor sleep")

        assertEquals(OTHER_TRIGGER, selected.inTriggerOrder().last())
    }

    @Test
    fun `other stays last even when it is not last in the options`() {
        // Guards the ordering against someone reshuffling TRIGGER_OPTIONS: the comparator
        // pins "Other" itself rather than relying on its position in the list.
        val optionsWithOtherFirst = listOf(OTHER_TRIGGER) + TRIGGER_OPTIONS.filterNot { it == OTHER_TRIGGER }

        assertEquals(OTHER_TRIGGER, optionsWithOtherFirst.inTriggerOrder().last())
    }

    @Test
    fun `values no longer offered sort after the known ones, before other`() {
        val stored = listOf(OTHER_TRIGGER, "Weather", "Stress", "Alcohol")

        assertEquals(
            listOf("Stress", "Alcohol", "Weather", OTHER_TRIGGER),
            stored.inTriggerOrder()
        )
    }

    @Test
    fun `ordering does not depend on input order`() {
        val stored = listOf("Weather", "Alcohol", "Screen time", OTHER_TRIGGER, "Hormonal")

        assertEquals(stored.inTriggerOrder(), stored.shuffled().inTriggerOrder())
    }
}
