package com.hereliesaz.illumera.domain

import com.hereliesaz.illumera.data.model.stremio.MetaItem

data class HomeRow(
    val configId: String,                        // Unique identifier from CatalogConfigEntity
    val title: String,
    val items: List<MetaItem>,
    val catalogUrl: String = "",                  // Base URL for fetching more pages
    val isInfiniteLoopEnabled: Boolean = false,  // Default: Standard linear scrolling (Grid View)
    val visibleItemCount: Int = 15,              // Only used when Grid View is enabled
    val isInfiniteScrollingEnabled: Boolean = true,  // Only used when Grid View is enabled; loops at View More
    val order: Int = 999,
    val supportsSkip: Boolean = false,           // Primary catalog supports skip
    val secondCatalogUrl: String? = null,         // Paired TV/movie catalog on Home
    val secondSupportsSkip: Boolean = false,
    val fetchedPrimaryCount: Int = 0,             // Raw source counts (before dedup)
    val fetchedSecondCount: Int = 0
)