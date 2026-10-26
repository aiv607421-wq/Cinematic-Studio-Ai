package com.cinematicstudio.ai.ui.theme

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

/**
 * Cinematic Studio AI visual language: a restrained dark workspace with a warm
 * film accent, tuned for contrast on a phone in a dim editing room.
 */
object StudioColors {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF14181D)
    val SurfaceHigh = Color(0xFF1B2027)
    val SurfaceVariant = Color(0xFF232A33)
    val Outline = Color(0xFF333B45)
    val Accent = Color(0xFFE8B44A)
    val AccentDim = Color(0xFF8A6A2C)
    val Teal = Color(0xFF57C4C0)
    val TextPrimary = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFF9AA3B0)
    val Danger = Color(0xFFE5544B)
    val Success = Color(0xFF55B685)
    val TrackVideo = Color(0xFF2A3B52)
    val TrackVideoBorder = Color(0xFF4F7CB5)
    val TrackAudio = Color(0xFF1F4032)
    val TrackAudioBorder = Color(0xFF4FA37A)
    val TrackText = Color(0xFF40304A)
    val TrackTextBorder = Color(0xFFA678C4)
    val Playhead = Color(0xFFE8B44A)
}

private val StudioColorScheme = darkColorScheme(
    primary = StudioColors.Accent,
    onPrimary = Color(0xFF161204),
    primaryContainer = StudioColors.AccentDim,
    onPrimaryContainer = Color(0xFFFFF3D6),
    secondary = StudioColors.Teal,
    onSecondary = Color(0xFF06211F),
    background = StudioColors.Background,
    onBackground = StudioColors.TextPrimary,
    surface = StudioColors.Surface,
    onSurface = StudioColors.TextPrimary,
    surfaceVariant = StudioColors.SurfaceVariant,
    onSurfaceVariant = StudioColors.TextSecondary,
    outline = StudioColors.Outline,
    error = StudioColors.Danger,
    onError = Color(0xFF2A0705),
)

private val StudioTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)

@Composable
fun CinematicStudioTheme(content: @Composable () -> Unit) {
    // The studio is intentionally dark in both system modes: a light editor
    // washes out the preview monitor.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = StudioTypography,
        content = content,
    )
}
