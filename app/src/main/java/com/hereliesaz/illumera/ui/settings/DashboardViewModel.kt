package com.hereliesaz.illumera.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.profile.ProfileMutationCoordinator
import com.hereliesaz.illumera.data.model.HubRowEntity
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.data.model.HubRowWithItems
import com.hereliesaz.illumera.domain.ScreenGenreRows
import com.hereliesaz.illumera.domain.DashboardTab
import com.hereliesaz.illumera.domain.HeroConfig
import com.hereliesaz.illumera.domain.HubShape
import com.hereliesaz.illumera.domain.withHero
import com.hereliesaz.illumera.domain.withLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

sealed interface EditorListItem {
    val key: String
    val order: Int

    data class HubRowItem(
        val hub: HubRowEntity,
        val items: List<HubRowItemEntity>,
        override val order: Int
    ) : EditorListItem {
        override val key: String = "hub:${hub.id}"
    }

    data class CategoryItem(
        val config: CatalogConfigEntity,
        override val order: Int
    ) : EditorListItem {
        override val key: String = "cat:${config.uniqueId}"
    }
}

sealed interface DialogState {
    data object None : DialogState
    data class ManageGenres(val screen: String) : DialogState
    data class ManageCategory(val config: CatalogConfigEntity) : DialogState
    data class RenameCategory(val config: CatalogConfigEntity, val currentName: String) : DialogState
    data class LayoutSettings(val config: CatalogConfigEntity) : DialogState
    data class AddToTab(val tab: String) : DialogState
    data object CreateHub : DialogState
    data class ManageHub(val hubItem: EditorListItem.HubRowItem) : DialogState
    data class EditHub(val hubItem: EditorListItem.HubRowItem) : DialogState
    data class HeroCarouselConfig(val tab: DashboardTab) : DialogState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dao: AddonDao,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val profileMutationCoordinator: ProfileMutationCoordinator,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _movieGenres = MutableStateFlow(ScreenGenreRows.availableGenres("movie", emptyList()))
    private val _seriesGenres = MutableStateFlow(ScreenGenreRows.availableGenres("series", emptyList()))
    fun availableGenres(screen: String): StateFlow<List<String>> =
        if (screen == "series") _seriesGenres else _movieGenres

    fun disabledGenres(profileId: Int, screen: String): Set<String> =
        ScreenGenreRows.disabledGenres(appContext, profileId, screen)

    fun toggleGenre(profileId: Int, screen: String, genre: String) {
        if (screen !in setOf("movies", "series")) return
        val disabled = disabledGenres(profileId, screen).toMutableSet()
        val existing = disabled.firstOrNull { it.equals(genre, ignoreCase = true) }
        if (existing == null) disabled.add(genre) else disabled.remove(existing)
        ScreenGenreRows.setDisabledGenres(appContext, profileId, screen, disabled)
        dashboardModified = true
    }

    fun titleOrder(profileId: Int, screen: String, genre: String): ScreenGenreRows.TitleOrder =
        ScreenGenreRows.titleOrder(appContext, profileId, screen, genre)

    fun cycleTitleOrder(profileId: Int, screen: String, genre: String) {
        val orders = ScreenGenreRows.TitleOrder.entries
        val current = titleOrder(profileId, screen, genre)
        ScreenGenreRows.setTitleOrder(appContext, profileId, screen, genre,
            orders[(current.ordinal + 1) % orders.size])
        dashboardModified = true
    }

    fun setAllGenresEnabled(profileId: Int, screen: String, enabled: Boolean) {
        val all = availableGenres(screen).value
        ScreenGenreRows.setDisabledGenres(
            appContext, profileId, screen, if (enabled) emptySet() else all.toSet()
        )
        dashboardModified = true
    }

    /**
     * Set whenever a dashboard write lands; the editor consumes it on exit to invalidate the
     * Home screen cache only when something actually changed.
     */
    @Volatile
    private var dashboardModified = false

