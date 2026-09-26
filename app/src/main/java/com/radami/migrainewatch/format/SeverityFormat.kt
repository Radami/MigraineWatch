package com.radami.migrainewatch.format

import com.radami.migrainewatch.data.model.Severity

/**
 * How a severity is spelled wherever the user sees one. Copy rather than a derivation of the
 * enum constant names, so renaming a constant can't silently reword the app, and every screen
 * shares one spelling.
 */
val Severity.label: String
    get() = when (this) {
        Severity.CLEAR -> "Clear"
        Severity.MILD -> "Mild"
        Severity.AURA -> "Aura"
        Severity.MIGRAINE -> "Migraine"
    }

/**
 * What each severity means, in the words the log entry picker offers them in. Sits beside
 * [label] so all severity wording lives in one file.
 */
val Severity.description: String
    get() = when (this) {
        Severity.CLEAR -> "No symptoms today"
        Severity.MILD -> "Manageable ache or tension"
        Severity.AURA -> "Visual / sensory warning signs"
        Severity.MIGRAINE -> "Full episode, hard to function"
    }
