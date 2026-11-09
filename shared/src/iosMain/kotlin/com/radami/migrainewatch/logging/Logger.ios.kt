package com.radami.migrainewatch.logging

import platform.Foundation.NSLog

/** Levels Logcat shows as a letter; NSLog has none, so each line carries its own. */
private enum class Level(val letter: String) {
    Debug("D"),
    Warning("W"),
    Error("E")
}

actual object Logger {
    actual fun d(tag: String, message: String) {
        write(Level.Debug, tag, message, error = null)
    }

    actual fun w(tag: String, message: String, error: Throwable?) {
        write(Level.Warning, tag, message, error)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        write(Level.Error, tag, message, error)
    }

    /**
     * Passed as the format itself, escaped so a `%` in a message can't be read as a specifier.
     * Not as a `%@` argument: NSLog's variadic arguments crash when called from Kotlin/Native.
     */
    private fun write(level: Level, tag: String, message: String, error: Throwable?) {
        val line = buildString {
            append("${level.letter}/$tag: $message")
            error?.let { append('\n').append(it.stackTraceToString()) }
        }
        NSLog(line.replace(FORMAT_SPECIFIER, ESCAPED_SPECIFIER))
    }
}

private const val FORMAT_SPECIFIER = "%"
private const val ESCAPED_SPECIFIER = "%%"
