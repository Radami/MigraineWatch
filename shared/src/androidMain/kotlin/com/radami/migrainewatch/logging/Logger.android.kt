package com.radami.migrainewatch.logging

import android.util.Log

actual object Logger {
    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    actual fun w(tag: String, message: String, error: Throwable?) {
        Log.w(tag, message, error)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        Log.e(tag, message, error)
    }
}
