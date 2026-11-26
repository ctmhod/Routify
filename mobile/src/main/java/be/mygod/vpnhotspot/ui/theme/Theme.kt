package be.mygod.vpnhotspot.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared look for Routify and Tubify: near-black base, soft cards, one blue accent. */
val BrandBase = Color(0xFF07080D)
val BrandAccent = Color(0xFF6EA8FF)

private val BrandColors = darkColorScheme(
    primary = BrandAccent,
    onPrimary = Color(0xFF07080D),
    primaryContainer = Color(0xFF1B2B4A),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF9DB4D9),
    onSecondary = Color(0xFF07080D),
    secondaryContainer = Color(0xFF1B2230),
    onSecondaryContainer = Color(0xFFDCE6F8),
    tertiary = Color(0xFF7FD6C2),
    onTertiary = Color(0xFF07080D),
    background = BrandBase,
    onBackground = Color(0xFFE8EAF0),
    surface = BrandBase,
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1A1D27),
    onSurfaceVariant = Color(0xFF9AA1B2),
    surfaceContainerLowest = Color(0xFF05060A),
    surfaceContainerLow = Color(0xFF0E1016),
    surfaceContainer = Color(0xFF13151D),
    surfaceContainerHigh = Color(0xFF191C26),
    surfaceContainerHighest = Color(0xFF202431),
    outline = Color(0xFF4A5062),
    outlineVariant = Color(0xFF262A37),
    error = Color(0xFFFF8A80),
)

private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun VpnHotspotTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BrandColors,
        shapes = BrandShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@Composable
fun VpnHotspotPreviewSurface(content: @Composable () -> Unit) {
    VpnHotspotTheme {
        Surface(content = content)
    }
}
