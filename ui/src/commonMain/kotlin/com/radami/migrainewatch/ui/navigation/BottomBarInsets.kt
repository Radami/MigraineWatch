package com.radami.migrainewatch.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * The most of the system's bottom inset the bar may take, or null for all of it. Set per
 * platform: Android needs every pixel of it, since with three-button navigation the buttons sit
 * there; an iPhone's home-indicator area is taller than its indicator needs.
 */
internal expect val BOTTOM_BAR_INSET_LIMIT: Dp?

/** The insets the bottom bar keeps clear of: the system's, with its bottom held to the limit. */
@Composable
internal fun bottomBarInsets(): WindowInsets {
    val system = NavigationBarDefaults.windowInsets
    val limit = BOTTOM_BAR_INSET_LIMIT ?: return system

    return BottomLimitedInsets(system, limit)
}

/** [base], except that its bottom is never more than [limit]. */
private class BottomLimitedInsets(private val base: WindowInsets, private val limit: Dp) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = base.getLeft(density, layoutDirection)

    override fun getTop(density: Density) = base.getTop(density)

    override fun getRight(density: Density, layoutDirection: LayoutDirection) = base.getRight(density, layoutDirection)

    override fun getBottom(density: Density): Int = with(density) {
        minOf(base.getBottom(density), limit.roundToPx())
    }
}
