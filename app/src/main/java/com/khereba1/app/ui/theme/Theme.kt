package com.khereba1.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TealPrimary = Color(0xFF0F766E)
val TealBright = Color(0xFF14B8A6)
val SlateDarkBg = Color(0xFF0D1518)
val SlateDarkSurface = Color(0xFF162226)
val SlateLightBg = Color(0xFFF4F7F8)
val SlateLightSurface = Color(0xFFFFFFFF)

val ChatAccentColors = listOf(
    Color(0xFF0F766E),
    Color(0xFF1D4ED8),
    Color(0xFF7C3AED),
    Color(0xFFB45309),
    Color(0xFFBE185D),
    Color(0xFF15803D)
)

private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    background = SlateDarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = SlateDarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E2D33),
    onSurfaceVariant = Color(0xFF94A3B8)
)

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF0284C7),
    background = SlateLightBg,
    onBackground = Color(0xFF0F172A),
    surface = SlateLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569)
)

@Composable
fun NearChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
