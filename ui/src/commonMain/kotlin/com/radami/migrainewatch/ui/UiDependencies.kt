package com.radami.migrainewatch.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertReconciler
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.ui.platform.AlertDebugTools
import com.radami.migrainewatch.ui.platform.NotificationPermissions

/**
 * What the shared screens' ViewModels are built from. Hilt fills it on Android and the app
 * graph on iOS, so the ViewModels need no injection framework of their own.
 */
interface UiDependencies {
    val userPreferences: UserPreferences
    val pressureRepository: PressureRepository
    val geocodingApi: GeocodingApi
    val symptomRepository: SymptomRepository
    val alertReconciler: AlertReconciler
    val alertUseCase: PressureAlertUseCase
    val notifiedAlertDao: NotifiedAlertDao
    val notificationPermissions: NotificationPermissions
    val alertDebugTools: AlertDebugTools
    val appInfo: AppInfo
}

/** Provided once at the root of each app, so any screen can build its ViewModel. */
val LocalUiDependencies = staticCompositionLocalOf<UiDependencies> {
    error("No UiDependencies provided; the app's root composable must provide them")
}
