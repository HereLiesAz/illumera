package com.hereliesaz.illumera.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.hereliesaz.illumera.R
import com.hereliesaz.illumera.data.model.stremio.MetaItem
import com.hereliesaz.illumera.ui.util.touchClick
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroCarousel(
    items: List<MetaItem>,
    autoScrollSeconds: Int,
    onItemClick: (MetaItem) -> Unit,
    startPadding: Dp,
    onFocusChange: (String) -> Unit,
    onCurrentItemVisible: (MetaItem) -> Unit = {},
    entryRequester: FocusRequester? = null,
    drawerRequester: FocusRequester? = null,
    isFirstItem: Boolean = false,
    isTopNav: Boolean = false,
    upKeyDebouncer: UpKeyDebouncer? = null,
    repeatGate: DpadRepeatGate? = null,
    onNavigateDown: (() -> Unit)? = null,
    restoreItemId: String? = null,
    tmdbEnabled: Boolean = false,
    tmdbEnrichedIds: Set<String> = emptySet()
) {
    if (items.isEmpty()) return

    var currentIndex by remember(items) { 
        mutableIntStateOf(
            if (restoreItemId != null) {
                val idx = items.indexOfFirst { it.id == restoreItemId }
                if (idx >= 0) idx else 0
            } else 0
        ) 
    }
    var hasFocus by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastLeftKeyTime by remember { mutableLongStateOf(0L) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val safeCurrentIndex = currentIndex.coerceIn(0, items.lastIndex)
    val currentItem = items[safeCurrentIndex]
    val accentColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(currentItem.id, currentItem.type, currentItem.logo) {
        onCurrentItemVisible(currentItem)
    }

    // If the backing list changes while preserving local state, keep index in-bounds.
    LaunchedEffect(items.size, currentIndex) {
        if (items.isNotEmpty()) {
            val clamped = currentIndex.coerceIn(0, items.lastIndex)
            if (clamped != currentIndex) currentIndex = clamped
        }
    }

    // Restore carousel position when regaining focus (e.g., returning from details screen)
    LaunchedEffect(isFocused, restoreItemId) {
        if (isFocused && restoreItemId != null) {
            val idx = items.indexOfFirst { it.id == restoreItemId }
            if (idx >= 0 && idx != currentIndex) {
                currentIndex = idx
                lastInteractionTime = System.currentTimeMillis()
            }
        }
        if (isFocused) {
            onFocusChange(items[currentIndex.coerceIn(0, items.lastIndex)].id)
        }
    }

    if (autoScrollSeconds > 0) {
        LaunchedEffect(autoScrollSeconds, hasFocus, items.size) {
            if (!hasFocus || items.size <= 1) return@LaunchedEffect
            val intervalMs = autoScrollSeconds * 1000L
            while (true) {
                delay(intervalMs)
                val timeSinceInteraction = System.currentTimeMillis() - lastInteractionTime
                if (timeSinceInteraction > intervalMs && items.isNotEmpty()) {
                    val nextIndex = (currentIndex.coerceIn(0, items.lastIndex) + 1) % items.size
                    currentIndex = nextIndex
                }
            }
        }
    }

    val upBlockModifier = if (isFirstItem) {
        Modifier.onPreviewKeyEvent {
            if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) {
                if (repeatGate?.shouldConsume(it) == true) return@onPreviewKeyEvent true
                if (isTopNav && drawerRequester != null) {
                    if (upKeyDebouncer != null) {
                        val now = System.currentTimeMillis()
                        val timeSinceLastUp = now - upKeyDebouncer.lastTime
                        upKeyDebouncer.lastTime = now
                        if (timeSinceLastUp > 300L) {
                            drawerRequester.requestFocus()
                        }
                    } else {
                        drawerRequester.requestFocus()
                    }
                }
                true
            } else false
        }
    } else Modifier

    fun goToPrevious() {
        val newIndex = if (safeCurrentIndex > 0) safeCurrentIndex - 1 else items.lastIndex
        currentIndex = newIndex
        lastInteractionTime = System.currentTimeMillis()
        onFocusChange(items[newIndex].id)
    }

    fun goToNext() {
        val newIndex = if (safeCurrentIndex < items.lastIndex) safeCurrentIndex + 1 else 0
        currentIndex = newIndex
        lastInteractionTime = System.currentTimeMillis()
        onFocusChange(items[newIndex].id)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .then(if (entryRequester != null) Modifier.focusRequester(entryRequester) else Modifier)
            .then(upBlockModifier)
            .onFocusChanged { hasFocus = it.isFocused || it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (repeatGate?.shouldConsume(event) == true) return@onPreviewKeyEvent true
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            val now = System.currentTimeMillis()
                            val timeSinceLastLeft = now - lastLeftKeyTime
                            lastLeftKeyTime = now
                            
                            if (safeCurrentIndex > 0) {
                                val newIndex = safeCurrentIndex - 1
                                currentIndex = newIndex
                                lastInteractionTime = System.currentTimeMillis()
                                onFocusChange(items[newIndex].id)
                                true
                            } else {
                                // Loop significantly improves UX
                                if (!isTopNav && drawerRequester != null && timeSinceLastLeft > 300L) {
                                    drawerRequester.requestFocus()
                                    true
                                } else {
                                    // Infinite scroll: loop to last item
                                    val newIndex = items.lastIndex
                                    currentIndex = newIndex
                                    lastInteractionTime = System.currentTimeMillis()
                                    onFocusChange(items[newIndex].id)
                                    true
                                }
                            }
                        }
                        Key.DirectionRight -> {
                            val newIndex = if (safeCurrentIndex < items.lastIndex) {
                                safeCurrentIndex + 1
                            } else {
                                // Infinite scroll: loop to first item
                                0
                            }
                            currentIndex = newIndex
                            lastInteractionTime = System.currentTimeMillis()
                            onFocusChange(items[newIndex].id)
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onItemClick(items[safeCurrentIndex])
                            true
                        }
                        Key.DirectionDown -> {
                            if (onNavigateDown != null) {
                                onNavigateDown()
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
            .focusable(interactionSource = interactionSource)
            .touchClick { onItemClick(items[safeCurrentIndex]) }
            .pointerInput(items.size) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        if (totalDrag <= -60f) goToNext() else if (totalDrag >= 60f) goToPrevious()
                    }
                )
            }
    ) {
        AnimatedContent(
            targetState = currentItem,
            transitionSpec = {
                fadeIn(tween(500)).togetherWith(fadeOut(tween(300)))
                    .using(androidx.compose.animation.SizeTransform(clip = false))
            },
            contentKey = { "${it.type}:${it.id}" },
            label = "hero_bg"
        ) { item ->
            val bgReady = !tmdbEnabled || tmdbEnrichedIds.contains("${item.type}:${item.id}")
            val imageUrl = item.background ?: item.poster
            if (imageUrl != null && bgReady) {
                val context = LocalContext.current
                val backgroundColor = androidx.compose.material3.MaterialTheme.colorScheme.background
                val request = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(false)
                        .size(1920, 1080)
                        .allowHardware(true)
                        .build()
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val bottomGradient = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.2f to backgroundColor.copy(0.05f),
                                        0.35f to backgroundColor.copy(0.15f),
                                        0.45f to backgroundColor.copy(0.25f),
                                        0.55f to backgroundColor.copy(0.38f),
                                        0.65f to backgroundColor.copy(0.52f),
                                        0.75f to backgroundColor.copy(0.68f),
                                        0.85f to backgroundColor.copy(0.82f),
                                        0.92f to backgroundColor.copy(0.92f),
                                        1.0f to backgroundColor
                                    )
                                )
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(bottomGradient)
                                }
                            }
                    )
                    com.hereliesaz.illumera.ui.components.NoiseOverlay()
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = startPadding, bottom = 40.dp, end = 40.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            AnimatedContent(
                targetState = currentItem,
                transitionSpec = { fadeIn(tween(400)).togetherWith(fadeOut(tween(200))) },
                contentKey = { "${it.type}:${it.id}" },
                label = "hero_info"
            ) { item ->
                // Hide info content while waiting for TMDB enrichment to prevent addon data flash
                val itemReady = !tmdbEnabled || tmdbEnrichedIds.contains("${item.type}:${item.id}")
                Column(modifier = Modifier.alpha(if (itemReady) 1f else 0f)) {
                    Box(
                        modifier = Modifier
                            // Was a hardcoded 600dp — overflows a phone screen outright.
                            .fillMaxWidth()
                            .height(70.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        if (!item.logo.isNullOrEmpty()) {
                            SubcomposeAsyncImage(
                                model = item.logo,
                                contentDescription = item.name,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 400.dp)
                                    .heightIn(max = 70.dp),
                                error = {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.displaySmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 32.sp
                                        ),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            )
                        } else {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 32.sp
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HeroMetaStrip(item = item)

                    if (item.description != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = item.description!!,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                letterSpacing = 0.sp,
                                lineHeight = 1.3.em
                            ),
                            color = Color.White.copy(0.75f),
                            textAlign = TextAlign.Start,
                            // Was a hardcoded 500dp — overflows a phone screen; capped
                            // fillMaxWidth preserves the original width on TV.
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 500.dp)
                                .height(60.dp) // Fixed height to prevent layout shift
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                items.forEachIndexed { index, _ ->
                    val isActive = index == safeCurrentIndex
                    val width by animateDpAsState(
                        targetValue = if (isActive) 24.dp else 8.dp,
                        label = "indicator_width"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .width(width)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isActive) accentColor
                                else Color.White.copy(0.3f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMetaStrip(item: MetaItem) {
    val typeLabel = item.type.replaceFirstChar { it.uppercase() }
    val genreLabel = item.genres
        ?.firstOrNull()
        ?.replaceFirstChar { it.uppercase() }
        ?: "Unknown"
    val yearLabel = extractHeroPrimaryYear(item.releaseInfo)
    val valueColor = Color.White.copy(alpha = 0.92f)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = typeLabel, style = textStyle, color = valueColor)
        HeroMetaDot()
        Text(text = genreLabel, style = textStyle, color = valueColor)
        HeroMetaDot()
        Text(text = yearLabel, style = textStyle, color = valueColor)

        item.runtime?.filter { it.isDigit() }?.toIntOrNull()?.let { mins ->
            HeroMetaDot()
            val hours = mins / 60
            val m = mins % 60
            val display = if (hours > 0) "${hours}h ${m}m" else "${m}m"
            Text(text = display, style = textStyle, color = valueColor)
        }

        item.imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
            HeroMetaDot()
            HeroImdbBadge()
            Text(text = rating, style = textStyle, color = valueColor)
        }
    }
}

@Composable
private fun HeroMetaDot() {
    Text(
        text = ".",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White.copy(alpha = 0.55f)
    )
}

@Composable
private fun HeroImdbBadge() {
    Image(
        painter = painterResource(id = R.drawable.imdb_logo),
        contentDescription = "IMDb",
        modifier = Modifier.height(16.dp)
    )
}

private fun extractHeroPrimaryYear(releaseInfo: String?): String {
    if (releaseInfo.isNullOrBlank()) return "----"
    return Regex("\\d{4}").find(releaseInfo)?.value ?: releaseInfo.take(4)
}
