package com.mohid.obd2dash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A car dashboard is looked at through a windscreen in daylight and at night,
// so it stays dark in both cases and leans on saturation, not brightness, for
// the accents.
val Ink = Color(0xFF07090C)
val Panel = Color(0xFF11161C)
val PanelRaised = Color(0xFF1A212A)
val Hairline = Color(0xFF2A343F)
val TextPrimary = Color(0xFFE6EDF3)
val TextMuted = Color(0xFF8A98A8)

val Cyan = Color(0xFF35D0E0)
val ZoneGood = Color(0xFF2ED573)
val ZoneWarn = Color(0xFFFFB020)
val ZoneDanger = Color(0xFFFF4757)

private val scheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    primaryContainer = Color(0xFF10343A),
    onPrimaryContainer = Cyan,
    secondary = ZoneWarn,
    onSecondary = Ink,
    error = ZoneDanger,
    onError = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextMuted,
    outline = Hairline,
    outlineVariant = Hairline,
)

/**
 * Live numbers use a monospaced face so digits keep a fixed width. Proportional
 * figures make a fast-moving readout jitter sideways, which is unreadable at a
 * glance.
 */
val NumericStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
)

private val typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

@Composable
fun Obd2DashTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Always dark: a light dashboard is unusable at night and washed out by day.
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content,
    )
}

/**
 * Where a value sits relative to its healthy range, used for the coloured zones
 * on gauges and the dot on each metric card.
 */
enum class Zone(val color: Color) {
    GOOD(ZoneGood),
    WARN(ZoneWarn),
    DANGER(ZoneDanger),
    UNKNOWN(TextMuted),
}
