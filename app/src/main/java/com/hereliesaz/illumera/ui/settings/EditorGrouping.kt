package com.hereliesaz.illumera.ui.settings

/**
 * What the Screen Editor list shows. Ungrouped, it is every row in order. Grouped, an addon with
 * two or more rows on the tab becomes one [Group] header (open or shut) followed, when open, by
 * its rows; hubs and addons with a single row stay as they are.
 */
internal sealed interface EditorDisplayItem {
    val key: String

    data class Group(
        val addonKey: String,
        val title: String,
        val rowCount: Int,
        val expanded: Boolean
    ) : EditorDisplayItem {
        override val key: String = "group:$addonKey"
    }

    /** [groupKey] is the addon the row is grouped under, or null for a row that stands alone. */
    data class Row(val item: EditorListItem, val groupKey: String?) : EditorDisplayItem {
        override val key: String = item.key
    }
}

/**
 * Lays [items] (in screen order) out for display. A group sits where its addon's first row was,
 * and pulls the addon's other rows up to it, in their order, so a long list collapses to one line
 * per addon.
 */
internal fun groupEditorItems(
    items: List<EditorListItem>,
    grouped: Boolean,
    expanded: Set<String>
): List<EditorDisplayItem> {
    if (!grouped) return items.map { EditorDisplayItem.Row(it, null) }
    val rowsByAddon = items.filterIsInstance<EditorListItem.CategoryItem>().groupBy { it.config.transportUrl }
    val done = mutableSetOf<String>()
    val out = mutableListOf<EditorDisplayItem>()
    for (item in items) {
        val addon = (item as? EditorListItem.CategoryItem)?.config?.transportUrl
        val rows = addon?.let(rowsByAddon::get)
        if (addon == null || rows == null || rows.size < 2) {
            out += EditorDisplayItem.Row(item, null)
            continue
        }
        if (!done.add(addon)) continue
        val isOpen = addon in expanded
        out += EditorDisplayItem.Group(addon, rows.first().config.addonName, rows.size, isOpen)
        if (isOpen) rows.forEach { out += EditorDisplayItem.Row(it, addon) }
    }
    return out
}

/**
 * The row [delta] places away from [item] that it may trade places with, or null at the end.
 * Ungrouped that is the next row in the list. Inside a group it is the next row of the same
 * addon, so a move never jumps over a header or into another addon.
 */
internal fun moveNeighbour(
    display: List<EditorDisplayItem>,
    item: EditorListItem,
    delta: Int
): EditorListItem? {
    val index = display.indexOfFirst { it.key == item.key }
    if (index < 0) return null
    val here = display[index] as? EditorDisplayItem.Row ?: return null
    val next = display.getOrNull(index + delta) as? EditorDisplayItem.Row ?: return null
    return next.item.takeIf { next.groupKey == here.groupKey }
}

/**
 * The row a row being picked up should jump to sit under: the row put down last, when the list is
 * shown as it is ordered (grouped, the display does not follow the order, so nothing jumps) and the
 * picked-up row is not that row nor already right under it. Null means stay where it is.
 */
internal fun pickupAnchor(
    grouped: Boolean,
    lastMovedKey: String?,
    pickedKey: String,
    items: List<EditorListItem>
): EditorListItem? {
    if (grouped || lastMovedKey == null || lastMovedKey == pickedKey) return null
    val anchorIndex = items.indexOfFirst { it.key == lastMovedKey }
    val pickedIndex = items.indexOfFirst { it.key == pickedKey }
    if (anchorIndex < 0 || pickedIndex < 0 || pickedIndex == anchorIndex + 1) return null
    return items[anchorIndex]
}

/** Where a row picked up from [from] ends up when it jumps under the one at [anchorIndex]. */
internal fun jumpTargetIndex(from: Int, anchorIndex: Int): Int =
    if (anchorIndex < from) anchorIndex + 1 else anchorIndex

/**
 * Where focus goes once a row has been put down: the row now at the place it was picked up from
 * (the one that moved up into the gap), or the nearest row if that place is a group header or past
 * the end. Null when there are no rows.
 */
internal fun returnFocusKey(display: List<EditorDisplayItem>, originIndex: Int): String? {
    if (display.isEmpty()) return null
    val start = originIndex.coerceIn(0, display.lastIndex)
    for (distance in 0..display.lastIndex) {
        for (candidate in listOf(start + distance, start - distance)) {
            (display.getOrNull(candidate) as? EditorDisplayItem.Row)?.let { return it.key }
        }
    }
    return null
}
