package io.omarchy.omasend.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

private val DarkColorScheme = darkColorScheme(
    primary = OmarchyAccent,
    onPrimary = OmarchyDarkBg,
    primaryContainer = OmarchyAccentDark,
    onPrimaryContainer = OmarchyAccentLight,
    secondary = OmarchyBlue,
    onSecondary = OmarchyDarkBg,
    secondaryContainer = OmarchyBlueDark,
    onSecondaryContainer = OmarchyBlueLight,
    background = OmarchyDarkBg,
    onBackground = OmarchyTextPrimary,
    surface = OmarchySurface,
    onSurface = OmarchyTextPrimary,
    surfaceVariant = OmarchySurfaceContainer,
    onSurfaceVariant = OmarchyTextSecondary,
    surfaceContainer = OmarchySurfaceContainer,
    surfaceContainerHigh = OmarchySurfaceContainerHigh,
    surfaceContainerHighest = OmarchyBorder,
    surfaceContainerLow = OmarchySurface,
    surfaceContainerLowest = OmarchyDarkBg,
    surfaceBright = OmarchySurfaceContainerHigh,
    surfaceDim = OmarchyDarkBg,
    inverseSurface = OmarchyTextPrimary,
    inverseOnSurface = OmarchyDarkBg,
    outline = OmarchyBorder,
    outlineVariant = OmarchyBorderSubtle,
    error = OmarchyRed,
    errorContainer = OmarchyRedContainer
)

private val LightColorScheme = lightColorScheme(
    primary = OmarchyLightPrimary,
    onPrimary = OmarchyLightOnPrimary,
    primaryContainer = OmarchyLightPrimaryContainer,
    onPrimaryContainer = OmarchyLightOnPrimaryContainer,
    secondary = OmarchyLightSecondary,
    onSecondary = OmarchyLightOnSecondary,
    secondaryContainer = OmarchyLightSecondaryContainer,
    onSecondaryContainer = OmarchyLightOnSecondaryContainer,
    background = OmarchyLightBg,
    onBackground = OmarchyLightTextPrimary,
    surface = OmarchyLightSurface,
    onSurface = OmarchyLightTextPrimary,
    surfaceVariant = OmarchyLightSurfaceContainer,
    onSurfaceVariant = OmarchyLightTextSecondary,
    surfaceContainer = OmarchyLightSurfaceContainer,
    surfaceContainerHigh = OmarchyLightSurfaceContainerHigh,
    surfaceContainerHighest = OmarchyLightBorder,
    surfaceContainerLow = OmarchyLightBg,
    surfaceContainerLowest = OmarchyLightSurface,
    surfaceBright = OmarchyLightSurface,
    surfaceDim = OmarchyLightSurfaceContainerHigh,
    inverseSurface = OmarchyLightTextPrimary,
    inverseOnSurface = OmarchyLightBg,
    outline = OmarchyLightBorder,
    outlineVariant = OmarchyLightBorderSubtle,
    error = OmarchyRed
)

/** Monospace is only for IDs and numbers; everything else uses the system font. */
val JetBrainsMonoFontFamily: FontFamily = FontFamily.Monospace

private val OmarchyTypography = Typography()

/**
 * Folds any scheme into one ink on one paper: primary, secondary, tertiary and error all become
 * the scheme's own onSurface, so the UI stays monochrome while the paper and ink keep whatever
 * tint the system gives them (Material You wallpaper tones on Android 12+).
 */
private fun ColorScheme.monochrome(): ColorScheme {
    val ink = onSurface
    val paper = surface
    return copy(
        primary = ink,
        onPrimary = paper,
        primaryContainer = surfaceContainerHighest,
        onPrimaryContainer = ink,
        inversePrimary = paper,
        secondary = onSurfaceVariant,
        onSecondary = paper,
        secondaryContainer = surfaceContainerHigh,
        onSecondaryContainer = ink,
        tertiary = onSurfaceVariant,
        onTertiary = paper,
        tertiaryContainer = surfaceContainerHigh,
        onTertiaryContainer = ink,
        error = ink,
        onError = paper,
        errorContainer = surfaceContainerHighest,
        onErrorContainer = ink,
        surfaceTint = Color.Transparent,
    )
}

/**
 * Follows the phone: light or dark from the system setting, and on Android 12+ the neutral
 * Material You tones of the wallpaper. Older phones fall back to the grey Omarchy palette.
 */
@Composable
fun OmaSendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = base.monochrome(),
        typography = OmarchyTypography,
        content = content
    )
}
