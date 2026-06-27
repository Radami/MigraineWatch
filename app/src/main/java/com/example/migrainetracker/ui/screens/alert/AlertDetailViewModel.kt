package com.example.migrainetracker.ui.screens.alert

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.repository.PressureRepository
import com.example.migrainetracker.domain.AlertWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class AlertDetailUiState(
    val readings: List<PressureReading> = emptyList(),
    val alertStartEpoch: Long = 0L,
    val alertEndEpoch: Long = 0L,
    val delta: Float = 0f,
    val direction: String = "",
    val allAlerts: List<AlertWindow> = emptyList(),
    val locationName: String = "",
    val isLoading: Boolean = true
)

@HiltViewModel
class AlertDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val startEpoch: Long = checkNotNull(savedStateHandle["startEpoch"])
    private val endEpoch: Long = checkNotNull(savedStateHandle["endEpoch"])
    private val delta: Float = checkNotNull(savedStateHandle["delta"])
    private val direction: String = checkNotNull(savedStateHandle["direction"])

    private val allAlerts: List<AlertWindow> = run {
        val starts = savedStateHandle.get<LongArray>("allStartEpochs") ?: longArrayOf(startEpoch)
        val ends = savedStateHandle.get<LongArray>("allEndEpochs") ?: longArrayOf(endEpoch)
        val deltas = savedStateHandle.get<FloatArray>("allDeltas") ?: floatArrayOf(delta)
        val directions = savedStateHandle.get<String>("allDirections")?.split(",") ?: listOf(direction)
        starts.indices.map { i ->
            AlertWindow(
                start = Instant.ofEpochSecond(starts[i]),
                end = Instant.ofEpochSecond(ends.getOrElse(i) { endEpoch }),
                delta = deltas.getOrElse(i) { delta },
                direction = directions.getOrElse(i) { direction }
            )
        }
    }

    private val _uiState = MutableStateFlow(
        AlertDetailUiState(
            alertStartEpoch = startEpoch,
            alertEndEpoch = endEpoch,
            delta = delta,
            direction = direction,
            allAlerts = allAlerts
        )
    )
    val uiState: StateFlow<AlertDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (pressureRepository.isForecastStale()) {
                pressureRepository.refresh()
            }
        }
        observeData()
    }

    private fun observeData() {
        val alertCenterEpoch = (startEpoch + endEpoch) / 2L
        // Fetch ±30 h around the alert centre — generous enough to cover the max chart window
        // of ±24 h (2.5 × max interval of 9.6 h) plus a small buffer for data lookup.
        val bufferSeconds = 30L * 3600L
        val from = Instant.ofEpochSecond(alertCenterEpoch - bufferSeconds)
        val to = Instant.ofEpochSecond(alertCenterEpoch + bufferSeconds)

        viewModelScope.launch {
            combine(
                pressureRepository.getReadingsInRange(from, to),
                userPreferences.settings
            ) { readings, settings -> Pair(readings, settings) }
                .collectLatest { (readings, settings) ->
                    _uiState.value = _uiState.value.copy(
                        readings = readings,
                        locationName = settings.location.name,
                        isLoading = false
                    )
                }
        }
    }
}
