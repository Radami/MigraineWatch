package com.radami.migrainewatch.data.model

/**
 * Declaration order runs clear to worst; screens that list severities (calendar legend,
 * log entry picker) rely on it. Keep the progression if a new one is added.
 */
enum class Severity {
    CLEAR, MILD, AURA, MIGRAINE;

    /** Whether the day counts as a symptom event; CLEAR does not, same as never logged. */
    val isSymptomEvent: Boolean get() = this != CLEAR
}
