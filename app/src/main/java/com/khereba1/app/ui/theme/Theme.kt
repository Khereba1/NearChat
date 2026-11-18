package com.khereba1.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khereba1.app.R

// NearChat visual identity: restrained teal accents, deep ink surfaces and soft light surfaces.
val TealPrimary = Color(0xFF0F766E)
val TealBright = Color(0xFF19C6B5)
val SlateDarkBg = Color(0xFF0B1216)
val SlateDarkSurface = Color(0xFF121D22)
val SlateLightBg = Color(0xFFF5F7F8)
val SlateLightSurface = Color(0xFFFFFFFF)

val ChatAccentColors = listOf(
    Color(0xFF0F766E), Color(0xFF2563EB), Color(0xFF7C3AED),
    Color(0xFFB45309), Color(0xFFBE185D), Color(0xFF15803D)
)

private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF164E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    background = SlateDarkBg,
    onBackground = Color(0xFFE8EEF1),
    surface = SlateDarkSurface,
    onSurface = Color(0xFFE8EEF1),
    surfaceVariant = Color(0xFF1B292F),
    onSurfaceVariant = Color(0xFFA1B0B8),
    outline = Color(0xFF304148)
)

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F5F0),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF0284C7),
    background = SlateLightBg,
    onBackground = Color(0xFF101B22),
    surface = SlateLightSurface,
    onSurface = Color(0xFF101B22),
    surfaceVariant = Color(0xFFE9EFF1),
    onSurfaceVariant = Color(0xFF586A73),
    outline = Color(0xFFD7E0E3)
)

private val NearChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** IBM Plex Sans Arabic includes Latin glyphs too, keeping both locales visually consistent. */
private val NearChatFont = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold)
)

private val BaseTypography = Typography()

private val NearChatTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = NearChatFont),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = NearChatFont),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = NearChatFont),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = NearChatFont),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = NearChatFont),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = NearChatFont),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = NearChatFont),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = NearChatFont),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = NearChatFont),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = NearChatFont),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = NearChatFont),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = NearChatFont),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = NearChatFont),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = NearChatFont),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = NearChatFont)
)

/** themeMode: system, light, or dark. */
@Composable
fun NearChatTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = NearChatShapes,
        typography = NearChatTypography,
        content = content
    )
}
