package com.radami.migrainewatch.ui.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.radami.migrainewatch.coroutines.catchingFailures
import com.radami.migrainewatch.logging.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TAG = "CurrentLocation"

/** Coarse is enough to pick a forecast, and asks the user for less. */
private const val LOCATION_PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

/** A fix this recent is still where the user is, and comes back at once instead of after a wait. */
private val FRESH_ENOUGH: Duration = 5.minutes

/** How long to wait for a fresh fix before settling for the last one; the user is waiting on it. */
private val LONGEST_WAIT: Duration = 10.seconds

/** Balanced power, as on iOS: about a kilometre is plenty to pick a forecast, and settles fast. */
private val CURRENT_LOCATION_REQUEST: CurrentLocationRequest = CurrentLocationRequest.Builder()
    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    .setMaxUpdateAgeMillis(FRESH_ENOUGH.inWholeMilliseconds)
    .setDurationMillis(LONGEST_WAIT.inWholeMilliseconds)
    .build()

@Composable
actual fun rememberCurrentLocationRequest(onResult: (DevicePosition?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)

    // The read can outlast a recomposition, so it answers through the latest callback.
    val readPosition = { scope.launch { result(context.currentPosition()) } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            result(null)
            return@rememberLauncherForActivityResult
        }

        readPosition()
    }

    return {
        if (context.hasLocationPermission()) readPosition() else launcher.launch(LOCATION_PERMISSION)
    }
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * Where the device is now, as iOS reads it: a recent fix at once, or a fresh one within
 * [LONGEST_WAIT], falling back to the last one the system holds when none comes. The last fix alone is often missing on a phone that hasn't needed one
 * lately, which would send the user to the city search for no reason. Null when neither is there
 * or the system can't be asked; a failure is logged and treated the same, so the user is offered
 * the city search instead.
 */
@SuppressLint("MissingPermission") // Only called once the permission is granted.
private suspend fun Context.currentPosition(): DevicePosition? {
    val client = LocationServices.getFusedLocationProviderClient(this)

    val location = catchingFailures {
        client.getCurrentLocation(CURRENT_LOCATION_REQUEST, null).await()
            ?: client.lastLocation.await()
    }
        .onFailure { Logger.e(TAG, "Could not read the device's location", it) }
        .getOrNull()
        ?: return null

    return DevicePosition(latitude = location.latitude, longitude = location.longitude)
}
