package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VoxShiftDarkColorScheme =
  darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianBase,
    secondary = SoftViolet,
    onSecondary = ObsidianBase,
    tertiary = EmeraldLive,
    onTertiary = ObsidianBase,
    error = CrimsonHangup,
    onError = TextPrimary,
    background = ObsidianBase,
    onBackground = TextPrimary,
    surface = SlateElevated,
    onSurface = TextPrimary,
    surfaceVariant = SlateCard,
    onSurfaceVariant = TextSecondary,
    outline = HairlineOutline,
  )

@Composable
fun VoxShiftTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = VoxShiftDarkColorScheme,
    typography = Typography,
    content = content,
  )
}

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  VoxShiftTheme(content = content)
}
