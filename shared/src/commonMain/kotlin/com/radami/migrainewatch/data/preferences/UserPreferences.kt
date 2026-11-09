package com.radami.migrainewatch.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.radami.migrainewatch.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone
import okio.IOException

/** Stored when a location's zone hasn't been looked up yet; see [LocationData.knownZone]. */
const val UNKNOWN_TIMEZONE = ""

/**
 * What GPS locations stored before the zone was looked up at all. Not a real lookup result,
 * so it counts as unknown too, and those installs repair it on their next refresh.
 */
private const val LEGACY_UNKNOWN_TIMEZONE = "UTC"

data class LocationData(
    val source: String = "",       // "gps" | "manual"
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val name: String = "",
    val timezone: String = UNKNOWN_TIMEZONE
) {
    /**
     * The location's own zone, or null until it's known. Unknown for a GPS fix, which comes
     * without one, and for a city the geocoder gave none for; the next forecast fetch looks
     * it up from the coordinates. An unparseable id counts as unknown so that lookup
     * replaces it.
     */
    val knownZone: TimeZone?
        get() {
            if (timezone == UNKNOWN_TIMEZONE || timezone == LEGACY_UNKNOWN_TIMEZONE) return null

            return try {
                TimeZone.of(timezone)
            } catch (e: IllegalTimeZoneException) {
                null
            }
        }

    /**
     * The zone the app counts this location's days and times in. Until the location's own is
     * known, the device's: an unknown zone is almost always a GPS fix, so the user is there.
     */
    val zone: TimeZone get() = knownZone ?: TimeZone.currentSystemDefault()
}

data class AppSettings(
    val alertThresholdHpa: Float = AlertSensitivity.Default.thresholdHpa,
    /** Whether the user wants alerts, not whether the system will deliver them. Tracked separately. */
    val notificationsEnabled: Boolean = true,
    val notificationPermissionRequested: Boolean = false,
    val onboardingComplete: Boolean = false,
    val location: LocationData = LocationData()
) {
    /** The preset the stored threshold corresponds to, for screens that offer the choice. */
    val alertSensitivity: AlertSensitivity get() = AlertSensitivity.forThreshold(alertThresholdHpa)
}

class UserPreferences(
    private val dataStore: DataStore<Preferences>
) {
    private companion object {
        const val TAG = "UserPreferences"

        /** Delay before retrying a failed read: long enough to avoid a tight loop, short enough to heal fast. */
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
     * Falls back to defaults on a read failure and keeps the flow alive: letting it throw
     * would crash a screen's viewModelScope and kill PressureRepository's move-watcher.
     * [retryWhen] resubscribes so it recovers if the store heals; `catch` would not.
     */
    val settings: Flow<AppSettings> = dataStore.data.retryWhen { cause, attempt ->
        if (cause !is IOException) return@retryWhen false
        Logger.e(TAG, "Could not read settings; falling back to defaults", cause)

        // Emit only once, on the first failure, not on every retry attempt.
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
                timezone = prefs[Keys.LOCATION_TIMEZONE] ?: UNKNOWN_TIMEZONE
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

    /**
     * Records [timezone] as [location]'s zone, unless the stored location has moved on since:
     * a lookup that finishes after the user picked somewhere else must not relabel it.
     */
    suspend fun adoptTimezone(location: LocationData, timezone: String) {
        dataStore.edit { prefs ->
            val sameLocation = prefs[Keys.LOCATION_LAT] == location.lat &&
                prefs[Keys.LOCATION_LON] == location.lon
            if (!sameLocation) return@edit

            prefs[Keys.LOCATION_TIMEZONE] = timezone
        }
    }

    /** Takes the preset rather than a raw value so an off-menu threshold can't be stored. */
    suspend fun setAlertSensitivity(sensitivity: AlertSensitivity) {
        dataStore.edit { it[Keys.ALERT_THRESHOLD] = sensitivity.thresholdHpa }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    /** Marks the permission dialog as shown; separates "not asked yet" from "refused". */
    suspend fun setNotificationPermissionRequested(requested: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_PERMISSION_REQUESTED] = requested }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }
}
