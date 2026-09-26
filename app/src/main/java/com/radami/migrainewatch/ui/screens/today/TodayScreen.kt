package com.radami.migrainewatch.ui.screens.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radami.migrainewatch.ui.components.RiskTransition
import com.radami.migrainewatch.ui.theme.Motion
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.SymptomFreeStreak
import com.radami.migrainewatch.format.AlertTimingDetail
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.formatAlertTiming
import com.radami.migrainewatch.format.label
import com.radami.migrainewatch.ui.components.DayEmphasis
import com.radami.migrainewatch.ui.components.DayMarker
import com.radami.migrainewatch.ui.components.DayRisk
import com.radami.migrainewatch.ui.components.HighRiskLegendSwatch
import com.radami.migrainewatch.ui.components.SectionHeading
import com.radami.migrainewatch.ui.components.SettlingText
import com.radami.migrainewatch.ui.components.TodayLegendSwatch
import com.radami.migrainewatch.ui.theme.BrandTerracottaDark
import com.radami.migrainewatch.ui.theme.BrandTerracottaLight
import com.radami.migrainewatch.ui.theme.color
import com.radami.migrainewatch.ui.theme.FULL_ALPHA
import com.radami.migrainewatch.ui.theme.MUTED_ALPHA
import com.radami.migrainewatch.ui.theme.SECONDARY_ALPHA
import com.radami.migrainewatch.ui.theme.SUBDUED_ALPHA
import com.radami.migrainewatch.ui.theme.SUPPORTING_ALPHA
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(
    onViewPressure: () -> Unit,
    onChangeLocation: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timeFormatter = remember {
        AppDateFormats.FULL_DATE_TIME.withZone(ZoneId.systemDefault())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                if (state.locationName.isNotEmpty()) {
                    AssistChip(
                        onClick = onChangeLocation,
                        label = { Text(state.locationName) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                            leadingIconContentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .semantics { contentDescription = "Location" }
                    )
                }
                SettlingText(
                    text = "Updated ${state.lastUpdated?.let { timeFormatter.format(it) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                    label = "updatedAt"
                )
            }
        }

        item {
            AnimatedVisibility(
                visible = state.pendingAlerts.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val alerts = state.pendingAlerts
                val phase = state.leadAlertPhase
                if (alerts.isNotEmpty() && phase != null) {
                    AlertBanner(alerts = alerts, phase = phase, onClick = onViewPressure)
                }
            }
        }

        item {
            OutlookCard(state = state, onDayClick = onViewPressure)
        }

        item {
            SymptomFreeCard(streak = state.symptomFreeStreak)
        }
    }
}

/**
 * @param alerts events under way or upcoming, earliest first, never empty. First is headlined, rest counted.
 * @param phase where the first event sits relative to now; comes from the ViewModel, which has the clock.
 */
@Composable
private fun AlertBanner(alerts: List<AlertWindow>, phase: AlertPhase, onClick: () -> Unit) {
    val first = alerts.first()
    val zone = remember { ZoneId.systemDefault() }

    // Only direction and timing here; magnitude belongs on the detail screen. Wording matches
    // the notification's own so the two never describe the same event differently.
    val timing = remember(first, phase, zone) {
        formatAlertTiming(first, phase, AlertTimingDetail.Brief, zone)
    }
    val headline = if (alerts.size == 1) "Elevated risk" else "Elevated risk · Multiple events"
    val message = "$headline\n$timing"

    Surface(
        shape = ALERT_BANNER_SHAPE,
        color = MaterialTheme.colorScheme.errorContainer,
        // Clip before clickable: Surface clips its own content, not modifiers applied to it,
        // so an unclipped ripple would flash square corners over the rounded shape.
        modifier = Modifier
            .fillMaxWidth()
            .clip(ALERT_BANNER_SHAPE)
            .clickable(onClickLabel = "View pressure detail", onClick = onClick)
            .semantics { contentDescription = "Pressure alert banner" }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            SettlingText(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                label = "alertMessage"
            )

            // Plain icon, not a button: the whole banner is already the tap target.
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Shared by the banner's fill and the clip its ripple has to stay inside. */
private val ALERT_BANNER_SHAPE = RoundedCornerShape(12.dp)

private val OUTLOOK_MARKER_SIZE = 36.dp

/** Rounded ripple shape; the column is barely wider than the marker, so square corners flash oddly. */
private val OUTLOOK_DAY_SHAPE = RoundedCornerShape(8.dp)

/** Extra fade for a day the forecast never reached, stacked on top of [DayEmphasis.ByRisk]'s own fade. */
private const val UNKNOWN_DAY_ALPHA = 0.6f

/** The weekday above a day worth looking at, and above one that isn't. */
private const val WEEKDAY_ALPHA_AT_RISK = 0.9f
private const val WEEKDAY_ALPHA = 0.45f

/** The week ahead: today, then which upcoming days carry a pressure event, using the calendar's own [DayMarker]. */
@Composable
private fun OutlookCard(state: TodayUiState, onDayClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeading("${DayOutlook.DAYS}-day outlook")
            Spacer(Modifier.height(12.dp))

            // No week to draw before first load, or if the forecast never reached it; see
            // TodayUiState.outlookGap for which case applies.
            val today = state.outlook.firstOrNull()
            val hasForecast = today != null && state.outlookGap == null

            // Keyed on whether there is a week to show, not the week itself, since the days
            // inside animate individually and a full crossfade would fight that.
            AnimatedContent(
                targetState = hasForecast,
                transitionSpec = {
                    fadeIn(tween(Motion.CONTENT_ENTER_MILLIS, delayMillis = Motion.CONTENT_EXIT_MILLIS))
                        .togetherWith(fadeOut(tween(Motion.CONTENT_EXIT_MILLIS)))
                        .using(SizeTransform(clip = false))
                },
                label = "outlookBody"
            ) { forecastArrived ->
                // Re-checked, not trusted: this lambda also runs for the outgoing branch during
                // a transition, by which point `today` may be stale.
                if (!forecastArrived || today == null) {
                    OutlookPlaceholder(gap = state.outlookGap, lastUpdated = state.lastUpdated)
                    return@AnimatedContent
                }

                Column {
                    TodayHeadline(today = today, outlook = state.outlook)
                    Spacer(Modifier.height(16.dp))
                    OutlookStrip(outlook = state.outlook, onDayClick = onDayClick)
                    Spacer(Modifier.height(12.dp))
                    OutlookLegend()
                }
            }
        }
    }
}

