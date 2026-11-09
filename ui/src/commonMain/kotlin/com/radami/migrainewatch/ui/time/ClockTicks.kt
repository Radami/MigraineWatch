package com.radami.migrainewatch.ui.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * How often [clockTicks] ticks. Every UTC offset in use is a whole number of quarter hours
 * (Kathmandu's +5:45 included), so a quarter-hour tick lands on every local hour and every local
 * midnight, wherever the location is: the moments a screen's picture of the forecast can change,
 * since Open-Meteo's readings are hourly in local time and days turn at local midnight.
 */
val CLOCK_TICK: Duration = 15.minutes

/**
 * The time now, then again at the start of every [CLOCK_TICK] after, for as long as it is
 * collected.
 *
 * Lets a screen that stays open, or sits suspended in the background for days as iOS keeps apps,
 * move its data window and "today" with the clock instead of keeping the ones it opened with.
 */
fun clockTicks(clock: Clock = Clock.System): Flow<Instant> = flow {
    val tickSeconds = CLOCK_TICK.inWholeSeconds

    while (true) {
        val now = clock.now()
        emit(now)

        val nextTick = Instant.fromEpochSeconds((now.epochSeconds / tickSeconds + 1) * tickSeconds)
        delay(nextTick - now)
    }
}
