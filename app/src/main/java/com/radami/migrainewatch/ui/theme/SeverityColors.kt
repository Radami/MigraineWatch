package com.radami.migrainewatch.ui.theme

import androidx.compose.ui.graphics.Color
import com.radami.migrainewatch.data.model.Severity

/**
 * The colour a severity wears everywhere it appears — calendar day markers, legend swatches,
 * the log entry picker, the streak card.
 *
 * Fixed rather than derived from the theme, like [alertColorPalette] and for the same reason: these
 * are a legend the user learns, so they have to mean the same thing under dynamic (wallpaper)
 * theming as they do without it. One mapping, so a change to the palette cannot reach some
 * screens and miss others.
 */
val Severity.color: Color
    get() = when (this) {
        Severity.CLEAR -> SeverityClear
        Severity.MILD -> SeverityMild
        Severity.AURA -> SeverityAura
        Severity.MIGRAINE -> SeverityMigraine
    }
