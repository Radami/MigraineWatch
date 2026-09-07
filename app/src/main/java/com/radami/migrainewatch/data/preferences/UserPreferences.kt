package com.radami.migrainewatch.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
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
    val alertThresholdHpa: Float = AlertSensitivity.Default.thresholdHpa,
    /**
     * Whether the user wants alerts, not whether the system will deliver them. The runtime
     * permission is tracked separately and must never be written back into this.
     */
    val notificationsEnabled: Boolean = true,
    val notificationPermissionRequested: Boolean = false,
    val onboardingComplete: Boolean = false,
    val location: LocationData = LocationData()
) {
    /** The preset the stored threshold corresponds to, for screens that offer the choice. */
    val alertSensitivity: AlertSensitivity get() = AlertSensitivity.forThreshold(alertThresholdHpa)
}

@Singleton
class UserPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private companion object {
        const val TAG = "UserPreferences"

        /**
         * How long to wait before reading the store again after it refused. Long enough that a
         * store which is broken for good is not re-read in a tight loop for the life of the
         * process, short enough that a transient failure heals while the user is still looking.
         */
        const val READ_RETRY_MILLIS = 10_000L
    }

    private object Keys {
        val LOCATION_SOURCE = stringPreferencesKey("location_source")
        val LOCATION_LAT = doublePreferencesKey("location_lat")
        val LOCATION_LON = doublePreferencesKey("location_lon")
        val LOCATION_NAME = stringPreferencesKey("location_name")
        val LOCATION_TIMEZONE = stringPreferencesKey("location_timezone")
        val ALERT_THRESHOLD = floatPreferencesKey("alert_threshold_hpa")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFICATION_PERMISSION_REQUESTED =
            booleanPreferencesKey("notification_permission_requested")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    /**
     * Falls back to the defaults when the store cannot be read, and keeps reading.
     *
     * DataStore reports a failed read by throwing into the stream, which ends every collector
     * of it. Two of those cannot afford to end: a screen's, where the exception reaches
     * `viewModelScope` and takes the process down, and [
     * com.radami.migrainewatch.data.repository.PressureRepository]'s watch for the user moving,
     * which is a single coroutine started once — its death is silent, and refetch-on-move
     * simply stops until the app is restarted.
     *
     * [retryWhen] rather than `catch` for exactly that second reason. `catch` emits and then
     * lets the flow complete, which leaves a collector no better off than an exception would: it
     * returns, quietly, and never hears anything again. Resubscribing keeps the stream open, so
     * a store that becomes readable again — a transient IO error, a device that was out of space
     * — is picked up rather than waited out until the next launch.
     *
     * Only [IOException], which is what a store that cannot be read raises; anything else is a
     * bug in the reading rather than in the file, and is left to surface.
     */
    val settings: Flow<AppSettings> = dataStore.data.retryWhen { cause, attempt ->
        if (cause !is IOException) return@retryWhen false
        Log.e(TAG, "Could not read settings; falling back to defaults", cause)

        // Once, on the first failure. Collectors need something to work with, but a fallback
        // republished on every attempt would have every screen recompute itself on a timer for
        // as long as the store stayed broken.
        if (attempt == 0L) emit(emptyPreferences())

        delay(READ_RETRY_MILLIS)
        true
    }.map { prefs ->
        AppSettings(
            alertThresholdHpa = prefs[Keys.ALERT_THRESHOLD] ?: AlertSensitivity.Default.thresholdHpa,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            notificationPermissionRequested =
                prefs[Keys.NOTIFICATION_PERMISSION_REQUESTED] ?: false,
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

    /** Takes the preset rather than a raw value so an off-menu threshold can't be stored. */
    suspend fun setAlertSensitivity(sensitivity: AlertSensitivity) {
        dataStore.edit { it[Keys.ALERT_THRESHOLD] = sensitivity.thresholdHpa }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    /**
     * Records that the runtime permission dialog has been shown once. Android will not show it
     * again after a denial, so this is what separates "not asked yet" from "refused".
     */
    suspend fun setNotificationPermissionRequested(requested: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_PERMISSION_REQUESTED] = requested }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }
}
