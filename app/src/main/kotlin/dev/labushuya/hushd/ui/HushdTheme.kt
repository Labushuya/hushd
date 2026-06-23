package dev.labushuya.hushd.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandPrimary = Color(0xFFEF4444) // accent red
private val BrandSurface = Color(0xFF0F172A) // background ink
private val BrandOnSurface = Color(0xFFE2E8F0) // off-white

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    background = BrandSurface,
    surface = BrandSurface,
    onBackground = BrandOnSurface,
    onSurface = BrandOnSurface,
)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFF8FAFC),
    onBackground = BrandSurface,
    onSurface = BrandSurface,
)

@Composable
fun HushdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
