package com.reprivacyspace.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

private val DarkColorPalette = darkColors(
    primary = MdPrimaryDark,
    primaryVariant = MdPrimaryContainerDark,
    secondary = MdSecondaryDark,
    secondaryVariant = MdSecondaryContainerDark,
    background = MdBackgroundDark,
    surface = MdSurfaceDark,
    error = MdErrorDark,
    onPrimary = MdOnPrimaryDark,
    onSecondary = MdOnSecondaryDark,
    onBackground = MdOnSurfaceDark,
    onSurface = MdOnSurfaceDark,
    onError = MdOnErrorDark,
)

private val LightColorPalette = lightColors(
    primary = MdPrimary,
    primaryVariant = MdPrimaryContainer,
    secondary = MdSecondary,
    secondaryVariant = MdSecondaryContainer,
    background = MdBackground,
    surface = MdSurface,
    error = MdError,
    onPrimary = MdOnPrimary,
    onSecondary = MdOnSecondary,
    onBackground = MdOnSurface,
    onSurface = MdOnSurface,
    onError = MdOnError,
)

@Composable
fun PrivacySpaceTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColorPalette else LightColorPalette

    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
