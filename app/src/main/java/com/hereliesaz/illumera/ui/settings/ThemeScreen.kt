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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.illumera.data.model.ProfileEntity
import com.hereliesaz.illumera.data.model.ThemeEntity
import com.hereliesaz.illumera.ui.addons.VoidButton
import com.hereliesaz.illumera.ui.addons.VoidDialog
import com.hereliesaz.illumera.ui.theme.ThemeManager
import kotlinx.coroutines.delay

@Composable
fun ThemeScreen(
    currentProfile: ProfileEntity?,
    themeManager: ThemeManager = hiltViewModel(),
    onBack: () -> Unit,
    onCreateCustom: () -> Unit,
    onEditTheme: (ThemeEntity) -> Unit,
    isTopNav: Boolean = false
) {
    if (currentProfile == null) return

    val availableThemes by themeManager.availableThemes.collectAsState()
    val currentTheme by themeManager.currentTheme.collectAsState()

    var selectedTab by remember { mutableStateOf("all") }
    var focusedTab by remember { mutableStateOf("all") }
    var isAnyTabFocused by remember { mutableStateOf(false) }

    // Dialog states
    var themeToManage by remember { mutableStateOf<ThemeEntity?>(null) }
    var themeToDelete by remember { mutableStateOf<ThemeEntity?>(null) }

    // Tab focus requesters
    val allTabReq = remember { FocusRequester() }
    val darkTabReq = remember { FocusRequester() }
    val colorfulTabReq = remember { FocusRequester() }
    val customTabReq = remember { FocusRequester() }
    val firstCardReq = remember { FocusRequester() }

    // SHARED: Go back on Left Arrow (only for leftmost items)
    val goBackModifier = Modifier.onPreviewKeyEvent {
        if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) {
            onBack()
            true
        } else false
    }

    // Filter themes by selected tab
    val filteredThemes = remember(availableThemes, selectedTab) {
        when (selectedTab) {
            "all" -> availableThemes
            "custom" -> availableThemes.filter { !it.isBuiltIn }
            else -> availableThemes.filter { it.category == selectedTab }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Text(
            "Theme",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
            color = Color.White
        )
        Text(
            "Choose a color scheme for your experience.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Color.White.copy(0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Block Up navigation when top nav is active
        val upBlockModifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
                true // Consume the event to block focus escape
            } else false
        }

        // --- TABS (Same style as Home Screen Editor) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isAnyTabFocused = it.hasFocus },
            horizontalArrangement = Arrangement.Center
        ) {
            VoidTabBtn(
                label = "All Themes",
                isSelected = selectedTab == "all",
                isTabRowFocused = isAnyTabFocused,
                onClick = { selectedTab = "all" },
                modifier = Modifier
                    .width(110.dp)
                    .focusRequester(allTabReq)
                    .onFocusChanged { if (it.isFocused) focusedTab = "all" }
                    .then(goBackModifier) // Only first tab goes back
                    .then(upBlockModifier)
            )

            VoidTabBtn(
                label = "Dark",
                isSelected = selectedTab == "dark",
                isTabRowFocused = isAnyTabFocused,
                onClick = { selectedTab = "dark" },
                modifier = Modifier
                    .width(80.dp)
                    .focusRequester(darkTabReq)
                    .onFocusChanged { if (it.isFocused) focusedTab = "dark" }
                    .then(upBlockModifier)
            )

            VoidTabBtn(
                label = "Colorful",
                isSelected = selectedTab == "colorful",
                isTabRowFocused = isAnyTabFocused,
                onClick = { selectedTab = "colorful" },
                modifier = Modifier
                    .width(100.dp)
                    .focusRequester(colorfulTabReq)
                    .onFocusChanged { if (it.isFocused) focusedTab = "colorful" }
                    .then(upBlockModifier)
            )

            VoidTabBtn(
                label = "My Themes",
                isSelected = selectedTab == "custom",
                isTabRowFocused = isAnyTabFocused,
                onClick = { selectedTab = "custom" },
                modifier = Modifier
                    .width(110.dp)
                    .focusRequester(customTabReq)
                    .onFocusChanged { if (it.isFocused) focusedTab = "custom" }
                    .then(upBlockModifier)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- ADD BUTTON ---
        ThemeAddButton(
            onClick = onCreateCustom,
            modifier = Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) {
                    when (it.key) {
                        Key.DirectionLeft -> { onBack(); true }
                        Key.DirectionUp -> {
                            // Smart jump to active tab
                            when (selectedTab) {
                                "all" -> allTabReq.requestFocus()
                                "dark" -> darkTabReq.requestFocus()
                                "colorful" -> colorfulTabReq.requestFocus()
                                "custom" -> customTabReq.requestFocus()
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- THEME GRID ---
        // 4 fixed columns crush each ThemeCard's color-preview row (two 24dp circles +
        // gap) below its content width on a phone screen; drop to 2 columns there. The
        // column count feeds the left-edge (D-pad back) check below so it stays correct.
        val isCompactTheme = LocalConfiguration.current.screenWidthDp < 600
        val themeColumnCount = if (isCompactTheme) 2 else 4
        LazyVerticalGrid(
            columns = GridCells.Fixed(themeColumnCount),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 50.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredThemes, key = { it.id }) { theme ->
                val isFirst = filteredThemes.indexOf(theme) == 0
                // Only first column items should go back on Left
                val isLeftEdge = filteredThemes.indexOf(theme) % themeColumnCount == 0
                
                ThemeCard(
                    theme = theme,
                    isSelected = currentTheme.id == theme.id,
                    focusRequester = if (isFirst) firstCardReq else null,
                    modifier = if (isLeftEdge) goBackModifier else Modifier,
                    onClick = {
                        themeManager.selectTheme(currentProfile.id, theme.id)
                    },
                    onPenClick = if (!theme.isBuiltIn) {{ themeToManage = theme }} else null
                )
            }
        }
    }

    // --- MANAGE THEME DIALOG ---
    if (themeToManage != null) {
        val theme = themeToManage!!
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(
            onDismissRequest = { themeToManage = null },
            title = "Manage Theme"
        ) {
            Text(
                theme.name,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(24.dp))

            VoidButton(
                text = "Edit",
                onClick = {
                    onEditTheme(theme)
                    themeToManage = null
                },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = focusRequester
            )

            Spacer(Modifier.height(12.dp))

            VoidButton(
                text = "Delete",
                onClick = {
                    themeToDelete = theme
                    themeToManage = null
                },
                modifier = Modifier.fillMaxWidth(),
                isDestructive = true
            )

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                VoidButton(text = "Close", onClick = { themeToManage = null }, modifier = Modifier.width(120.dp))
            }
        }
    }

    // --- DELETE CONFIRMATION DIALOG ---
    if (themeToDelete != null) {
        val theme = themeToDelete!!
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusRequester.requestFocus() }

        VoidDialog(
            onDismissRequest = { themeToDelete = null },
            title = "Delete Theme?"
        ) {
            Text(
                "Are you sure you want to delete \"${theme.name}\"?",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VoidButton(
                    text = "No",
                    onClick = { themeToDelete = null },
                    modifier = Modifier.weight(1f),
                    focusRequester = focusRequester
                )
                VoidButton(
                    text = "Yes",
                    onClick = {
                        themeManager.deleteCustomTheme(theme.id)
                        themeToDelete = null
                        onBack() // Focus back to Sidebar as requested
                    },
                    modifier = Modifier.weight(1f),
                    isDestructive = true
                )
            }
        }
    }
}

@Composable
private fun ThemeAddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1f)
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(0.05f))
            .border(
                if (isFocused) 2.dp else 1.dp,
                if (isFocused) accentColor else Color.White.copy(0.2f),
                RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = accentColor)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "Create Custom Theme",
            color = if (isFocused) accentColor else Color.White.copy(0.8f),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ThemeCard(
    theme: ThemeEntity,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPenClick: (() -> Unit)?
) {
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardFocused by cardInteractionSource.collectIsFocusedAsState()
    
    val penInteractionSource = remember { MutableInteractionSource() }
    val isPenFocused by penInteractionSource.collectIsFocusedAsState()
    
    // Pen is visible when either card or pen is focused (only for custom themes)
    val isPenVisible = (isCardFocused || isPenFocused) && onPenClick != null
    
    val scale by animateFloatAsState(if (isCardFocused) 1.05f else 1f)
    
    val borderColor by animateColorAsState(
        when {
            isCardFocused || isPenFocused -> Color.White
            isSelected -> Color(theme.primaryColor.toInt())
            else -> Color.Transparent
        }
    )

    val focusModifier = if (focusRequester != null) 
        Modifier.focusRequester(focusRequester) else Modifier

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.scale(scale)
    ) {
        // Theme Card
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(0.08f))
                .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                .then(modifier)
                .then(focusModifier)
                .clickable(interactionSource = cardInteractionSource, indication = null) { onClick() }
                .focusable(interactionSource = cardInteractionSource)
                .padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = theme.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))

            // Color preview circles
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorPreviewCircle(Color(theme.primaryColor.toInt()))
                ColorPreviewCircle(Color(theme.backgroundColor.toInt()))
            }
        }
        
        // Pen icon for custom themes (like ProfileCard)
        if (onPenClick != null) {
            Spacer(Modifier.height(8.dp))
            
            val penScale by animateFloatAsState(if (isPenFocused) 1.2f else 1f)
            val penAlpha by animateFloatAsState(if (isPenVisible) 1f else 0f)
            
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .scale(penScale)
                    .alpha(penAlpha)
                    .clip(CircleShape)
                    .background(if (isPenFocused) Color.White else Color.Transparent)
                    .clickable(
                        interactionSource = penInteractionSource,
                        indication = null,
                        onClick = onPenClick
                    )
                    .focusable(interactionSource = penInteractionSource),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit Theme",
                    tint = if (isPenFocused) Color.Black else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            // Invisible placeholder to maintain consistent spacing
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun ColorPreviewCircle(color: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(0.3f), CircleShape)
    )
}
