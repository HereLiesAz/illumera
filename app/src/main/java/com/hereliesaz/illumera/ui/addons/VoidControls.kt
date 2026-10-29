package com.hereliesaz.illumera.ui.addons

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hereliesaz.illumera.ui.audio.Dialog
import com.hereliesaz.illumera.ui.components.ButtonEmphasis
import com.hereliesaz.illumera.ui.components.buttonColors
import com.hereliesaz.illumera.ui.util.rememberDialogWidth
import com.hereliesaz.illumera.ui.util.rememberIsTvDevice
import androidx.compose.runtime.LaunchedEffect

@Composable
fun VoidInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    compact: Boolean = false
) {
    val isTv = rememberIsTvDevice()
    val shellInteractionSource = remember { MutableInteractionSource() }
    val shellFocused by shellInteractionSource.collectIsFocusedAsState()
    val shellFocusRequester = remember { FocusRequester() }
    val inputFocusRequester = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val isFocused = shellFocused || inputFocused

    LaunchedEffect(editing) {
        if (isTv && editing) {
            kotlinx.coroutines.delay(50)
            runCatching { inputFocusRequester.requestFocus() }
        }
    }

    val borderBrush = if (isFocused) {
        Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary))
    } else {
        SolidColor(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
    }

    Box(
        modifier = modifier
            .height(if (compact) 36.dp else 50.dp)
            .then(if (isTv) Modifier.focusRequester(shellFocusRequester) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(if (isFocused) 2.dp else 1.dp, borderBrush, RoundedCornerShape(8.dp))
            .then(
                if (isTv) {
                    Modifier
                        .clickable(interactionSource = shellInteractionSource, indication = null) { editing = true }
                        .focusable(interactionSource = shellInteractionSource)
                } else Modifier
            )
            .padding(horizontal = if (compact) 10.dp else 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                if (isTv && shellFocused && !editing) "$placeholder · press OK to type" else placeholder,
                color = Color.Gray
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = !isTv || editing,
            textStyle = (if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (isTv) {
                        editing = false
                        runCatching { shellFocusRequester.requestFocus() }
                    }
                    onDone?.invoke()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputFocusRequester)
                .onFocusChanged {
                    inputFocused = it.isFocused
                    if (isTv && !it.isFocused) editing = false
                }
        )
    }
}

@Composable
fun VoidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused && enabled) 1.05f else 1f, label = "VoidButtonScale")

    val colors = buttonColors(
        focused = isFocused,
        emphasis = when {
            isDestructive -> ButtonEmphasis.Destructive
            isPrimary -> ButtonEmphasis.Primary
            else -> ButtonEmphasis.Normal
        },
        enabled = enabled
    )

    Box(
        modifier = modifier
            .height(if (compact) 36.dp else 50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.container)
            .border(colors.borderWidth, colors.border, RoundedCornerShape(8.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = interactionSource, indication = null) { onClick() }
                } else {
                    Modifier
                }
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (enabled) Modifier.focusable(interactionSource = interactionSource) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            style = (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium).copy(fontWeight = FontWeight.Bold),
            color = colors.content
        )
    }
}

@Composable
fun VoidToggleRow(
    label: String,
    isChecked: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bgColor = if (isFocused) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f) else Color.Transparent
    val iconColor = if (isChecked) MaterialTheme.colorScheme.primary else Color.Gray

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null) { onToggle() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground)
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, iconColor, RoundedCornerShape(4.dp))
                .background(if (isChecked) iconColor else Color.Transparent, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun VoidDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = modifier
                .width(rememberDialogWidth(400))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(24.dp))
                content()
            }
        }
    }
}

@Composable
fun VoidIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused && enabled) 1.1f else 1f, label = "VoidIconButtonScale")
    val colors = buttonColors(
        focused = isFocused,
        emphasis = if (isActive) ButtonEmphasis.Primary else ButtonEmphasis.Normal,
        enabled = enabled
    )

    Box(
        modifier = modifier
            .size(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.container)
            .border(colors.borderWidth, colors.border, RoundedCornerShape(8.dp))
            .then(
                if (enabled) {
                    Modifier
                        .clickable(interactionSource = interactionSource, indication = null) { onClick() }
                        .focusable(interactionSource = interactionSource)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.content,
            modifier = Modifier.size(24.dp)
        )
    }
}
