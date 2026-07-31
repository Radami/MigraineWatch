package com.radami.migrainewatch.ui.screens.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radami.migrainewatch.data.remote.dto.GeocodingResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.savedSuccessfully) {
        if (state.savedSuccessfully) onComplete()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // The grant is not recorded as a preference: the alerts setting holds intent, and the
        // Settings screen reads the live permission to explain a denial.
        viewModel.onNotificationPermissionRequestFinished()
    }

    // Asked once setup has succeeded, so the dialog lands after the user has seen what the app
    // is for rather than on the way in.
    LaunchedEffect(state.requestNotificationPermission) {
        if (state.requestNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                @SuppressLint("MissingPermission")
                val location = LocationServices.getFusedLocationProviderClient(context)
                    .lastLocation.await()
                if (location != null) {
                    viewModel.saveGpsLocation(location.latitude, location.longitude, "Current location")
                } else {
                    viewModel.showCitySearch()
                }
            }
        } else {
            viewModel.showCitySearch()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (state.step) {
            OnboardingStep.Rationale -> RationaleStep(
                isSaving = state.isSaving,
                onGrantLocation = {
                    val alreadyGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (alreadyGranted) {
                        scope.launch {
                            @SuppressLint("MissingPermission")
                            val location = LocationServices.getFusedLocationProviderClient(context)
                                .lastLocation.await()
                            if (location != null) {
                                viewModel.saveGpsLocation(location.latitude, location.longitude, "Current location")
                            } else {
                                viewModel.showCitySearch()
                            }
                        }
                    } else {
                        locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
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
    isSaving: Boolean,
    onGrantLocation: () -> Unit,
    onSkipToSearch: () -> Unit
) {
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onGrantLocation,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving
        ) {
            Text("Allow location")
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = onSkipToSearch,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving
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
