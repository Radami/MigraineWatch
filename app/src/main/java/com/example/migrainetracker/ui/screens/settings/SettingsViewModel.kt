package com.example.migrainetracker.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.migrainetracker.data.preferences.AlertSensitivity
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.repository.SymptomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val symptomRepository: SymptomRepository
) : ViewModel() {

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
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotificationsEnabled(enabled)
        }
    }
}
