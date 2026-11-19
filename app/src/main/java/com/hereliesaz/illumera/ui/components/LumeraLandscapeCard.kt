package com.hereliesaz.illumera.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.ui.theme.LocalRoundCorners
import com.hereliesaz.illumera.ui.util.touchClick

// Built once and shared by every card: an 8-stop gradient is not rebuilt per card/recomposition.
private val TitleScrimBrush: Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.15f to Color.Black.copy(alpha = 0.05f),
        0.3f to Color.Black.copy(alpha = 0.15f),
        0.45f to Color.Black.copy(alpha = 0.30f),
        0.6f to Color.Black.copy(alpha = 0.48f),
        0.75f to Color.Black.copy(alpha = 0.64f),
        0.88f to Color.Black.copy(alpha = 0.77f),
        1.0f to Color.Black.copy(alpha = 0.85f)
    )
)

// background(shape) paints rounded ends without the clip layer Modifier.clip would add per card.
private val ProgressBarShape = RoundedCornerShape(1.5.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LumeraLandscapeCard(
    title: String,
    backdropUrl: String?,
    logoUrl: String?,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    hasNewEpisode: Boolean = false,
    isWatched: Boolean = false,
    onFocused: (() -> Unit)? = null,
    mediaItem: MetaItem? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    var dpadLongPressTriggered by remember { mutableStateOf(false) }
    var dpadKeyHeld by remember { mutableStateOf(false) }
    var contextMenuExpanded by remember(mediaItem?.id) { mutableStateOf(false) }
    val glowColor = MaterialTheme.colorScheme.primary
    val roundCorners = LocalRoundCorners.current
    val onLongClick: (() -> Unit)? = mediaItem?.let { { contextMenuExpanded = true } }

    androidx.compose.runtime.LaunchedEffect(dpadKeyHeld) {
        if (dpadKeyHeld) {
            kotlinx.coroutines.delay(1_000L)
            if (dpadKeyHeld && !dpadLongPressTriggered) {
                dpadLongPressTriggered = true
                onLongClick?.invoke()
            }
        }
    }

    val cardShape = if (roundCorners) RoundedCornerShape(12.dp) else RectangleShape
    val focusedCardShape = if (roundCorners) RoundedCornerShape(16.dp) else RectangleShape

    Box(
        modifier = modifier
            .width(190.dp)
            .aspectRatio(16f / 9f)
            .zIndex(if (isFocused || contextMenuExpanded) 10f else 0f)
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .touchClick(onClick = onClick, onLongClick = onLongClick)
                .onPreviewKeyEvent { event ->
                    if (onLongClick == null) return@onPreviewKeyEvent false
                    val native = event.nativeKeyEvent
                    val activation = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_BUTTON_A
                    if (!activation) return@onPreviewKeyEvent false
                    when (native.action) {
                        KeyEvent.ACTION_DOWN -> {
                            // Track the hold; the one-second timer opens the menu independently of key repeats.
                            dpadKeyHeld = true
                            dpadLongPressTriggered
                        }
                        KeyEvent.ACTION_UP -> {
                            dpadKeyHeld = false
                            if (dpadLongPressTriggered) {
                                // The menu already opened while held; the release is not a click.
                                dpadLongPressTriggered = false
                                true
                            } else false
                        }
                        else -> false
                    }
                }
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (!it.isFocused) {
                        dpadKeyHeld = false
                        if (!contextMenuExpanded) dpadLongPressTriggered = false
                    }
                    if (it.isFocused) onFocused?.invoke()
                },
            shape = ClickableSurfaceDefaults.shape(
                shape = cardShape,
                focusedShape = focusedCardShape
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color.White
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, glowColor),
                    shape = focusedCardShape
                )
            )
        ) {
            val context = LocalContext.current
            val imageUrl = backdropUrl?.takeIf { it.isNotBlank() }
                ?: posterUrl?.takeIf { it.isNotBlank() }
            val imageRequest = remember(imageUrl) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .scale(Scale.FILL)
                    .size(380, 214)
                    .allowHardware(true)
                    .build()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    // No .clip/.background here: Surface already clips its content to its
                    // shape and paints the same surface colour behind it.
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .align(Alignment.BottomStart)
                        .background(TitleScrimBrush)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = if (progress > 0f) 12.dp else 6.dp)
                ) {
                    // A failed logo load falls back to the title text. Plain AsyncImage plus
                    // an error flag replaces SubcomposeAsyncImage, which sets up a subcomposition per card.
                    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                    if (!logoUrl.isNullOrEmpty() && !logoFailed) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier.widthIn(max = 130.dp).heightIn(max = 35.dp),
                            onError = { logoFailed = true }
                        )
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isWatched) {
                    Box(
                        modifier = Modifier.align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "✓", color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (hasNewEpisode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        androidx.compose.material3.Text(
                            "+1",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .height(3.dp)
                            .background(Color.White.copy(0.3f), ProgressBarShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary, ProgressBarShape)
                        )
                    }
                }
            }
        }

        if (mediaItem != null) {
            // Composed (and its ViewModel/Regex/effect set up) only once the menu is opened.
            LazyMediaCardActionMenu(
                target = MediaActionTarget.fromMeta(mediaItem),
                expanded = contextMenuExpanded,
                focusable = !dpadKeyHeld,
                onDismissRequest = { if (!dpadKeyHeld) contextMenuExpanded = false }
            )
        }
    }
}
