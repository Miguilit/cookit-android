package be.cookit.pos.android.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CookitOrange = Color(0xFFF47A24)
val CookitGreen = Color(0xFF1F8A57)
val CookitInk = Color(0xFF17201B)
val CookitCanvas = Color(0xFFF6F7F3)
val CookitSurface = Color(0xFFFFFFFF)
val CookitMuted = Color(0xFF69736D)
val CookitLine = Color(0xFFE5E8E4)
val CookitSoftOrange = Color(0xFFFFF1E7)
val CookitSoftGreen = Color(0xFFEAF7F0)

private val LightColors = lightColorScheme(
    primary = CookitOrange,
    onPrimary = Color.White,
    secondary = CookitGreen,
    onSecondary = Color.White,
    background = CookitCanvas,
    onBackground = CookitInk,
    surface = CookitSurface,
    onSurface = CookitInk,
    outline = CookitLine
)

@Composable
fun CookitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content
    )
}
