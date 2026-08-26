package com.radami.migrainewatch.ui.screens.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureDirection
import com.radami.migrainewatch.domain.SymptomFreeStreak
import com.radami.migrainewatch.format.AlertTimingDetail
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.formatAlertSummary
import com.radami.migrainewatch.format.formatAlertTiming
import com.radami.migrainewatch.format.label
import com.radami.migrainewatch.ui.components.CardHeading
import com.radami.migrainewatch.ui.components.DayEmphasis
import com.radami.migrainewatch.ui.components.DayMarker
import com.radami.migrainewatch.ui.components.DayRisk
import com.radami.migrainewatch.ui.components.HighRiskLegendSwatch
import com.radami.migrainewatch.ui.components.TodayLegendSwatch
import com.radami.migrainewatch.ui.theme.color
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
                Text(
                    "Updated ${state.lastUpdated?.let { timeFormatter.format(it) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
 * @param alerts events under way or still ahead, earliest first, and never empty. The banner
 *   heads the first — the one under way when there is one, otherwise the one arriving soonest
 *   — and counts the rest, so a caller passing a finished event would have it announced as
 *   something the user still has ahead of them.
 * @param phase where that first event sits relative to now, which is the difference between
 *   telling the user something is coming and telling them they are already in it. It comes
 *   from the ViewModel because a composable has no clock of its own.
 */
@Composable
private fun AlertBanner(alerts: List<AlertWindow>, phase: AlertPhase, onClick: () -> Unit) {
    val first = alerts.first()
    val zone = remember { ZoneId.systemDefault() }

    // The banner says which way the risk runs and when, and stops there. How big the swing is
    // is what the screen behind the chevron is for, and reading it off a two-line banner never
    // told anyone anything they could act on. The wording is the notification's own, so the two
    // cannot describe the same event differently.
    val timing = remember(first, phase, zone) {
        formatAlertTiming(first, phase, AlertTimingDetail.Brief, zone)
    }
    val headline = if (alerts.size == 1) "Elevated risk" else "Elevated risk · Multiple events"
    val message = "$headline\n$timing"

    Surface(
        shape = ALERT_BANNER_SHAPE,
        color = MaterialTheme.colorScheme.errorContainer,
        // Clipped before it is made clickable: a modifier passed to Surface sits outside the
        // clipping Surface does for its own shape, so an unclipped ripple would flash square
        // corners over the rounded ones.
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
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )

            // A plain icon now, not a button: the whole banner is the target, and a nested one
            // would be a second thing to tap and a second thing to announce for the same trip.
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

/**
 * The ripple a tapped day shows. Rounded rather than square because the column is barely wider
 * than the marker inside it, so a hard-cornered flash reads as a glitch rather than a press.
 */
private val OUTLOOK_DAY_SHAPE = RoundedCornerShape(8.dp)

/**
 * How far a day the forecast never reached is faded. It sits on top of the fading a quiet day
 * already gets from [DayEmphasis.ByRisk], so it only has to open a gap below that — enough to
 * read as "nothing known here" rather than "checked, and quiet".
 */
private const val UNKNOWN_DAY_ALPHA = 0.6f

/** The weekday above a day worth looking at, and above one that isn't. */
private const val WEEKDAY_ALPHA_AT_RISK = 0.9f
private const val WEEKDAY_ALPHA = 0.45f

/**
 * The week ahead at a glance: what today looks like, then which of the days after it carry a
 * pressure event. The days are drawn with the calendar's own [DayMarker], so a day marked to
 * watch here has the silhouette it will have there.
 */
@Composable
private fun OutlookCard(state: TodayUiState, onDayClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeading("${DayOutlook.DAYS}-day outlook")
            Spacer(Modifier.height(12.dp))

            // Nothing can be said about the week before the first load lands, and a week the
            // forecast never reached is a failure to report rather than a row of empty days.
            val today = state.outlook.firstOrNull()
            if (today == null || state.outlook.all { it.risk == OutlookRisk.Unknown }) {
                OutlookPlaceholder(isLoading = state.isLoading)
                return@Column
            }

            TodayHeadline(today = today, outlook = state.outlook)
            Spacer(Modifier.height(16.dp))
            OutlookStrip(outlook = state.outlook, onDayClick = onDayClick)
            Spacer(Modifier.height(12.dp))
            OutlookLegend()
        }
    }
}

@Composable
private fun TodayHeadline(today: DayOutlook, outlook: List<DayOutlook>) {
    val isElevated = today.risk == OutlookRisk.Elevated

    Text(
        todayLabel(today),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        // Today's own risk is the one thing on this card worth colouring: everything below is
        // context for it.
        color = if (isElevated) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    )
    Spacer(Modifier.height(2.dp))
    Text(
        weekAheadLabel(outlook),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                // Only the first column is today, and it is today by construction rather
                // than by a date comparison that could disagree with the list it was built
                // from. Left open across midnight with nothing emitting, the whole strip goes
                // stale together — the ViewModel's LocalDate.now() dated these days — so the
                // ring stays on the column the dates agree is today rather than drifting off
                // its own strip. Wrong by a day, but not wrong about itself.
                isToday = index == 0,
                weekday = weekdayFormatter.format(day.date),
                onClick = onDayClick
            )
        }
    }
}

