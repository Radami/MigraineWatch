package com.radami.migrainewatch.ui.platform

import androidx.compose.runtime.Composable

/**
 * Remembers an action that shows the system's notification-permission prompt, then calls
 * [onFinished] whatever the answer. The answer itself is not passed on: the screen re-reads the
 * live system state, so a denial can't overwrite the user's standing request for alerts.
 */
@Composable
expect fun rememberNotificationPermissionRequest(onFinished: () -> Unit): () -> Unit

/** Remembers an action that opens the system screen where the app's alerts can be turned back on. */
@Composable
expect fun rememberOpenNotificationSettings(): () -> Unit

/** What the system that can block the app's notifications is called, for the warning that says so. */
expect val systemName: String