@Composable
private fun TodayHeadline(today: DayOutlook, outlook: List<DayOutlook>) {
    val isElevated = today.risk == OutlookRisk.Elevated

    // Brand terracotta, not the theme's error color: a day to watch isn't an error. Animated
    // independently of the text so a risk change shows even without new wording.
    val riskColor = if (isSystemInDarkTheme()) BrandTerracottaDark else BrandTerracottaLight
    val headlineColor by animateColorAsState(
        targetValue = if (isElevated) riskColor else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(Motion.EMPHASIS_MILLIS),
        label = "headlineColor"
    )

    SettlingText(
        text = todayLabel(today),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = headlineColor,
        label = "todayHeadline"
    )
    Spacer(Modifier.height(2.dp))
    SettlingText(
        text = weekAheadLabel(outlook),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        label = "weekAhead"
    )
}

@Composable
private fun OutlookStrip(outlook: List<DayOutlook>, onDayClick: () -> Unit) {
    val weekdayFormatter = remember { AppDateFormats.WEEKDAY }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        outlook.forEachIndexed { index, day ->
            OutlookDay(
                day = day,
                // Today is the first column by construction, not by date comparison, so it
                // can't disagree with the list it came from even if the whole strip goes stale.
                isToday = index == 0,
                weekday = weekdayFormatter.format(day.date),
                onClick = onDayClick
            )
        }
    }
}

@Composable
private fun OutlookDay(day: DayOutlook, isToday: Boolean, weekday: String, onClick: () -> Unit) {
    // Announced as one node (weekday + number + risk) rather than three separate ones.
    // Tap target is the whole column, including the weekday above the marker.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(OUTLOOK_DAY_SHAPE)
            .clickable(onClickLabel = "View pressure detail", onClick = onClick)
            .padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = outlookDayDescription(day, isToday, weekday)
            }
    ) {
        // The weekday label also bolds at risk, so the whole column signals together.
        val atRisk = day.risk == OutlookRisk.Elevated

        // Animated alongside the marker's own morph so the column moves as one piece.
        val weekdayAlpha by animateFloatAsState(
            targetValue = if (atRisk) WEEKDAY_ALPHA_AT_RISK else WEEKDAY_ALPHA,
            animationSpec = tween(Motion.EMPHASIS_MILLIS),
            label = "weekdayAlpha"
        )
        val markerAlpha by animateFloatAsState(
            targetValue = if (day.risk == OutlookRisk.Unknown) UNKNOWN_DAY_ALPHA else FULL_ALPHA,
            animationSpec = tween(Motion.EMPHASIS_MILLIS),
            label = "markerAlpha"
        )

        Text(
            weekday,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (atRisk) FontWeight.SemiBold else null,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = weekdayAlpha)
        )
        Spacer(Modifier.height(4.dp))
        DayMarker(
            day = day.date.dayOfMonth,
            severityColor = null,
            risk = if (atRisk) DayRisk.High else DayRisk.Normal,
            isToday = isToday,
            modifier = Modifier
                .size(OUTLOOK_MARKER_SIZE)
                .alpha(markerAlpha),
            // Strip days animate between silhouettes as the forecast lands; calendar days don't.
            transition = RiskTransition.Animated,
            // Only here: calendar numbers all carry equal weight since it's a lookup grid.
            emphasis = DayEmphasis.ByRisk
        )
    }
}

/** What the strip's rings mean. Swatches come from the marker itself so the legend can't drift from the days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutlookLegend() {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Today first, matching the strip's first column.
        LegendItem(swatch = { TodayLegendSwatch() }, label = "Today")
        LegendItem(swatch = { HighRiskLegendSwatch() }, label = "High risk")
    }
}

@Composable
private fun LegendItem(swatch: @Composable () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * What the card says when it has no week to draw. Each [OutlookGap] gets its own message; only
 * an actual failed fetch is blamed on the network.
 */
