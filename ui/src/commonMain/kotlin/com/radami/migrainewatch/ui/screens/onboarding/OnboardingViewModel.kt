package com.radami.migrainewatch.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.coroutines.catchingFailures
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UNKNOWN_TIMEZONE
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.remote.dto.GeocodingResult
import com.radami.migrainewatch.notifications.NotificationPermissionState
import com.radami.migrainewatch.ui.UiDependencies
import com.radami.migrainewatch.ui.platform.NotificationPermissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

class OnboardingViewModel(
    private val geocodingApi: GeocodingApi,
    private val userPreferences: UserPreferences,
    private val permissions: NotificationPermissions
) : ViewModel() {

    companion object {
        /** The shortest query worth sending; one letter matches too many places to be useful. */
        const val MIN_QUERY_LENGTH = 2

        /** How long typing must pause before the query is sent, so each keystroke isn't a request. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L

        /** Builds it from the app's dependencies, the same way on every platform. */
        fun create(dependencies: UiDependencies) = OnboardingViewModel(
            geocodingApi = dependencies.geocodingApi,
            userPreferences = dependencies.userPreferences,
            permissions = dependencies.notificationPermissions
        )
    }

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** The search for the latest query, cancelled when a newer one replaces it. */
    private var search: Job? = null

    fun showCitySearch() {
        _uiState.value = _uiState.value.copy(step = OnboardingStep.CitySearch)
    }

    /**
     * Searches for [query] once typing pauses. Only the latest query's answer is ever shown: an
     * older search still out is cancelled, or its slower reply could replace the newer results.
     */
    fun updateSearchQuery(query: String) {
        search?.cancel()
        _uiState.update { it.copy(searchQuery = query, error = null) }

        if (query.length < MIN_QUERY_LENGTH) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        search = viewModelScope.launch { searchCities(query) }
    }

    private suspend fun searchCities(query: String) {
        _uiState.update { it.copy(isSearching = true) }
        delay(SEARCH_DEBOUNCE_MILLIS)

        catchingFailures { geocodingApi.search(query) }
            .onSuccess { resp ->
                _uiState.update { it.copy(searchResults = resp.results ?: emptyList(), isSearching = false) }
            }
            .onFailure { error ->
                _uiState.update { it.copy(isSearching = false, error = error.message) }
            }
    }

    fun saveGpsLocation(lat: Double, lon: Double, name: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            catchingFailures {
                // A GPS fix carries no zone; the first forecast fetch looks it up from the coordinates.
                userPreferences.saveLocation(LocationData("gps", lat, lon, name, UNKNOWN_TIMEZONE))
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
            catchingFailures {
                userPreferences.saveLocation(
                    LocationData(
                        source = "manual",
                        lat = result.latitude,
                        lon = result.longitude,
                        name = result.displayName,
                        timezone = result.timezone ?: UNKNOWN_TIMEZONE
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

    /** Alerts default on, so ask for the permission here — nothing in Settings would trigger it later. */
    private suspend fun finishSetup() {
        val mustAsk = permissions.currentState() == NotificationPermissionState.REQUESTABLE
        _uiState.value = _uiState.value.copy(
            isSaving = false,
            savedSuccessfully = !mustAsk,
            requestNotificationPermission = mustAsk
        )
    }

    /** Either answer moves on: a denial is surfaced by the Settings screen, not blocked here. */
    fun onNotificationPermissionRequestFinished() {
        viewModelScope.launch {
            permissions.markRequested()
            _uiState.value = _uiState.value.copy(
                savedSuccessfully = true,
                requestNotificationPermission = false
            )
        }
    }
}
