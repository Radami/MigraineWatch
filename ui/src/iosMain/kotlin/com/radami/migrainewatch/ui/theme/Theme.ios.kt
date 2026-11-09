package com.radami.migrainewatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** iOS has no wallpaper-derived palette, so the app's own colours always apply. */
@Composable
internal actual fun systemColorScheme(darkTheme: Boolean): ColorScheme? = null
