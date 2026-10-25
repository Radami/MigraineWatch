package com.radami.migrainewatch.logging

/**
 * Writes to the platform's own log: Logcat on Android, the system log on iOS. Shared code
 * logs through this rather than a library; the app only needs these three levels.
 */
expect object Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
