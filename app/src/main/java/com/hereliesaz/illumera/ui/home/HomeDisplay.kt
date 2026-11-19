package com.hereliesaz.illumera.ui.home

import com.hereliesaz.illumera.domain.CategoryRow
import com.hereliesaz.illumera.domain.DashboardTab
import com.hereliesaz.illumera.domain.HomeRowItem
import com.hereliesaz.illumera.domain.ScreenGenreRows
import com.hereliesaz.illumera.domain.ScreenGenreRows.TitleOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Per-tab presentation of [HomeViewModel.HomeState]: profile genre ordering on Movies/Series,
 * watched titles hidden on Home, "Don't like" titles hidden everywhere, personal rows on Home and
 * the sort chosen on streaming-service rows. It is derived in the ViewModel (see
 * [HomeDisplayDeriver] and [displayStates]) so Compose only collects ready lists.
 */

/**
 * Movies/Series: each row's titles in the order chosen for its genre ([titleOrderFor], by row
 * title). Home: watched titles removed from the catalog rows and the hero.
 */
internal fun displayStateFor(
    tab: DashboardTab,
    state: HomeViewModel.HomeState,
    titleOrderFor: ((rowTitle: String) -> TitleOrder)?
): HomeViewModel.HomeState =
    if (tab != DashboardTab.HOME) {
        if (titleOrderFor == null) state else state.copy(
            rows = state.rows.map { row ->
                row.copy(items = ScreenGenreRows.sortTitles(row.items, titleOrderFor(row.title)))
            },
            mixedRows = state.mixedRows.map { row ->
                if (row is CategoryRow) row.copy(
                    items = ScreenGenreRows.sortTitles(row.items, titleOrderFor(row.title))
                ) else row
            }
        )
    } else {
        // Home-only rule: once a movie or any episode of a series is watched,
        // remove that title from Home catalog/hero lists immediately. The
        // Continue Watching data remains untouched and manages itself from history.
        val hiddenIds = state.watchedIds
        if (hiddenIds.isEmpty()) {
            state
        } else {
            state.copy(
                rows = state.rows.map { row -> row.copy(items = row.items.filterNot { it.id in hiddenIds }) },
                mixedRows = state.mixedRows.map { row ->
                    if (row is CategoryRow) row.copy(items = row.items.filterNot { it.id in hiddenIds }) else row
                },
                heroRow = state.heroRow?.let { row -> row.copy(items = row.items.filterNot { it.id in hiddenIds }) }
            )
        }
    }

/** Home gets the personal rows; they sit after every catalog row (see visibleMixedRows). */
internal fun withPersonalRows(tab: DashboardTab, state: HomeViewModel.HomeState): HomeViewModel.HomeState =
    if (tab != DashboardTab.HOME || state.personalRows.isEmpty()) state
    else state.copy(mixedRows = state.mixedRows + state.personalRows)

/** "Don't like" removes a title from every row and the hero. */
internal fun withoutDisliked(state: HomeViewModel.HomeState): HomeViewModel.HomeState {
    val disliked = state.dislikedIds
    if (disliked.isEmpty()) return state
    return state.copy(
        rows = state.rows.map { row -> row.copy(items = row.items.filterNot { it.id in disliked }) },
        mixedRows = state.mixedRows.map { row ->
            if (row is CategoryRow) row.copy(items = row.items.filterNot { it.id in disliked }) else row
        },
        heroRow = state.heroRow?.let { row -> row.copy(items = row.items.filterNot { it.id in disliked }) }
    )
}

/**
 * Turns the loaded [HomeViewModel.HomeState] into what [tab] shows, reusing the previous result's
 * row lists while the data they come from is unchanged. That matters because the state also
 * changes for reasons that do not touch the rows (Continue Watching progress, an enrichment of
 * some other title): without it each of those would hand the screen brand-new row lists to
 * compare, re-key and re-run effects on.
 *
 * Not thread-safe: one deriver is driven by one flow, sequentially.
 *
 * @param titleOrderFor the sort chosen for a genre row, by its title (Movies/Series; a stored
 *   setting, so it is read here, once per derivation, never from composition)
 */
internal class HomeDisplayDeriver(
    private val tab: DashboardTab,
    private val profileId: Int?,
    private val titleOrderFor: (rowTitle: String) -> TitleOrder
) {
    /** Everything the displayed rows are computed from. */
    private data class Inputs(
        val rows: List<com.hereliesaz.illumera.domain.HomeRow>,
        val mixedRows: List<HomeRowItem>,
        val heroRow: com.hereliesaz.illumera.domain.HomeRow?,
        val watchedIds: Set<String>,
        val dislikedIds: Set<String>,
        val personalRows: List<CategoryRow>,
        val rowSortOrders: Map<String, TitleOrder>,
        val genreOrders: Map<String, TitleOrder>
    )

    private class DisplayRows(
        val rows: List<com.hereliesaz.illumera.domain.HomeRow>,
        val mixedRows: List<HomeRowItem>,
        val heroRow: com.hereliesaz.illumera.domain.HomeRow?
    )

    private var lastInputs: Inputs? = null
    private var lastRows: DisplayRows? = null

    /** Number of times the row lists were actually rebuilt (for tests). */
    var derivations = 0
        private set

    fun derive(
        state: HomeViewModel.HomeState,
        rowSortOrders: Map<String, TitleOrder>
    ): HomeViewModel.HomeState {
        val genreOrders: Map<String, TitleOrder> =
            if (tab == DashboardTab.HOME || profileId == null) emptyMap()
            else {
                val titles = LinkedHashSet<String>()
                state.rows.forEach { titles.add(it.title) }
                state.mixedRows.forEach { if (it is CategoryRow) titles.add(it.title) }
                titles.associateWith(titleOrderFor)
            }
        val inputs = Inputs(
            rows = state.rows,
            mixedRows = state.mixedRows,
            heroRow = state.heroRow,
            watchedIds = state.watchedIds,
            dislikedIds = state.dislikedIds,
            personalRows = state.personalRows,
            rowSortOrders = rowSortOrders,
            genreOrders = genreOrders
        )
        val cached = lastRows
        val rows = if (cached != null && inputs == lastInputs) cached else {
            derivations++
            val shown = withRowSorts(
                displayStateFor(
                    tab,
                    withoutDisliked(withPersonalRows(tab, state)),
                    if (tab == DashboardTab.HOME || profileId == null) null
                    else { title -> genreOrders[title] ?: TitleOrder.CATALOG }
                ),
                rowSortOrders
            )
            DisplayRows(shown.rows, shown.mixedRows, shown.heroRow).also {
                lastInputs = inputs
                lastRows = it
            }
        }
        return if (rows.rows === state.rows && rows.mixedRows === state.mixedRows && rows.heroRow === state.heroRow) state
        else state.copy(rows = rows.rows, mixedRows = rows.mixedRows, heroRow = rows.heroRow)
    }
}

/**
 * [state] as the tab shows it: derived on [dispatcher] (never the main thread), only emitting when
 * the result differs from the last one. Neither the viewer's focus nor scroll memory is part of
 * [state], so moving the D-pad never reaches this flow.
 */
internal fun displayStates(
    state: Flow<HomeViewModel.HomeState>,
    rowSortOrders: Flow<Map<String, TitleOrder>>,
    deriver: HomeDisplayDeriver,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<HomeViewModel.HomeState> =
    combine(state, rowSortOrders) { current, orders -> deriver.derive(current, orders) }
        .distinctUntilChanged()
        .flowOn(dispatcher)
