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
