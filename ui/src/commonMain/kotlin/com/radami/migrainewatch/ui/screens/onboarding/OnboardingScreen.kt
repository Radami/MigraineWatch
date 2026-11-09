package com.radami.migrainewatch.ui.screens.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radami.migrainewatch.data.remote.dto.GeocodingResult
import com.radami.migrainewatch.ui.LocalUiDependencies
import com.radami.migrainewatch.ui.UiDependencies
import com.radami.migrainewatch.ui.platform.rememberCurrentLocationRequest
import com.radami.migrainewatch.ui.platform.rememberNotificationPermissionRequest
import com.radami.migrainewatch.ui.theme.SUPPORTING_ALPHA

/** What a place picked from the device's own position is called, since it has no city name. */
private const val CURRENT_LOCATION_NAME = "Current location"

private const val ALLOW_LOCATION = "Allow location"

/** Said while the device works out where it is, which can take several seconds. */
private const val FINDING_LOCATION = "Finding your location…"

/** What the location step is busy with, if anything. */
private enum class LocationStepActivity {
    Idle,

    /** Waiting for the device's position, after the user allowed it. */
    Locating,

    /** Storing the place that was picked. */
    Saving
}

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    dependencies: UiDependencies = LocalUiDependencies.current,
    viewModel: OnboardingViewModel = viewModel { OnboardingViewModel.create(dependencies) }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedSuccessfully) {
        if (state.savedSuccessfully) onComplete()
    }

    // Grant isn't stored as a preference; Settings reads the live permission instead.
    val requestNotificationPermission = rememberNotificationPermissionRequest(
        onFinished = viewModel::onNotificationPermissionRequestFinished
    )

    // Asked after setup succeeds, not on the way in, so the user has seen what the app is for.
    LaunchedEffect(state.requestNotificationPermission) {
        if (state.requestNotificationPermission) requestNotificationPermission()
    }

    // A fix can take seconds to come, so the step shows it is waiting rather than looking stuck.
    var locating by remember { mutableStateOf(false) }

    // A refused permission or a missing fix both fall back to the city search.
    val requestLocation = rememberCurrentLocationRequest { position ->
        locating = false
        if (position == null) {
            viewModel.showCitySearch()
            return@rememberCurrentLocationRequest
        }

        viewModel.saveGpsLocation(position.latitude, position.longitude, CURRENT_LOCATION_NAME)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (state.step) {
            OnboardingStep.Rationale -> RationaleStep(
                activity = when {
                    state.isSaving -> LocationStepActivity.Saving
                    locating -> LocationStepActivity.Locating
                    else -> LocationStepActivity.Idle
                },
                onGrantLocation = {
                    locating = true
                    requestLocation()
                },
                onSkipToSearch = viewModel::showCitySearch
            )

            OnboardingStep.CitySearch -> CitySearchStep(
                query = state.searchQuery,
                results = state.searchResults,
                isSearching = state.isSearching,
                isSaving = state.isSaving,
                onQueryChange = viewModel::updateSearchQuery,
                onSelectCity = viewModel::selectCity
            )
        }
    }
}

@Composable
private fun RationaleStep(
    activity: LocationStepActivity,
    onGrantLocation: () -> Unit,
    onSkipToSearch: () -> Unit
) {
    // Both choices wait while either is under way, so a second tap can't start another.
    val idle = activity == LocationStepActivity.Idle

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Your location",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Pressure varies by location. We need your location to show accurate readings.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUPPORTING_ALPHA)
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onGrantLocation,
            modifier = Modifier.fillMaxWidth(),
            enabled = idle
        ) {
            Text(if (activity == LocationStepActivity.Locating) FINDING_LOCATION else ALLOW_LOCATION)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = onSkipToSearch,
            modifier = Modifier.fillMaxWidth(),
            enabled = idle
        ) {
            Text("Enter city manually")
        }
    }
}

@Composable
private fun CitySearchStep(
    query: String,
    results: List<GeocodingResult>,
    isSearching: Boolean,
    isSaving: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectCity: (GeocodingResult) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            "Find your city",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search city...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            singleLine = true,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onQueryChange(query) })
        )
        Spacer(Modifier.height(8.dp))
        when {
            isSaving -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
            isSearching -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
            else -> LazyColumn {
                items(results) { result ->
                    ListItem(
                        headlineContent = { Text(result.displayName) },
                        modifier = Modifier.clickable { onSelectCity(result) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
