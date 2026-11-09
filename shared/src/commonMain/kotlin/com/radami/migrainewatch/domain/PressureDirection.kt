package com.radami.migrainewatch.domain

/**
 * Which way pressure moved across an event.
 *
 * [wireName] is the persisted spelling (DB column, work names, notification ids). Deliberately
 * not [name], so renaming an enum constant can't orphan or re-announce existing warnings.
 */
enum class PressureDirection(val wireName: String) {
    DROP("drop"),
    RISE("rise");

    companion object {
        /**
         * The direction [wireName] names, or null if it names none. Stored data can outlive
         * the code that wrote it, so callers must handle an unreadable value themselves.
         */
        fun ofWireName(wireName: String): PressureDirection? =
            entries.firstOrNull { it.wireName == wireName }
    }
}
