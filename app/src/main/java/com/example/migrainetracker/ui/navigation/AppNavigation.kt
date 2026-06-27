package com.example.migrainetracker.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.migrainetracker.AlertDetailActivity
import com.example.migrainetracker.ui.screens.calendar.CalendarScreen
import com.example.migrainetracker.ui.screens.calendar.LogEntryScreen
import com.example.migrainetracker.ui.screens.onboarding.OnboardingScreen
import com.example.migrainetracker.ui.screens.pressure.PressureScreen
import com.example.migrainetracker.ui.screens.settings.SettingsScreen
import com.example.migrainetracker.ui.screens.today.TodayScreen

@Composable
fun AppNavigation(startDestination: String) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.screen.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
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
                    onAlertTap = { alerts ->
                        val first = alerts.first()
                        context.startActivity(
                            Intent(context, AlertDetailActivity::class.java).apply {
                                putExtra("startEpoch", first.start.epochSecond)
                                putExtra("endEpoch", first.end.epochSecond)
                                putExtra("delta", first.delta)
                                putExtra("direction", first.direction)
                                putExtra("allStartEpochs", alerts.map { it.start.epochSecond }.toLongArray())
                                putExtra("allEndEpochs", alerts.map { it.end.epochSecond }.toLongArray())
                                putExtra("allDeltas", alerts.map { it.delta }.toFloatArray())
                                putExtra("allDirections", alerts.joinToString(",") { it.direction })
                            }
                        )
                    },
                    onLogTap = {
                        navController.navigate(
                            Screen.LogEntry.withDate(java.time.LocalDate.now().toString())
                        )
                    },
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
