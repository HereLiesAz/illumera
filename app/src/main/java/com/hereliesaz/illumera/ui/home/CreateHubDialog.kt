package com.hereliesaz.illumera.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.domain.HubShape
import com.hereliesaz.illumera.ui.settings.HubEditorContent
import com.hereliesaz.illumera.ui.settings.VoidCheckboxRow
import com.hereliesaz.illumera.ui.addons.VoidDialog
import com.hereliesaz.illumera.ui.settings.HubItemEditorRow
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.ui.addons.VoidButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.ui.settings.HubCategoryManagerDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

@Composable
fun CreateHubDialog(
    categories: List<CatalogConfigEntity>,
    onDismiss: () -> Unit,
    onCreate: (name: String, shape: HubShape, items: List<HubRowItemEntity>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(HubShape.HORIZONTAL) }
    // Use List<HubRowItemEntity> to store selected items + images
    var addedItems by remember { mutableStateOf<List<HubRowItemEntity>>(emptyList()) }
    
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    // Track which sub-dialog was last opened to restore focus on return
    var lastSubDialog by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }
    val shapeFocusRequester = remember { FocusRequester() }
    val addCategoryFocusRequester = remember { FocusRequester() }
    val manageCategoriesFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
    }

    // Restore focus when returning from a sub-dialog
    val isMainVisible = !showCategoryPicker && !showManager
    LaunchedEffect(isMainVisible, lastSubDialog) {
        if (isMainVisible && lastSubDialog != null) {
            kotlinx.coroutines.delay(100)
            when (lastSubDialog) {
                "add" -> addCategoryFocusRequester.requestFocus()
                "manage" -> manageCategoriesFocusRequester.requestFocus()
            }
        }
    }

    // Main Content (always rendered — sub-dialogs overlay on top)
    HubEditorContent(
        title = "Create New Hub Row",
        name = name,
        onNameChange = { name = it },
        shape = shape,
        onShapeChange = { shape = it },
        categoryCount = addedItems.size,
        onAddCategory = { lastSubDialog = "add"; showCategoryPicker = true },
        onManageCategories = { lastSubDialog = "manage"; showManager = true },
        onCancel = onDismiss,
        onSave = {
            // Determine final order based on list index
            val finalItems = addedItems.mapIndexed { index, item ->
                item.copy(itemOrder = index)
            }
            onCreate(name.ifBlank { "Hub Row" }, shape, finalItems)
        },
        saveEnabled = name.isNotBlank() && addedItems.isNotEmpty(),
        focusRequester = focusRequester,
        shapeFocusRequester = shapeFocusRequester,
        addCategoryFocusRequester = addCategoryFocusRequester,
        manageCategoriesFocusRequester = manageCategoriesFocusRequester
    )

    // Manager Sub-Dialog
    if (showManager) {
        HubCategoryManagerDialog(
            initialItems = addedItems,
            hubShape = shape,
            onDismiss = { showManager = false },
            onSave = { updatedItems ->
                addedItems = updatedItems
                showManager = false
            }
        )
    }

    // Category Picker Sub-Dialog
    if (showCategoryPicker) {
        SelectCategoriesDialog(
            allCategories = categories,
            selectedIds = addedItems.map { it.configUniqueId }.toSet(),
            onDismiss = { showCategoryPicker = false },
            onConfirm = { newSet -> 
                // 1. Keep existing items that are still in the set (to preserve images/order)
                val keptItems = addedItems.filter { it.configUniqueId in newSet }
                
                // 2. Add new items
                val existingIds = keptItems.map { it.configUniqueId }.toSet()
                val newConfigIds = newSet.filter { it !in existingIds }
                
                val newItems = newConfigIds.mapNotNull { id ->
                    categories.find { it.uniqueId == id }?.let { config ->
                        HubRowItemEntity(
                            hubRowId = "TEMP",
                            configUniqueId = config.uniqueId,
                            title = config.customTitle ?: if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId}",
                            itemOrder = 0 // Order will be fixed on save
                        )
                    }
                }
                
                addedItems = keptItems + newItems
                showCategoryPicker = false
            }
        )
    }
}

@Composable
fun SelectCategoriesDialog(
    allCategories: List<CatalogConfigEntity>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var currentSelection by remember(selectedIds) { mutableStateOf(selectedIds) }
    val confirmFocusRequester = remember { FocusRequester() }

    VoidDialog(onDismissRequest = onDismiss, title = "Select Categories") {
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(allCategories, key = { _, it -> it.uniqueId }) { index, config ->
                val isLastItem = index == allCategories.lastIndex
                VoidCheckboxRow(
                    label = config.customTitle ?: if (config.catalogName != null) "${config.catalogName} - ${config.catalogType.replaceFirstChar { it.uppercase() }}" else "${config.addonName} - ${config.catalogId}",
                    isChecked = config.uniqueId in currentSelection,
                    onCheckedChange = { isChecked ->
                        currentSelection = if (isChecked) {
                            currentSelection + config.uniqueId
                        } else {
                            currentSelection - config.uniqueId
                        }
                    },
                    modifier = if (isLastItem) Modifier.onPreviewKeyEvent {
                        if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                            confirmFocusRequester.requestFocus()
                            true
                        } else false
                    } else Modifier
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VoidButton("Cancel", onDismiss, Modifier.weight(1f))
            VoidButton("Confirm", { onConfirm(currentSelection) }, Modifier.weight(1f), focusRequester = confirmFocusRequester)
        }
    }
}
