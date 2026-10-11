package dev.knap43.reader.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Amber = Color(0xFFE1A34F)
val Ink = Color(0xFF12100E)

private val DarkAmber = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1800),
    primaryContainer = Color(0xFF4A3212),
    onPrimaryContainer = Color(0xFFFFDDB0),
    inversePrimary = Color(0xFF7D5520),
    secondary = Color(0xFFD6C2A6),
    onSecondary = Color(0xFF3A2E1C),
    secondaryContainer = Color(0xFF3A3024),
    onSecondaryContainer = Color(0xFFF2DFC2),
    tertiary = Color(0xFFB8C99F),
    onTertiary = Color(0xFF243418),
    tertiaryContainer = Color(0xFF3A4A2D),
    onTertiaryContainer = Color(0xFFD4E5BA),
    background = Ink,
    onBackground = Color(0xFFEAE1D7),
    surface = Ink,
    onSurface = Color(0xFFEAE1D7),
    surfaceVariant = Color(0xFF2B2620),
    onSurfaceVariant = Color(0xFFCFC4B6),
    surfaceTint = Amber,
    inverseSurface = Color(0xFFEAE1D7),
    inverseOnSurface = Color(0xFF33302B),
    surfaceBright = Color(0xFF3A3530),
    surfaceDim = Ink,
    surfaceContainerLowest = Color(0xFF0D0B09),
    surfaceContainerLow = Color(0xFF1A1714),
    surfaceContainer = Color(0xFF1F1B17),
    surfaceContainerHigh = Color(0xFF29251F),
    surfaceContainerHighest = Color(0xFF34302A),
    outline = Color(0xFF988F82),
    outlineVariant = Color(0xFF4C463E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/** The amber scheme, or the wallpaper's (always dark) when "Use system colors" is on. */
@Composable
private fun colorScheme(amber: ColorScheme): ColorScheme {
    val context = LocalContext.current
    val systemColors by Appearance.systemColors(context).collectAsState()
    return if (systemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else amber
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme(DarkAmber), typography = Typography(), content = content)
}
