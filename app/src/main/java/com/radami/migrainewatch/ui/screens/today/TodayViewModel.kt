package com.radami.migrainewatch.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.SymptomFreeStreak
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class TodayUiState(
    val currentPressure: Float? = null,
    val historical: List<PressureReading> = emptyList(),
    val forecast: List<PressureReading> = emptyList(),
    val alertWindows: List<AlertWindow> = emptyList(),
    val alertThresholdHpa: Float = AlertSensitivity.Default.thresholdHpa,
    val symptomFreeStreak: SymptomFreeStreak? = null,
    val locationName: String = "",
    val lastUpdated: Instant? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val symptomRepository: SymptomRepository,
    private val userPreferences: UserPreferences,
    private val alertUseCase: PressureAlertUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            pressureRepository.refresh()
        }
        observeData()
    }

    private fun observeData() {
        // Query 4 days back for the chart, but 7 days ahead for full forecast analysis
        val queryStart = Instant.now()
        val from = queryStart.minus(4, ChronoUnit.DAYS)
        val to = queryStart.plus(7, ChronoUnit.DAYS)

        viewModelScope.launch {
            combine(
                pressureRepository.getReadingsInRange(from, to),
                symptomRepository.getAllEntries(),
                userPreferences.settings
            ) { readings, entries, settings ->
                Triple(readings, entries, settings)
            }.collectLatest { (readings, entries, settings) ->
                // Re-evaluate "now" on every emission so the current pressure and the
                // historical/forecast split don't go stale while the screen stays open.
                val now = Instant.now()
                val hist = readings.filter { it.dateTime.isBefore(now) }
                val fore = readings.filter { !it.dateTime.isBefore(now) }

                // Detection goes through the shared use case so the banner and the scheduled
                // notifications always describe the same set of events. The streak walks the
                // user's entire history, so it stays off the main thread alongside it.
                val (alerts, streak) = withContext(Dispatchers.Default) {
                    val detected = alertUseCase.alertsIn(readings, settings.alertThresholdHpa, now)

                    // Today is read here rather than hoisted out of the flow so the count is at
                    // least right for every emission. It can still go stale if the screen is left
                    // open across midnight with nothing else emitting.
                    detected to SymptomFreeStreak.from(entries, LocalDate.now())
                }

                _uiState.value = TodayUiState(
                    currentPressure = hist.lastOrNull()?.pressureMsl
                        ?: fore.firstOrNull()?.pressureMsl,
                    historical = hist,
                    forecast = fore,
                    alertWindows = alerts,
                    alertThresholdHpa = settings.alertThresholdHpa,
                    symptomFreeStreak = streak,
                    locationName = settings.location.name,
                    lastUpdated = readings.maxOfOrNull { it.fetchedDateTime },
                    isLoading = false
                )
            }
        }
    }
}
