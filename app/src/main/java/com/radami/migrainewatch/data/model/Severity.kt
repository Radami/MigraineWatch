package com.radami.migrainewatch.data.model

enum class Severity {
    CLEAR, MILD, AURA, MIGRAINE;

    /**
     * How a severity is spelled wherever the user sees one: "Clear", "Mild", "Aura", "Migraine".
     * Kept in one place so the screens showing a severity cannot drift into different wording.
     */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * Whether the day counts as a symptom event. CLEAR is a logged day *without* one, so it sits
     * on the same side of this line as a day that was never logged.
     */
    val isSymptomEvent: Boolean get() = this != CLEAR
}
