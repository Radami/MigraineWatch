package com.radami.migrainewatch.ui.theme

import androidx.compose.ui.graphics.Color
import com.radami.migrainewatch.data.model.Severity

/**
 * The colour a severity wears everywhere: day markers, legend swatches, the log entry
 * picker, the streak card. Fixed rather than theme-derived, like [alertColorPalette], since
 * these are a legend the user learns and must mean the same thing under dynamic theming.
 */
val Severity.color: Color
    get() = when (this) {
        Severity.CLEAR -> SeverityClear
        Severity.MILD -> SeverityMild
        Severity.AURA -> SeverityAura
        Severity.MIGRAINE -> SeverityMigraine
    }
