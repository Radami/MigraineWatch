package com.radami.migrainewatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radami.migrainewatch.ui.navigation.AppNavigation
import com.radami.migrainewatch.ui.navigation.Screen
import com.radami.migrainewatch.ui.theme.MigraineWatchTheme

/**
 * The whole app, the same on every platform: the theme, the dependencies every screen builds
 * from, and onboarding or the main tabs depending on whether a place has been set.
 *
 * @param openTab a tab to show on top of Today once, for a notification that opens the app on
 *   a different screen than usual.
 * @param onTabOpened called once [openTab] is shown, so a caller can clear the request.
 */
@Composable
fun MigraineWatchApp(dependencies: UiDependencies, openTab: Screen? = null, onTabOpened: () -> Unit = {}) {
    // The shared screens build their ViewModels from these.
    CompositionLocalProvider(LocalUiDependencies provides dependencies) {
        MigraineWatchTheme {
            AppContent(dependencies = dependencies, openTab = openTab, onTabOpened = onTabOpened)
        }
    }
}

@Composable
private fun AppContent(
    dependencies: UiDependencies,
    openTab: Screen?,
    onTabOpened: () -> Unit,
    viewModel: AppViewModel = viewModel { AppViewModel.create(dependencies) }
) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val locationZone by viewModel.locationZone.collectAsStateWithLifecycle()

    // Nothing to draw until the settings load: guessing would flash the wrong first screen.
    val complete = onboardingComplete ?: return

    AppNavigation(
        startDestination = if (complete) Screen.Today.route else Screen.Onboarding.route,
        locationZone = locationZone,
        // A tab asked for mid-onboarding would land on a screen with no location to draw, so
        // it waits until there is an app to open.
        openTab = openTab.takeIf { complete },
        onTabOpened = onTabOpened
    )
}
