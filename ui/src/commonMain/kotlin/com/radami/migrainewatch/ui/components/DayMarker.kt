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
import com.radami.migrainewatch.ui.theme.MUTED_ALPHA

/**
 * Corner rounding shared by every severity-coloured surface. A percentage, not a fixed dp,
 * so swatches of different sizes read as the same shape.
 */
val SeverityShape = RoundedCornerShape(percent = SEVERITY_CORNER_PERCENT)

/** Corner percentages for the two marker silhouettes; 50% of a square is a circle, letting
 * a marker morph between them instead of swapping shapes. */
private const val SEVERITY_CORNER_PERCENT = 28
private const val HIGH_RISK_CORNER_PERCENT = 50

/** Silhouette of a high-risk day. Shape, not colour, carries the risk. */
val HighRiskShape = CircleShape

private val TODAY_BORDER_WIDTH = 2.dp

/** Thinner than today's border since both rings can land on one day and today's ring must
 * stay the heavier of the two. Public so the legend can draw an identical ring. */
val HIGH_RISK_BORDER_WIDTH = 1.5.dp

/** The width a marker wearing no ring is drawn at, so the ring has a value to animate from. */
private val NO_BORDER_WIDTH = 0.dp

/** Whether a pressure event crossing the alert threshold touches the day. */
enum class DayRisk { Normal, High }

/** Whether a change of [DayRisk] is travelled or simply arrived at. */
enum class RiskTransition {

    /** Takes the new silhouette instantly. Used by the calendar grid, where a recycled cell
     * would otherwise morph from the day it used to show into the new one. */
    Immediate,

    /** Rounds from one silhouette into the other. Used by the outlook strip, which
     * rewrites itself under the reader as new forecasts arrive. */
    Animated
}

/**
 * [target], or its animated approach to it, depending on [RiskTransition].
 * All risk-driven properties must share this switch so a day changes as one movement.
 */
@Composable
private fun <T> RiskTransition.settle(target: T, animate: @Composable (T) -> T): T =
    when (this) {
        RiskTransition.Animated -> animate(target)
        RiskTransition.Immediate -> target
    }

/** How much weight the day number carries relative to the days around it. */
enum class DayEmphasis {

    /** Every day reads the same, so a quiet day stays as legible as a busy one. */
    Uniform,

    /** Risky days are bold and the rest recede, for a glance-read strip like the outlook. */
    ByRisk
}

/** Recede amount for an unemphasised day under [DayEmphasis.ByRisk], matched to the
 * muted text alpha used elsewhere on screen. */
private const val UNEMPHASISED_DAY_ALPHA = MUTED_ALPHA

/** The size every legend swatch is drawn at, so two legends on different screens match. */
val LEGEND_SWATCH_SIZE = 12.dp

/**
 * A single day in the calendar grid: number centred in a shape marking risk, filled with
 * severity colour once logged.
 *
 * @param severityColor fill for a logged day; null leaves it unfilled.
 * @param risk whether an alert-threshold pressure event touches the day.
 * @param emphasis whether the number's weight follows [risk].
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
    // All risk-driven properties animate off the same switch, so shape, ring and weight
    // change as one movement. Immediate reads targets straight instead of snap-animating,
    // avoiding the one-frame lag and per-marker state a snapped animation would still cost.
    val cornerPercent = transition.settle(
        if (risk == DayRisk.High) HIGH_RISK_CORNER_PERCENT else SEVERITY_CORNER_PERCENT
    ) {
        animateIntAsState(
            targetValue = it,
            animationSpec = tween(Motion.SHAPE_MORPH_MILLIS),
            label = "markerCorner"
        ).value
    }
    val markerShape = RoundedCornerShape(percent = cornerPercent)

    // The ring marks the shape as deliberate rather than an oddly-shaped chip. Only one ring
    // is ever drawn; today outranks risk since the shape already signals risk. No ring still
    // targets a transparent zero-width one, giving an animation something to travel from.
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
    val borderWidth = transition.settle(targetBorderWidth) {
        animateDpAsState(
            targetValue = it,
            animationSpec = tween(Motion.EMPHASIS_MILLIS),
            label = "markerBorderWidth"
        ).value
    }
    val borderColor = transition.settle(targetBorderColor) {
        animateColorAsState(
            targetValue = it,
            animationSpec = tween(Motion.EMPHASIS_MILLIS),
            label = "markerBorderColor"
        ).value
    }

    Box(
        // Clip and fill first so the click ripple follows the marker's shape.
        modifier = modifier
            .clip(markerShape)
            .background(severityColor ?: Color.Transparent)
            // Only add the border node when there is a ring to draw, so the many cells with
            // no ring carry no extra node.
            .then(
                if (borderWidth > NO_BORDER_WIDTH) {
                    Modifier.border(borderWidth, borderColor, markerShape)
                } else {
                    Modifier
                }
            )
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
        val textColor = transition.settle(targetTextColor) {
            animateColorAsState(
                targetValue = it,
                animationSpec = tween(Motion.EMPHASIS_MILLIS),
                label = "markerTextColor"
            ).value
        }

        Text(
            day.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (emphasised) FontWeight.Bold else null,
            color = textColor
        )
    }
}

/** The ring a high-risk day wears, at legend size, so the legend matches exactly what a
 * day itself draws. */
@Composable
fun HighRiskLegendSwatch(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(LEGEND_SWATCH_SIZE)
            .border(HIGH_RISK_BORDER_WIDTH, MaterialTheme.colorScheme.outline, HighRiskShape)
    )
}

/** The ring today wears, at legend size, drawn on the plain day shape rather than the
 * circle (which already means "high risk"). */
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
