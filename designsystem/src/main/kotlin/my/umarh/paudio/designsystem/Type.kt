package my.umarh.paudio.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Bundled Roboto Flex variable font (res/font/roboto_flex.ttf, OFL-1.1; no downloadable fonts, no GMS).
 * One font file, several weights realised through the `wght` axis (variable fonts work on API 26+).
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val RobotoFlex: FontFamily = FontFamily(
    listOf(
        FontWeight.Light,
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
        FontWeight.ExtraBold,
    ).map { weight ->
        Font(
            resId = R.font.roboto_flex,
            weight = weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.width(100f),
            ),
        )
    },
)

/** Material 3 type scale with Roboto Flex everywhere; display/headline are heavier for the expressive look. */
internal fun paudioTypography(): Typography {
    val base = Typography()
    fun TextStyle.flex(weight: FontWeight? = null) =
        copy(fontFamily = RobotoFlex, fontWeight = weight ?: fontWeight)
    return Typography(
        displayLarge = base.displayLarge.flex(FontWeight.SemiBold),
        displayMedium = base.displayMedium.flex(FontWeight.SemiBold),
        displaySmall = base.displaySmall.flex(FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.flex(FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.flex(FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.flex(FontWeight.Medium),
        titleLarge = base.titleLarge.flex(FontWeight.Medium),
        titleMedium = base.titleMedium.flex(FontWeight.Medium),
        titleSmall = base.titleSmall.flex(FontWeight.Medium),
        bodyLarge = base.bodyLarge.flex(),
        bodyMedium = base.bodyMedium.flex(),
        bodySmall = base.bodySmall.flex(),
        labelLarge = base.labelLarge.flex(FontWeight.Medium),
        labelMedium = base.labelMedium.flex(FontWeight.Medium),
        labelSmall = base.labelSmall.flex(FontWeight.Medium),
    )
}
