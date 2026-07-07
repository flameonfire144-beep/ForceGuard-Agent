package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = PrimarySleek,
    secondary = SecondarySleek,
    tertiary = TertiarySleek,
    background = BackgroundSleekDark,
    surface = SurfaceSleekDark,
    onPrimary = OnPrimarySleek,
    onSecondary = OnSurfaceSleekDark,
    onBackground = OnBackgroundSleekDark,
    onSurface = OnSurfaceSleekDark,
    primaryContainer = CardBackgroundSleekDark,
    onPrimaryContainer = OnBackgroundSleekDark,
    surfaceVariant = Color(0xFF25232A),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = CardBorderSleekDark
  )

private val LightColorScheme =
  lightColorScheme(
    primary = PrimarySleek,
    secondary = SecondarySleek,
    tertiary = TertiarySleek,
    background = BackgroundSleek,
    surface = SurfaceSleek,
    onPrimary = OnPrimarySleek,
    onSecondary = OnSurfaceSleek,
    onBackground = OnBackgroundSleek,
    onSurface = OnSurfaceSleek,
    outline = OutlineSleek,
    primaryContainer = CardBackgroundSleek,
    onPrimaryContainer = OnCardSleek,
    surfaceVariant = Color(0xFFF3EDF7),
    onSurfaceVariant = Color(0xFF49454F)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disabling dynamic colors to fully enforce Sleek Interface branding guidelines
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
