package com.radami.migrainewatch.format

import com.radami.migrainewatch.data.model.Severity

/**
 * How a severity is spelled wherever the user sees one.
 *
 * Copy rather than a derivation of the enum constant names, so renaming a constant cannot
 * silently reword the app. It lives here and not on [Severity] for the same reason the alert
 * sensitivity presets get their labels in the settings screen: the data layer stays free of
 * user-facing wording. One mapping also means every screen showing a severity cannot drift
 * into different spellings of it.
 */
val Severity.label: String
    get() = when (this) {
        Severity.CLEAR -> "Clear"
        Severity.MILD -> "Mild"
        Severity.AURA -> "Aura"
        Severity.MIGRAINE -> "Migraine"
    }

/**
 * What each severity means, in the words the log entry picker offers them in.
 *
 * Only the picker spells a severity out this far — everywhere else a [label] and its colour are
 * enough. It sits beside [label] anyway so all severity wording is in one file: a reworded
 * severity is one edit, not a hunt through the screens.
 */
val Severity.description: String
    get() = when (this) {
        Severity.CLEAR -> "No symptoms today"
        Severity.MILD -> "Manageable ache or tension"
        Severity.AURA -> "Visual / sensory warning signs"
        Severity.MIGRAINE -> "Full episode, hard to function"
    }
