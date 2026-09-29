package be.cookit.pos.android.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cookit brand colors stay available for semantic/brand moments.
val CookitOrange = Color(0xFFF47A24)
val CookitGreen = Color(0xFF39C77A)

// Android visual polish inspired by CookitPad: black canvas, anthracite surfaces,
// cyan interaction accent. This intentionally does not change navigation or flows.
val CookitAccent = Color(0xFF42C8F5)
val CookitInk = Color(0xFFF4F7FA)
val CookitCanvas = Color(0xFF090B0E)
val CookitSurface = Color(0xFF15191E)
val CookitSurfaceRaised = Color(0xFF1D2228)
val CookitSidebar = Color(0xFF0D1014)
val CookitMuted = Color(0xFF9EA7B0)
val CookitLine = Color(0xFF2A3037)
val CookitSoftOrange = Color(0xFF2D211A)
val CookitSoftGreen = Color(0xFF14271D)
val CookitSoftAccent = Color(0xFF102A34)

private val DarkColors = darkColorScheme(
    primary = CookitAccent,
    onPrimary = Color(0xFF00131B),
    primaryContainer = Color(0xFF103644),
    onPrimaryContainer = Color(0xFFD4F4FF),
    secondary = CookitOrange,
    onSecondary = Color.White,
    secondaryContainer = CookitSoftOrange,
    onSecondaryContainer = Color(0xFFFFD9C4),
    tertiary = CookitGreen,
    onTertiary = Color(0xFF041C10),
    tertiaryContainer = CookitSoftGreen,
    onTertiaryContainer = Color(0xFFB8F3D0),
    background = CookitCanvas,
    onBackground = CookitInk,
    surface = CookitSurface,
    onSurface = CookitInk,
    surfaceVariant = CookitSurfaceRaised,
    onSurfaceVariant = CookitMuted,
    outline = CookitLine,
    outlineVariant = Color(0xFF232930),
    error = Color(0xFFFF7373),
    onError = Color(0xFF2C0002),
    errorContainer = Color(0xFF3A171B),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun CookitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
