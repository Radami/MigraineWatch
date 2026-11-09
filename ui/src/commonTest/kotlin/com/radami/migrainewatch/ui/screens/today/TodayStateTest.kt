package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureDirection
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The Today screen's rules, run on every platform. The Android ViewModel tests drive the same
 * function through Room and coroutines; these pin it directly.
 */
class TodayStateTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")

        val ZURICH = LocationData(source = "manual", name = "Zurich", timezone = "Europe/Zurich")

        /** UTC+14: already the next calendar day when it is noon in UTC. */
        val KIRITIMATI = LocationData(source = "manual", name = "Kiritimati", timezone = "Pacific/Kiritimati")
    }

    private fun reading(at: Instant, fetchedAt: Instant = NOW) =
        PressureReading(dateTime = at, pressureMsl = 1015f, surfacePressure = 960f, fetchedDateTime = fetchedAt)

    private fun alert(start: Instant, end: Instant) =
        AlertWindow(start = start, end = end, delta = 9f, direction = PressureDirection.DROP)

    private fun state(
        readings: List<PressureReading> = listOf(reading(NOW)),
        refreshState: RefreshState = RefreshState.Updated,
        alerts: List<AlertWindow> = emptyList(),
        coveredThrough: Instant? = NOW + 7.days,
        location: LocationData = ZURICH
    ) = todayState(
        readings = readings,
        entries = emptyList(),
        settings = AppSettings(location = location),
        refreshState = refreshState,
        alerts = alerts,
        coveredThrough = coveredThrough,
        now = NOW
    )

    @Test
    fun aFinishedEventIsNotOfferedToTheBanner() {
        val finished = alert(NOW - 10.hours, NOW - 2.hours)
        val ahead = alert(NOW + 5.hours, NOW + 15.hours)

        assertEquals(listOf(ahead), state(alerts = listOf(finished, ahead)).pendingAlerts)
    }

    @Test
    fun anEventUnderWayLeadsAsUnderWay() {
        val underway = alert(NOW - 2.hours, NOW + 8.hours)

        assertEquals(AlertPhase.UNDERWAY, state(alerts = listOf(underway)).leadAlertPhase)
    }

    @Test
    fun anEventStillAheadLeadsAsAhead() {
        assertEquals(AlertPhase.AHEAD, state(alerts = listOf(alert(NOW + 5.hours, NOW + 15.hours))).leadAlertPhase)
    }

    @Test
    fun noPendingEventLeavesNothingToLead() {
        assertNull(state().leadAlertPhase)
    }

    @Test
    fun aForecastCoveringOnlyTodayIsNotAGap() {
        val coveredToday = state(coveredThrough = Instant.parse("2026-08-23T22:00:00Z"))

        assertNull(coveredToday.outlookGap)
        assertEquals(OutlookRisk.Clear, coveredToday.outlook.first().risk)
    }

    @Test
    fun readingsThatStopBeforeTodayAreAForecastFallenBehind() {
        assertEquals(OutlookGap.ForecastBehind, state(coveredThrough = NOW - 2.days).outlookGap)
    }

    @Test
    fun withNoReadingsTheFetchStateSaysWhy() {
        val gaps = RefreshState.entries.associateWith {
            state(readings = emptyList(), refreshState = it, coveredThrough = null).outlookGap
        }

        assertEquals(
            mapOf(
                RefreshState.InFlight to OutlookGap.Loading,
                RefreshState.Updated to OutlookGap.Loading,
                RefreshState.NoReadings to OutlookGap.NoReadings,
                RefreshState.Failed to OutlookGap.FetchFailed,
                RefreshState.NoLocation to OutlookGap.NoLocation
            ),
            gaps
        )
    }

    @Test
    fun lastUpdatedIsTheNewestFetch() {
        val readings = listOf(reading(NOW, fetchedAt = NOW - 3.hours), reading(NOW + 1.hours, fetchedAt = NOW - 1.hours))

        assertEquals(NOW - 1.hours, state(readings = readings).lastUpdated)
    }

    @Test
    fun theOutlookStartsOnTheLocationsToday() {
        val farEast = state(location = KIRITIMATI)

        assertEquals(LocalDate(2026, 8, 24), farEast.outlook.first().date)
        assertEquals(TimeZone.of("Pacific/Kiritimati"), farEast.zone)
    }

    @Test
    fun noSymptomHistoryHasNoStreak() {
        assertNull(state().symptomFreeStreak)
    }
}
