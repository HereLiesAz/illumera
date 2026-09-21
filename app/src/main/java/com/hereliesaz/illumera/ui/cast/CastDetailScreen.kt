package com.hereliesaz.illumera.ui.cast

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import com.hereliesaz.illumera.ui.theme.LocalRoundCorners
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.hereliesaz.illumera.data.tmdb.TmdbMetaPreview
import com.hereliesaz.illumera.data.tmdb.TmdbPersonDetail
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.hereliesaz.illumera.ui.home.DpadRepeatGate
import com.hereliesaz.illumera.ui.home.FocusPivotSpec

@Composable
fun CastDetailScreen(
    personId: Int,
    personName: String,
    onBackPress: () -> Unit = {},
    onNavigateToDetails: (type: String, id: String) -> Unit = { _, _ -> },
    viewModel: CastDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.state.collectAsState()
    val bg = MaterialTheme.colorScheme.background
    val accentColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground

    androidx.activity.compose.BackHandler { onBackPress() }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        when (val state = uiState) {
            is CastDetailState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = accentColor
                )
            }
            is CastDetailState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = state.message,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.retry() }) {
                        Text("Retry")
                    }
                }
            }
            is CastDetailState.Success -> {
                val restoreFocusRequester = remember { FocusRequester() }
                val initialFocusRequester = remember { FocusRequester() }
                var restoreIndex by rememberSaveable { mutableStateOf(-1) }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (restoreIndex >= 0) {
                        kotlinx.coroutines.delay(300)
                        runCatching { restoreFocusRequester.requestFocus() }
                        restoreIndex = -1
                    } else {
                        runCatching { initialFocusRequester.requestFocus() }
                    }
                }

                CastDetailContent(
                    state.person, bg, accentColor, textColor,
                    onNavigateToDetails = { type, id, index ->
                        restoreIndex = index
                        onNavigateToDetails(type, id)
                    },
                    restoreIndex = restoreIndex,
                    restoreFocusRequester = restoreFocusRequester,
                    initialFocusRequester = initialFocusRequester
                )
            }
        }
    }
}

@Composable
private fun CastDetailContent(
    person: TmdbPersonDetail,
    bg: Color,
    accentColor: Color,
    textColor: Color,
    onNavigateToDetails: (String, String, Int) -> Unit,
    restoreIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    initialFocusRequester: FocusRequester? = null
) {
    // Background photo
    if (person.profilePhoto != null) {
        AsyncImage(
            model = person.profilePhoto,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.15f)
        )
    }

    // Gradient overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to bg,
                        0.15f to bg.copy(alpha = 0.95f),
                        0.3f to bg.copy(alpha = 0.8f),
                        0.5f to bg.copy(alpha = 0.5f),
                        1.0f to Color.Transparent
                    )
                )
            )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
    ) {
        // Hero: photo + bio side by side
        Box(modifier = Modifier.padding(start = 48.dp, end = 48.dp)) {
            HeroSection(person, accentColor, textColor)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Filmography
        val allCredits = remember(person) {
            (person.movieCredits + person.tvCredits)
                .distinctBy { it.tmdbId }
                .sortedByDescending { it.releaseInfo }
        }

        if (allCredits.isNotEmpty()) {
            FilmographySection(allCredits, accentColor, textColor, onNavigateToDetails, restoreIndex, restoreFocusRequester, initialFocusRequester)
        }
    }
}

