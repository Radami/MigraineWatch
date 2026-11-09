package com.radami.migrainewatch.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the keys and value types settings are stored under. Existing installs hold their
 * settings under exactly these, so renaming one would silently reset that setting.
 */
class StoredPreferencesTest {

    private companion object {
        const val STORE_FILE = "user_prefs.preferences_pb"
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferences: UserPreferences

    private val kathmandu = LocationData(
        source = "manual",
        lat = 27.7172,
        lon = 85.324,
        name = "Kathmandu, Nepal",
        timezone = "Asia/Kathmandu"
    )

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve(STORE_FILE) }
        preferences = UserPreferences(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `settings are written under their established keys`() = runBlocking {
        preferences.saveLocation(kathmandu)
        preferences.setAlertSensitivity(AlertSensitivity.HIGH)
        preferences.setNotificationsEnabled(false)
        preferences.setNotificationPermissionRequested(true)
        preferences.setOnboardingComplete(true)

        val stored = dataStore.data.first().asMap().mapKeys { (key, _) -> key.name }

        assertEquals(
            mapOf(
                "location_source" to "manual",
                "location_lat" to 27.7172,
                "location_lon" to 85.324,
                "location_name" to "Kathmandu, Nepal",
                "location_timezone" to "Asia/Kathmandu",
                "alert_threshold_hpa" to AlertSensitivity.HIGH.thresholdHpa,
                "notifications_enabled" to false,
                "notification_permission_requested" to true,
                "onboarding_complete" to true
            ),
            stored
        )
    }

    @Test
    fun `settings stored under the established keys are read back`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("location_source")] = "manual"
            prefs[doublePreferencesKey("location_lat")] = 27.7172
            prefs[doublePreferencesKey("location_lon")] = 85.324
            prefs[stringPreferencesKey("location_name")] = "Kathmandu, Nepal"
            prefs[stringPreferencesKey("location_timezone")] = "Asia/Kathmandu"
            prefs[floatPreferencesKey("alert_threshold_hpa")] = AlertSensitivity.HIGH.thresholdHpa
            prefs[booleanPreferencesKey("notifications_enabled")] = false
            prefs[booleanPreferencesKey("notification_permission_requested")] = true
            prefs[booleanPreferencesKey("onboarding_complete")] = true
        }

        assertEquals(
            AppSettings(
                alertThresholdHpa = AlertSensitivity.HIGH.thresholdHpa,
                notificationsEnabled = false,
                notificationPermissionRequested = true,
                onboardingComplete = true,
                location = kathmandu
            ),
            preferences.settings.first()
        )
    }
}
