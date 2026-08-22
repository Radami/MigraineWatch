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

// The daily range band. Deliberately not the measured line's colour, which it used to borrow:
// that terracotta sits a shade away from the orange of the second alert, so a band and a risk
// window read as the same thing. Blue is the one hue the alert palette does not use.
val ChartRangeBandLight = Color(0xFF5B8DC8)
val ChartRangeBandDark = Color(0xFF8FB9E8)

// Alert colours — fixed palette shared by the chart's risk shading and the alert rows
// beside it, independent of dynamic (wallpaper-based) theming. One hue per alert, in a
// light and a dark variant.
val Alert1Light = Color(0xFFD32F2F)
val Alert1Dark = Color(0xFFEF5350)

val Alert2Light = Color(0xFFEF6C00)
val Alert2Dark = Color(0xFFFFA726)

val Alert3Light = Color(0xFF7B1FA2)
val Alert3Dark = Color(0xFFCE93D8)
