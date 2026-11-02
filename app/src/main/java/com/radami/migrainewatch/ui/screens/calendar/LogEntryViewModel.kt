package com.radami.migrainewatch.ui.screens.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.domain.Severity
import com.radami.migrainewatch.data.repository.SymptomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import kotlin.time.Clock

@HiltViewModel
class LogEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val symptomRepository: SymptomRepository
) : ViewModel() {

    private val dateStr: String = checkNotNull(savedStateHandle["date"])
    private val date: LocalDate = LocalDate.parse(dateStr)

    private val _uiState = MutableStateFlow(LogEntryState(date = date))
    val uiState: StateFlow<LogEntryState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = symptomRepository.getByDate(date) ?: return@launch
            _uiState.update { it.prefilledFrom(existing) }
        }
    }

    fun selectSeverity(severity: Severity) {
        _uiState.update { it.copy(severity = severity) }
    }

    fun continueToStep2() {
        _uiState.update { it.continuedToTriggers() }
    }

    fun toggleTrigger(trigger: String) {
        _uiState.update { it.withTriggerToggled(trigger) }
    }

    fun continueToStep3() {
        _uiState.update { it.continuedToDetails() }
    }

    fun selectDuration(duration: String?) {
        _uiState.update { it.copy(durationBucket = duration) }
    }

    /** Null clears the field, which is not the same as recording 0% — that is a real answer. */
    fun setRelief(percent: Int?) {
        _uiState.update { it.copy(reliefPercent = percent) }
    }

    fun setMedication(text: String) {
        _uiState.update { it.copy(medication = text) }
    }

    fun setNotes(text: String) {
        _uiState.update { it.copy(notes = text) }
    }

    fun goBack() {
        _uiState.update { it.steppedBack() }
    }

    fun save() {
        val state = _uiState.value

        // Severity is the one required answer; there is nothing to save without it.
        if (state.severity == null) return

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            runCatching {
                // An edit keeps the day's original creation time.
                val createdAt = symptomRepository.getByDate(state.date)?.createdAt
                symptomRepository.save(state.toEntry(createdAt, Clock.System.now()))
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isSaving = false, savedSuccessfully = true)
            }.onFailure {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }
}
