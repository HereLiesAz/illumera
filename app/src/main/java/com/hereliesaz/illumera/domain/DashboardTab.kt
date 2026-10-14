package com.hereliesaz.illumera.domain

import androidx.compose.runtime.Immutable
import com.hereliesaz.illumera.data.model.ProfileEntity

/** Explicitly disabled; null means automatic unified Hero on Movies/Series. */
const val HERO_DISABLED_MARKER = "__hero_disabled__"

enum class DashboardTab {
    HOME, MOVIES, SERIES;

    companion object {
        fun fromString(s: String): DashboardTab = when (s.lowercase()) {
            "home" -> HOME
            "movies" -> MOVIES
            "series" -> SERIES
            else -> HOME
        }
    }
}

@Immutable
data class HeroConfig(
    val categoryId: String?,
    val posterCount: Int,
    val autoScrollSeconds: Int
)

fun ProfileEntity.layoutFor(tab: DashboardTab): String = when (tab) {
    DashboardTab.HOME -> homeTabLayout
    DashboardTab.MOVIES -> moviesTabLayout
    DashboardTab.SERIES -> seriesTabLayout
}

fun ProfileEntity.withLayout(tab: DashboardTab, layout: String): ProfileEntity = when (tab) {
    DashboardTab.HOME -> copy(homeTabLayout = layout)
    DashboardTab.MOVIES -> copy(moviesTabLayout = layout)
    DashboardTab.SERIES -> copy(seriesTabLayout = layout)
}

fun ProfileEntity.heroFor(tab: DashboardTab): HeroConfig = when (tab) {
    DashboardTab.HOME -> HeroConfig(homeHeroCategory, homeHeroPosterCount, homeHeroAutoScrollSeconds)
    DashboardTab.MOVIES -> HeroConfig(moviesHeroCategory, moviesHeroPosterCount, moviesHeroAutoScrollSeconds)
    DashboardTab.SERIES -> HeroConfig(seriesHeroCategory, seriesHeroPosterCount, seriesHeroAutoScrollSeconds)
}

fun ProfileEntity.withHero(tab: DashboardTab, config: HeroConfig): ProfileEntity = when (tab) {
    DashboardTab.HOME -> copy(
        homeHeroCategory = config.categoryId,
        homeHeroPosterCount = config.posterCount,
        homeHeroAutoScrollSeconds = config.autoScrollSeconds
    )
    DashboardTab.MOVIES -> copy(
        moviesHeroCategory = config.categoryId,
        moviesHeroPosterCount = config.posterCount,
        moviesHeroAutoScrollSeconds = config.autoScrollSeconds
    )
    DashboardTab.SERIES -> copy(
        seriesHeroCategory = config.categoryId,
        seriesHeroPosterCount = config.posterCount,
        seriesHeroAutoScrollSeconds = config.autoScrollSeconds
    )
}
