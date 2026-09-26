package com.radami.migrainewatch.format

import java.time.format.DateTimeFormatter

/**
 * The one place user-visible dates and times get their language, since an unqualified
 * [DateTimeFormatter] follows the device locale, not the app's English-only text. Zones are
 * left off; each caller applies its own with [DateTimeFormatter.withZone].
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

    /** "Saturday 14:00" — notification and banner text, where the day is worth spelling out. */
    val WEEKDAY_AND_TIME: DateTimeFormatter = display("EEEE HH:mm")

    /** "Sat" — chart day labels. */
    val WEEKDAY: DateTimeFormatter = display("EEE")

    /** "3PM" — the hourly chart's axis labels. */
    val HOUR: DateTimeFormatter = display("ha")

    private fun display(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, AppLocale.DISPLAY)
}
