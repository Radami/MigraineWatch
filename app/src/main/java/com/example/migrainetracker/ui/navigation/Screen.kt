package com.example.migrainetracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Today : Screen("today")
    data object Pressure : Screen("pressure")
    data object Calendar : Screen("calendar")
    data object Settings : Screen("settings")
    data object LogEntry : Screen("log_entry/{date}") {
        fun withDate(date: String) = "log_entry/$date"
    }
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Today, "Today", Icons.Default.Home, "Today screen"),
    BottomNavItem(Screen.Pressure, "Pressure", Icons.Default.TrendingDown, "Pressure screen"),
    BottomNavItem(Screen.Calendar, "Calendar", Icons.Default.CalendarMonth, "Calendar screen"),
    BottomNavItem(Screen.Settings, "Settings", Icons.Default.Settings, "Settings screen")
)
