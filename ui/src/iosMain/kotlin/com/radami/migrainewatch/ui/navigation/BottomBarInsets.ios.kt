package com.radami.migrainewatch.ui.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Android's gesture-navigation inset, so the bar and its labels sit exactly where they do there.
 * The home-indicator area is 34pt, but the indicator itself is a thin line near the bottom edge,
 * and labels this high stay well clear of it.
 */
internal actual val BOTTOM_BAR_INSET_LIMIT: Dp? = 24.dp