    /** Returns whether the dashboard was modified since the last call, and clears the flag. */
    fun consumeDashboardModified(): Boolean {
        val modified = dashboardModified
        dashboardModified = false
        return modified
    }

    // Serializes reorder writes so rapid D-pad presses persist in order.
    private val reorderMutex = Mutex()

    private suspend fun persistProfileState() {
        dashboardModified = true
        profileConfigurationManager.saveActiveRuntimeState()
        profileConfigurationManager.resetStartupCapture()
    }

    private val _configs = MutableStateFlow<List<CatalogConfigEntity>>(emptyList())
    val configs: StateFlow<List<CatalogConfigEntity>> = _configs

    private val _hubRows = MutableStateFlow<List<HubRowWithItems>>(emptyList())
    val hubRows: StateFlow<List<HubRowWithItems>> = _hubRows

    // Reorder writes still in flight. While > 0 the in-memory lists hold the optimistic
    // order, and a Room emission (which can still carry the pre-write rows) would revert
    // it, so emissions are ignored until the last write lands; the emission that follows
    // that write carries the final state.
    private val pendingLocalWrites = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        viewModelScope.launch {
            dao.getAllCatalogConfigs().collect { list ->
                if (pendingLocalWrites.get() == 0) _configs.value = list
            }
        }
        viewModelScope.launch {
            dao.getAllAddons().collect { addons ->
                val catalogs = addons.filter { it.isEnabled }.flatMap { addon ->
                    runCatching {
                        com.google.gson.Gson().fromJson(
                            addon.catalogsJson, Array<CatalogManifest>::class.java
                        )?.toList().orEmpty()
                    }.getOrDefault(emptyList())
                }
                _movieGenres.value = ScreenGenreRows.availableGenres("movie", catalogs)
                _seriesGenres.value = ScreenGenreRows.availableGenres("series", catalogs)
            }
        }
        viewModelScope.launch {
            dao.getHubRowsWithItems().collect { list ->
                if (pendingLocalWrites.get() == 0) _hubRows.value = list
            }
        }
    }

    fun renameCatalog(config: CatalogConfigEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.saveCatalogConfig(config.copy(customTitle = newName))
            persistProfileState()
        }
    }

    fun addItemToTab(item: EditorListItem, screen: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            // After any queued reorder write, so the max order comes from the latest list.
            reorderMutex.withLock {
                val currentItems = getEditorItems(screen)
                val maxOrder = currentItems.maxOfOrNull { it.order } ?: -1
                val newOrder = maxOrder + 1

                when(item) {
                    is EditorListItem.CategoryItem -> {
                        // The row as it is now, not the snapshot the UI handed in.
                        val config = _configs.value.firstOrNull { it.uniqueId == item.config.uniqueId } ?: item.config
                        val updated = when(screen) {
                            "home" -> config.copy(showInHome = true, homeOrder = newOrder)
                            "movies" -> config.copy(showInMovies = true, moviesOrder = newOrder)
                            "series" -> config.copy(showInSeries = true, seriesOrder = newOrder)
                            else -> config
                        }
                        dao.saveCatalogConfig(updated)
                    }
                    is EditorListItem.HubRowItem -> {
                        val hub = _hubRows.value.firstOrNull { it.hub.id == item.hub.id }?.hub ?: item.hub
                        val updated = when(screen) {
                            "home" -> hub.copy(showInHome = true, homeOrder = newOrder)
                            "movies" -> hub.copy(showInMovies = true, moviesOrder = newOrder)
                            "series" -> hub.copy(showInSeries = true, seriesOrder = newOrder)
                            else -> hub
                        }
                        dao.updateHubRow(updated)
                    }
                }
                persistProfileState()
            }
        }
    }

    fun removeItemFromTab(item: EditorListItem, screen: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            when(item) {
                is EditorListItem.CategoryItem -> {
                    val config = item.config
                    val updated = when(screen) {
                        "home" -> config.copy(showInHome = false)
                        "movies" -> config.copy(showInMovies = false)
                        "series" -> config.copy(showInSeries = false)
                        else -> config
                    }
                    dao.saveCatalogConfig(updated)
                }
                is EditorListItem.HubRowItem -> {
                    val hub = item.hub
                    val updated = when(screen) {
                        "home" -> hub.copy(showInHome = false)
                        "movies" -> hub.copy(showInMovies = false)
                        "series" -> hub.copy(showInSeries = false)
                        else -> hub
                    }
                    dao.updateHubRow(updated)
                }
            }
            persistProfileState()
        }
    }

    fun moveEditorItem(item: EditorListItem, direction: Int, screen: String) {
        // Reads the in-memory list, which is updated optimistically below, so rapid presses
        // build on the previous move instead of on a Room emission that hasn't arrived yet.
        val currentList = getEditorItems(screen).toMutableList()
        val index = currentList.indexOfFirst { it.key == item.key }
        if (index == -1) return

        val newIndex = index + direction
        if (newIndex in 0 until currentList.size) {
            Collections.swap(currentList, index, newIndex)

            val updatedConfigs = ArrayList<CatalogConfigEntity>()
            val updatedHubs = ArrayList<HubRowEntity>()

            currentList.forEachIndexed { i, listItem ->
                when(listItem) {
                    is EditorListItem.CategoryItem -> {
                        val updated = when(screen) {
                            "home" -> listItem.config.copy(homeOrder = i)
                            "movies" -> listItem.config.copy(moviesOrder = i)
                            "series" -> listItem.config.copy(seriesOrder = i)
                            else -> listItem.config
                        }
                        updatedConfigs.add(updated)
                    }
                    is EditorListItem.HubRowItem -> {
                        val updated = when(screen) {
                            "home" -> listItem.hub.copy(homeOrder = i)
                            "movies" -> listItem.hub.copy(moviesOrder = i)
                            "series" -> listItem.hub.copy(seriesOrder = i)
                            else -> listItem.hub
                        }
                        updatedHubs.add(updated)
                    }
                }
            }

            // Optimistic local update (Room re-emits the same values once the write lands).
            val configsById = updatedConfigs.associateBy { it.uniqueId }
            _configs.value = _configs.value.map { configsById[it.uniqueId] ?: it }
            val hubsById = updatedHubs.associateBy { it.id }
            _hubRows.value = _hubRows.value.map { row ->
                hubsById[row.hub.id]?.let { row.copy(hub = it) } ?: row
            }

            pendingLocalWrites.incrementAndGet()
            viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                try {
                    reorderMutex.withLock {
                        if (updatedHubs.isNotEmpty()) dao.updateHubRows(updatedHubs)
                        if (updatedConfigs.isNotEmpty()) dao.saveCatalogConfigs(updatedConfigs)
                        persistProfileState()
                    }
                } finally {
                    pendingLocalWrites.decrementAndGet()
                }
            }
        }
    }

    fun updateLayoutSettings(
        config: CatalogConfigEntity,
        isInfiniteLoopEnabled: Boolean,
        visibleItemCount: Int,
        isInfiniteScrollingEnabled: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.saveCatalogConfig(
                config.copy(
                    isInfiniteLoopEnabled = isInfiniteLoopEnabled,
                    visibleItemCount = visibleItemCount.coerceIn(5, 50),
                    isInfiniteScrollingEnabled = isInfiniteScrollingEnabled
                )
            )
            persistProfileState()
        }
    }

    fun createHubRow(name: String, shape: HubShape, items: List<HubRowItemEntity>, tab: String = "home") {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val maxOrder = when (tab) {
                "movies" -> dao.getMaxHubMoviesOrder() ?: -1
                "series" -> dao.getMaxHubSeriesOrder() ?: -1
                else -> dao.getMaxHubHomeOrder() ?: -1
            }
            val hubRowId = UUID.randomUUID().toString()
            val hubRow = HubRowEntity(
                id = hubRowId,
                title = name,
                shape = shape.name,
                showInHome = tab == "home",
                homeOrder = if (tab == "home") maxOrder + 1 else 999,
                showInMovies = tab == "movies",
                moviesOrder = if (tab == "movies") maxOrder + 1 else 999,
                showInSeries = tab == "series",
                seriesOrder = if (tab == "series") maxOrder + 1 else 999
            )
            val hubItems = items.mapIndexed { index, item ->
                item.copy(
                    hubRowId = hubRowId,
                    itemOrder = index
                )
            }
            dao.insertHubRowWithItems(hubRow, hubItems)
            persistProfileState()
        }
    }

    fun deleteHubRow(hubRowId: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.deleteHubRowWithItems(hubRowId)
            persistProfileState()
        }
    }

    /** Saves title and shape in one write so neither change overwrites the other. */
    fun updateHubRow(hubRowId: String, newTitle: String, shape: HubShape) {
        val currentHub = _hubRows.value.find { it.hub.id == hubRowId }?.hub ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.updateHubRow(currentHub.copy(title = newTitle, shape = shape.name))
            persistProfileState()
        }
    }

    fun addCategoryToHubRow(hubRowId: String, config: CatalogConfigEntity) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val maxOrder = dao.getMaxHubItemOrder(hubRowId) ?: -1
            val item = HubRowItemEntity(
                hubRowId = hubRowId,
                configUniqueId = config.uniqueId,
                title = config.customTitle ?: if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId}",
                itemOrder = maxOrder + 1
            )
            dao.insertHubRowItem(item)
            persistProfileState()
        }
    }

    fun removeCategoryFromHubRow(hubRowId: String, configUniqueId: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.deleteHubRowItem(hubRowId, configUniqueId)
            persistProfileState()
        }
    }

    fun updateHubItemImage(hubRowId: String, configUniqueId: String, imageUrl: String?) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.updateHubItemImage(hubRowId, configUniqueId, imageUrl)
            persistProfileState()
        }
    }

    fun updateHubRowItemsOrder(hubRowId: String, orderedItems: List<HubRowItemEntity>) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val updatedItems = orderedItems.mapIndexed { index, item ->
                item.copy(hubRowId = hubRowId, itemOrder = index)
            }
            dao.updateHubRowItems(updatedItems)
            persistProfileState()
        }
    }

    fun updateTabLayout(profileId: Int, tab: DashboardTab, layout: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            profileMutationCoordinator.update(profileId) { it.withLayout(tab, layout) }
            persistProfileState()
        }
    }

    fun updateTabHero(profileId: Int, tab: DashboardTab, config: HeroConfig) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            profileMutationCoordinator.update(profileId) { it.withHero(tab, config) }
            persistProfileState()
        }
    }

    fun getEditorItems(selectedTab: String): List<EditorListItem> {
        val categoryItems = _configs.value
            .filter {
                when (selectedTab) {
                    "home" -> it.showInHome
                    "movies" -> it.showInMovies
                    "series" -> it.showInSeries
                    else -> false
                }
            }
            .map {
                val order = when(selectedTab) {
                    "home" -> it.homeOrder
                    "movies" -> it.moviesOrder
                    "series" -> it.seriesOrder
                    else -> 0
                }
                EditorListItem.CategoryItem(it, order)
            }

        val hubItems = _hubRows.value
            .filter {
                when (selectedTab) {
                    "home" -> it.hub.showInHome
                    "movies" -> it.hub.showInMovies
                    "series" -> it.hub.showInSeries
                    else -> false
                }
            }
            .map {
                val order = when(selectedTab) {
                    "home" -> it.hub.homeOrder
                    "movies" -> it.hub.moviesOrder
                    "series" -> it.hub.seriesOrder
                    else -> 0
                }
                EditorListItem.HubRowItem(it.hub, it.items.sortedBy { i -> i.itemOrder }, order)
            }

        return (hubItems + categoryItems).sortedBy { it.order }
    }

}
