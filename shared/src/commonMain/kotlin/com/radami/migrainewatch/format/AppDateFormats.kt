package com.radami.migrainewatch.format

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The one place user-visible dates and times get their shape. Day and month names are spelled
 * out in English here, matching the app's English-only text, so nothing follows the device
 * locale. Zones are left off; callers convert with [format] or to a local date first.
 *
 * Formats built from others come after them, since an object sets its properties in order.
 */
object AppDateFormats {

    /** "Sat" — chart day labels and the outlook strip. */
    val WEEKDAY: DateTimeFormat<LocalDate> = LocalDate.Format {
        dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    }

    /** "Saturday, 1 August 2026" — a date on its own, where the time is not the point. */
    val FULL_DATE: DateTimeFormat<LocalDate> = LocalDate.Format {
        dayOfWeek(DayOfWeekNames.ENGLISH_FULL)
        chars(", ")
        day(Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        year()
    }

    /** "Saturday, 1 August 2026, 14:00" — screen headers that state exactly when data is from. */
    val FULL_DATE_TIME: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
        date(FULL_DATE)
        chars(", ")
        hour()
        char(':')
        minute()
    }

    /** "Saturday 1 August" — a date already understood to be in the month on screen. */
    val DAY_AND_MONTH: DateTimeFormat<LocalDate> = LocalDate.Format {
        dayOfWeek(DayOfWeekNames.ENGLISH_FULL)
        char(' ')
        day(Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_FULL)
    }

    /** "Saturday 1 August 2026" — the same, where the year can no longer be assumed. */
    val DAY_MONTH_AND_YEAR: DateTimeFormat<LocalDate> = LocalDate.Format {
        date(DAY_AND_MONTH)
        char(' ')
        year()
    }

    /** "August 2026" — the calendar's month heading. */
    val MONTH_AND_YEAR: DateTimeFormat<LocalDate> = LocalDate.Format {
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        year()
    }

    /** "1 Aug" — a bare date, for a range that has to fit on one line of a narrow screen. */
    val SHORT_DAY_AND_MONTH: DateTimeFormat<LocalDate> = LocalDate.Format {
        day(Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
    }

    /** "1 Aug 2026" — the same, where the year can no longer be assumed to be this one. */
    val SHORT_DATE_AND_YEAR: DateTimeFormat<LocalDate> = LocalDate.Format {
        date(SHORT_DAY_AND_MONTH)
        char(' ')
        year()
    }

    /** "Sat 1 Aug, 14:00" — a full timestamp compact enough to sit in a list row. */
    val DAY_AND_TIME: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
        date(LocalDate.Format {
            date(WEEKDAY)
            char(' ')
            date(SHORT_DAY_AND_MONTH)
        })
        chars(", ")
        hour()
        char(':')
        minute()
    }

    /** "Saturday 14:00" — notification and banner text, where the day is worth spelling out. */
    val WEEKDAY_AND_TIME: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
        dayOfWeek(DayOfWeekNames.ENGLISH_FULL)
        char(' ')
        hour()
        char(':')
        minute()
    }

    /** "3PM" — the hourly chart's axis labels. */
    val HOUR: DateTimeFormat<LocalDateTime> = LocalDateTime.Format {
        amPmHour(Padding.NONE)
        amPmMarker(AM_MARKER, PM_MARKER)
    }
}

private const val AM_MARKER = "AM"
private const val PM_MARKER = "PM"

/** [instant] as it reads in [zone]. */
fun DateTimeFormat<LocalDateTime>.format(instant: Instant, zone: TimeZone): String =
    format(instant.toLocalDateTime(zone))
