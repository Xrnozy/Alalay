package com.alalay.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Alalay palette — derived from product identity, not trend: a warm amber
 * ("alalay" = assistance) on near-black for low-vision contrast (R-21:
 * dark is the deliberate default for an eyes-free tool, R-25: every
 * text/background pair below is >= WCAG AA on the surfaces used).
 */
private val AlalayDark = darkColorScheme(
    primary = Color(0xFFFFC857),       // warm amber — the one accent
    onPrimary = Color(0xFF1A1205),
    secondary = Color(0xFF9EB8C7),     // muted slate for secondary info
    onSecondary = Color(0xFF0D1B22),
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFF2F2EF),
    surface = Color(0xFF17191D),
    onSurface = Color(0xFFF2F2EF),
    surfaceVariant = Color(0xFF23262C),
    onSurfaceVariant = Color(0xFFC9CDD3),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF1A0505),
)

private val AlalayLight = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF39586B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFAF7),
    onBackground = Color(0xFF191B1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191B1E),
    surfaceVariant = Color(0xFFEDE9E0),
    onSurfaceVariant = Color(0xFF4A4D52),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun AlalayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AlalayDark else AlalayLight,
        content = content,
    )
}
