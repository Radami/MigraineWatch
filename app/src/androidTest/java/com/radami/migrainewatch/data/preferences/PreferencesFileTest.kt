package com.radami.migrainewatch.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radami.migrainewatch.di.DataStoreModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pins where settings live on disk. Existing installs keep them in this file, so a store
 * created anywhere else would come up empty and put every user back through onboarding.
 */
@RunWith(AndroidJUnit4::class)
class PreferencesFileTest {

    private companion object {
        /** Relative to the app's files directory. */
        const val STORE_PATH = "datastore/user_prefs.preferences_pb"

        /** Written and removed again, so the app's real settings are left as they were. */
        val PROBE_KEY = stringPreferencesKey("stored_format_probe")
    }

    @Test
    fun settingsAreKeptInTheEstablishedFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore = DataStoreModule.provideDataStore(context)

        runBlocking {
            dataStore.edit { it[PROBE_KEY] = "probe" }
            dataStore.edit { it.remove(PROBE_KEY) }
        }

        assertTrue(File(context.filesDir, STORE_PATH).isFile)
    }
}
