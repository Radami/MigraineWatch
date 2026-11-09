package com.radami.migrainewatch.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.radami.migrainewatch.logging.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSError
import platform.darwin.NSObject

private const val TAG = "CurrentLocation"

@Composable
actual fun rememberCurrentLocationRequest(onResult: (DevicePosition?) -> Unit): () -> Unit {
    val result by rememberUpdatedState(onResult)

    // Created during composition, so on the main thread, where Core Location then calls back.
    val reader = remember { OneShotLocationReader { result(it) } }

    return remember(reader) { { reader.request() } }
}

/**
 * Asks Core Location for one position per [request], asking for permission first when the user
 * hasn't answered yet. Holds its own delegate: the manager only keeps a weak reference to it.
 */
private class OneShotLocationReader(private val onResult: (DevicePosition?) -> Unit) {

    private val manager = CLLocationManager().apply {
        // A kilometre is plenty to pick a forecast, and settles faster than a precise fix.
        desiredAccuracy = kCLLocationAccuracyKilometer
    }

    private val delegate = Delegate()

    /** Whether a request is waiting for the user's answer to the permission prompt. */
    private var awaitingPermission = false

    init {
        manager.delegate = delegate
    }

    fun request() {
        val status = manager.authorizationStatus

        // Nobody has answered yet: ask, and carry on once the delegate hears the answer.
        if (status == kCLAuthorizationStatusNotDetermined) {
            awaitingPermission = true
            manager.requestWhenInUseAuthorization()
            return
        }

        readOrGiveUp(status)
    }

    private fun readOrGiveUp(status: CLAuthorizationStatus) {
        if (!status.allowsLocation()) {
            onResult(null)
            return
        }

        manager.requestLocation()
    }

    private fun CLAuthorizationStatus.allowsLocation(): Boolean =
        this == kCLAuthorizationStatusAuthorizedWhenInUse || this == kCLAuthorizationStatusAuthorizedAlways

    private inner class Delegate : NSObject(), CLLocationManagerDelegateProtocol {

        /** Also called once when the manager is created, which no request is waiting on. */
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (!awaitingPermission) return
            if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return

            awaitingPermission = false
            readOrGiveUp(manager.authorizationStatus)
        }

        @OptIn(ExperimentalForeignApi::class)
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation
            if (location == null) {
                onResult(null)
                return
            }

            val position = location.coordinate.useContents {
                DevicePosition(latitude = latitude, longitude = longitude)
            }
            onResult(position)
        }

        /** No fix this time; the user is offered the city search instead. */
        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            Logger.e(TAG, "Could not read the device's location: ${didFailWithError.localizedDescription}")
            onResult(null)
        }
    }
}
