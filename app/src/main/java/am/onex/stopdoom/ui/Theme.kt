package am.onex.stopdoom.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF3F9E58)
private val GreenBright = Color(0xFF7BD88F)
private val Ink = Color(0xFF1B1C22)

private val DarkScheme = darkColorScheme(
    primary = GreenBright,
    onPrimary = Color(0xFF11121A),
    secondary = Color(0xFF9AA0AC),
    background = Ink,
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF23252D),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF2B2D36),
    onSurfaceVariant = Color(0xFFB9BEC8),
    error = Color(0xFFE5806E),
)

private val LightScheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Color(0xFF5C6270),
    background = Color(0xFFF7F8FA),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEBEDF1),
    onSurfaceVariant = Color(0xFF4A505C),
    error = Color(0xFFB3261E),
)

@Composable
fun DoomGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
