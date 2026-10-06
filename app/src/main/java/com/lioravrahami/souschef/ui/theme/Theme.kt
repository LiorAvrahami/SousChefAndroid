package com.lioravrahami.souschef.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4532A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF5F6B3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2EFB3),
    onSecondaryContainer = Color(0xFF1B2000),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF2C1A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F3),
    onBackground = Color(0xFF221A15),
    surface = Color(0xFFFFF8F3),
    onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF4DED5),
    onSurfaceVariant = Color(0xFF53433D),
    outline = Color(0xFF85736C),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59B),
    onPrimary = Color(0xFF5E1A00),
    primaryContainer = Color(0xFF862F0E),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFC6D399),
    onSecondary = Color(0xFF30380E),
    secondaryContainer = Color(0xFF474F24),
    onSecondaryContainer = Color(0xFFE2EFB3),
    tertiary = Color(0xFFF8BD4D),
    onTertiary = Color(0xFF492D00),
    tertiaryContainer = Color(0xFF684200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A110D),
    onBackground = Color(0xFFF0DFD8),
    surface = Color(0xFF1A110D),
    onSurface = Color(0xFFF0DFD8),
    surfaceVariant = Color(0xFF53433D),
    onSurfaceVariant = Color(0xFFD8C2B9),
    outline = Color(0xFFA08D85),
)

@Composable
fun SousChefTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SousChefTypography,
        content = content,
    )
}
