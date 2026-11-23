package dev.vis.booru.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import kotlin.math.max
import kotlin.math.min

/**
 * Full Material 3 colour schemes from one seed colour (HCT tonal palettes, 2025 colour spec).
 * The primary chroma is pinned so accents stay vivid instead of drifting to pastel.
 */
object SchemeGen {
    private val cache = HashMap<Pair<Int, Boolean>, ColorScheme>()

    fun scheme(seed: Int, dark: Boolean): ColorScheme = synchronized(cache) {
        cache.getOrPut(seed to dark) { build(seed, dark) }
    }

    private fun build(seed: Int, dark: Boolean): ColorScheme {
        val hct = Hct.fromInt(seed)
        val v = ColorSpec.SpecVersion.SPEC_2025
        val base = SchemeTonalSpot(hct, dark, 0.0, v)
        val s = DynamicScheme(
            sourceColorHct = hct,
            variant = base.variant,
            isDark = dark,
            contrastLevel = 0.0,
            primaryPalette = TonalPalette.fromHueAndChroma(hct.hue, max(48.0, min(hct.chroma, 64.0))),
            secondaryPalette = base.secondaryPalette,
            tertiaryPalette = base.tertiaryPalette,
            neutralPalette = base.neutralPalette,
            neutralVariantPalette = base.neutralVariantPalette,
            platform = DynamicScheme.Platform.PHONE,
            specVersion = v,
            errorPalette = base.errorPalette,
        )
        return s.toColorScheme()
    }

    private fun DynamicScheme.toColorScheme(): ColorScheme = ColorScheme(
        primary = Color(primary), onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary),
        secondary = Color(secondary), onSecondary = Color(onSecondary),
        secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary), onTertiary = Color(onTertiary),
        tertiaryContainer = Color(tertiaryContainer), onTertiaryContainer = Color(onTertiaryContainer),
        background = Color(background), onBackground = Color(onBackground),
        surface = Color(surface), onSurface = Color(onSurface),
        surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(surfaceTint),
        inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface),
        error = Color(error), onError = Color(onError),
        errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer),
        outline = Color(outline), outlineVariant = Color(outlineVariant),
        scrim = Color(scrim),
        surfaceBright = Color(surfaceBright), surfaceDim = Color(surfaceDim),
        surfaceContainer = Color(surfaceContainer), surfaceContainerHigh = Color(surfaceContainerHigh),
        surfaceContainerHighest = Color(surfaceContainerHighest), surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainerLowest = Color(surfaceContainerLowest),
        primaryFixed = Color(primaryFixed), primaryFixedDim = Color(primaryFixedDim),
        onPrimaryFixed = Color(onPrimaryFixed), onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
        secondaryFixed = Color(secondaryFixed), secondaryFixedDim = Color(secondaryFixedDim),
        onSecondaryFixed = Color(onSecondaryFixed), onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
        tertiaryFixed = Color(tertiaryFixed), tertiaryFixedDim = Color(tertiaryFixedDim),
        onTertiaryFixed = Color(onTertiaryFixed), onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
    )
}
