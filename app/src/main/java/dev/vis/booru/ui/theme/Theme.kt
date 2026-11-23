package dev.vis.booru.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.vis.booru.data.Accent
import dev.vis.booru.data.AppSettings
import dev.vis.booru.data.ThemeMode

/** True-black variant for OLED panels. */
private fun ColorScheme.toOled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    surfaceVariant = Color(0xFF1E1E1E),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF2A2A2A),
)

@Composable
fun BooruTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val base = when {
        settings.accent == Accent.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> SchemeGen.scheme(settings.accent.seed.toInt(), dark)
    }
    val scheme = if (dark && settings.oled) base.toOled() else base

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                val c = WindowCompat.getInsetsController(w, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = AppType.typography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Tag colors, loosely following the colors booru sites use per category. */
fun tagColor(category: dev.vis.booru.data.TagCategory?, dark: Boolean): Color = when (category) {
    dev.vis.booru.data.TagCategory.ARTIST -> if (dark) Color(0xFFFF8A80) else Color(0xFFC62828)
    dev.vis.booru.data.TagCategory.COPYRIGHT -> if (dark) Color(0xFFE1A6FF) else Color(0xFF8E24AA)
    dev.vis.booru.data.TagCategory.CHARACTER -> if (dark) Color(0xFF8BE39A) else Color(0xFF2E7D32)
    dev.vis.booru.data.TagCategory.SPECIES -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
    dev.vis.booru.data.TagCategory.META -> if (dark) Color(0xFFFFE082) else Color(0xFF9E7B00)
    else -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
}

fun ratingColor(r: dev.vis.booru.data.Rating): Color = when (r) {
    dev.vis.booru.data.Rating.GENERAL -> Color(0xFF2E9E5B)
    dev.vis.booru.data.Rating.SENSITIVE -> Color(0xFF3F7FD9)
    dev.vis.booru.data.Rating.QUESTIONABLE -> Color(0xFFE09A1B)
    dev.vis.booru.data.Rating.EXPLICIT -> Color(0xFFD83A4A)
    dev.vis.booru.data.Rating.UNKNOWN -> Color(0xFF7A7A7A)
}

/** Whether the active scheme is dark (follows the in-app theme choice, not just the system). */
@Composable
fun isDarkScheme(): Boolean = androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f
