package com.radami.migrainewatch.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.radami.migrainewatch.ui.theme.Motion

/**
 * A line of text that crosses over to its new value instead of switching to it, for text
 * rewritten while the reader is looking at it (a timestamp, a headline, a changing figure).
 * The outgoing value fully leaves before the incoming one arrives; unclipped so reflow works.
 *
 * @param label names the transition for tooling and animation inspection.
 */
@Composable
fun SettlingText(
    text: String,
    style: TextStyle,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            val enter = fadeIn(
                tween(Motion.CONTENT_ENTER_MILLIS, delayMillis = Motion.CONTENT_EXIT_MILLIS)
            ) + slideInVertically(
                tween(Motion.CONTENT_ENTER_MILLIS, delayMillis = Motion.CONTENT_EXIT_MILLIS)
            ) { height -> height / Motion.CONTENT_SLIDE_FRACTION }

            enter togetherWith fadeOut(tween(Motion.CONTENT_EXIT_MILLIS)) using
                SizeTransform(clip = false)
        },
        modifier = modifier,
        label = label
    ) { value ->
        Text(value, style = style, color = color, fontWeight = fontWeight)
    }
}
