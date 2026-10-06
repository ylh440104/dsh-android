package com.deepseek.harness.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DeepSeekBlue = Color(0xFF4D6BFE)
private val DeepSeekBlueDark = Color(0xFF7B93FF)

private val LightScheme = lightColorScheme(
    primary = DeepSeekBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF5B6478),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    onSurface = Color(0xFF1B1D22),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF5B6478)
)

private val DarkScheme = darkColorScheme(
    primary = DeepSeekBlueDark,
    onPrimary = Color(0xFF10131A),
    secondary = Color(0xFFA8B1C4),
    background = Color(0xFF10131A),
    surface = Color(0xFF181C24),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = Color(0xFFA8B1C4)
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp)
)

@Composable
fun DshTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) DarkScheme else LightScheme
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}