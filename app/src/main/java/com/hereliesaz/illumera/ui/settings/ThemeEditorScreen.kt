package com.hereliesaz.illumera.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.illumera.data.model.ThemeEntity
import com.hereliesaz.illumera.ui.theme.DefaultThemes
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// HSL to RGB conversion helpers
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
    val m = l - c / 2

    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f)
    )
}

private fun colorToHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2

    if (max == min) return Triple(0f, 0f, l)

    val d = max - min
    // Safety check to prevent division by zero
    val denominator = if (l > 0.5f) (2 - max - min) else (max + min)
    val s = if (denominator <= 0f) 0f else (d / denominator).coerceIn(0f, 1f)

    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6 else 0)) * 60
        g -> ((b - r) / d + 2) * 60
        else -> ((r - g) / d + 4) * 60
    }

    return Triple(h.coerceIn(0f, 360f), s, l.coerceIn(0f, 1f))
}

private fun colorToLong(color: Color): Long {
    val a = 0xFF
    val r = (color.red * 255).roundToInt()
    val g = (color.green * 255).roundToInt()
    val b = (color.blue * 255).roundToInt()
    return (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

enum class ColorSlot { PRIMARY, BACKGROUND }

@Composable
fun ThemeEditorScreen(
    editingTheme: ThemeEntity?,
    onSave: (name: String, primary: Long, background: Long) -> Unit,
    onCancel: () -> Unit
) {
    val isEditing = editingTheme != null
    
    // Initialize with editing theme or defaults
    var themeName by remember { 
        mutableStateOf(editingTheme?.name ?: "My Theme") 
    }
    
    val initialPrimary = editingTheme?.let { Color(it.primaryColor.toInt()) }
        ?: Color(DefaultThemes.VOID.primaryColor.toInt())
    val initialBackground = editingTheme?.let { Color(it.backgroundColor.toInt()) }
        ?: Color(DefaultThemes.VOID.backgroundColor.toInt())

    var primaryColor by remember { mutableStateOf(initialPrimary) }
    var backgroundColor by remember { mutableStateOf(initialBackground) }
    
    var selectedSlot by remember { mutableStateOf(ColorSlot.PRIMARY) }
    
    // HSL values for current selected color
    val currentColor = when (selectedSlot) {
        ColorSlot.PRIMARY -> primaryColor
        ColorSlot.BACKGROUND -> backgroundColor
    }
    
    val (h, s, l) = remember(selectedSlot) { colorToHsl(currentColor) }
    var hue by remember(selectedSlot) { mutableStateOf(h) }
    var saturation by remember(selectedSlot) { mutableStateOf(s) }
    var lightness by remember(selectedSlot) { mutableStateOf(l) }
    
    // Update color when HSL changes
    val newColor = hslToColor(hue, saturation, lightness)
    LaunchedEffect(newColor) {
        when (selectedSlot) {
            ColorSlot.PRIMARY -> primaryColor = newColor
            ColorSlot.BACKGROUND -> backgroundColor = newColor
        }
    }
    
    // Focus management
    val nameInputFocus = remember { FocusRequester() }
    val primarySlotFocus = remember { FocusRequester() }
    val backgroundSlotFocus = remember { FocusRequester() }
    val saveButtonFocus = remember { FocusRequester() }
    val cancelButtonFocus = remember { FocusRequester() }
    var isCancelFocused by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler {
        if (isCancelFocused) {
            onCancel()
        } else {
            cancelButtonFocus.requestFocus()
        }
    }
    
    LaunchedEffect(Unit) {
        primarySlotFocus.requestFocus()
    }

    // Block focus escape upwards
    val upBlockModifier = Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
            true // Consume event
        } else false
    }

    // Below 600dp the 0.4/0.6 side-by-side split leaves each panel too cramped for its
    // content (a live theme preview on one side, sliders on the other) — stack them
    // instead, same treatment as the Stremio connect dialog's form/QR split.
    val isCompactThemeEditor = LocalConfiguration.current.screenWidthDp < 600

    val leftPanel: @Composable () -> Unit = {
        // Left panel: Preview. Uses fillMaxWidth(fraction) rather than RowScope.weight
        // since this content is shared between a Row (wide) and a Column (narrow) below.
        Column(
            modifier = Modifier
                .then(if (isCompactThemeEditor) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.4f))
                .then(if (isCompactThemeEditor) Modifier.height(240.dp) else Modifier.fillMaxHeight())
                .padding(end = if (isCompactThemeEditor) 0.dp else 24.dp)
        ) {
            Text(
                text = if (isEditing) "Edit Theme" else "Create Theme",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Theme name input
            ThemeNameInput(
                value = themeName,
                onValueChange = { themeName = it },
                focusRequester = nameInputFocus,
                modifier = upBlockModifier.onPreviewKeyEvent {
                    if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
                        true
                    } else false
                }
            )

            Spacer(Modifier.height(16.dp))

            // Live preview
            ThemePreview(
                primaryColor = primaryColor,
                backgroundColor = backgroundColor,
                modifier = Modifier.weight(1f)
            )
        }
    }

    val rightPanel: @Composable () -> Unit = {
        // Right panel: Color editing
        Column(
            modifier = Modifier
                .then(if (isCompactThemeEditor) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.6f))
                .then(if (isCompactThemeEditor) Modifier else Modifier.fillMaxHeight())
        ) {
            Text(
                text = "Colors",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Color slot selection - equal width boxes
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ColorSlotButton(
                    label = "Primary",
                    color = primaryColor,
                    isSelected = selectedSlot == ColorSlot.PRIMARY,
                    focusRequester = primarySlotFocus,
                    onClick = { selectedSlot = ColorSlot.PRIMARY },
                    onLeftPress = { nameInputFocus.requestFocus() },
                    modifier = Modifier.weight(1f).then(upBlockModifier)
                )
                ColorSlotButton(
                    label = "Background",
                    color = backgroundColor,
                    isSelected = selectedSlot == ColorSlot.BACKGROUND,
                    focusRequester = backgroundSlotFocus,
                    onClick = { selectedSlot = ColorSlot.BACKGROUND },
                    modifier = Modifier.weight(1f).then(upBlockModifier)
                )
            }
            
            Spacer(Modifier.height(24.dp))
            
            // HSL Sliders
            Text(
                text = "Adjust ${selectedSlot.name.lowercase().replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            HslSlider(
                label = "Hue",
                value = hue,
                maxValue = 360f,
                onValueChange = { hue = it },
                gradientColors = (0..360 step 30).map { hslToColor(it.toFloat(), 1f, 0.5f) },
                onUp = {
                    when (selectedSlot) {
                        ColorSlot.PRIMARY -> primarySlotFocus.requestFocus()
                        ColorSlot.BACKGROUND -> backgroundSlotFocus.requestFocus()
                    }
                    true
                }
            )
            
            Spacer(Modifier.height(16.dp))
            
            HslSlider(
                label = "Saturation",
                value = saturation,
                maxValue = 1f,
                onValueChange = { saturation = it },
                gradientColors = listOf(
                    hslToColor(hue, 0f, lightness),
                    hslToColor(hue, 1f, lightness)
                )
            )
            
            Spacer(Modifier.height(16.dp))
            
            HslSlider(
                label = "Lightness",
                value = lightness,
                maxValue = 1f,
                onValueChange = { lightness = it },
                gradientColors = listOf(
                    Color.Black,
                    hslToColor(hue, saturation, 0.5f),
                    Color.White
                ),
                onDown = { saveButtonFocus.requestFocus(); true }
            )
            
            Spacer(Modifier.height(24.dp))
            
            // Save/Cancel buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                EditorButton(
                    text = "Cancel",
                    isPrimary = false,
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(cancelButtonFocus)
                        .onFocusChanged { isCancelFocused = it.isFocused }
                        .onPreviewKeyEvent {
                            if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
                                true
                            } else false
                        }
                )
                EditorButton(
                    text = "Save",
                    isPrimary = true,
                    onClick = {
                        onSave(
                            themeName,
                            colorToLong(primaryColor),
                            colorToLong(backgroundColor)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(saveButtonFocus)
                )
            }
        }
    }

    if (isCompactThemeEditor) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            leftPanel()
            Spacer(Modifier.height(24.dp))
            rightPanel()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
        ) {
            leftPanel()
            rightPanel()
        }
    }
}

