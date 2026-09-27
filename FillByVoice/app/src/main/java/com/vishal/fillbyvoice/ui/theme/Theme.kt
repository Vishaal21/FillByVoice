package com.vishal.fillbyvoice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NavyLight,
    onPrimary = NavyDeep,
    primaryContainer = NavyContainerDark,
    onPrimaryContainer = NavyContainer,
    secondary = SaffronLight,
    onSecondary = SaffronDeep,
    secondaryContainer = SaffronContainerDark,
    onSecondaryContainer = SaffronContainer,
    tertiary = GreenLight,
    onTertiary = GreenDeep,
    tertiaryContainer = GreenContainerDark,
    onTertiaryContainer = GreenContainer,
    background = Night,
    onBackground = Snow,
    surface = Night,
    onSurface = Snow,
    onSurfaceVariant = SnowSoft,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = NightLow,
    surfaceContainer = NightMid,
    surfaceContainerHigh = NightHigh,
    surfaceContainerHighest = NightHighest,
)

private val LightColorScheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = NavyContainer,
    onPrimaryContainer = NavyDeep,
    secondary = Saffron,
    onSecondary = Color.White,
    secondaryContainer = SaffronContainer,
    onSecondaryContainer = SaffronDeep,
    tertiary = Green,
    onTertiary = Color.White,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = GreenDeep,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperMid,
    surfaceContainerHigh = PaperHigh,
    surfaceContainerHighest = PaperHighest,
)

// Our own colours, not the wallpaper's (dynamic colour made the demo phone purple).
@Composable
fun FillByVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
