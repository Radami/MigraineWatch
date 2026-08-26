package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.AlertWindow
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

/**
 * How an event is named for the user: the direction first, then the swing in brackets.
 *
 * The swing is bracketed and qualified rather than led with, because it is a 24-hour figure
 * and the event it describes can be longer than that. Leading with a bare "11.1 hPa" invited
 * the reading that the number was the event's total, which made the same event look like it
 * had changed size when the sensitivity moved. What the direction says is the part that never
 * changes; the figure is the supporting detail. See [AlertWindow.delta].
 */
fun formatAlertSummary(delta: Float, direction: PressureDirection): String =
    "${direction.label} (${formatHpa(delta)} hPa in 24h)"

/** The same, opening a line rather than sitting inside one: "Pressure rise (8.2 hPa in 24h)". */
fun formatAlertHeadline(delta: Float, direction: PressureDirection): String =
    formatAlertSummary(delta, direction).replaceFirstChar { it.uppercase() }
