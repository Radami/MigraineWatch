package com.example.migrainetracker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.migrainetracker.data.preferences.AlertSensitivity
import androidx.work.WorkManager
import com.example.migrainetracker.data.local.dao.NotifiedAlertDao
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.repository.SymptomRepository
import com.example.migrainetracker.domain.AlertNotificationScheduler
import com.example.migrainetracker.domain.PressureAlertUseCase
import com.example.migrainetracker.domain.ReconcileResult
import com.example.migrainetracker.notifications.AlertNotifier
import com.example.migrainetracker.workers.PressureFetchWorker
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
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class SettingsUiState(
    val alertSensitivity: AlertSensitivity = AlertSensitivity.Default,
    val notificationsEnabled: Boolean = true,
    val totalEntries: Int = 0,
    val trackingSince: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val symptomRepository: SymptomRepository,
    private val alertScheduler: AlertNotificationScheduler,
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

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferences.settings,
        symptomRepository.getTotalCount()
    ) { settings, count ->
        SettingsUiState(
            alertSensitivity = settings.alertSensitivity,
            notificationsEnabled = settings.notificationsEnabled,
            totalEntries = count
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _trackingSince = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val earliest = symptomRepository.getEarliestDate()
            if (earliest != null) {
                val formatter = DateTimeFormatter.ofPattern("MMMM yyyy")
                _trackingSince.value = earliest.format(formatter)
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

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotificationsEnabled(enabled)
            // Switching off cancels everything pending; switching on rebuilds it.
            alertScheduler.reconcile()
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

            val message = when {
                notifier.notify(next) -> "Sent: %.1f hPa %s".format(next.delta, next.direction)
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
