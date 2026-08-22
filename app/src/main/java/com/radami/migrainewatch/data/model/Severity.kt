package com.radami.migrainewatch.data.model

/**
 * Declaration order runs clear to worst, and the screens listing every severity — the calendar
 * legend, the log entry picker — present them in it. Reordering these constants reorders those
 * lists, so keep the progression if one is ever added.
 */
enum class Severity {
    CLEAR, MILD, AURA, MIGRAINE;

    /**
     * Whether the day counts as a symptom event. CLEAR is a logged day *without* one, so it sits
     * on the same side of this line as a day that was never logged.
     */
    val isSymptomEvent: Boolean get() = this != CLEAR
}