@Composable
private fun ThemeNameInput(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.1f))
            .border(
                2.dp,
                if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(0.2f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Medium
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
        )
        
        if (value.isEmpty()) {
            Text(
                text = "Theme Name",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun ThemePreview(
    primaryColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            // Fake header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(60.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(0.5f))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White.copy(0.3f)))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White.copy(0.3f)))
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Featured poster mockup
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(0.1f))
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    Box(
                        Modifier
                            .width(80.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(0.7f))
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .width(50.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(primaryColor)
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Content row mockup
            Box(
                Modifier
                    .width(50.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(0.4f))
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(4) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(0.15f))
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSlotButton(
    label: String,
    color: Color,
    isSelected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onLeftPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f)
    
    val borderColor by animateColorAsState(
        when {
            isFocused -> Color.White
            isSelected -> color
            else -> Color.White.copy(0.3f)
        }
    )
    
    val focusModifier = if (focusRequester != null) 
        Modifier.focusRequester(focusRequester) else Modifier
    
    val keyModifier = if (onLeftPress != null) Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
            onLeftPress()
            true
        } else false
    } else Modifier

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) color.copy(0.2f) else Color.White.copy(0.05f))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .then(keyModifier)
            .then(focusModifier)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
            .padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, Color.White.copy(0.3f), CircleShape)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (isFocused || isSelected) Color.White else Color.Gray,
            maxLines = 1
        )
    }
}

