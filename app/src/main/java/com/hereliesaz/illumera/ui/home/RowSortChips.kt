package com.hereliesaz.illumera.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import com.hereliesaz.illumera.domain.CategoryRow
import com.hereliesaz.illumera.domain.ScreenGenreRows
import com.hereliesaz.illumera.domain.ScreenGenreRows.TitleOrder
import com.hereliesaz.illumera.domain.StreamingServices

/**
 * Sort orders for the streaming-service rows on Home ("Netflix", "HBO Max", ...), chosen from
 * chips beside the row's title. [orders] is by row id; a row with no entry is in catalog order.
 */
class RowSorts(
    val orders: Map<String, TitleOrder>,
    val onSelect: (rowId: String, order: TitleOrder) -> Unit
)

val LocalRowSorts = compositionLocalOf<RowSorts?> { null }

/** A streaming-service row: the only rows that offer sort chips. */
internal fun isSortableServiceRow(row: CategoryRow): Boolean = StreamingServices.isServiceRowTitle(row.title)

/** [state] with each streaming-service row's titles in the order chosen for it. */
internal fun withRowSorts(
    state: HomeViewModel.HomeState,
    orders: Map<String, TitleOrder>
): HomeViewModel.HomeState {
    if (orders.isEmpty()) return state
    fun sorted(row: CategoryRow): CategoryRow {
        val order = orders[row.id] ?: return row
        if (!isSortableServiceRow(row) || order == TitleOrder.CATALOG) return row
        return row.copy(items = ScreenGenreRows.sortTitles(row.items, order))
    }
    return state.copy(mixedRows = state.mixedRows.map { if (it is CategoryRow) sorted(it) else it })
}

/** Short chip labels: they sit on the title's line, so the long menu labels would not fit. */
internal fun TitleOrder.chipLabel(): String = when (this) {
    TitleOrder.CATALOG -> "Default"
    TitleOrder.RATING -> "Rating"
    TitleOrder.NEWEST -> "Newest"
    TitleOrder.OLDEST -> "Oldest"
    TitleOrder.TITLE_ASC -> "A–Z"
    TitleOrder.TITLE_DESC -> "Z–A"
}

/** Header controls for [row], or null when it is not a streaming-service row. */
@Composable
fun rowSortActions(row: CategoryRow): (@Composable () -> Unit)? {
    val sorts = LocalRowSorts.current ?: return null
    if (!isSortableServiceRow(row)) return null
    val selected = sorts.orders[row.id] ?: TitleOrder.CATALOG
    val rowId = row.id
    return {
        Spacer(Modifier.width(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TitleOrder.entries.forEach { order ->
                SortChip(
                    label = order.chipLabel(),
                    active = order == selected,
                    onClick = { sorts.onSelect(rowId, order) }
                )
            }
        }
    }
}

@Composable
private fun SortChip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = label,
        color = when {
            focused -> Color.Black
            active -> Color.White
            else -> Color.White.copy(alpha = 0.6f)
        },
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    active -> Color.White.copy(alpha = 0.22f)
                    else -> Color.Transparent
                },
                shape
            )
            // Select with the D-pad's centre/enter key as well as a tap.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter)
                ) {
                    onClick()
                    true
                } else false
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
