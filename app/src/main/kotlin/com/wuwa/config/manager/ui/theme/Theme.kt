package com.wuwa.config.manager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightSuccess,
    onTertiary = LightOnPrimary,
    tertiaryContainer = LightSuccessContainer,
    onTertiaryContainer = LightOnSuccessContainer,
    surface = LightSurface,
    surfaceVariant = LightSurfaceContainerHigh,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = Color.White,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    surfaceContainerLow = LightSurfaceContainer,
    surfaceContainerLowest = LightBackground,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkSuccess,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = DarkSuccessContainer,
    onTertiaryContainer = DarkOnSuccessContainer,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceContainerHigh,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = Color(0xFF3B0000),
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    surfaceContainerLow = DarkSurfaceContainer,
    surfaceContainerLowest = DarkBackground,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

object WuWaShapes {
    val CardLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val CardMedium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val Button = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val Dialog = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
}

@Composable
fun WuWaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WuWaTypography,
            shapes = Shapes(
                small = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            ),
            content = content,
        )
    }
}
