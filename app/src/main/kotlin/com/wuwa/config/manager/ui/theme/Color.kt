package com.wuwa.config.manager.ui.theme

import androidx.compose.ui.graphics.Color

// Light: Changli maple white
val LightBackground = Color(0xFFFFF7F0)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainer = Color(0xFFFBF0E7)
val LightSurfaceContainerHigh = Color(0xFFF7E7DC)
val LightPrimary = Color(0xFFC64A32)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFF6D8CE)
val LightOnPrimaryContainer = Color(0xFF451108)
val LightSecondary = Color(0xFF7F5D48)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFF3E0D2)
val LightOnSecondaryContainer = Color(0xFF321E13)
val LightOnSurface = Color(0xFF2D241F)
val LightOnSurfaceVariant = Color(0xFF74655C)
val LightOutline = Color(0xFF9A7F70)
val LightOutlineVariant = Color(0xFFE3C9BA)
val LightError = Color(0xFFC64A4A)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightSuccess = Color(0xFF3E8E68)
val LightWarning = Color(0xFFA9680A)
val LightSuccessContainer = Color(0xFFD0EBDD)
val LightOnSuccessContainer = Color(0xFF0B3524)

// Dark: Maple Night
val DarkBackground = Color(0xFF17110E)
val DarkSurface = Color(0xFF211815)
val DarkSurfaceContainer = Color(0xFF2A1F1A)
val DarkSurfaceContainerHigh = Color(0xFF33231D)
val DarkPrimary = Color(0xFFF08A70)
val DarkOnPrimary = Color(0xFF48150C)
val DarkPrimaryContainer = Color(0xFF5B241A)
val DarkOnPrimaryContainer = Color(0xFFFFDAD0)
val DarkSecondary = Color(0xFFD0A57C)
val DarkOnSecondary = Color(0xFF402A1A)
val DarkSecondaryContainer = Color(0xFF523B2B)
val DarkOnSecondaryContainer = Color(0xFFF5DCC2)
val DarkOnSurface = Color(0xFFF5E9E2)
val DarkOnSurfaceVariant = Color(0xFFD3BEB3)
val DarkOutline = Color(0xFFA58C7E)
val DarkOutlineVariant = Color(0xFF4A342B)
val DarkError = Color(0xFFFFB4AB)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkSuccess = Color(0xFF78B99A)
val DarkWarning = Color(0xFFE3AD63)
val DarkSuccessContainer = Color(0xFF184F39)
val DarkOnSuccessContainer = Color(0xFFC4F0D8)

data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
)

val LightExtendedColors = ExtendedColors(
    success = LightSuccess,
    onSuccess = LightOnPrimary,
    successContainer = LightSuccessContainer,
    onSuccessContainer = LightOnSuccessContainer,
    warning = LightWarning,
)

val DarkExtendedColors = ExtendedColors(
    success = DarkSuccess,
    onSuccess = DarkOnPrimary,
    successContainer = DarkSuccessContainer,
    onSuccessContainer = DarkOnSuccessContainer,
    warning = DarkWarning,
)
