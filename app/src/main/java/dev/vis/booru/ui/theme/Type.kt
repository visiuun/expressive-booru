package dev.vis.booru.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.vis.booru.R

/**
 * Typography on the variable Google Sans Flex, matching Expressive Weather's default look:
 * wide ("chunky") and rounded, with an optical size matched to each style's font size.
 */
object AppType {
    private val weights = (100..900 step 100).toList()
    private val families = HashMap<Int, FontFamily>()

    private fun family(opsz: Int, wdth: Int): FontFamily = synchronized(families) {
        families.getOrPut(opsz * 1000 + wdth) {
            FontFamily(
                weights.map { w ->
                    Font(
                        R.font.google_sans_flex,
                        weight = FontWeight(w),
                        variationSettings = FontVariation.Settings(
                            // One weight step heavier, like Weather's chunky style.
                            FontVariation.Setting("wght", (w + 100).coerceAtMost(1000).toFloat()),
                            FontVariation.Setting("wdth", wdth.toFloat()),
                            FontVariation.Setting("ROND", 100f),
                            FontVariation.Setting("opsz", opsz.toFloat()),
                        ),
                    )
                },
            )
        }
    }

    val typography: Typography by lazy {
        Typography().map { s ->
            val size = s.fontSize.value
            val display = size >= 36f
            s.copy(
                fontFamily = family(opsz = if (size >= 50f) 120 else size.toInt().coerceIn(6, 144), wdth = if (display) 118 else 125),
                letterSpacing = if (display) s.letterSpacing.tighten() else s.letterSpacing,
            )
        }
    }

    private fun TextUnit.tighten(): TextUnit = if (isSpecified) (value - 0.5f).sp else this

    private inline fun Typography.map(f: (TextStyle) -> TextStyle) = Typography(
        displayLarge = f(displayLarge), displayMedium = f(displayMedium), displaySmall = f(displaySmall),
        headlineLarge = f(headlineLarge), headlineMedium = f(headlineMedium), headlineSmall = f(headlineSmall),
        titleLarge = f(titleLarge), titleMedium = f(titleMedium), titleSmall = f(titleSmall),
        bodyLarge = f(bodyLarge), bodyMedium = f(bodyMedium), bodySmall = f(bodySmall),
        labelLarge = f(labelLarge), labelMedium = f(labelMedium), labelSmall = f(labelSmall),
        displayLargeEmphasized = f(displayLargeEmphasized), displayMediumEmphasized = f(displayMediumEmphasized),
        displaySmallEmphasized = f(displaySmallEmphasized),
        headlineLargeEmphasized = f(headlineLargeEmphasized), headlineMediumEmphasized = f(headlineMediumEmphasized),
        headlineSmallEmphasized = f(headlineSmallEmphasized),
        titleLargeEmphasized = f(titleLargeEmphasized), titleMediumEmphasized = f(titleMediumEmphasized),
        titleSmallEmphasized = f(titleSmallEmphasized),
        bodyLargeEmphasized = f(bodyLargeEmphasized), bodyMediumEmphasized = f(bodyMediumEmphasized),
        bodySmallEmphasized = f(bodySmallEmphasized),
        labelLargeEmphasized = f(labelLargeEmphasized), labelMediumEmphasized = f(labelMediumEmphasized),
        labelSmallEmphasized = f(labelSmallEmphasized),
    )
}
