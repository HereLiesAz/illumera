package com.hereliesaz.illumera.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.hereliesaz.illumera.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hereliesaz.illumera.ui.theme.LocalRoundCorners
import com.hereliesaz.illumera.ui.util.touchClick

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ViewMoreCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    isLandscape: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val glowColor = MaterialTheme.colorScheme.primary
    val roundCorners = LocalRoundCorners.current
    
    // Shape based on user preference
    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = if (roundCorners) RoundedCornerShape(16.dp) else RectangleShape

    Box(
        modifier = modifier
            .width(if (isLandscape) 190.dp else 140.dp)
            .aspectRatio(if (isLandscape) 16f / 9f else 2f / 3f)
            .zIndex(if (isFocused) 10f else 0f)
            .graphicsLayer { clip = false }
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .touchClick(onClick = onClick)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                },
            shape = ClickableSurfaceDefaults.shape(
                shape = cardShape,
                focusedShape = focusedCardShape
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                contentColor = Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, glowColor),
                    shape = focusedCardShape
                )
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_view_more_arrow),
                    contentDescription = "View More",
                    modifier = Modifier.size(48.dp),
                    tint = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)
                )
                Text(
                    text = "View More",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    ),
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}
