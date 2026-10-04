package com.wholphinplus.sources.cinema

import androidx.compose.ui.graphics.Color

/**
 * Cinema mode's app-wide colours, used in place of Wholphin's theme while Cinema mode is on:
 * black stage, grey surfaces, white focus with black text. Settings, dialogs and menus follow it.
 */
object CinemaColors {
    /** The one accent: progress bars, switches, labels. */
    val accent = Label

    private val Surface = Color(0xFF161616)
    private val Raised = Color(0xFF242424)
    private val Line = Color(0xFF4A4A4A)

    val tv =
        androidx.tv.material3.darkColorScheme(
            primary = Ink,
            onPrimary = Stage,
            primaryContainer = Raised,
            onPrimaryContainer = Ink,
            inversePrimary = Stage,
            secondary = InkDim,
            onSecondary = Stage,
            secondaryContainer = Raised,
            onSecondaryContainer = Ink,
            tertiary = Label,
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFF3A1519),
            onTertiaryContainer = Ink,
            background = Stage,
            onBackground = Ink,
            surface = Surface,
            onSurface = Ink,
            surfaceVariant = Raised,
            onSurfaceVariant = InkDim,
            surfaceTint = Ink,
            inverseSurface = Ink,
            inverseOnSurface = Stage,
            error = Color(0xFFFF8A80),
            onError = Stage,
            errorContainer = Color(0xFF5C1A1A),
            onErrorContainer = Ink,
            border = Line,
            borderVariant = Raised,
            scrim = Color.Black,
        )

    val material =
        androidx.compose.material3.darkColorScheme(
            primary = Ink,
            onPrimary = Stage,
            primaryContainer = Raised,
            onPrimaryContainer = Ink,
            inversePrimary = Stage,
            secondary = InkDim,
            onSecondary = Stage,
            secondaryContainer = Raised,
            onSecondaryContainer = Ink,
            tertiary = Label,
            onTertiary = Color.White,
            background = Stage,
            onBackground = Ink,
            surface = Surface,
            onSurface = Ink,
            surfaceVariant = Raised,
            onSurfaceVariant = InkDim,
            surfaceTint = Ink,
            inverseSurface = Ink,
            inverseOnSurface = Stage,
            error = Color(0xFFFF8A80),
            onError = Stage,
            outline = Line,
            outlineVariant = Raised,
            scrim = Color.Black,
            surfaceContainerLowest = Stage,
            surfaceContainerLow = Color(0xFF121212),
            surfaceContainer = Surface,
            surfaceContainerHigh = Raised,
            surfaceContainerHighest = Color(0xFF2E2E2E),
        )
}