@Composable
private fun HeroSection(person: TmdbPersonDetail, accentColor: Color, textColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Profile photo
        val roundCorners = LocalRoundCorners.current
        val photoShape = if (roundCorners) RoundedCornerShape(16.dp) else RectangleShape
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(240.dp)
                .clip(photoShape)
                .background(Color.White.copy(0.08f))
                .border(1.dp, Color.White.copy(0.1f), photoShape)
        ) {
            if (person.profilePhoto != null) {
                AsyncImage(
                    model = person.profilePhoto,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(photoShape)
                )
            } else {
                Text(
                    text = person.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displayMedium,
                    color = textColor.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Bio info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            person.knownFor?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentColor
                )
            }

            val birthInfo = buildBirthInfo(person)
            if (birthInfo.isNotEmpty()) {
                Text(
                    text = birthInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.6f)
                )
            }

            person.biography?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.8f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilmographySection(
    credits: List<TmdbMetaPreview>,
    accentColor: Color,
    textColor: Color,
    onNavigateToDetails: (String, String, Int) -> Unit,
    restoreIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    initialFocusRequester: FocusRequester? = null
) {
    val density = LocalDensity.current
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L) }
    val startPad = 48.dp
    val paddingPx = remember(density) { with(density) { startPad.toPx() } }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val endPadding = (screenWidth - startPad - 120.dp).coerceAtLeast(120.dp)

    val pivotSpec = remember(paddingPx) {
        FocusPivotSpec(
            customOffset = paddingPx,
            stiffnessProvider = { Spring.StiffnessLow }
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 48.dp)
    ) {
        Text(
            text = "Filmography",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor.copy(alpha = 0.9f)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "${credits.size}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = textColor.copy(alpha = 0.6f),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(0.1f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
        LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = startPad, end = endPadding)
        ) {
            itemsIndexed(credits, key = { i, it -> "${it.tmdbId}_$i" }) { index, item ->
                Box(modifier = Modifier.onPreviewKeyEvent {
                    if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0) true else false
                }) {
                    FilmographyCard(
                        item, accentColor, textColor,
                        modifier = when {
                            restoreFocusRequester != null && index == restoreIndex -> Modifier.focusRequester(restoreFocusRequester)
                            initialFocusRequester != null && index == 0 -> Modifier.focusRequester(initialFocusRequester)
                            else -> Modifier
                        }
                    ) {
                        val stremioType = if (item.type == "tv") "series" else item.type
                        onNavigateToDetails(stremioType, "tmdb:${item.tmdbId}", index)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmographyCard(
    item: TmdbMetaPreview,
    accentColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val roundCorners = LocalRoundCorners.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val cardShape = if (roundCorners) RoundedCornerShape(if (isFocused) 16.dp else 12.dp) else RectangleShape
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f, label = "filmScale")

    Box(
        modifier = modifier
            .width(120.dp)
            .height(180.dp)
            .scale(scale)
            .clip(cardShape)
            .background(Color.White.copy(0.06f))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) accentColor else Color.Transparent,
                shape = cardShape
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .focusable(interactionSource = interactionSource)
    ) {
        if (item.poster != null) {
            AsyncImage(
                model = item.poster,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(cardShape)
            )
        } else {
            Text(
                text = item.name,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp)
            )
        }
    }
}

private fun buildBirthInfo(person: TmdbPersonDetail): String {
    val parts = mutableListOf<String>()
    person.birthday?.let { bday ->
        val age = calculateAge(bday, person.deathday)
        if (person.deathday != null) {
            parts.add("Born $bday — Died ${person.deathday}" + if (age != null) " (age $age)" else "")
        } else {
            parts.add("Born $bday" + if (age != null) " (age $age)" else "")
        }
    }
    person.placeOfBirth?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

private fun calculateAge(birthday: String, deathday: String?): Int? {
    return try {
        val parts = birthday.split("-")
        if (parts.size != 3) return null
        val birthYear = parts[0].toInt()
        val birthMonth = parts[1].toInt()
        val birthDay = parts[2].toInt()

        val endDate = if (deathday != null) {
            val dp = deathday.split("-")
            if (dp.size != 3) return null
            Triple(dp[0].toInt(), dp[1].toInt(), dp[2].toInt())
        } else {
            val cal = java.util.Calendar.getInstance()
            Triple(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        }

        var age = endDate.first - birthYear
        if (endDate.second < birthMonth || (endDate.second == birthMonth && endDate.third < birthDay)) {
            age--
        }
        if (age >= 0) age else null
    } catch (_: Exception) { null }
}
