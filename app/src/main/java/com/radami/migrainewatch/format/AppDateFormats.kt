package com.radami.migrainewatch.format

import java.time.format.DateTimeFormatter

/**
 * The one place user-visible dates and times get their language.
 *
 * A [DateTimeFormatter] built without an explicit locale follows the *device*, not the app.
 * The app's own text is English and only English, so on a German phone that produced a screen
 * reading "Samstag, 1 August 2026" under an English heading — and, because roughly half the
 * formatters here already pinned English by hand, the app disagreed with itself: the chart
 * axis said "Sat" while the headline above it said "Samstag".
 *
 * Routing every display format through here means the language of a date can only ever be
 * changed for all of them at once.
 *
 * Zones are deliberately left off. Some callers want the device zone and some want the zone of
 * the event being shown, and a zone captured here would be fixed for the life of the process,
 * so each caller applies its own with [DateTimeFormatter.withZone].
 *
 * Machine-readable formats do not belong here — see the API request formats in
 * PressureRepository, which are pinned to Locale.ROOT for the opposite reason.
 */
object AppDateFormats {

    /** "Saturday, 1 August 2026, 14:00" — screen headers that state exactly when data is from. */
    val FULL_DATE_TIME: DateTimeFormatter = display("EEEE, d MMMM yyyy, HH:mm")

    /** "Saturday, 1 August 2026" — a date on its own, where the time is not the point. */
    val FULL_DATE: DateTimeFormatter = display("EEEE, d MMMM yyyy")

    /** "Saturday 1 August" — a date already understood to be in the month on screen. */
    val DAY_AND_MONTH: DateTimeFormatter = display("EEEE d MMMM")

    /** "Saturday 1 August 2026" — the same, where the year can no longer be assumed. */
    val DAY_MONTH_AND_YEAR: DateTimeFormatter = display("EEEE d MMMM yyyy")

    /** "August 2026" — the calendar's month heading. */
    val MONTH_AND_YEAR: DateTimeFormatter = display("MMMM yyyy")

    /** "1 Aug" — a bare date, for a range that has to fit on one line of a narrow screen. */
    val SHORT_DAY_AND_MONTH: DateTimeFormatter = display("d MMM")

    /** "1 Aug 2026" — the same, where the year can no longer be assumed to be this one. */
    val SHORT_DATE_AND_YEAR: DateTimeFormatter = display("d MMM yyyy")

    /** "Sat 1 Aug, 14:00" — a full timestamp compact enough to sit in a list row. */
    val DAY_AND_TIME: DateTimeFormatter = display("EEE d MMM, HH:mm")

    /** "Saturday 14:00" — notification text, where there is room to spell the day out. */
    val WEEKDAY_AND_TIME: DateTimeFormatter = display("EEEE HH:mm")

    /** "Sat 14:00" — the same thing inside an alert banner, where there is not. */
    val SHORT_WEEKDAY_AND_TIME: DateTimeFormatter = display("EEE HH:mm")

    /** "Sat" — chart day labels. */
    val WEEKDAY: DateTimeFormatter = display("EEE")

    /** "3PM" — the hourly chart's axis labels. */
    val HOUR: DateTimeFormatter = display("ha")

    /** "PM" — an axis label for a chart whose day is established elsewhere. */
    val MERIDIEM: DateTimeFormatter = display("a")

    private fun display(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, AppLocale.DISPLAY)
}
