package my.umarh.paudio.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

/** Brand seed for the static fallback scheme (API < 31 without a profile seed). */
internal val BrandSeedColor = Color(0xFF3F6FB5)

/**
 * PAudio theme.
 *  - [seedColor] != null: scheme generated from the profile seed colour via MaterialKolor.
 *  - else on API 31+: wallpaper-based dynamic colour.
 *  - else: static scheme generated from the brand seed.
 */
@Composable
fun PAudioTheme(
    seedColor: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        seedColor != null -> rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
            isAmoled = false,
            style = PaletteStyle.Expressive,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> rememberDynamicColorScheme(
            seedColor = BrandSeedColor,
            isDark = darkTheme,
            isAmoled = false,
            style = PaletteStyle.Expressive,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    }
    // Material 3 Expressive: springy motion scheme comes with MaterialExpressiveTheme (material3 1.5.0).
    // Experimental-API usage must stay inside :designsystem (SPEC §2.2 trap 19).
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = paudioTypography(),
        content = content,
    )
}
