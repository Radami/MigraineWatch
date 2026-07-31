package com.radami.migrainewatch.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

// Severity colours
val SeverityClear = Color(0xFF4CAF50)
val SeverityMild = Color(0xFFFFC107)
val SeverityAura = Color(0xFFFF9800)
val SeverityMigraine = Color(0xFFF44336)

// Destructive actions. Deliberately darker than SeverityMigraine so a delete button never
// reads as just another severity swatch.
val DangerRed = Color(0xFF8E1B1B)

// Chart colours
val ChartMeasuredLight = Color(0xFFB05C3B)
val ChartMeasuredDark = Color(0xFFE48D6C)
val ChartForecastLight = Color(0xFF757575)
val ChartForecastDark = Color(0xFFBDBDBD)
val ChartAlert = Color(0xFFF44336)
val ChartNowLineLight = Color(0xFF000000)
val ChartNowLineDark = Color(0xFFFFFFFF)

// Alert colours — fixed palette shared by the alert banners and the chart shading,
// independent of dynamic (wallpaper-based) theming. Each alert has a base hue plus a
// light/dark container pair; the container of one mode doubles as the on-container
// (text/icon) colour of the other.
val Alert1Light = Color(0xFFD32F2F)
val Alert1Dark = Color(0xFFEF5350)
val Alert1ContainerLight = Color(0xFFF9DEDC)
val Alert1ContainerDark = Color(0xFF8C1D18)

val Alert2Light = Color(0xFFEF6C00)
val Alert2Dark = Color(0xFFFFA726)
val Alert2ContainerLight = Color(0xFFFFE0B2)
val Alert2ContainerDark = Color(0xFF6B3E00)

val Alert3Light = Color(0xFF7B1FA2)
val Alert3Dark = Color(0xFFCE93D8)
val Alert3ContainerLight = Color(0xFFE1BEE7)
val Alert3ContainerDark = Color(0xFF4A148C)
