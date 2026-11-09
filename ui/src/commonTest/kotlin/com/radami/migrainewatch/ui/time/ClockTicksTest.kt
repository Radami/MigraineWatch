package com.radami.migrainewatch.ui.time

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The screens' clock: now, then every quarter hour on the quarter hour. On the quarter hour, not
 * a quarter hour after opening, so a tick lands on every local hour and midnight in every zone.
 */
class ClockTicksTest {

    /** A clock that moves with the test's virtual time, starting at [start]. */
    private fun TestScope.clockFrom(start: Instant) = object : Clock {
        override fun now(): Instant = start + testScheduler.currentTime.milliseconds
    }

    @Test
    fun ticksNowThenOnEveryQuarterHour() = runTest {
        val opened = Instant.parse("2026-11-10T10:07:30Z")

        val ticks = clockTicks(clockFrom(opened)).take(3).toList()

        assertEquals(
            listOf(opened, Instant.parse("2026-11-10T10:15:00Z"), Instant.parse("2026-11-10T10:30:00Z")),
            ticks
        )
    }

    /** Kathmandu is UTC+5:45, so its midnight falls at a quarter past a UTC hour. */
    @Test
    fun aTickLandsOnMidnightInAZoneOffsetByThreeQuarters() = runTest {
        val beforeMidnight = Instant.parse("2026-11-10T18:10:00Z")
        val kathmanduMidnight = Instant.parse("2026-11-10T18:15:00Z")

        val ticks = clockTicks(clockFrom(beforeMidnight)).take(2).toList()

        assertEquals(kathmanduMidnight, ticks.last())
    }
}
