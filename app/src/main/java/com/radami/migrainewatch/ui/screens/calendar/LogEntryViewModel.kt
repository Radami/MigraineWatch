package com.radami.migrainewatch.ui.screens.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.repository.SymptomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class LogEntryUiState(
    val date: LocalDate = LocalDate.now(),
    val step: Int = 1,
    val severity: Severity? = null,
    val triggers: Set<String> = emptySet(),
    val durationBucket: String? = null,
    val reliefPercent: Int? = null,
    val medication: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false
)

const val OTHER_TRIGGER = "Other"

/** The canonical order every screen shows triggers in. */
val TRIGGER_OPTIONS = listOf(
    "Poor sleep", "Stress", "Food / skipped meal", "Hormonal", "Screen time", OTHER_TRIGGER
)

private val TRIGGER_RANKS = TRIGGER_OPTIONS.withIndex()
    .associate { (rank, trigger) -> trigger to rank }

/**
 * Sorts triggers into [TRIGGER_OPTIONS] order with [OTHER_TRIGGER] last, so a saved entry
 * reads the same regardless of tap order. Unrecognized (e.g. old) values sort after known ones.
 */
fun Iterable<String>.inTriggerOrder(): List<String> = sortedWith(
    compareBy<String>(
        // "Other" always sorts last, ahead of any other comparison.
        { it == OTHER_TRIGGER },
        // Elvis matters: null would otherwise sort an unranked trigger first, not last.
        { TRIGGER_RANKS[it] ?: Int.MAX_VALUE },
        // Tiebreaker for unranked triggers, which all share MAX_VALUE above.
        { it }
    )
)

val DURATION_OPTIONS = listOf("<2h", "2-6h", "6-12h", ">12h")

/**
 * How much the medication helped, in quarters. Offered as a short list rather than a
 * continuous scale: nobody recalls relief to the percentage point hours later, and a scale
 * that invites 63% only makes the entry harder to give.
 */
val RELIEF_OPTIONS = listOf(0, 25, 50, 75, 100)

@HiltViewModel
class LogEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val symptomRepository: SymptomRepository
) : ViewModel() {

    private val dateStr: String = checkNotNull(savedStateHandle["date"])
    private val date: LocalDate = LocalDate.parse(dateStr)

    private val _uiState = MutableStateFlow(LogEntryUiState(date = date))
    val uiState: StateFlow<LogEntryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = symptomRepository.getByDate(date)
            if (existing != null) {
                _uiState.value = _uiState.value.copy(
                    severity = existing.severity,
                    triggers = existing.triggers.toSet(),
                    durationBucket = existing.durationBucket,
                    reliefPercent = existing.reliefPercent,
                    medication = existing.medication ?: "",
                    notes = existing.notes ?: ""
                )
            }
        }
    }

    fun selectSeverity(severity: Severity) {
        _uiState.value = _uiState.value.copy(severity = severity)
    }

    fun continueToStep2() {
        if (_uiState.value.severity != null) {
            _uiState.value = _uiState.value.copy(step = 2)
        }
    }

    fun toggleTrigger(trigger: String) {
        val current = _uiState.value.triggers.toMutableSet()
        if (!current.add(trigger)) current.remove(trigger)
        _uiState.value = _uiState.value.copy(triggers = current)
    }

    fun continueToStep3() {
        _uiState.value = _uiState.value.copy(step = 3)
    }

    fun selectDuration(duration: String?) {
        _uiState.value = _uiState.value.copy(durationBucket = duration)
    }

    /** Null clears the field, which is not the same as recording 0% — that is a real answer. */
    fun setRelief(percent: Int?) {
        _uiState.value = _uiState.value.copy(reliefPercent = percent)
    }

    fun setMedication(text: String) {
        _uiState.value = _uiState.value.copy(medication = text)
    }

    fun setNotes(text: String) {
        _uiState.value = _uiState.value.copy(notes = text)
    }

    fun goBack() {
        val step = _uiState.value.step
        if (step > 1) _uiState.value = _uiState.value.copy(step = step - 1)
    }

    fun save() {
        val state = _uiState.value
        val severity = state.severity ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            runCatching {
                val now = Instant.now()
                val existing = symptomRepository.getByDate(state.date)
                symptomRepository.save(
                    SymptomEntry(
                        date = state.date,
                        severity = severity,
                        triggers = state.triggers.inTriggerOrder(),
                        durationBucket = state.durationBucket,
                        reliefPercent = state.reliefPercent,
                        medication = state.medication.takeIf { it.isNotBlank() },
                        notes = state.notes.takeIf { it.isNotBlank() },
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                )
            }.onSuccess {
                _uiState.value = _uiState.value.copy(isSaving = false, savedSuccessfully = true)
            }.onFailure {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }
}
