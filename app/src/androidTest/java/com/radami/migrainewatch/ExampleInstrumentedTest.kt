package com.radami.migrainewatch

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.assertEquals

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Compared against BuildConfig, not a literal: the sandbox build type's id has a
        // suffix, and this keeps that detail out of the test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }
}