@Composable
private fun HslSlider(
    label: String,
    value: Float,
    maxValue: Float,
    onValueChange: (Float) -> Unit,
    gradientColors: List<Color>,
    onLeftAtZero: (() -> Unit)? = null,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    // D-pad control
    val step = maxValue / 100f

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused) Color.White else Color.Gray
            )
            Text(
                text = if (maxValue > 1) "${value.roundToInt()}°" else "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused) Color.White else Color.Gray
            )
        }
        
        Spacer(Modifier.height(6.dp))
        
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.horizontalGradient(gradientColors))
                .border(
                    2.dp,
                    if (isFocused) Color.White else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (onUp != null) onUp() else false
                            }
                            Key.DirectionDown -> {
                                if (onDown != null) onDown() else false
                            }
                            Key.DirectionLeft -> {
                                if (value <= step && onLeftAtZero != null) {
                                    // At zero or near zero, go to name input
                                    onLeftAtZero()
                                } else {
                                    onValueChange((value - step).coerceAtLeast(0f))
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                onValueChange((value + step).coerceAtMost(maxValue))
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .pointerInput(maxValue) {
                    detectTapGestures { offset ->
                        val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(ratio * maxValue)
                    }
                }
                .pointerInput(maxValue) {
                    detectHorizontalDragGestures { change, _ ->
                        val ratio = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onValueChange(ratio * maxValue)
                        change.consume()
                    }
                }
                .focusable(interactionSource = interactionSource)
        ) {
            val thumbSize = 22.dp
            val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumbSize.toPx() }
            val maxPx = constraints.maxWidth.toFloat()
            
            // Symmetric sliding: Center the thumb exactly on the progress point
            // At 0: Center is at 0 (Left half clipped, Right half visible -> Vertical line/D-shape)
            // At 1: Center is at Max (Right half clipped, Left half visible -> Vertical line/Reverse D-shape)
            val progress = value / maxValue
            val offsetPx = (progress * maxPx) - (thumbPx / 2)
            
            val offsetDp = with(androidx.compose.ui.platform.LocalDensity.current) { offsetPx.toDp() }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = offsetDp)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, if (isFocused) Color.Black else Color.Gray, CircleShape)
            )
        }
    }
}

@Composable
private fun EditorButton(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f)
    val accentColor = MaterialTheme.colorScheme.primary
    
    val bgColor = Color.White.copy(0.08f)
    
    val borderColor = when {
        isFocused -> accentColor
        isPrimary -> accentColor.copy(0.75f)
        else -> Color.White.copy(0.2f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .scale(scale)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = when {
                isFocused -> accentColor
                isPrimary -> accentColor.copy(alpha = 0.95f)
                else -> Color.White
            }
        )
    }
}
