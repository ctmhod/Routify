package be.mygod.vpnhotspot.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.SharedPreferences
import be.mygod.vpnhotspot.App.Companion.app
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

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E5FF),
    onPrimaryContainer = Color(0xFF0B2A5C),
    secondary = Color(0xFF4F6285),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6F8),
    onSecondaryContainer = Color(0xFF1B2230),
    tertiary = Color(0xFF1F8A75),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF12151C),
    surface = Color(0xFFF6F8FC),
    onSurface = Color(0xFF12151C),
    surfaceVariant = Color(0xFFE3E8F2),
    onSurfaceVariant = Color(0xFF50586A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3F9),
    surfaceContainer = Color(0xFFEAEEF6),
    surfaceContainerHigh = Color(0xFFE4E9F2),
    surfaceContainerHighest = Color(0xFFDDE3EE),
    outline = Color(0xFF7A8296),
    outlineVariant = Color(0xFFC8CEDB),
    error = Color(0xFFBA1A1A),
)

/** Theme choice: 0 = Auto (follow system), 1 = Dark, 2 = Light. */
object ThemeMode {
    const val KEY = "routify.theme"
    const val AUTO = 0
    const val DARK = 1
    const val LIGHT = 2
    var mode: Int
        get() = app.pref.getInt(KEY, AUTO)
        set(value) = app.pref.edit().putInt(KEY, value).apply()
}

@Composable
fun rememberThemeMode(): Int {
    var mode by remember { mutableIntStateOf(ThemeMode.mode) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == ThemeMode.KEY) mode = ThemeMode.mode }
        app.pref.registerOnSharedPreferenceChangeListener(l)
        mode = ThemeMode.mode
        onDispose { app.pref.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return mode
}

@Composable
fun isDarkTheme(): Boolean = when (rememberThemeMode()) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    else -> isSystemInDarkTheme()
}

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
        colorScheme = if (isDarkTheme()) BrandColors else LightColors,
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
