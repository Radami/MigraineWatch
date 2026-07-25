package com.example.migrainetracker.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.migrainetracker.BuildConfig
import com.example.migrainetracker.data.preferences.AlertSensitivity
import com.example.migrainetracker.ui.theme.ChartMeasuredLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trackingSince by viewModel.trackingSince.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                            append("Migraine")
                        }
                        withStyle(style = SpanStyle(color = ChartMeasuredLight)) {
                            append("Watch")
                        }
                    },
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold
                    )
                )
                val subtitle = when {
                    trackingSince.isNotEmpty() && state.totalEntries > 0 ->
                        "Tracking since $trackingSince · ${state.totalEntries} entries"
                    state.totalEntries > 0 -> "${state.totalEntries} entries"
                    else -> "No entries yet"
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        item {
            SectionHeader("Alert sensitivity")
        }
        item {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    "We warn you when pressure is forecast to drop by this amount within 24 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    "${state.alertSensitivity.thresholdHpa.toInt()} hPa",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    AlertSensitivity.entries.forEachIndexed { index, sensitivity ->
                        SegmentedButton(
                            selected = sensitivity == state.alertSensitivity,
                            onClick = { viewModel.setAlertSensitivity(sensitivity) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = AlertSensitivity.entries.size
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = "Alert sensitivity ${sensitivity.label}"
                            }
                        ) {
                            Text(sensitivity.label)
                        }
                    }
                }
                // Reserve both lines: the longest description wraps, and letting the block
                // shrink to one line would make the divider below jump as levels change.
                Text(
                    state.alertSensitivity.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    minLines = 2
                )
            }
        }

        item { HorizontalDivider() }
        item { SectionHeader("Notifications") }
        item {
            SettingsRow(
                title = "Pressure drop alerts",
                subtitle = "Push when a drop crosses your threshold",
                trailing = {
                    Switch(
                        checked = state.notificationsEnabled,
                        onCheckedChange = viewModel::setNotificationsEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "Pressure drop alerts toggle"
                        }
                    )
                }
            )
        }

        item { HorizontalDivider() }
        item {
            SettingsRow(
                title = "Weather source",
                subtitle = "Open-Meteo",
                trailing = null
            )
        }
        item { HorizontalDivider() }

        item {
            Text(
                "MigraineWatch · v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)?,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier.semantics { contentDescription = "$title: $subtitle" }
    } else {
        Modifier
    }

    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            trailing?.invoke()
        }
    }
}

// Presentation of the sensitivity presets lives here rather than on the enum, so the
// preferences layer stays free of user-facing copy.
private val AlertSensitivity.label: String
    get() = when (this) {
        AlertSensitivity.HIGH -> "High"
        AlertSensitivity.MEDIUM -> "Medium"
        AlertSensitivity.LOW -> "Low"
    }

private val AlertSensitivity.description: String
    get() = when (this) {
        AlertSensitivity.HIGH -> "Warns on smaller drops. Expect more alerts, some of them minor."
        AlertSensitivity.MEDIUM -> "Balanced. Most weather-sensitive people react around this level."
        AlertSensitivity.LOW -> "Only large drops. Fewer alerts, but a milder event may pass unflagged."
    }
