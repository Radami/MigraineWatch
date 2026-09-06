package com.radami.migrainewatch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radami.migrainewatch.ui.theme.Motion

/**
 * Corner rounding shared by every severity-coloured surface. It is a percentage rather than
 * a fixed dp so a 12 dp legend swatch and a 45 dp day cell read as the same shape.
 */
val SeverityShape = RoundedCornerShape(percent = SEVERITY_CORNER_PERCENT)

/**
 * The two silhouettes as corner percentages, which is what lets a marker travel between them
 * rather than swap. 50% of a square is a circle, so the high-risk value draws exactly
 * [HighRiskShape] — the legend and the days it explains stay identical.
 */
private const val SEVERITY_CORNER_PERCENT = 28
private const val HIGH_RISK_CORNER_PERCENT = 50

/**
 * The silhouette of a high-risk day. Shape, not colour, carries the risk so that the severity
 * palette is left free to mean only what it has always meant.
 */
val HighRiskShape = CircleShape

private val TODAY_BORDER_WIDTH = 2.dp

/**
 * Thinner than today's border on purpose: both rings can land on the same day, and today is
 * the cell users hunt for, so it has to stay the heavier of the two. Public because the legend
 * draws the same ring, and the two have to stay identical for the legend to mean anything.
 */
val HIGH_RISK_BORDER_WIDTH = 1.5.dp

/** The width a marker wearing no ring is drawn at, so the ring has a value to animate from. */
private val NO_BORDER_WIDTH = 0.dp

/** Whether a pressure event crossing the alert threshold touches the day. */
enum class DayRisk { Normal, High }

/** Whether a change of [DayRisk] is travelled or simply arrived at. */
enum class RiskTransition {

    /**
     * The marker takes its new silhouette on the frame it changes, having animated nothing.
     * What the calendar grid wants: it scrolls, and a recycled composition slot would otherwise
     * morph from whichever day it used to hold into the one it now shows.
     */
    Immediate,

    /**
     * The marker rounds from one silhouette into the other. For the outlook strip, which is
     * rewritten under the reader whenever a new forecast lands.
     */
    Animated
}

/** How much weight the day number carries relative to the days around it. */
enum class DayEmphasis {

    /**
     * Every day reads the same. What a calendar grid wants: the numbers are how a day is
     * found, so a quiet day has to stay as legible as a busy one.
     */
    Uniform,

    /**
     * A day at risk is set in bold and the rest recede. For a short strip read at a glance,
     * where the point is which days to look at rather than which day is which.
     */
    ByRisk
}

/** How far a day recedes under [DayEmphasis.ByRisk] when nothing touches it. */
private const val UNEMPHASISED_DAY_ALPHA = 0.5f

/** The size every legend swatch is drawn at, so two legends on different screens match. */
val LEGEND_SWATCH_SIZE = 12.dp

/**
 * A single day in the calendar grid: the day number centred in a shape that says whether the
 * day is high risk, filled with the severity colour once something has been logged.
 *
 * @param severityColor fill for a logged day; null leaves the marker unfilled.
 * @param risk whether a pressure event crossing the alert threshold touches the day.
 * @param emphasis whether the number leans on [risk] for its weight.
 */
