package com.radami.migrainewatch.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertReconciler
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.ReconcileResult
import com.radami.migrainewatch.format.formatHpa
import com.radami.migrainewatch.notifications.NotificationPermissionState
import com.radami.migrainewatch.ui.UiDependencies
import com.radami.migrainewatch.ui.platform.AlertDebugTools
import com.radami.migrainewatch.ui.platform.NotificationPermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

class SettingsViewModel(
    private val userPreferences: UserPreferences,
    private val symptomRepository: SymptomRepository,
    private val alertReconciler: AlertReconciler,
    private val permissions: NotificationPermissions,
    // Debug section only.
    private val alertUseCase: PressureAlertUseCase,
    private val notifiedAlertDao: NotifiedAlertDao,
    private val debugTools: AlertDebugTools
) : ViewModel() {

    companion object {
        /** A few actions in quick succession should all get a snackbar. */
        private const val MESSAGE_BUFFER = 4

        /** Builds it from the app's dependencies, the same way on every platform. */
        fun create(dependencies: UiDependencies) = SettingsViewModel(
            userPreferences = dependencies.userPreferences,
            symptomRepository = dependencies.symptomRepository,
            alertReconciler = dependencies.alertReconciler,
            permissions = dependencies.notificationPermissions,
            alertUseCase = dependencies.alertUseCase,
            notifiedAlertDao = dependencies.notifiedAlertDao,
            debugTools = dependencies.alertDebugTools
        )
    }

    private val _permission = MutableStateFlow(NotificationPermissionState.GRANTED)

    private val _trackingSince = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<SettingsState> = combine(
        userPreferences.settings,
        symptomRepository.getTotalCount(),
        _permission,
        _trackingSince
    ) { settings, count, permission, trackingSince ->
        SettingsState(
            alertSensitivity = settings.alertSensitivity,
            notificationsEnabled = settings.notificationsEnabled,
            permission = permission,
            totalEntries = count,
            trackingSince = trackingSince
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    init {
        viewModelScope.launch {
            _trackingSince.value = symptomRepository.getEarliestDate()
        }
    }

    /** The queued warnings follow on their own: the app's reconcile monitor watches this setting. */
    fun setAlertSensitivity(sensitivity: AlertSensitivity) {
        viewModelScope.launch {
            userPreferences.setAlertSensitivity(sensitivity)
        }
    }

    /**
     * Stores intent only. A missing permission is never folded in here: it would leave the
     * user's answer unrecoverable once the system state changed underneath it. Switching off
     * cancels everything pending and switching on rebuilds it, through the reconcile monitor.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotificationsEnabled(enabled)
        }
    }

    /**
     * Re-reads what the system allows. Called every time the screen resumes, because the
     * permission can be revoked while the app sits in the background.
     */
    fun refreshPermissionState() {
        viewModelScope.launch {
            _permission.value = permissions.currentState()
        }
    }

    // Android shows the permission dialog only once, so mark it requested before re-reading:
    // a denial must resolve to BLOCKED, not a dead retry button.
    fun onPermissionRequestFinished() {
        viewModelScope.launch {
            permissions.markRequested()
            _permission.value = permissions.currentState()
        }
    }

    // ---- Debug-only. The settings section driving all of this is composed only in debug
    // builds, so none of it is reachable in a release build.

    private val _debugMessages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)

    /** One line per action for the debug snackbar; includes reconciles triggered elsewhere too. */
    val debugMessages: Flow<String> = merge(
        _debugMessages,
        alertReconciler.results.map { it.describe() }
    )

    /** Runs the production fetch-and-reconcile path now; result arrives on [debugMessages]. */
    fun runAlertCheckNow() {
        debugTools.runAlertCheckNow()
    }

    /** Posts the next warning now, ignoring lead time and notified history. Records nothing, so the real alert still fires later. */
    fun previewNextAlert() {
        viewModelScope.launch {
            val now = Clock.System.now()
            val next = alertUseCase.currentAlerts(now)
                .filter { it.end > now }
                .minByOrNull { it.start }

            if (next == null) {
                _debugMessages.emit("No upcoming event to preview")
                return@launch
            }

            val phase = AlertPhase.of(next, now)
            val zone = userPreferences.settings.first().location.zone
            val message = when {
                debugTools.postNow(next, phase, zone) ->
                    "Sent: ${formatHpa(next.delta)} hPa ${next.direction.wireName} ($phase)"
                else -> "Blocked — notifications are not permitted"
            }
            _debugMessages.emit(message)
        }
    }

    /** Clears announced-event history so events can be re-tested; otherwise they look like a broken alert. */
    fun clearNotificationHistory() {
        viewModelScope.launch {
            notifiedAlertDao.deleteAll()
            _debugMessages.emit("Alert history cleared")

            // Events the history was hiding qualify again, so put them back in the queue.
            alertReconciler.reconcile()
        }
    }

    private fun ReconcileResult.describe(): String = when (this) {
        is ReconcileResult.Success -> "$pending pending · $cancelled cancelled"
        is ReconcileResult.Failed -> "Reconcile failed: ${cause.message ?: cause::class.simpleName}"
    }
}
