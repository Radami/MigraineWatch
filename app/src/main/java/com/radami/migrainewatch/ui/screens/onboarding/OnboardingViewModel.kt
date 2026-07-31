package com.radami.migrainewatch.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.remote.dto.GeocodingResult
import com.radami.migrainewatch.notifications.NotificationPermissionMonitor
import com.radami.migrainewatch.notifications.NotificationPermissionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Rationale,
    val searchQuery: String = "",
    val searchResults: List<GeocodingResult> = emptyList(),
    val isSearching: Boolean = false,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    /** Set once, at the end, when the notification dialog still has to be shown. */
    val requestNotificationPermission: Boolean = false,
    val error: String? = null
)

enum class OnboardingStep { Rationale, CitySearch }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val geocodingApi: GeocodingApi,
    private val userPreferences: UserPreferences,
    private val permissionMonitor: NotificationPermissionMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun showCitySearch() {
        _uiState.value = _uiState.value.copy(step = OnboardingStep.CitySearch)
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query, error = null)
        if (query.length >= 2) searchCities(query)
        else _uiState.value = _uiState.value.copy(searchResults = emptyList())
    }

    private fun searchCities(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            runCatching { geocodingApi.search(query) }
                .onSuccess { resp ->
                    _uiState.value = _uiState.value.copy(
                        searchResults = resp.results ?: emptyList(),
                        isSearching = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isSearching = false, error = it.message)
                }
        }
    }

    fun saveGpsLocation(lat: Double, lon: Double, name: String, timezone: String = "UTC") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                userPreferences.saveLocation(LocationData("gps", lat, lon, name, timezone))
                userPreferences.setOnboardingComplete(true)
            }.onSuccess {
                finishSetup()
            }.onFailure {
                _uiState.value = _uiState.value.copy(isSaving = false, error = it.message)
            }
        }
    }

    fun selectCity(result: GeocodingResult) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            runCatching {
                userPreferences.saveLocation(
                    LocationData(
                        source = "manual",
                        lat = result.latitude,
                        lon = result.longitude,
                        name = result.displayName,
                        timezone = result.timezone ?: "UTC"
                    )
                )
                userPreferences.setOnboardingComplete(true)
            }.onSuccess {
                finishSetup()
            }.onFailure {
                _uiState.value = _uiState.value.copy(isSaving = false, error = it.message)
            }
        }
    }

    /**
     * Alerts are on by default, so the permission behind them is asked for here rather than
     * left for the user to discover. Without this the default is a promise the app never keeps:
     * the Settings switch already reads on, so nothing there would trigger the request.
     */
    private suspend fun finishSetup() {
        val mustAsk = permissionMonitor.currentState() == NotificationPermissionState.REQUESTABLE
        _uiState.value = _uiState.value.copy(
            isSaving = false,
            savedSuccessfully = !mustAsk,
            requestNotificationPermission = mustAsk
        )
    }

    /** Either answer moves on: a denial is surfaced by the Settings screen, not blocked here. */
    fun onNotificationPermissionRequestFinished() {
        viewModelScope.launch {
            permissionMonitor.markRequested()
            _uiState.value = _uiState.value.copy(
                savedSuccessfully = true,
                requestNotificationPermission = false
            )
        }
    }
}
