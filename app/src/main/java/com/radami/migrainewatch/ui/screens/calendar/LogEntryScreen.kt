package com.radami.migrainewatch.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.ui.theme.SeverityAura
import com.radami.migrainewatch.ui.theme.SeverityClear
import com.radami.migrainewatch.ui.theme.SeverityMigraine
import com.radami.migrainewatch.ui.theme.SeverityMild

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogEntryScreen(
    dateStr: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: LogEntryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dateFormatter = AppDateFormats.FULL_DATE

    LaunchedEffect(state.savedSuccessfully) {
        if (state.savedSuccessfully) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.date.format(dateFormatter),
                            style = MaterialTheme.typography.titleMedium
                        )
                        StepIndicator(currentStep = state.step, totalSteps = 3)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = if (state.step > 1) viewModel::goBack else onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.step) {
                1 -> Step1Severity(
                    selectedSeverity = state.severity,
                    onSelect = viewModel::selectSeverity,
                    onContinue = viewModel::continueToStep2
                )
                2 -> Step2Triggers(
                    selectedTriggers = state.triggers,
                    onToggle = viewModel::toggleTrigger,
                    onContinue = viewModel::continueToStep3
                )
                3 -> Step3Details(
                    durationBucket = state.durationBucket,
                    reliefPercent = state.reliefPercent,
                    medication = state.medication,
                    notes = state.notes,
                    isSaving = state.isSaving,
                    onDurationSelect = viewModel::selectDuration,
                    onReliefChange = viewModel::setRelief,
                    onMedicationChange = viewModel::setMedication,
                    onNotesChange = viewModel::setNotes,
                    onSave = viewModel::save
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(totalSteps) { idx ->
            Text(
                if (idx < currentStep) "●" else "○",
                color = if (idx < currentStep)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Step1Severity(
    selectedSeverity: Severity?,
    onSelect: (Severity) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        "How are you feeling?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(4.dp))
    SeverityOption(
        severity = Severity.CLEAR,
        label = "Clear",
        description = "No symptoms today",
        selected = selectedSeverity == Severity.CLEAR,
        onClick = { onSelect(Severity.CLEAR) }
    )
    SeverityOption(
        severity = Severity.MILD,
        label = "Mild",
        description = "Manageable ache or tension",
        selected = selectedSeverity == Severity.MILD,
        onClick = { onSelect(Severity.MILD) }
    )
    SeverityOption(
        severity = Severity.AURA,
        label = "Aura",
        description = "Visual / sensory warning signs",
        selected = selectedSeverity == Severity.AURA,
        onClick = { onSelect(Severity.AURA) }
    )
    SeverityOption(
        severity = Severity.MIGRAINE,
        label = "Migraine",
        description = "Full episode, hard to function",
        selected = selectedSeverity == Severity.MIGRAINE,
        onClick = { onSelect(Severity.MIGRAINE) }
    )
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = onContinue,
        enabled = selectedSeverity != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Continue →")
    }
}

@Composable
private fun SeverityOption(
    severity: Severity,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = severity.toColor()
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) color.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label: $description" }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step2Triggers(
    selectedTriggers: Set<String>,
    onToggle: (String) -> Unit,
    onContinue: () -> Unit
) {
    Text(
        "Any triggers?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "Optional — select all that apply",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TRIGGER_OPTIONS.forEach { trigger ->
            FilterChip(
                selected = trigger in selectedTriggers,
                onClick = { onToggle(trigger) },
                label = { Text(trigger) },
                modifier = Modifier.semantics { contentDescription = trigger }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Continue →")
    }
}

@Composable
private fun Step3Details(
    durationBucket: String?,
    reliefPercent: Int?,
    medication: String,
    notes: String,
    isSaving: Boolean,
    onDurationSelect: (String?) -> Unit,
    onReliefChange: (Int) -> Unit,
    onMedicationChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Text(
        "Add details",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "Optional",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(Modifier.height(4.dp))
    Text("Duration", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DURATION_OPTIONS.forEach { dur ->
            FilterChip(
                selected = durationBucket == dur,
                onClick = { onDurationSelect(if (durationBucket == dur) null else dur) },
                label = { Text(dur) }
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Relief: ${reliefPercent ?: 0}%",
        style = MaterialTheme.typography.labelLarge
    )
    Slider(
        value = (reliefPercent ?: 0).toFloat(),
        onValueChange = { onReliefChange(it.toInt()) },
        valueRange = 0f..100f,
        steps = 19,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Relief percentage slider" }
    )
    OutlinedTextField(
        value = medication,
        onValueChange = onMedicationChange,
        label = { Text("Medication") },
        placeholder = { Text("e.g. Sumatriptan 50 mg") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text("Notes") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 5
    )
    Button(
        onClick = onSave,
        enabled = !isSaving,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (isSaving) "Saving…" else "Save")
    }
}

private fun Severity.toColor(): Color = when (this) {
    Severity.CLEAR -> SeverityClear
    Severity.MILD -> SeverityMild
    Severity.AURA -> SeverityAura
    Severity.MIGRAINE -> SeverityMigraine
}
