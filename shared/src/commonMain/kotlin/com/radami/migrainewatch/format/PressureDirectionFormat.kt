package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureDirection

/**
 * How a direction is spelled wherever the user sees one. Copy rather than a derivation of the
 * constant names, so the domain layer stays free of user-facing wording.
 */
val PressureDirection.label: String
    get() = when (this) {
        PressureDirection.DROP -> "pressure drop"
        PressureDirection.RISE -> "pressure rise"
    }

/**
 * How an event is named for the user: the direction first, then the swing in brackets. The
 * swing is a 24-hour figure and the event can run longer, so leading with a bare number would
 * misread as the event's total. See [AlertWindow.delta].
 */
fun formatAlertSummary(delta: Float, direction: PressureDirection): String =
    "${direction.label} (${formatHpa(delta)} hPa in 24h)"

/** The same, opening a line rather than sitting inside one: "Pressure rise (8.2 hPa in 24h)". */
fun formatAlertHeadline(delta: Float, direction: PressureDirection): String =
    formatAlertSummary(delta, direction).replaceFirstChar { it.uppercase() }
