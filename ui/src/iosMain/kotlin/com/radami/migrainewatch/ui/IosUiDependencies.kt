package com.radami.migrainewatch.ui

import com.radami.migrainewatch.IosAppGraph
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertReconciler
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.ui.platform.AlertDebugTools
import com.radami.migrainewatch.ui.platform.IosAlertDebugTools
import com.radami.migrainewatch.ui.platform.IosNotificationPermissions
import com.radami.migrainewatch.ui.platform.NotificationPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSBundle
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

private const val DISPLAY_NAME_KEY = "CFBundleDisplayName"
private const val VERSION_KEY = "CFBundleShortVersionString"

/**
 * The shared screens' dependencies, filled from the iOS app graph. Create one for the app's
 * lifetime, next to the graph it reads from.
 */
class IosUiDependencies(graph: IosAppGraph) : UiDependencies {

    /** Outlives every screen, so a debug action keeps running after the screen that started it closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val userPreferences: UserPreferences = graph.userPreferences
    override val pressureRepository: PressureRepository = graph.pressureRepository
    override val geocodingApi: GeocodingApi = graph.geocodingApi
    override val symptomRepository: SymptomRepository = graph.symptomRepository
    override val alertReconciler: AlertReconciler = graph.alertReconciler
    override val alertUseCase: PressureAlertUseCase = graph.alertUseCase
    override val notifiedAlertDao: NotifiedAlertDao = graph.database.notifiedAlertDao()
    override val notificationPermissions: NotificationPermissions = IosNotificationPermissions()
    override val alertDebugTools: AlertDebugTools = IosAlertDebugTools(graph, scope)
    override val appInfo: AppInfo = bundleAppInfo()
}

/** The name and version from the app's Info.plist; the build type from how Kotlin was compiled. */
@OptIn(ExperimentalNativeApi::class)
private fun bundleAppInfo(): AppInfo = AppInfo(
    name = infoString(DISPLAY_NAME_KEY),
    version = infoString(VERSION_KEY),
    buildType = if (Platform.isDebugBinary) BuildType.DEBUG else BuildType.RELEASE
)

private fun infoString(key: String): String =
    checkNotNull(NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String) { "Info.plist has no $key" }
