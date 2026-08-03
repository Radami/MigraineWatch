package com.radami.migrainewatch.ui.screens.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import androidx.work.WorkManager
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertNotificationScheduler
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.ReconcileResult
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.formatHpa
import com.radami.migrainewatch.notifications.AlertNotifier
import com.radami.migrainewatch.notifications.NotificationPermissionMonitor
import com.radami.migrainewatch.notifications.NotificationPermissionState
import com.radami.migrainewatch.workers.PressureFetchWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class SettingsUiState(
    val alertSensitivity: AlertSensitivity = AlertSensitivity.Default,
    /** What the user asked for, which is not the same as what the system will deliver. */
    val notificationsEnabled: Boolean = true,
    // Assumed granted until the first check answers, so the warning row does not flash on
    // every entry to the screen.
    val permission: NotificationPermissionState = NotificationPermissionState.GRANTED,
    val totalEntries: Int = 0,
    val trackingSince: String = ""
) {
    /** The switch tracks delivery: on means an alert would actually arrive. */
    val alertsDelivering: Boolean
        get() = notificationsEnabled && permission == NotificationPermissionState.GRANTED

    /** Wanted but undeliverable — the silent failure the warning row exists to explain. */
    val alertsBlocked: Boolean
        get() = notificationsEnabled && permission != NotificationPermissionState.GRANTED
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val symptomRepository: SymptomRepository,
    private val alertScheduler: AlertNotificationScheduler,
    private val permissionMonitor: NotificationPermissionMonitor,
    private val workManager: WorkManager,
    // Debug section only.
    private val alertUseCase: PressureAlertUseCase,
    private val notifiedAlertDao: NotifiedAlertDao,
    private val notifier: AlertNotifier
) : ViewModel() {

    private companion object {
        /** A few actions in quick succession should all get a snackbar. */
        const val MESSAGE_BUFFER = 4
    }

    private val _permission = MutableStateFlow(NotificationPermissionState.GRANTED)

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferences.settings,
        symptomRepository.getTotalCount(),
        _permission
    ) { settings, count, permission ->
        SettingsUiState(
            alertSensitivity = settings.alertSensitivity,
            notificationsEnabled = settings.notificationsEnabled,
            permission = permission,
            totalEntries = count
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _trackingSince = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val earliest = symptomRepository.getEarliestDate()
            if (earliest != null) {
                _trackingSince.value = earliest.format(AppDateFormats.MONTH_AND_YEAR)
            }
        }
    }

    val trackingSince: StateFlow<String> = _trackingSince.asStateFlow()

    fun setAlertSensitivity(sensitivity: AlertSensitivity) {
        viewModelScope.launch {
            userPreferences.setAlertSensitivity(sensitivity)
            // Events that no longer clear the new threshold lose their pending warning, and
            // events that now clear it gain one.
            alertScheduler.reconcile()
        }
    }

    /**
     * Stores intent only. A missing permission is never folded in here: it would leave the
     * user's answer unrecoverable once the system state changed underneath it.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotificationsEnabled(enabled)
            // Switching off cancels everything pending; switching on rebuilds it.
            alertScheduler.reconcile()
        }
    }

    /**
     * Re-reads what the system allows. Called every time the screen resumes, because the
     * permission can be revoked while the app sits in the background.
     */
    fun refreshPermissionState() {
        viewModelScope.launch {
            _permission.value = permissionMonitor.currentState()
        }
    }

    /**
     * The dialog is only ever shown once by Android, so record that it happened before
     * re-reading: a denial has to resolve to [NotificationPermissionState.BLOCKED], which is
     * what sends the user to the system settings instead of a button that does nothing.
     */
    /** Where a blocked app has to go to be re-enabled; the screen starts it. */
    fun notificationSettingsIntent(): Intent = permissionMonitor.appNotificationSettingsIntent()

    fun onPermissionRequestFinished() {
        viewModelScope.launch {
            permissionMonitor.markRequested()
            _permission.value = permissionMonitor.currentState()
        }
    }

    // ---- Debug-only. The settings section driving all of this is composed behind
    // BuildConfig.DEBUG, so none of it is reachable in a release build.

    private val _debugMessages = MutableSharedFlow<String>(extraBufferCapacity = MESSAGE_BUFFER)

    /**
     * One line per action for the debug snackbar. Reconciles the user did not trigger — the
     * hourly worker, a sensitivity change — report here too: seeing them is the point.
     */
    val debugMessages: Flow<String> = merge(
        _debugMessages,
        alertScheduler.results.map { it.describe() }
    )

    /**
     * Runs the production fetch-and-reconcile path immediately. The result arrives on
     * [debugMessages] once the worker gets to it.
     */
    fun runAlertCheckNow() {
        PressureFetchWorker.runNow(workManager)
    }

    /**
     * Posts the next warning straight away, ignoring both the 12-hour lead time and the
     * already-notified history. A preview rather than a delivery: it records nothing, so the
     * real warning still arrives at its proper time.
     */
    fun previewNextAlert() {
        viewModelScope.launch {
            val now = Instant.now()
            val next = alertUseCase.currentAlerts(now)
                .filter { it.end.isAfter(now) }
                .minByOrNull { it.start }

            if (next == null) {
                _debugMessages.emit("No upcoming event to preview")
                return@launch
            }

            val phase = AlertPhase.of(next, now)
            val message = when {
                notifier.notify(next, phase) ->
                    "Sent: ${formatHpa(next.delta)} hPa ${next.direction} ($phase)"
                else -> "Blocked — notifications are not permitted"
            }
            _debugMessages.emit(message)
        }
    }

    /**
     * Forgets which events have been announced and reschedules from scratch. Without this an
     * event can only be tested once: the decider suppresses anything already notified, which
     * looks exactly like a broken alert.
     */
    fun clearNotificationHistory() {
        viewModelScope.launch {
            notifiedAlertDao.deleteAll()
            _debugMessages.emit("Alert history cleared")

            // Events the history was hiding qualify again, so put them back in the queue.
            alertScheduler.reconcile()
        }
    }

    private fun ReconcileResult.describe(): String = when (this) {
        is ReconcileResult.Success -> "$pending pending · $cancelled cancelled"
        is ReconcileResult.Failed -> "Reconcile failed: ${cause.message ?: cause::class.simpleName}"
    }
}
