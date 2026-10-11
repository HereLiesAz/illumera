package com.hereliesaz.illumera.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hereliesaz.illumera.data.model.ThemeEntity
// 1. Alias the Mobile Theme (For Settings, Dialogs)
import androidx.compose.material3.MaterialTheme as MobileMaterialTheme
import androidx.compose.material3.darkColorScheme as mobileDarkColorScheme
// 2. Alias the TV Theme (For Cards, Home Screen)
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

// CompositionLocal for poster corner style (true = round, false = sharp)
val LocalRoundCorners = staticCompositionLocalOf { true }

// CompositionLocal for hub corner style (true = round, false = sharp)
val LocalHubRoundCorners = staticCompositionLocalOf { true }

// Animation duration for theme transitions
private const val THEME_ANIMATION_DURATION = 400

@Composable
fun LumeraTheme(
    theme: ThemeEntity = DefaultThemes.VOID,
    content: @Composable () -> Unit
) {
    // Animate all colors for smooth theme transitions
    val primaryColor by animateColorAsState(
        targetValue = Color(theme.primaryColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val backgroundColor by animateColorAsState(
        targetValue = Color(theme.backgroundColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val surfaceColor by animateColorAsState(
        targetValue = Color(theme.surfaceColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val textColor by animateColorAsState(
        targetValue = Color(theme.textColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )
    val errorColor by animateColorAsState(
        targetValue = Color(theme.errorColor.toInt()),
        animationSpec = tween(THEME_ANIMATION_DURATION)
    )

    // Readable content colour on primary/error fills: black on light, white on dark.
    val onPrimaryColor = contentColorFor(primaryColor)
    val onErrorColor = contentColorFor(errorColor)

    // A. Configure Mobile Colors
    val mobileScheme = mobileDarkColorScheme(
        primary = primaryColor,
        secondary = primaryColor,
        background = backgroundColor,
        surface = surfaceColor,
        onPrimary = onPrimaryColor,
        onBackground = textColor,
        onSurface = textColor,
        error = errorColor,
        onError = onErrorColor
    )

    // B. Configure TV Colors (Crucial for LumeraCard Glow)
    val tvScheme = tvDarkColorScheme(
        primary = primaryColor,
        secondary = primaryColor,
        background = backgroundColor,
        surface = surfaceColor,
        onPrimary = onPrimaryColor,
        onSurface = textColor,
        error = errorColor,
        onError = onErrorColor
    )

    // androidx.tv.material3.Typography is a distinct class from
    // androidx.compose.material3.Typography, so LumeraTypography can't be passed to
    // TvMaterialTheme directly — mirror the styles it actually defines onto a TV
    // Typography, otherwise every TV-styled screen (the majority of the app) silently
    // falls back to the tv-material3 default type scale instead of the app's own.
    val tvTypography = androidx.tv.material3.Typography().copy(
        titleLarge = LumeraTypography.titleLarge,
        titleMedium = LumeraTypography.titleMedium,
        bodyLarge = LumeraTypography.bodyLarge,
        labelSmall = LumeraTypography.labelSmall
    )

    MobileMaterialTheme(
        colorScheme = mobileScheme,
        typography = LumeraTypography
    ) {
        TvMaterialTheme(
            colorScheme = tvScheme,
            typography = tvTypography,
            content = content
        )
    }
}

/** Black on light backgrounds, white on dark ones. */
private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White
