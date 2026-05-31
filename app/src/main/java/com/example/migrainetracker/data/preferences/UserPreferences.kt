package com.example.migrainetracker.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class LocationData(
    val source: String = "",       // "gps" | "manual"
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val name: String = "",
    val timezone: String = "UTC"
)

data class AppSettings(
    val alertThresholdHpa: Float = 6f,
    val notificationsEnabled: Boolean = true,
    val onboardingComplete: Boolean = false,
    val location: LocationData = LocationData()
)

@Singleton
class UserPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val LOCATION_SOURCE = stringPreferencesKey("location_source")
        val LOCATION_LAT = doublePreferencesKey("location_lat")
        val LOCATION_LON = doublePreferencesKey("location_lon")
        val LOCATION_NAME = stringPreferencesKey("location_name")
        val LOCATION_TIMEZONE = stringPreferencesKey("location_timezone")
        val ALERT_THRESHOLD = floatPreferencesKey("alert_threshold_hpa")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            alertThresholdHpa = prefs[Keys.ALERT_THRESHOLD] ?: 6f,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            location = LocationData(
                source = prefs[Keys.LOCATION_SOURCE] ?: "",
                lat = prefs[Keys.LOCATION_LAT] ?: 0.0,
                lon = prefs[Keys.LOCATION_LON] ?: 0.0,
                name = prefs[Keys.LOCATION_NAME] ?: "",
                timezone = prefs[Keys.LOCATION_TIMEZONE] ?: "UTC"
            )
        )
    }

    suspend fun saveLocation(data: LocationData) {
        dataStore.edit { prefs ->
            prefs[Keys.LOCATION_SOURCE] = data.source
            prefs[Keys.LOCATION_LAT] = data.lat
            prefs[Keys.LOCATION_LON] = data.lon
            prefs[Keys.LOCATION_NAME] = data.name
            prefs[Keys.LOCATION_TIMEZONE] = data.timezone
        }
    }

    suspend fun setAlertThreshold(hpa: Float) {
        dataStore.edit { it[Keys.ALERT_THRESHOLD] = hpa }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }
}
