package com.radami.migrainewatch.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.coroutines.catchingFailures
import com.radami.migrainewatch.domain.Severity
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.logging.Logger
import com.radami.migrainewatch.ui.UiDependencies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** Logs or edits the entry for [date], the day the user picked before opening the screen. */
class LogEntryViewModel(
    private val date: LocalDate,
    private val symptomRepository: SymptomRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LogEntryViewModel"

        /** Builds it from the app's dependencies, the same way on every platform. */
        fun create(dependencies: UiDependencies, date: LocalDate) = LogEntryViewModel(
            date = date,
            symptomRepository = dependencies.symptomRepository
        )
    }

    private val _uiState = MutableStateFlow(LogEntryState(date = date))
    val uiState: StateFlow<LogEntryState> = _uiState.asStateFlow()

    init {
        // Prefills an edit. A failed read opens the form empty instead of crashing the screen;
        // saving from there still replaces the day's entry.
        viewModelScope.launch {
            val existing = catchingFailures { symptomRepository.getByDate(date) }
                .onFailure { Logger.e(TAG, "Failed to load entry for $date", it) }
                .getOrNull()
                ?: return@launch

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
            catchingFailures {
                // An edit keeps the day's original creation time.
                val createdAt = symptomRepository.getByDate(state.date)?.createdAt
                symptomRepository.save(state.toEntry(createdAt, Clock.System.now()))
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isSaving = false, savedSuccessfully = true)
            }.onFailure {
                // The screen stays on its last step with Save enabled again, so the user can
                // retry; the log keeps the cause, which the screen doesn't show.
                Logger.e(TAG, "Failed to save entry for ${state.date}", it)
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }
}