@Composable
fun DayMarker(
    day: Int,
    severityColor: Color?,
    risk: DayRisk,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    emphasis: DayEmphasis = DayEmphasis.Uniform,
    transition: RiskTransition = RiskTransition.Immediate,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    // Every property that risk moves is animated off the same switch, so a day that turns
    // risky changes shape, ring and weight as one movement rather than three.
    //
    // An Immediate marker reads its targets straight rather than animating them on `snap()`.
    // A snapped animation is still an animation: it holds state per property per marker — four
    // of them across forty-two calendar cells — and it still arrives on the frame after the
    // change, which is the single stale frame Immediate exists to prevent.
    val targetCornerPercent =
        if (risk == DayRisk.High) HIGH_RISK_CORNER_PERCENT else SEVERITY_CORNER_PERCENT
    val cornerPercent = when (transition) {
        RiskTransition.Animated -> animateIntAsState(
            targetValue = targetCornerPercent,
            animationSpec = tween(Motion.SHAPE_MORPH_MILLIS),
            label = "markerCorner"
        ).value

        RiskTransition.Immediate -> targetCornerPercent
    }
    val markerShape = RoundedCornerShape(percent = cornerPercent)

    // A circle is always outlined, filled or not: the ring is what makes the silhouette read
    // as deliberate rather than as a differently-shaped severity chip. Exactly one ring is
    // ever drawn, and today outranks risk for the edge — the shape has already said
    // "high risk" by then. A day wearing no ring is drawn with a transparent one of no width
    // rather than no border at all, so the ring has something to fade in from.
    val targetBorderWidth = when {
        isToday -> TODAY_BORDER_WIDTH
        risk == DayRisk.High -> HIGH_RISK_BORDER_WIDTH
        else -> NO_BORDER_WIDTH
    }
    val targetBorderColor = when {
        isToday -> MaterialTheme.colorScheme.primary
        risk == DayRisk.High -> MaterialTheme.colorScheme.outline
        else -> Color.Transparent
    }
    val borderWidth = when (transition) {
        RiskTransition.Animated -> animateDpAsState(
            targetValue = targetBorderWidth,
            animationSpec = tween(Motion.EMPHASIS_MILLIS),
            label = "markerBorderWidth"
        ).value

        RiskTransition.Immediate -> targetBorderWidth
    }
    val borderColor = when (transition) {
        RiskTransition.Animated -> animateColorAsState(
            targetValue = targetBorderColor,
            animationSpec = tween(Motion.EMPHASIS_MILLIS),
            label = "markerBorderColor"
        ).value

        RiskTransition.Immediate -> targetBorderColor
    }

    Box(
        // Clip and fill first so the click ripple follows the marker's shape.
        modifier = modifier
            .clip(markerShape)
            .background(severityColor ?: Color.Transparent)
            .border(borderWidth, borderColor, markerShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val emphasised = emphasis == DayEmphasis.ByRisk && risk == DayRisk.High

        // Only an unfilled number can recede: white on a severity fill is the one pairing
        // that has to stay at full strength to stay readable at all.
        val recedes = emphasis == DayEmphasis.ByRisk && risk == DayRisk.Normal && severityColor == null

        val targetTextColor = when {
            severityColor != null -> Color.White
            recedes -> MaterialTheme.colorScheme.onSurface.copy(alpha = UNEMPHASISED_DAY_ALPHA)
            else -> MaterialTheme.colorScheme.onSurface
        }
        // Weight cannot be interpolated, so bold still arrives in one frame. The colour
        // carries the change instead, which is the half of it the eye actually follows.
        val textColor = when (transition) {
            RiskTransition.Animated -> animateColorAsState(
                targetValue = targetTextColor,
                animationSpec = tween(Motion.EMPHASIS_MILLIS),
                label = "markerTextColor"
            ).value

            RiskTransition.Immediate -> targetTextColor
        }

        Text(
            day.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (emphasised) FontWeight.Bold else null,
            color = textColor
        )
    }
}

/**
 * The ring a high-risk day wears, at legend size. Shared rather than redrawn per screen: a
 * legend showing anything other than the exact ring the day wears explains nothing.
 */
@Composable
fun HighRiskLegendSwatch(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(LEGEND_SWATCH_SIZE)
            .border(HIGH_RISK_BORDER_WIDTH, MaterialTheme.colorScheme.outline, HighRiskShape)
    )
}

/**
 * The ring today wears, at legend size. Drawn in the plain day silhouette rather than the
 * circle: the circle is what says "high risk", and a legend entry that borrowed it would be
 * explaining two things at once.
 */
@Composable
fun TodayLegendSwatch(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(LEGEND_SWATCH_SIZE)
            .border(TODAY_BORDER_WIDTH, MaterialTheme.colorScheme.primary, SeverityShape)
    )
}

/** The colour chip that stands for a severity in legends and summaries. */
@Composable
fun SeveritySwatch(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(SeverityShape)
            .background(color)
    )
}
