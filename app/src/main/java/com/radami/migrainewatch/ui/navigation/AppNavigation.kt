package com.radami.migrainewatch.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.radami.migrainewatch.ui.screens.calendar.CalendarScreen
import com.radami.migrainewatch.ui.screens.calendar.LogEntryScreen
import com.radami.migrainewatch.ui.screens.onboarding.OnboardingScreen
import com.radami.migrainewatch.ui.screens.pressure.PressureScreen
import com.radami.migrainewatch.ui.screens.settings.SettingsScreen
import com.radami.migrainewatch.ui.screens.today.TodayScreen

/**
 * @param openTab a tab to show on top of [startDestination] once, for a notification that
 *   opens the app on a screen other than the one it normally starts on.
 */
@Composable
fun AppNavigation(startDestination: String, openTab: Screen? = null) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.screen.route }

    // Navigating rather than starting there keeps Today underneath, so back out of an alert
    // leaves the user where the app would have opened anyway.
    LaunchedEffect(openTab) {
        openTab?.let { navController.navigateToTab(it) }
    }

    Scaffold(
        floatingActionButton = {
            // One shared log-symptoms FAB for the three main tabs. The Scaffold slot keeps
            // it pinned to the bottom-right corner, floating above scrolling content.
            if (showBottomBar) {
                FloatingActionButton(
                    onClick = {
                        navController.navigate(
                            Screen.LogEntry.withDate(java.time.LocalDate.now().toString())
                        )
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Log today's symptoms")
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = { navController.navigateToTab(item.screen) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.contentDescription
                                )
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate(Screen.Today.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Today.route) {
                TodayScreen(
                    // Both the banner and the outlook days lead here: they name an event or a
                    // day, and the Pressure tab is where every one of them is listed and shaded
                    // on the chart.
                    onViewPressure = { navController.navigateToTab(Screen.Pressure) },
                    onChangeLocation = { navController.navigate(Screen.Onboarding.route) }
                )
            }
            composable(Screen.Pressure.route) {
                PressureScreen(
                    onChangeLocation = { navController.navigate(Screen.Onboarding.route) }
                )
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(
                    onLogEntry = { date ->
                        navController.navigate(Screen.LogEntry.withDate(date.toString()))
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            composable(Screen.LogEntry.route) { backStackEntry ->
                val dateStr = backStackEntry.arguments?.getString("date") ?: return@composable
                LogEntryScreen(
                    dateStr = dateStr,
                    onSaved = { navController.popBackStack() },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/**
 * Switches to a bottom-bar tab: one entry per tab on the back stack, each remembering where it
 * was left, and back always returning to the start destination rather than retracing the tabs.
 */
private fun NavController.navigateToTab(screen: Screen) {
    navigate(screen.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
