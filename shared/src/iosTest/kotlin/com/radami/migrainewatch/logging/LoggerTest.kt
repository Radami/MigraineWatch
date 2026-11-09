package com.radami.migrainewatch.logging

import kotlin.test.Test

/** Logging has no output to assert on; what matters is that writing a line never takes the app down. */
class LoggerTest {

    @Test
    fun aDebugLineIsWritten() {
        Logger.d("LoggerTest", "plain line")
    }

    @Test
    fun aWarningWithAnErrorIsWritten() {
        Logger.w("LoggerTest", "warning", IllegalStateException("cause"))
    }

    @Test
    fun aPercentSignInTheMessageIsWrittenAsIs() {
        Logger.e("LoggerTest", "100% of %@ and %d", error = null)
    }
}