@Composable
private fun OutlookDay(day: DayOutlook, isToday: Boolean, weekday: String, onClick: () -> Unit) {
    // The column reads as one thing, so it is announced as one: left alone, the weekday, the
    // day number and the swing are three separate stops that never mention the risk. The tap
    // target is the whole column rather than the marker, so the weekday above it works too.
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
        // The whole column leans one way or the other, label included: a bold number under a
        // weekday of the same weight as every other would be a smaller signal than it should be.
        val atRisk = day.risk == OutlookRisk.Elevated

        Text(
            weekday,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (atRisk) FontWeight.SemiBold else null,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (atRisk) WEEKDAY_ALPHA_AT_RISK else WEEKDAY_ALPHA
            )
        )
        Spacer(Modifier.height(4.dp))
        DayMarker(
            day = day.date.dayOfMonth,
            severityColor = null,
            risk = if (atRisk) DayRisk.High else DayRisk.Normal,
            isToday = isToday,
            modifier = Modifier
                .size(OUTLOOK_MARKER_SIZE)
                .alpha(if (day.risk == OutlookRisk.Unknown) UNKNOWN_DAY_ALPHA else 1f),
            // Only here: the calendar is a grid people read a specific day out of, so its
            // numbers all carry the same weight.
            emphasis = DayEmphasis.ByRisk
        )
    }
}

/**
 * What the rings in the strip mean. Both swatches are drawn by the marker itself rather than
 * redrawn here, so the legend cannot drift from the days: high risk in the calendar's own
 * words and swatch, since the two screens mark a day to watch the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutlookLegend() {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Today first, matching the strip: its first column is the one this entry explains.
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

@Composable
private fun OutlookPlaceholder(isLoading: Boolean) {
    Text(
        if (isLoading) "Loading forecast…" else "Unable to load — check your connection",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    )
}

private fun todayLabel(today: DayOutlook): String = when (today.risk) {
    OutlookRisk.Elevated -> "Elevated risk today"
    OutlookRisk.Clear -> "Clear today"
    OutlookRisk.Unknown -> "No forecast for today"
}

/**
 * What the days after today add up to.
 *
 * Only the days the forecast actually reached can be called clear, so a short forecast says
 * how far it got rather than reporting quiet days it knows nothing about. The count of days to
 * watch is deliberately not hedged the same way: a detected event is known whatever the
 * forecast does after it, and a day the readings never reached cannot add to the count anyway,
 * so "2 of the next 6 days" stays true where coverage runs out early.
 *
 * Today is dropped rather than counted, so the days counted here and the days circled in the
 * strip are deliberately different sets: [TodayHeadline] above already speaks for today, and
 * counting it twice would have the card say the same thing in two voices.
 */
private fun weekAheadLabel(outlook: List<DayOutlook>): String {
    val ahead = outlook.drop(1)
    val toWatch = ahead.count { it.risk == OutlookRisk.Elevated }
    if (toWatch > 0) return "$toWatch of the next ${ahead.size} days to watch"

    val covered = ahead.count { it.risk != OutlookRisk.Unknown }
    if (covered == 0) return "No forecast beyond today"
    return "Clear for the next ${dayCount(covered.toLong())}"
}

private fun outlookDayDescription(day: DayOutlook, isToday: Boolean, weekday: String): String {
    val name = if (isToday) "Today" else "$weekday ${day.date.dayOfMonth}"
    return when (day.risk) {
        OutlookRisk.Elevated ->
            "$name, elevated risk, " +
                formatAlertSummary(day.peakDelta ?: 0f, day.direction ?: PressureDirection.DROP)

        OutlookRisk.Clear -> "$name, clear"
        OutlookRisk.Unknown -> "$name, no forecast"
    }
}

private val STREAK_SEVERITY_DOT_SIZE = 10.dp

@Composable
private fun SymptomFreeCard(streak: SymptomFreeStreak?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeading("Symptom-free")
            Spacer(Modifier.height(12.dp))
            if (streak == null) {
                NotEnoughDataMessage(
                    hint = "Log a mild, aura or migraine day to start counting."
                )
                return@Column
            }
            // Both halves date-stamp a past day, so they share one reading of "this year"
            // rather than each deciding separately whether a year is worth spelling out.
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

    // The count and the date read as one sentence, so they are announced as one node. Left
    // unmerged, the figure, its unit and the date below it are each their own node and the
    // date ends up spoken twice.
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The swatch ties the date back to the colour the day already wears in the calendar.
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Spells the year out only once this year is the wrong thing to assume — a long streak dates its
 * last event years back, and "Saturday 15 August" would read as a recent one.
 */
private fun dateFormatterFor(date: LocalDate, currentYear: Int): DateTimeFormatter =
    if (date.year == currentYear) AppDateFormats.DAY_AND_MONTH else AppDateFormats.DAY_MONTH_AND_YEAR

@Composable
private fun LongestStreak(longest: SymptomFreeStreak.Run?, currentYear: Int) {
    // Label and figure sit side by side rather than spread across the row: the log FAB floats
    // over the bottom-right corner of this card, so anything right-aligned here disappears
    // under it on a narrow screen.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Longest streak",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        Spacer(Modifier.width(8.dp))
        // A second event is what creates the first gap to measure, so until then there is
        // genuinely nothing to report rather than a streak of zero.
        Text(
            longest?.let { dayCount(it.days) } ?: NOT_ENOUGH_DATA,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }

    // A run of zero days spans no days at all, so there is no range to name.
    if (longest == null || longest.days == 0L) return

    val rangeLabel = remember(longest, currentYear) { longest.rangeLabel(currentYear) }
    Spacer(Modifier.height(2.dp))
    Text(
        rangeLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

/**
 * "12 May – 3 Jun", carrying the year on the same terms as [dateFormatterFor] — a record set
 * years ago would otherwise read as a recent one.
 */
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
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(Modifier.height(4.dp))
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

private const val NOT_ENOUGH_DATA = "Not enough data"

private fun dayCount(days: Long): String = "$days ${dayUnit(days)}"

private fun dayUnit(days: Long): String = if (days == 1L) "day" else "days"
