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

// Destructive actions. Darker than SeverityMigraine so a delete button doesn't read as
// just another severity swatch.
val DangerRed = Color(0xFF8E1B1B)

// The app's brand colour (Play Store icon terracotta), used in the Settings wordmark and the
// Today headline. Dark variant is for the headline only — it needs the extra contrast that
// the wordmark, a lockup rather than body text, does not.
val BrandTerracottaLight = Color(0xFFB05C3B)
val BrandTerracottaDark = Color(0xFFE48D6C)

// Chart colours
val ChartNowLineLight = Color(0xFF000000)
val ChartNowLineDark = Color(0xFFFFFFFF)

// The chart's data colour, whichever rendering is used. Deliberately blue, not terracotta:
// blue is the one hue the alert palette doesn't use, so data and risk shading stay distinct.
val ChartSeriesLight = Color(0xFF5B8DC8)
val ChartSeriesDark = Color(0xFF8FB9E8)

// Alert colours — fixed palette shared by the chart's risk shading and the alert rows,
// independent of dynamic theming. One hue per alert, light and dark variant.
val Alert1Light = Color(0xFFD32F2F)
val Alert1Dark = Color(0xFFEF5350)

val Alert2Light = Color(0xFFEF6C00)
val Alert2Dark = Color(0xFFFFA726)

val Alert3Light = Color(0xFF7B1FA2)
val Alert3Dark = Color(0xFFCE93D8)
