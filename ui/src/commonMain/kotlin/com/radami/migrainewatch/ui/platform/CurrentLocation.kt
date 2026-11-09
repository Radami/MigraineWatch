package com.radami.migrainewatch.ui.platform

import androidx.compose.runtime.Composable

/** Where the device is, as precisely as the user allowed: enough to pick a forecast for. */
data class DevicePosition(val latitude: Double, val longitude: Double)

/**
 * Remembers an action that asks for the location permission when the app doesn't have it yet,
 * then reads where the device is, once. [onResult] gets null when the user refuses or the device
 * has no position to give, so the screen can offer another way to pick a place.
 */
@Composable
expect fun rememberCurrentLocationRequest(onResult: (DevicePosition?) -> Unit): () -> Unit
