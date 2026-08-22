package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.PressureDirection

/**
 * How a direction is spelled wherever the user sees one — the alert rows on the Pressure
 * screen, the Today banner and the notification title.
 *
 * Copy rather than a derivation of the constant names, for the reason the severity labels in
 * this package give: the domain layer stays free of user-facing wording, and a notification
 * cannot drift into a different spelling from the row describing the same event.
 */
val PressureDirection.label: String
    get() = when (this) {
        PressureDirection.DROP -> "pressure drop"
        PressureDirection.RISE -> "pressure rise"
    }
