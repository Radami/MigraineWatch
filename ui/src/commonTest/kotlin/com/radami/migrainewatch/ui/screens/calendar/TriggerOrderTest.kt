package com.radami.migrainewatch.ui.screens.calendar

import kotlin.test.Test
import kotlin.test.assertEquals

class TriggerOrderTest {

    @Test
    fun triggersComeBackInTheOrderThePickerOffersThem() {
        val tapped = TRIGGER_OPTIONS.reversed()

        assertEquals(TRIGGER_OPTIONS, tapped.inTriggerOrder())
    }

    @Test
    fun otherIsLastWhateverElseIsSelected() {
        val selected = listOf(OTHER_TRIGGER, "Stress", "Poor sleep")

        assertEquals(OTHER_TRIGGER, selected.inTriggerOrder().last())
    }

    @Test
    fun otherStaysLastEvenWhenItIsNotLastInTheOptions() {
        // Guards the ordering against someone reshuffling TRIGGER_OPTIONS: the comparator
        // pins "Other" itself rather than relying on its position in the list.
        val optionsWithOtherFirst = listOf(OTHER_TRIGGER) + TRIGGER_OPTIONS.filterNot { it == OTHER_TRIGGER }

        assertEquals(OTHER_TRIGGER, optionsWithOtherFirst.inTriggerOrder().last())
    }

    @Test
    fun valuesNoLongerOfferedSortAfterTheKnownOnesBeforeOther() {
        val stored = listOf(OTHER_TRIGGER, "Weather", "Stress", "Alcohol")

        assertEquals(
            listOf("Stress", "Alcohol", "Weather", OTHER_TRIGGER),
            stored.inTriggerOrder()
        )
    }

    @Test
    fun orderingDoesNotDependOnInputOrder() {
        val stored = listOf("Weather", "Alcohol", "Screen time", OTHER_TRIGGER, "Hormonal")

        assertEquals(stored.inTriggerOrder(), stored.shuffled().inTriggerOrder())
    }
}
