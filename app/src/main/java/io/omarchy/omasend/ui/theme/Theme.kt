package io.omarchy.omasend.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

private val DarkColorScheme = darkColorScheme(
    primary = OmarchyCyan,
    onPrimary = OmarchyDarkBg,
    primaryContainer = OmarchyCyanDark,
    onPrimaryContainer = OmarchyCyanLight,
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
    outline = OmarchyLightBorder,
    outlineVariant = OmarchyLightBorderSubtle,
    error = OmarchyRed
)

val JetBrainsMonoFontFamily: FontFamily = FontFamily.Monospace

private val DefaultTypography = Typography()
private val OmarchyTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(fontFamily = JetBrainsMonoFontFamily),
    displayMedium = DefaultTypography.displayMedium.copy(fontFamily = JetBrainsMonoFontFamily),
    displaySmall = DefaultTypography.displaySmall.copy(fontFamily = JetBrainsMonoFontFamily),
    headlineLarge = DefaultTypography.headlineLarge.copy(fontFamily = JetBrainsMonoFontFamily),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontFamily = JetBrainsMonoFontFamily),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontFamily = JetBrainsMonoFontFamily),
    titleLarge = DefaultTypography.titleLarge.copy(fontFamily = JetBrainsMonoFontFamily),
    titleMedium = DefaultTypography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
    titleSmall = DefaultTypography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
    bodyLarge = DefaultTypography.bodyLarge.copy(fontFamily = JetBrainsMonoFontFamily),
    bodyMedium = DefaultTypography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
    bodySmall = DefaultTypography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
    labelLarge = DefaultTypography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
    labelMedium = DefaultTypography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
    labelSmall = DefaultTypography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
)

@Composable
fun OmaSendTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OmarchyTypography,
        content = content
    )
}
