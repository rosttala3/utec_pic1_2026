package uy.edu.utec.pic1.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Metal = Color(0xFF8A939C)
val SteelDark = Color(0xFF4A525A)
val Rust = Color(0xFFB4501C)
val RustLight = Color(0xFFE7A36F)
val Celeste = Color(0xFF00AEEF)
val StatusOk = Color(0xFF2E7D4F)
val StatusWarn = Color(0xFFC07A00)
val StatusErr = Color(0xFFB3261E)

private val Light = lightColorScheme(
    primary = Rust, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC8), onPrimaryContainer = Color(0xFF3A1200),
    secondary = SteelDark, onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE2E6), onSecondaryContainer = Color(0xFF1B2024),
    tertiary = Color(0xFF00709E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC6E9FA), onTertiaryContainer = Color(0xFF001E2E),
    background = Color(0xFFF4F5F6), surface = Color(0xFFF4F5F6),
    surfaceVariant = Color(0xFFE3E6E9), onSurfaceVariant = Color(0xFF43494E),
    outline = Metal, error = StatusErr,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEE8F57), onPrimary = Color(0xFF4B1D00),
    primaryContainer = Color(0xFF7A3510), onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFC4CCD3), onSecondary = Color(0xFF2A3036),
    secondaryContainer = Color(0xFF3B4249), onSecondaryContainer = Color(0xFFDDE2E6),
    tertiary = Color(0xFF5CCBFF), onTertiary = Color(0xFF00344B),
    tertiaryContainer = Color(0xFF004C6C), onTertiaryContainer = Color(0xFFC6E9FA),
    background = Color(0xFF15181B), surface = Color(0xFF15181B),
    surfaceVariant = Color(0xFF33393F), onSurfaceVariant = Color(0xFFC1C7CD),
    outline = Color(0xFF8A939C), error = Color(0xFFFFB4AB),
)

@Composable
fun UtecorroeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
