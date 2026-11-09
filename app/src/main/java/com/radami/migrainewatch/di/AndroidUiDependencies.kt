package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertReconciler
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.ui.AppInfo
import com.radami.migrainewatch.ui.UiDependencies
import com.radami.migrainewatch.ui.platform.AlertDebugTools
import com.radami.migrainewatch.ui.platform.NotificationPermissions
import javax.inject.Inject

/** The shared screens' dependencies, filled from Hilt's graph. */
class AndroidUiDependencies @Inject constructor(
    override val userPreferences: UserPreferences,
    override val pressureRepository: PressureRepository,
    override val geocodingApi: GeocodingApi,
    override val symptomRepository: SymptomRepository,
    override val alertReconciler: AlertReconciler,
    override val alertUseCase: PressureAlertUseCase,
    override val notifiedAlertDao: NotifiedAlertDao,
    override val notificationPermissions: NotificationPermissions,
    override val alertDebugTools: AlertDebugTools,
    override val appInfo: AppInfo
) : UiDependencies
