package com.radami.migrainewatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    userPreferences: UserPreferences
) : ViewModel() {

    val onboardingComplete = userPreferences.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The location's zone, for anything outside a screen that needs today's date there. */
    val locationZone: StateFlow<ZoneId> = userPreferences.settings
        .map { it.location.zone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocationData().zone)
}
