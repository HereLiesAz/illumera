package com.hereliesaz.illumera.ui.theme

import com.hereliesaz.illumera.data.model.ThemeEntity

/**
 * Built-in theme presets for Lumera TV.
 * These themes are marked as isBuiltIn = true and cannot be deleted.
 */
object DefaultThemes {

    val VOID = ThemeEntity(
        id = "void",
        name = "Void",
        primaryColor = 0xFFFFFFFF,      // White
        backgroundColor = 0xFF000000,   // Pure Black
        surfaceColor = 0xFF111111,      // Very Dark Gray
        textColor = 0xFFEEEEEE,         // Text White
        textMutedColor = 0xFF9AA0A6,    // Text Grey
        errorColor = 0xFFFF5252,        // Error Red
        isBuiltIn = true,
        category = "dark"
    )

    val NEON = ThemeEntity(
        id = "neon",
        name = "Neon",
        primaryColor = 0xFFD500F9,      // Magenta
        backgroundColor = 0xFF0D001A,   // Deep Purple-Black
        surfaceColor = 0xFF1A0033,      // Dark Purple
        textColor = 0xFFFFFFFF,
        textMutedColor = 0xFFB388FF,
        errorColor = 0xFFFF1744,
        isBuiltIn = true,
        category = "colorful"
    )

    val OCEAN = ThemeEntity(
        id = "ocean",
        name = "Ocean",
        primaryColor = 0xFF2979FF,      // Blue
        backgroundColor = 0xFF0A1628,   // Deep Ocean Blue
        surfaceColor = 0xFF132238,
        textColor = 0xFFE3F2FD,
        textMutedColor = 0xFF90CAF9,
        errorColor = 0xFFFF5252,
        isBuiltIn = true,
        category = "dark"
    )

    val SUNSET = ThemeEntity(
        id = "sunset",
        name = "Sunset",
        primaryColor = 0xFFFF9100,      // Orange
        backgroundColor = 0xFF1A0A0A,   // Dark Red-Brown
        surfaceColor = 0xFF2A1515,
        textColor = 0xFFFFF3E0,
        textMutedColor = 0xFFFFCC80,
        errorColor = 0xFFFF1744,
        isBuiltIn = true,
        category = "colorful"
    )

    val EMERALD = ThemeEntity(
        id = "emerald",
        name = "Emerald",
        primaryColor = 0xFF00E676,      // Green
        backgroundColor = 0xFF0A1A0D,   // Deep Forest
        surfaceColor = 0xFF152A18,
        textColor = 0xFFE8F5E9,
        textMutedColor = 0xFFA5D6A7,
        errorColor = 0xFFFF5252,
        isBuiltIn = true,
        category = "dark"
    )

    val AMBER = ThemeEntity(
        id = "amber",
        name = "Amber",
        primaryColor = 0xFFFFEA00,      // Gold
        backgroundColor = 0xFF1A150A,   // Dark Amber
        surfaceColor = 0xFF2A2515,
        textColor = 0xFFFFFDE7,
        textMutedColor = 0xFFFFF59D,
        errorColor = 0xFFFF5252,
        isBuiltIn = true,
        category = "colorful"
    )

    val CRIMSON = ThemeEntity(
        id = "crimson",
        name = "Crimson",
        primaryColor = 0xFFFF1744,      // Red
        backgroundColor = 0xFF1A0A0F,   // Deep Crimson
        surfaceColor = 0xFF2A151A,
        textColor = 0xFFFFEBEE,
        textMutedColor = 0xFFEF9A9A,
        errorColor = 0xFFFF1744,
        isBuiltIn = true,
        category = "colorful"
    )

    val SLATE = ThemeEntity(
        id = "slate",
        name = "Slate",
        primaryColor = 0xFF78909C,      // Blue Grey
        backgroundColor = 0xFF12141A,   // Dark Slate
        surfaceColor = 0xFF1E2128,
        textColor = 0xFFECEFF1,
        textMutedColor = 0xFF90A4AE,
        errorColor = 0xFFFF5252,
        isBuiltIn = true,
        category = "dark"
    )

    val ILLUMERA = ThemeEntity(
        id = "illumera",
        name = "Illumera",
        primaryColor = 0xFFFF2E7A,      // Logo Pink
        backgroundColor = 0xFF1A0510,   // Deep Magenta-Black
        surfaceColor = 0xFF2A0D1C,
        textColor = 0xFFFFF0F5,
        textMutedColor = 0xFFFFAB6B,    // Logo Orange
        errorColor = 0xFFFF1744,
        isBuiltIn = true,
        category = "colorful"
    )

    /**
     * All built-in themes in display order.
     */
    val ALL = listOf(VOID, ILLUMERA, NEON, OCEAN, SUNSET, EMERALD, AMBER, CRIMSON, SLATE)

    /**
     * Get a built-in theme by ID, returns ILLUMERA (the app default) as fallback.
     */
    fun getById(id: String): ThemeEntity {
        return ALL.find { it.id == id } ?: ILLUMERA
    }
}
