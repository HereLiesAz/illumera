package com.hereliesaz.illumera.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import com.hereliesaz.illumera.domain.HubShape
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.addons.VoidDialog
import com.hereliesaz.illumera.ui.addons.VoidInput

@Composable
fun HubEditorContent(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    shape: HubShape,
    onShapeChange: (HubShape) -> Unit,
    categoryCount: Int,
    onAddCategory: () -> Unit,
    onManageCategories: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    shapeFocusRequester: FocusRequester? = null,
    addCategoryFocusRequester: FocusRequester? = null,
    manageCategoriesFocusRequester: FocusRequester? = null
) {
    val saveFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }

    // Helper modifier to redirect Back press to Cancel button
    val interceptBackToCancel = Modifier.onPreviewKeyEvent {
        if (it.key == Key.Back && it.type == KeyEventType.KeyDown) {
            cancelFocusRequester.requestFocus()
            true
        } else false
    }

    VoidDialog(
        onDismissRequest = onCancel, 
        title = title
    ) {
        // Name
        Text("Name", color = MaterialTheme.colorScheme.onBackground.copy(0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
        VoidInput(
            value = name,
            onValueChange = onNameChange,
            placeholder = "Hub Row Name",
            modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester).onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                    shapeFocusRequester?.requestFocus()
                    true
                } else if (it.key == Key.Back && it.type == KeyEventType.KeyDown) {
                    cancelFocusRequester.requestFocus()
                    true
                } else false
            } else Modifier.then(interceptBackToCancel).onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                    shapeFocusRequester?.requestFocus()
                    true
                } else false
            },
            onDone = { shapeFocusRequester?.requestFocus() }
        )

        Spacer(Modifier.height(20.dp))

        // Shape
        Text("Shape", color = MaterialTheme.colorScheme.onBackground.copy(0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HubShape.entries.forEach { s ->
                val isSelected = shape == s
                HubShapeItem(
                    label = s.name,
                    isSelected = isSelected,
                    onClick = { onShapeChange(s) },
                    modifier = interceptBackToCancel,
                    focusRequester = if (isSelected) shapeFocusRequester else null
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Actions
        Text("Categories ($categoryCount)", color = MaterialTheme.colorScheme.onBackground.copy(0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoidButton(
                text = "Add Category", 
                onClick = onAddCategory, 
                modifier = Modifier.fillMaxWidth().then(interceptBackToCancel).onPreviewKeyEvent {
                    if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
                        shapeFocusRequester?.requestFocus()
                        true
                    } else false
                },
                focusRequester = addCategoryFocusRequester
            )
            
            VoidButton(
                text = "Manage Categories", 
                onClick = onManageCategories, 
                modifier = Modifier.fillMaxWidth().alpha(if (categoryCount > 0) 1f else 0.5f).then(interceptBackToCancel).onPreviewKeyEvent {
                    if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                        saveFocusRequester.requestFocus()
                        true
                    } else false
                },
                enabled = categoryCount > 0,
                focusRequester = manageCategoriesFocusRequester
            )
        }

        Spacer(Modifier.height(24.dp))

        // Footer Actions (Cancel / Save)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VoidButton(
                text = "Cancel", 
                onClick = onCancel, 
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.key == Key.Back && it.type == KeyEventType.KeyDown) {
                        onCancel()
                        true
                    } else false
                },
                focusRequester = cancelFocusRequester
            )
            VoidButton(
                text = "Save", 
                onClick = onSave, 
                modifier = Modifier.weight(1f).then(interceptBackToCancel), 
                enabled = saveEnabled,
                focusRequester = saveFocusRequester
            )
        }
    }
}

@Composable
private fun RowScope.HubShapeItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    val scale by animateFloatAsState(if (isFocused) 1.04f else 1f)

    val bgColor = MaterialTheme.colorScheme.onBackground.copy(0.05f)
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isFocused -> MaterialTheme.colorScheme.onBackground
        else -> MaterialTheme.colorScheme.onBackground.copy(0.7f)
    }
    
    val borderColor by animateColorAsState(
        when {
            isFocused -> MaterialTheme.colorScheme.onBackground
            isSelected -> MaterialTheme.colorScheme.primary
            else -> Color.Transparent
        }
    )
    val borderWidth = if (isFocused || isSelected) 2.dp else 0.dp

    Box(
        modifier = modifier
            .weight(1f)
            .height(50.dp) // Match VoidButton height
            .scale(scale)
            .clip(RoundedCornerShape(8.dp)) // Match VoidButton radius
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 4.dp), // Minimal padding to prevent text touching edges
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label, 
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            ), 
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
