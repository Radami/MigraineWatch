package com.radami.migrainewatch.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * How long switching tabs crossfades: Android's own default for navigation, so its tabs keep the
 * feel they had, and iOS's tabs now share it.
 */
private const val TAB_CROSSFADE_MILLIS = 700

/** The routes the bottom bar switches between. */
private val TAB_ROUTES: Set<String> = bottomNavItems.mapTo(mutableSetOf()) { it.screen.route }

private typealias Transitions = AnimatedContentTransitionScope<NavBackStackEntry>

/** Whether the change on screen is from one bottom-bar tab to another. */
private fun Transitions.isBetweenTabs(): Boolean =
    initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES

/**
 * Tabs crossfade on every platform, as they always have on Android: iOS has no sideways slide
 * between tabs either. Everything else returns null, which leaves the platform's own transition
 * in place: a crossfade on Android, and on iOS the slide of a screen pushed onto the stack.
 */
private val tabEnter: Transitions.() -> EnterTransition? = {
    if (isBetweenTabs()) fadeIn(tween(TAB_CROSSFADE_MILLIS)) else null
}

private val tabExit: Transitions.() -> ExitTransition? = {
    if (isBetweenTabs()) fadeOut(tween(TAB_CROSSFADE_MILLIS)) else null
}

/**
 * A destination drawn on the app's background, so a screen entering over another hides it.
 * Screens paint no background of their own, and the scaffold's sits behind both screens during
 * a transition: iOS's slide would otherwise show the old screen through the new one.
 */
internal fun NavGraphBuilder.screen(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    composable(
        route = route,
        enterTransition = tabEnter,
        exitTransition = tabExit,
        popEnterTransition = tabEnter,
        popExitTransition = tabExit
    ) { entry ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            content(entry)
        }
    }
}
