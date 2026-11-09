package com.radami.migrainewatch.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The whole shared app as a view controller, for the Swift app to show full screen. Opens the
 * tab a tapped notification asks for, whether the tap launched the app or arrived while it ran.
 */
fun MainViewController(dependencies: UiDependencies, notificationTaps: NotificationTaps): UIViewController =
    ComposeUIViewController {
        val requestedTab by notificationTaps.requestedTab.collectAsState()

        MigraineWatchApp(
            dependencies = dependencies,
            openTab = requestedTab,
            onTabOpened = notificationTaps::tabShown
        )
    }
