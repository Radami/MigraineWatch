package com.example.migrainetracker.ui.screens.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.migrainetracker.BuildConfig
import com.example.migrainetracker.data.preferences.AlertSensitivity
import com.example.migrainetracker.notifications.NotificationPermissionState
import com.example.migrainetracker.ui.theme.ChartMeasuredLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // The permission can be revoked, or granted from the system settings, while the app sits in
    // the background — so it is re-read on every resume rather than once at launch. Kept at the
    // screen root: an item scrolled out of the LazyColumn below would be disposed.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissionState()
        onPauseOrDispose { }
    }

    if (BuildConfig.DEBUG) {
        // Collected at the screen root rather than inside the debug section: a LazyColumn item
        // that scrolls out of view is disposed, and would take the subscription with it.
        LaunchedEffect(Unit) {
            viewModel.debugMessages.collect { message ->
                // The newest outcome is the interesting one, so it replaces rather than queues.
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsList(viewModel)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsList(viewModel: SettingsViewModel) {
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
            val context = LocalContext.current
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {
                // The answer itself is not stored. The switch reads the live system state, and
                // a denial must not overwrite the user's standing request for alerts.
                viewModel.onPermissionRequestFinished()
            }

            SettingsRow(
                title = "Pressure drop alerts",
                subtitle = "Push when a drop crosses your threshold",
                trailing = {
                    Switch(
                        // Delivery, not intent: never claims alerts are on while the system is
                        // dropping them.
                        checked = state.alertsDelivering,
                        onCheckedChange = { enabled ->
                            viewModel.setNotificationsEnabled(enabled)

                            // Turning it on is the moment the user has a reason to say yes.
                            if (enabled && state.permission == NotificationPermissionState.REQUESTABLE) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Pressure drop alerts toggle"
                        }
                    )
                }
            )

            // Wanted but undeliverable. Without this the switch simply reads off and the user
            // has nothing to act on.
            if (state.alertsBlocked) {
                NotificationBlockedWarning(
                    permission = state.permission,
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenSystemSettings = {
                        context.startActivity(viewModel.notificationSettingsIntent())
                    }
                )
            }
        }

        item { HorizontalDivider() }
        item {
            SettingsRow(
                title = "Weather source",
                subtitle = "Open-Meteo",
                trailing = null
            )
        }

        if (BuildConfig.DEBUG) {
            item { HorizontalDivider() }
            item { SectionHeader("Debug") }
            item {
                // Drives the alert pipeline over whatever forecast is loaded. The mock data is
                // built to cover every sensitivity preset on its own, so there is nothing to
                // set up first — DebugAlertReceiver can still swap the forecast shape over adb
                // in the rare case a different one is wanted.
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // Short labels and tight padding so the three fit one row; the full action
                    // is spelled out in each content description. FlowRow rather than Row so
                    // they wrap instead of clipping at large font scales.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(DEBUG_ACTION_SPACING)) {
                        TextButton(
                            onClick = viewModel::runAlertCheckNow,
                            contentPadding = DebugActionPadding,
                            modifier = Modifier.semantics {
                                contentDescription = "Run alert check now"
                            }
                        ) {
                            Text("Run check")
                        }
                        // Bypasses the 12-hour lead time; the only way to see the tray without
                        // waiting out an event.
                        TextButton(
                            onClick = viewModel::previewNextAlert,
                            contentPadding = DebugActionPadding,
                            modifier = Modifier.semantics {
                                contentDescription = "Send next alert now"
                            }
                        ) {
                            Text("Send alert")
                        }
                        // Makes already-announced events announceable again.
                        TextButton(
                            onClick = viewModel::clearNotificationHistory,
                            contentPadding = DebugActionPadding,
                            modifier = Modifier.semantics {
                                contentDescription = "Clear alert history"
                            }
                        ) {
                            Text("Clear history")
                        }
                    }
                }
            }
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

// The debug actions sit tighter than a normal button row: three of them have to share one
// line, and default text-button padding alone pushes the last one onto a second row.
private val DEBUG_ACTION_SPACING = 0.dp
private val DebugActionPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)

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

/**
 * Explains why alerts the user asked for are not arriving, and offers the one action that can
 * fix it: the runtime dialog while Android will still show it, the system settings screen once
 * it won't.
 */
@Composable
private fun NotificationBlockedWarning(
    permission: NotificationPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    val requestable = permission == NotificationPermissionState.REQUESTABLE
    val message = when {
        requestable -> "Alerts need notification permission before they can reach you."
        else -> "Android is blocking these alerts. Turn notifications on in system settings."
    }
    val actionLabel = if (requestable) "Allow" else "Open settings"

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = if (requestable) onRequestPermission else onOpenSystemSettings,
                modifier = Modifier.semantics { contentDescription = "$actionLabel notifications" }
            ) {
                Text(actionLabel)
            }
        }
    }
}
