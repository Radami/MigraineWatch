package com.radami.migrainewatch.domain

/**
 * Which way pressure moved across an event.
 *
 * [wireName] is the spelling that leaves the process: the `notified_alerts` column, the
 * notification worker's input data, the unique work name of a scheduled warning and the
 * notification id are all built from it, and every one of those has to keep matching what an
 * earlier install wrote. It is deliberately not [name] — renaming a constant here must not
 * orphan a warning that is already scheduled or re-announce an event already delivered.
 */
enum class PressureDirection(val wireName: String) {
    DROP("drop"),
    RISE("rise");

    companion object {
        /**
         * The direction [wireName] names, or null if it names none. Stored rows and work
         * input outlive the code that wrote them, so the caller has to decide what an
         * unreadable direction means rather than being handed a wrong one.
         */
        fun ofWireName(wireName: String): PressureDirection? =
            entries.firstOrNull { it.wireName == wireName }
    }
}
