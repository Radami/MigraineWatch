package com.radami.migrainewatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone

/** What the app needs before any screen: whether a place has been set, and that place's zone. */
class AppViewModel(userPreferences: UserPreferences) : ViewModel() {

    companion object {
        /** Keeps the settings flowing across a quick configuration change instead of restarting. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Builds it from the app's dependencies, the same way on every platform. */
        fun create(dependencies: UiDependencies) = AppViewModel(dependencies.userPreferences)
    }

    /** Null until the settings load, so a returning user never sees onboarding flash first. */
    val onboardingComplete: StateFlow<Boolean?> = userPreferences.settings
        .map { it.onboardingComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** The location's zone, for anything outside a screen that needs today's date there. */
    val locationZone: StateFlow<TimeZone> = userPreferences.settings
        .map { it.location.zone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LocationData().zone)
}