@Composable
private fun OutlookPlaceholder(gap: OutlookGap?, lastUpdated: Instant?) {
    val timeFormatter = remember {
        AppDateFormats.FULL_DATE_TIME.withZone(ZoneId.systemDefault())
    }

    val message = when (gap) {
        OutlookGap.ForecastBehind -> lastUpdated
            ?.let { "Forecast is out of date — last updated ${timeFormatter.format(it)}" }
            ?: "Forecast is out of date" // fallback; ViewModel doesn't actually produce this case

        OutlookGap.FetchFailed -> COULD_NOT_REACH_FORECAST
        OutlookGap.NoLocation -> NO_LOCATION_SET
        OutlookGap.NoReadings -> NO_FORECAST_FOR_LOCATION

        // Still waiting, not failing — no diagnosis needed.
        OutlookGap.Loading, null -> LOADING_FORECAST
    }

    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_ALPHA)
    )
}

private const val LOADING_FORECAST = "Loading forecast…"

/** Said only once a fetch has actually failed, which is the one case the connection explains. */
private const val COULD_NOT_REACH_FORECAST = "Couldn't reach the forecast — check your connection"

/** The onboarding gap: the app has somewhere to put a forecast but nowhere to fetch one for. */
private const val NO_LOCATION_SET = "Set a location to see the forecast"

/** The fetch worked and brought nothing back, which is about the place rather than the app. */
private const val NO_FORECAST_FOR_LOCATION = "No forecast available for this location"

private val STREAK_SEVERITY_DOT_SIZE = 10.dp

@Composable
private fun SymptomFreeCard(streak: SymptomFreeStreak?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeading("Symptom-free")
            Spacer(Modifier.height(12.dp))
            if (streak == null) {
                NotEnoughDataMessage(
                    hint = "Log a mild, aura or migraine day to start counting."
                )
                return@Column
            }
            // Shared so both date labels below agree on whether "this year" needs spelling out.
            val currentYear = remember { LocalDate.now().year }

            CurrentStreak(streak = streak, currentYear = currentYear)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            LongestStreak(longest = streak.longest, currentYear = currentYear)
        }
    }
}

@Composable
private fun CurrentStreak(streak: SymptomFreeStreak, currentYear: Int) {
    val lastEventLabel = remember(streak.lastEvent, currentYear) {
        val date = streak.lastEvent.date.format(dateFormatterFor(streak.lastEvent.date, currentYear))
        "Last: ${streak.lastEvent.severity.label} on $date"
    }

    // Merged into one semantics node so screen readers don't announce the date twice.
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "${dayCount(streak.currentDays)} symptom-free. $lastEventLabel"
        }
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                streak.currentDays.toString(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                dayUnit(streak.currentDays),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUPPORTING_ALPHA),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Matches the color this day already has in the calendar.
            Box(
                modifier = Modifier
                    .size(STREAK_SEVERITY_DOT_SIZE)
                    .clip(CircleShape)
                    .background(streak.lastEvent.severity.color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                lastEventLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
            )
        }
    }
}

/** Spells out the year only when it isn't this year, so an old date doesn't read as recent. */
private fun dateFormatterFor(date: LocalDate, currentYear: Int): DateTimeFormatter =
    if (date.year == currentYear) AppDateFormats.DAY_AND_MONTH else AppDateFormats.DAY_MONTH_AND_YEAR

@Composable
private fun LongestStreak(longest: SymptomFreeStreak.Run?, currentYear: Int) {
    // Kept side by side, not right-aligned: the log FAB covers the bottom-right corner on narrow screens.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Longest streak",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUBDUED_ALPHA)
        )
        Spacer(Modifier.width(8.dp))
        // Needs a second event to form a gap to measure; until then there's nothing to report.
        Text(
            longest?.let { dayCount(it.days) } ?: NOT_ENOUGH_DATA,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }

    // A zero-day run has no range to name.
    if (longest == null || longest.days == 0L) return

    val rangeLabel = remember(longest, currentYear) { longest.rangeLabel(currentYear) }
    Spacer(Modifier.height(2.dp))
    Text(
        rangeLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
    )
}

/** "12 May – 3 Jun", carrying the year the same way [dateFormatterFor] does. */
private fun SymptomFreeStreak.Run.rangeLabel(currentYear: Int): String {
    val formatter = if (to.year == currentYear) {
        AppDateFormats.SHORT_DAY_AND_MONTH
    } else {
        AppDateFormats.SHORT_DATE_AND_YEAR
    }
    return "${from.format(formatter)} – ${to.format(formatter)}"
}

@Composable
private fun NotEnoughDataMessage(hint: String) {
    Text(
        NOT_ENOUGH_DATA,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SUPPORTING_ALPHA)
    )
    Spacer(Modifier.height(4.dp))
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
    )
}

private const val NOT_ENOUGH_DATA = "Not enough data"

