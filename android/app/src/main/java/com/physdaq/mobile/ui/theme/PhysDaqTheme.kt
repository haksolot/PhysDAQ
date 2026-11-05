package com.physdaq.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Same palette as the desktop app: Catppuccin Mocha, with the desktop's
 * emerald for the primary action. The app is dark only, like the desktop app.
 */
object PhysDaqColors {
    val Crust = Color(0xFF11111B)
    val Mantle = Color(0xFF181825)
    val Base = Color(0xFF1E1E2E)
    val Surface0 = Color(0xFF313244)
    val Surface1 = Color(0xFF45475A)
    val Overlay = Color(0xFF6C7086)
    val Subtext = Color(0xFFA6ADC8)
    val Text = Color(0xFFCDD6F4)

    val Emerald = Color(0xFF35966A)      // primary button, as on the desktop
    val Green = Color(0xFFA6E3A1)        // connected and worn
    val Amber = Color(0xFFFAB387)        // connected but not worn
    val Mauve = Color(0xFFCBA6F7)        // connecting
    val Pink = Color(0xFFF5C2E7)         // heart / logo accent
    val Red = Color(0xFFF38BA8)          // stop, errors
}

val Mono: FontFamily = FontFamily.Monospace

private val Scheme = darkColorScheme(
    primary = PhysDaqColors.Emerald,
    onPrimary = Color.White,
    secondary = PhysDaqColors.Mauve,
    onSecondary = PhysDaqColors.Crust,
    tertiary = PhysDaqColors.Pink,
    background = PhysDaqColors.Mantle,
    onBackground = PhysDaqColors.Text,
    surface = PhysDaqColors.Base,
    onSurface = PhysDaqColors.Text,
    surfaceVariant = PhysDaqColors.Surface0,
    onSurfaceVariant = PhysDaqColors.Subtext,
    outline = PhysDaqColors.Surface1,
    outlineVariant = PhysDaqColors.Surface0,
    error = PhysDaqColors.Red,
    onError = PhysDaqColors.Crust,
)

private val Type = Typography(
    displayMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 52.sp, lineHeight = 56.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    // Small uppercase section labels ("SESSION RECORDING") and the tagline.
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.2.sp),
)

@Composable
fun PhysDaqTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Dark background, so the status bar icons must be light.
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
