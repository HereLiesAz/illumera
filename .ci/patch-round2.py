from pathlib import Path
import re


def read(path):
    return Path(path).read_text()


def write(path, text):
    Path(path).write_text(text)


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected 1 match, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def replace_all(path, old, new, expected_min=1):
    text = read(path)
    count = text.count(old)
    if count < expected_min:
        raise RuntimeError(f"{path}: expected >= {expected_min} matches, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new))


def regex_replace_once(path, pattern, replacement):
    text = read(path)
    new, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{path}: regex expected 1 match, found {count}: {pattern[:120]!r}")
    write(path, new)


# ---------------------------------------------------------------------------
# Trakt collection/library support + batched show watched/unwatched helper.
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/data/trakt/TraktSyncManager.kt",
    "    // ── Watch History (mark as watched) ──\n",
    '''    /** Add a movie/show to the user's Trakt collection (Library). */
    suspend fun pushAddToCollection(imdbId: String, type: String) {
        if (traktAuthManager.getAccessToken() == null || !imdbId.startsWith("tt")) return
        withContext(Dispatchers.IO) {
            try {
                val item = listOf(TraktSyncItem(ids = TraktIds(imdb = imdbId)))
                val body = if (type == "movie") TraktSyncRequest(movies = item)
                    else TraktSyncRequest(shows = item)
                val response = traktSyncApi.addToCollection(body)
                if (!response.isSuccessful) {
                    Log.w(TAG, "Trakt collection add failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add item to Trakt collection", e)
            }
        }
    }

    /** Push a whole set of show episodes in one Trakt history request. */
    suspend fun pushSeriesEpisodesWatched(
        showImdbId: String,
        episodes: List<Pair<Int, Int>>,
        watched: Boolean
    ) {
        if (traktAuthManager.getAccessToken() == null || !showImdbId.startsWith("tt") || episodes.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val seasons = episodes
                    .groupBy { it.first }
                    .toSortedMap()
                    .map { (season, entries) ->
                        TraktSyncSeason(
                            number = season,
                            episodes = entries.map { TraktSyncEpisode(it.second) }.distinctBy { it.number }
                        )
                    }
                val body = TraktSyncRequest(
                    shows = listOf(
                        TraktSyncItem(
                            ids = TraktIds(imdb = showImdbId),
                            seasons = seasons
                        )
                    )
                )
                val response = if (watched) traktSyncApi.addToHistory(body) else traktSyncApi.removeFromHistory(body)
                if (!response.isSuccessful) {
                    Log.w(TAG, "Trakt bulk history update failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update series history on Trakt", e)
            }
        }
    }

    // ── Watch History (mark as watched) ──
'''
)

# ---------------------------------------------------------------------------
# Home-only hiding: completed titles disappear from Home rows/hero, nowhere else.
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/home/HomeScreen.kt",
    '''    val state by viewModel.state.collectAsState()
    val layoutMode = currentProfile?.layoutFor(tab) ?: "simple"
''',
    '''    val state by viewModel.state.collectAsState()
    val displayState = remember(tab, state.rows, state.mixedRows, state.heroRow, state.history, state.seriesNextUp) {
        if (tab != DashboardTab.HOME) {
            state
        } else {
            val hiddenIds = buildSet {
                state.history
                    .asSequence()
                    .filter { it.watched && it.type == "movie" }
                    .mapTo(this) { it.id }
                state.seriesNextUp
                    .asSequence()
                    .filter { it.isComplete }
                    .mapTo(this) { it.seriesId }
            }
            if (hiddenIds.isEmpty()) {
                state
            } else {
                state.copy(
                    rows = state.rows.map { row -> row.copy(items = row.items.filterNot { it.id in hiddenIds }) },
                    mixedRows = state.mixedRows.map { row ->
                        if (row is CategoryRow) row.copy(items = row.items.filterNot { it.id in hiddenIds }) else row
                    },
                    heroRow = state.heroRow?.let { row -> row.copy(items = row.items.filterNot { it.id in hiddenIds }) }
                )
            }
        }
    }
    val layoutMode = currentProfile?.layoutFor(tab) ?: "simple"
'''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/home/HomeScreen.kt",
    "                    state = state,\n",
    "                    state = displayState,\n"
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/home/HomeScreen.kt",
    '''                val heroItems = remember(state.heroRow, heroConfig.posterCount) {
                    state.heroRow?.items?.take(heroConfig.posterCount) ?: emptyList()
                }
''',
    '''                val heroItems = remember(displayState.heroRow, heroConfig.posterCount) {
                    displayState.heroRow?.items?.take(heroConfig.posterCount) ?: emptyList()
                }
'''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/home/HomeScreen.kt",
    "                    state = state,\n                    heroItems = heroItems,\n",
    "                    state = displayState,\n                    heroItems = heroItems,\n"
)

# ---------------------------------------------------------------------------
# Wire shared card context menus into normal movie/series card surfaces.
# ---------------------------------------------------------------------------
p = "app/src/main/java/com/hereliesaz/illumera/ui/home/InfiniteLoopRow.kt"
text = read(p)
text = text.replace(
    '''                        posterUrl = item.poster,
                        onClick = { onMovieClick(item) },
                        progress = item.progress,''',
    '''                        posterUrl = item.poster,
                        onClick = { onMovieClick(item) },
                        mediaItem = enriched ?: item,
                        progress = item.progress,'''
)
text = text.replace(
    '''                        posterUrl = item.poster,
                        onClick = { onMovieClick(item) },
                        progress = item.progress,
                        isWatched =''',
    '''                        posterUrl = item.poster,
                        onClick = { onMovieClick(item) },
                        mediaItem = item,
                        progress = item.progress,
                        isWatched ='''
)
text = text.replace(
    '''                            posterUrl = item.movie.poster,
                            onClick = { onMovieClick(item.movie) },
                            progress = item.movie.progress,''',
    '''                            posterUrl = item.movie.poster,
                            onClick = { onMovieClick(item.movie) },
                            mediaItem = item.movie,
                            progress = item.movie.progress,'''
)
# finite-grid occurrence uses the same item.movie shape.
if "mediaItem = item.movie" not in text:
    raise RuntimeError("InfiniteLoopRow: failed to add mediaItem")
write(p, text)

replace_all(
    "app/src/main/java/com/hereliesaz/illumera/ui/home/GridViewScreen.kt",
    '''                        onClick = { onMovieClick(item) },
                        isWatched = item.id in watchedIds,''',
    '''                        onClick = { onMovieClick(item) },
                        mediaItem = item,
                        isWatched = item.id in watchedIds,'''
)

p = "app/src/main/java/com/hereliesaz/illumera/ui/search/SearchScreen.kt"
text = read(p)
text = text.replace(
    '''                                            onClick = { onMovieClick(movie) },
                                            isWatched = movie.id in watchedIds,''',
    '''                                            onClick = { onMovieClick(movie) },
                                            mediaItem = movie,
                                            isWatched = movie.id in watchedIds,'''
)
text = text.replace(
    '''                                            onClick = { onMovieClick(series) },
                                            modifier = Modifier''',
    '''                                            onClick = { onMovieClick(series) },
                                            mediaItem = series,
                                            modifier = Modifier'''
)
text = text.replace(
    '''                onClick = { onItemClick(item) },
                isWatched = item.id in watchedIds,''',
    '''                onClick = { onItemClick(item) },
                mediaItem = item,
                isWatched = item.id in watchedIds,'''
)
write(p, text)

# ---------------------------------------------------------------------------
# Resume/next-up: follow the most recently watched episode, never first gap.
# ---------------------------------------------------------------------------
regex_replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt",
    r'''    private fun refreshResumeStateIfNeeded\(meta: MetaItem\?\) \{.*?\n    fun refreshResumeState\(\) \{\n        refreshResumeStateIfNeeded\(_state.value.meta\)\n    \}\n''',
    '''    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {
        if (meta == null) {
            if (_state.value.resumePlaybackId != null) {
                _state.value = _state.value.copy(resumePlaybackId = null)
            }
            return
        }

        _state.value = _state.value.copy(isResumeStateReady = false)
        viewModelScope.launch {
            val resumePlaybackId = if (meta.type == "series") {
                resolveSeriesResumePlaybackId(meta.id, meta.videos)
            } else {
                val movieHistory = dao.getHistoryItem(meta.id)
                if (movieHistory?.watched == true) null else movieHistory?.id
            }
            val isMovieWatched = if (meta.type != "series") {
                dao.getHistoryItem(meta.id)?.watched == true
            } else false
            if (_state.value.meta?.id == meta.id && _state.value.meta?.type == meta.type) {
                val episodeProgressMap = if (meta.type == "series") {
                    buildEpisodeProgressMap(meta.id)
                } else emptyMap()
                _state.value = _state.value.copy(
                    resumePlaybackId = resumePlaybackId,
                    isResumeStateReady = true,
                    isMovieWatched = isMovieWatched,
                    autoPlayStream = null,
                    episodeProgressMap = episodeProgressMap
                )
                if (meta.type == "series") {
                    computeAndStoreNextUp(meta.id, meta.name, meta.poster, meta.videos)
                }
            }
        }
    }

    private suspend fun resolveSeriesResumePlaybackId(
        seriesId: String,
        videos: List<MetaVideo>?
    ): String? {
        val latest = dao.getLatestSeriesEpisodeHistory("$seriesId:%")
        if (latest != null && !latest.watched) {
            val parsed = parseSeasonEpisode(seriesId, latest.id)
            return parsed?.let { (season, episode) -> "$seriesId:$season:$episode" } ?: latest.id
        }

        val sortedEpisodes = videos.orEmpty()
            .filter { it.season > 0 && it.episode > 0 }
            .sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
        if (sortedEpisodes.isEmpty()) return null

        val next = if (latest == null) {
            sortedEpisodes.firstOrNull()
        } else {
            val latestKey = parseSeasonEpisode(seriesId, latest.id) ?: return null
            sortedEpisodes.firstOrNull { episode ->
                episode.season > latestKey.first ||
                    (episode.season == latestKey.first && episode.episode > latestKey.second)
            }
        } ?: return null

        val today = java.time.LocalDate.now().toString()
        val releaseDate = next.released?.take(10)
        if (releaseDate != null && releaseDate > today) return null
        return "$seriesId:${next.season}:${next.episode}"
    }

    fun refreshResumeState() {
        refreshResumeStateIfNeeded(_state.value.meta)
    }
'''
)

regex_replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt",
    r'''    suspend fun computeAndStoreNextUp\(\n        seriesId: String,\n        title: String,\n        poster: String\?,\n        videos: List<MetaVideo>\?\n    \) \{.*?\n    \}\n\n    private fun loadTmdbEnrichment''',
    '''    suspend fun computeAndStoreNextUp(
        seriesId: String,
        title: String,
        poster: String?,
        videos: List<MetaVideo>?
    ) {
        if (videos.isNullOrEmpty()) return

        val latest = dao.getLatestSeriesEpisodeHistory("$seriesId:%")
        if (latest == null) {
            dao.deleteSeriesNextUp(profileId, seriesId)
            return
        }

        val sortedEpisodes = videos
            .filter { it.season > 0 && it.episode > 0 }
            .sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
        if (sortedEpisodes.isEmpty()) return

        val latestKey = parseSeasonEpisode(seriesId, latest.id)
        val nextEpisode = when {
            !latest.watched && latestKey != null -> sortedEpisodes.firstOrNull {
                it.season == latestKey.first && it.episode == latestKey.second
            }
            latestKey != null -> sortedEpisodes.firstOrNull { ep ->
                ep.season > latestKey.first ||
                    (ep.season == latestKey.first && ep.episode > latestKey.second)
            }
            else -> null
        }

        val existing = dao.getSeriesNextUp(profileId, seriesId)
        if (nextEpisode != null) {
            val unchanged = existing != null &&
                !existing.isComplete &&
                existing.nextSeason == nextEpisode.season &&
                existing.nextEpisode == nextEpisode.episode
            val revived = existing?.isComplete == true
            val badgeState = when {
                revived -> true
                unchanged -> existing?.isNewEpisode ?: false
                else -> false
            }
            dao.upsertSeriesNextUp(
                SeriesNextUpEntity(
                    profileId = profileId,
                    seriesId = seriesId,
                    title = title,
                    poster = poster ?: existing?.poster,
                    nextSeason = nextEpisode.season,
                    nextEpisode = nextEpisode.episode,
                    nextEpisodeTitle = nextEpisode.title.takeIf { it.isNotBlank() && it != "Episode" },
                    nextReleased = nextEpisode.released?.take(10),
                    isComplete = false,
                    isNewEpisode = badgeState,
                    updatedAt = if (unchanged) existing.updatedAt else System.currentTimeMillis()
                )
            )
        } else {
            val alreadyComplete = existing?.isComplete == true
            dao.upsertSeriesNextUp(
                SeriesNextUpEntity(
                    profileId = profileId,
                    seriesId = seriesId,
                    title = title,
                    poster = poster ?: existing?.poster,
                    nextSeason = 0,
                    nextEpisode = 0,
                    nextEpisodeTitle = null,
                    nextReleased = null,
                    isComplete = true,
                    isNewEpisode = false,
                    updatedAt = if (alreadyComplete) existing.updatedAt else System.currentTimeMillis()
                )
            )
        }
    }

    private fun loadTmdbEnrichment'''
)

# ---------------------------------------------------------------------------
# Player backend: per-video source-queue exclusion + source-list disabling.
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/PlayerBackend.kt",
    '''    val sourceOptions: StateFlow<List<PlayerSourceOption>>
    val audioTracks: StateFlow<List<PlayerTrackOption>>
''',
    '''    val sourceOptions: StateFlow<List<PlayerSourceOption>>
    val excludedSourceIds: StateFlow<Set<String>>
    val sourceListDisabled: StateFlow<Boolean>
    val audioTracks: StateFlow<List<PlayerTrackOption>>
'''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/PlayerBackend.kt",
    '''    fun selectSource(sourceId: String)
    fun selectAudioTrack(trackId: String?)
''',
    '''    fun selectSource(sourceId: String)
    fun setSourceExcluded(sourceId: String, excluded: Boolean)
    fun setSourceListDisabled(disabled: Boolean)
    fun selectAudioTrack(trackId: String?)
'''
)

replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/ExoPlayerBackend.kt",
    '''    private val _sourceOptions = MutableStateFlow<List<PlayerSourceOption>>(emptyList())
    override val sourceOptions: StateFlow<List<PlayerSourceOption>> = _sourceOptions

    private val _audioTracks''',
    '''    private val _sourceOptions = MutableStateFlow<List<PlayerSourceOption>>(emptyList())
    override val sourceOptions: StateFlow<List<PlayerSourceOption>> = _sourceOptions
    private val _excludedSourceIds = MutableStateFlow<Set<String>>(emptySet())
    override val excludedSourceIds: StateFlow<Set<String>> = _excludedSourceIds
    private val _sourceListDisabled = MutableStateFlow(false)
    override val sourceListDisabled: StateFlow<Boolean> = _sourceListDisabled

    private val _audioTracks'''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/ExoPlayerBackend.kt",
    '''    override fun load(request: PlayerLoadRequest) {
        if (released) return
        loadToken++
''',
    '''    override fun load(request: PlayerLoadRequest) {
        if (released) return
        loadToken++
        _excludedSourceIds.value = emptySet()
        _sourceListDisabled.value = false
'''
)
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/ExoPlayerBackend.kt",
    '''    override fun selectSource(sourceId: String) {
''',
    '''    override fun setSourceExcluded(sourceId: String, excluded: Boolean) {
        _excludedSourceIds.update { current ->
            if (excluded) current + sourceId else current - sourceId
        }
    }

    override fun setSourceListDisabled(disabled: Boolean) {
        _sourceListDisabled.value = disabled
    }

    override fun selectSource(sourceId: String) {
'''
)
regex_replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/ExoPlayerBackend.kt",
    r'''    private fun tryNextSourceOnParsingError\(error: PlaybackException\): Boolean \{.*?\n    \}\n''',
    '''    private fun tryNextSourceOnParsingError(error: PlaybackException): Boolean {
        val codeName = error.errorCodeName.uppercase(Locale.US)
        if (!codeName.contains("PARSING") || _sourceListDisabled.value) return false
        val sources = _sourceOptions.value
        if (sources.size <= 1) return false
        val currentIdx = sources.indexOfFirst { it.id == currentSourceId }
        if (currentIdx < 0) return false
        val excluded = _excludedSourceIds.value
        val nextSource = sources.drop(currentIdx + 1).firstOrNull { it.id !in excluded } ?: return false
        selectSource(nextSource.id)
        return true
    }
'''
)

# ---------------------------------------------------------------------------
# Glass sidebar: episode context menu + source context menu.
# ---------------------------------------------------------------------------
p = "app/src/main/java/com/hereliesaz/illumera/ui/details/GlassSidebar.kt"
text = read(p)
text = text.replace(
    "package com.hereliesaz.illumera.ui.details\n\n",
    "package com.hereliesaz.illumera.ui.details\n\nimport android.view.KeyEvent\n"
)
text = text.replace(
    "import com.hereliesaz.illumera.ui.home.DpadRepeatGate\n",
    "import com.hereliesaz.illumera.ui.home.DpadRepeatGate\nimport com.hereliesaz.illumera.ui.components.MediaActionTarget\nimport com.hereliesaz.illumera.ui.components.MediaCardActionMenu\nimport com.hereliesaz.illumera.ui.util.touchClick\n"
)
text = text.replace(
    '''    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    onToggleWatched: (MetaVideo) -> Unit = {},
''',
    '''    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    mediaActionTarget: MediaActionTarget? = null,
    excludedSourceIds: Set<String> = emptySet(),
    sourceListDisabled: Boolean = false,
    onToggleSourceExcluded: ((Stream) -> Unit)? = null,
    onToggleSourceListDisabled: (() -> Unit)? = null,
    onToggleWatched: (MetaVideo) -> Unit = {},
''', 1
)
text = text.replace(
    '''                    episodeEnrichmentMap = episodeEnrichmentMap,
                    onToggleWatched = onToggleWatched,''',
    '''                    episodeEnrichmentMap = episodeEnrichmentMap,
                    mediaActionTarget = mediaActionTarget,
                    onToggleWatched = onToggleWatched,'''
)
text = text.replace(
    '''                    focusRequester = focusRequester,
                    onSourceClick = onSourceSelected,
                    onBack = onBack''',
    '''                    focusRequester = focusRequester,
                    excludedSourceIds = excludedSourceIds,
                    sourceListDisabled = sourceListDisabled,
                    onToggleSourceExcluded = onToggleSourceExcluded,
                    onToggleSourceListDisabled = onToggleSourceListDisabled,
                    onSourceClick = onSourceSelected,
                    onBack = onBack'''
)
text = text.replace(
    '''    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    onToggleWatched: (MetaVideo) -> Unit = {},
''',
    '''    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    mediaActionTarget: MediaActionTarget? = null,
    onToggleWatched: (MetaVideo) -> Unit = {},
''', 1
)
text = text.replace(
    '''                        enrichment = epEnrichment,
                        onToggleWatched = { onToggleWatched(ep) },''',
    '''                        enrichment = epEnrichment,
                        mediaActionTarget = mediaActionTarget?.forEpisode(ep),
                        onToggleWatched = { onToggleWatched(ep) },'''
)
text = text.replace(
    '''    selectedStreamId: String? = null,
    focusRequester: FocusRequester,
    onSourceClick: (Stream) -> Unit,
''',
    '''    selectedStreamId: String? = null,
    focusRequester: FocusRequester,
    excludedSourceIds: Set<String> = emptySet(),
    sourceListDisabled: Boolean = false,
    onToggleSourceExcluded: ((Stream) -> Unit)? = null,
    onToggleSourceListDisabled: (() -> Unit)? = null,
    onSourceClick: (Stream) -> Unit,
'''
)
text = text.replace(
    '''                                isPlaying = index == selectedIndex && selectedStreamId != null,
                                modifier = if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier
                            ) { onSourceClick(s) }''',
    '''                                isPlaying = index == selectedIndex && selectedStreamId != null,
                                isExcluded = (s.addonTransportUrl ?: s.url) in excludedSourceIds,
                                sourceListDisabled = sourceListDisabled,
                                onToggleExcluded = onToggleSourceExcluded?.let { callback -> { callback(s) } },
                                onToggleSourceListDisabled = onToggleSourceListDisabled,
                                modifier = if (index == focusIndex) Modifier.focusRequester(focusRequester) else Modifier
                            ) { onSourceClick(s) }'''
)
text = text.replace(
    '''    enrichment: TmdbEpisodeEnrichment? = null,
    onToggleWatched: () -> Unit = {},
''',
    '''    enrichment: TmdbEpisodeEnrichment? = null,
    mediaActionTarget: MediaActionTarget? = null,
    onToggleWatched: () -> Unit = {},
'''
)
text = text.replace(
    '''    var thumbnailFocused by remember { mutableStateOf(false) }
    var buttonFocused by remember { mutableStateOf(false) }
''',
    '''    var thumbnailFocused by remember { mutableStateOf(false) }
    var buttonFocused by remember { mutableStateOf(false) }
    var contextMenuExpanded by remember(mediaActionTarget?.key) { mutableStateOf(false) }
    var dpadLongPressTriggered by remember { mutableStateOf(false) }
'''
)
text = text.replace(
    '''                .onFocusChanged { thumbnailFocused = it.isFocused }
                .clickable(onClick = onClick)
                .focusable()
''',
    '''                .onFocusChanged {
                    thumbnailFocused = it.isFocused
                    if (!it.isFocused) dpadLongPressTriggered = false
                }
                .touchClick(
                    onClick = onClick,
                    onLongClick = mediaActionTarget?.let { { contextMenuExpanded = true } }
                )
                .onPreviewKeyEvent { event ->
                    if (mediaActionTarget == null) return@onPreviewKeyEvent false
                    val native = event.nativeKeyEvent
                    val activation = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_BUTTON_A
                    if (!activation) return@onPreviewKeyEvent false
                    when (native.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (native.repeatCount > 0) dpadLongPressTriggered = true
                            dpadLongPressTriggered
                        }
                        KeyEvent.ACTION_UP -> {
                            if (dpadLongPressTriggered) {
                                dpadLongPressTriggered = false
                                contextMenuExpanded = true
                                true
                            } else {
                                onClick()
                                true
                            }
                        }
                        else -> false
                    }
                }
                .focusable()
'''
)
text = text.replace(
    '''            if (progress != null && progress > 0f && !isWatched) {
''',
    '''            if (mediaActionTarget != null) {
                MediaCardActionMenu(
                    target = mediaActionTarget,
                    expanded = contextMenuExpanded,
                    onDismissRequest = { contextMenuExpanded = false }
                )
            }

            if (progress != null && progress > 0f && !isWatched) {
''', 1
)
# Replace RawSourceItem wholesale.
text, count = re.subn(
    r'''@Composable\nfun RawSourceItem\(stream: Stream, isPlaying: Boolean = false, modifier: Modifier = Modifier, onClick: \(\) -> Unit\) \{.*?\n\}\s*$''',
    '''@Composable
fun RawSourceItem(
    stream: Stream,
    isPlaying: Boolean = false,
    isExcluded: Boolean = false,
    sourceListDisabled: Boolean = false,
    onToggleExcluded: (() -> Unit)? = null,
    onToggleSourceListDisabled: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var dpadLongPressTriggered by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val mainText = stream.description ?: stream.title ?: stream.name ?: "Unknown"
    val subText = stream.name ?: ""
    val hasContextActions = onToggleExcluded != null || onToggleSourceListDisabled != null

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isExcluded -> Color.White.copy(0.025f)
                    isFocused -> Color.White.copy(0.1f)
                    else -> Color.White.copy(0.05f)
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (!it.isFocused) dpadLongPressTriggered = false
                }
                .border(
                    if (isFocused) 3.dp else if (isExcluded) 1.dp else 0.dp,
                    if (isFocused) primary else if (isExcluded) Color.White.copy(0.25f) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .touchClick(
                    onClick = onClick,
                    onLongClick = if (hasContextActions) ({ menuExpanded = true }) else null
                )
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val activation = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == KeyEvent.KEYCODE_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                        native.keyCode == KeyEvent.KEYCODE_BUTTON_A
                    if (!activation) return@onPreviewKeyEvent false
                    when (native.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (hasContextActions && native.repeatCount > 0) dpadLongPressTriggered = true
                            dpadLongPressTriggered
                        }
                        KeyEvent.ACTION_UP -> {
                            if (dpadLongPressTriggered) {
                                dpadLongPressTriggered = false
                                menuExpanded = true
                                true
                            } else {
                                onClick()
                                true
                            }
                        }
                        else -> false
                    }
                }
                .focusable()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mainText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            isExcluded -> Color.White.copy(0.35f)
                            isFocused -> Color.White
                            else -> Color.LightGray
                        },
                        modifier = Modifier.weight(1f)
                    )
                    when {
                        isPlaying -> Text(
                            "Playing",
                            style = MaterialTheme.typography.labelSmall,
                            color = primary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        isExcluded -> Text(
                            "Excluded",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(0.45f),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (subText.isNotEmpty() && subText != mainText) {
                    Text(
                        subText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFocused) primary else Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            if (onToggleExcluded != null) {
                DropdownMenuItem(
                    text = { Text(if (isExcluded) "Include in source queue" else "Exclude from source queue") },
                    onClick = {
                        menuExpanded = false
                        onToggleExcluded()
                    }
                )
            }
            if (onToggleSourceListDisabled != null) {
                DropdownMenuItem(
                    text = { Text(if (sourceListDisabled) "Enable source list for this video" else "Disable source list for this video") },
                    onClick = {
                        menuExpanded = false
                        onToggleSourceListDisabled()
                    }
                )
            }
        }
    }
}
''',
    text,
    count=1,
    flags=re.S
)
if count != 1:
    raise RuntimeError(f"GlassSidebar: RawSourceItem replacement count={count}")
write(p, text)

# Details screen supplies series identity to episode-card context menus.
replace_once(
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsScreen.kt",
    '''            episodeEnrichmentMap = state.episodeEnrichmentMap,
            onToggleWatched = { episode -> viewModel.toggleEpisodeWatched(episode) },''',
    '''            episodeEnrichmentMap = state.episodeEnrichmentMap,
            mediaActionTarget = movie?.let { com.hereliesaz.illumera.ui.components.MediaActionTarget.fromMeta(it) },
            onToggleWatched = { episode -> viewModel.toggleEpisodeWatched(episode) },'''
)

# ---------------------------------------------------------------------------
# Player UI: explicit next source + next episode controls and source context state.
# ---------------------------------------------------------------------------
p = "app/src/main/java/com/hereliesaz/illumera/ui/player/base/BasePlayerScaffold.kt"
text = read(p)
text = text.replace(
    "import androidx.compose.material.icons.filled.Add\n",
    "import androidx.compose.material.icons.filled.Add\nimport androidx.compose.material.icons.filled.ArrowForward\n"
)
text = text.replace(
    '''    val sources by playbackController.sourceOptions.collectAsState()
    val audioTracks by playbackController.audioTracks.collectAsState()
''',
    '''    val sources by playbackController.sourceOptions.collectAsState()
    val excludedSourceIds by playbackController.excludedSourceIds.collectAsState()
    val sourceListDisabled by playbackController.sourceListDisabled.collectAsState()
    val audioTracks by playbackController.audioTracks.collectAsState()
'''
)
text = text.replace(
    '''    fun exitPlaybackOrShowSourcesList() {
        if (sources.size > 1) {''',
    '''    fun exitPlaybackOrShowSourcesList() {
        if (!sourceListDisabled && sources.size > 1) {'''
)
text = text.replace(
    '''                if (sources.size > 1) {
                    markInteraction()
                    sourcesPanelOpenedFromError = true''',
    '''                if (!sourceListDisabled && sources.size > 1) {
                    markInteraction()
                    sourcesPanelOpenedFromError = true'''
)
text = text.replace(
    '''                showSourceControl = !isTrailer && sources.size > 1,
                showAudioControl =''',
    '''                showSourceControl = !isTrailer && !sourceListDisabled && sources.size > 1,
                showNextSourceControl = !isTrailer && !sourceListDisabled && sources.count { it.id !in excludedSourceIds } > 1,
                showNextEpisodeControl = !isTrailer && nextEpisodeInfo != null && onAutoplayNextEpisode != null,
                showAudioControl ='''
)
text = text.replace(
    '''                onSeekBy = { deltaMs ->
                    markInteraction()
                    pendingPreviewSeekPosition = null
                    playbackController.seekBy(deltaMs)
                    scheduleHideControls()
                },
                onShowSourcesPanel = {''',
    '''                onSeekBy = { deltaMs ->
                    markInteraction()
                    pendingPreviewSeekPosition = null
                    playbackController.seekBy(deltaMs)
                    scheduleHideControls()
                },
                onNextSource = {
                    markInteraction()
                    val currentIndex = sources.indexOfFirst { it.id == uiState.currentSourceId }
                    val ordered = if (currentIndex >= 0) {
                        sources.drop(currentIndex + 1) + sources.take(currentIndex + 1)
                    } else {
                        sources
                    }
                    ordered.firstOrNull { it.id != uiState.currentSourceId && it.id !in excludedSourceIds }
                        ?.let { playbackController.selectSource(it.id) }
                    showControlsTemporarily()
                },
                onNextEpisode = {
                    markInteraction()
                    onAutoplayNextEpisode?.invoke(currentSourceUrl)
                },
                onShowSourcesPanel = {'''
)
text = text.replace(
    '''            sources = sources,
            currentSourceId = uiState.currentSourceId,
            onSelectSource = { sourceId ->''',
    '''            sources = sources,
            currentSourceId = uiState.currentSourceId,
            excludedSourceIds = excludedSourceIds,
            sourceListDisabled = sourceListDisabled,
            onToggleSourceExcluded = { sourceId ->
                playbackController.setSourceExcluded(sourceId, sourceId !in excludedSourceIds)
            },
            onToggleSourceListDisabled = {
                playbackController.setSourceListDisabled(!sourceListDisabled)
            },
            onSelectSource = { sourceId ->'''
)
text = text.replace(
    '''    showSourceControl: Boolean,
    showAudioControl: Boolean,''',
    '''    showSourceControl: Boolean,
    showNextSourceControl: Boolean,
    showNextEpisodeControl: Boolean,
    showAudioControl: Boolean,'''
)
text = text.replace(
    '''    onSeekBy: (Long) -> Unit,
    onShowSourcesPanel: () -> Unit,''',
    '''    onSeekBy: (Long) -> Unit,
    onNextSource: () -> Unit,
    onNextEpisode: () -> Unit,
    onShowSourcesPanel: () -> Unit,'''
)
text = text.replace(
    '''                    if (showSubtitleControl) {''',
    '''                    if (showNextSourceControl) {
                        ControlButton(
                            icon = Icons.Default.ArrowForward,
                            contentDescription = "Next source",
                            onClick = onNextSource,
                            onFocused = onResetHideTimer,
                            buttonSize = 44.dp,
                            iconSize = 20.dp
                        )
                    }

                    if (showNextEpisodeControl) {
                        ControlButton(
                            icon = Icons.Default.SkipNext,
                            contentDescription = "Next episode",
                            onClick = onNextEpisode,
                            onFocused = onResetHideTimer,
                            buttonSize = 44.dp,
                            iconSize = 20.dp
                        )
                    }

                    if (showSubtitleControl) {''', 1
)
text = text.replace(
    '''    currentSourceId: String?,
    onClose: () -> Unit,
    onSelectSource: (String) -> Unit
) {''',
    '''    currentSourceId: String?,
    excludedSourceIds: Set<String>,
    sourceListDisabled: Boolean,
    onToggleSourceExcluded: (String) -> Unit,
    onToggleSourceListDisabled: () -> Unit,
    onClose: () -> Unit,
    onSelectSource: (String) -> Unit
) {'''
)
text = text.replace(
    '''        state = sidebarState,
        onEpisodeSelected = {},
        onSourceSelected = { stream ->''',
    '''        state = sidebarState,
        excludedSourceIds = excludedSourceIds,
        sourceListDisabled = sourceListDisabled,
        onToggleSourceExcluded = { stream ->
            val sourceId = stream.addonTransportUrl ?: stream.url ?: return@GlassSidebar
            onToggleSourceExcluded(sourceId)
        },
        onToggleSourceListDisabled = onToggleSourceListDisabled,
        onEpisodeSelected = {},
        onSourceSelected = { stream ->''', 1
)
write(p, text)

# ---------------------------------------------------------------------------
# Queue UX: single Move action, reorder mode, dislike removes + refills immediately.
# ---------------------------------------------------------------------------
p = "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt"
text = read(p)
text = text.replace(
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.input.key.Key\nimport androidx.compose.ui.input.key.KeyEventType\nimport androidx.compose.ui.input.key.key\nimport androidx.compose.ui.input.key.onPreviewKeyEvent\nimport androidx.compose.ui.input.key.type\n"
)
text = text.replace(
    '''                    IconButton(onClick = { queueManager.rateSuggestion(item.stableKey, -1) }) {''',
    '''                    IconButton(onClick = {
                        scope.launch {
                            queueManager.rateSuggestion(item.stableKey, -1)
                            queueManager.removeSuggestion(item.stableKey)
                            queueManager.ensureSuggestions()
                        }
                    }) {'''
)
text, count = re.subn(
    r'''@Composable\nprivate fun QueueCardRow\(.*?\n\}\n\nprivate fun queueSubtitle''',
    '''@Composable
private fun QueueCardRow(
    title: String,
    items: List<QueueItem>,
    startPadding: Dp,
    onOpenItem: (QueueItem) -> Unit,
    focusedKey: String? = null,
    onFocused: (String) -> Unit = {},
    onMoveSuggestion: ((QueueItem, Int) -> Unit)? = null,
    onRemoveSuggestion: ((QueueItem) -> Unit)? = null,
    actions: @Composable (QueueItem, Int) -> Unit
) {
    var movingKey by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = startPadding, bottom = 12.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = startPadding, end = 40.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.stableKey }) { index, item ->
                val cardFocusRequester = remember(item.stableKey) { FocusRequester() }
                val isMoving = movingKey == item.stableKey
                LaunchedEffect(focusedKey, movingKey, item.stableKey) {
                    if (focusedKey == item.stableKey || isMoving) {
                        kotlinx.coroutines.delay(50)
                        runCatching { cardFocusRequester.requestFocus() }
                    }
                }

                Column(modifier = Modifier.width(140.dp)) {
                    var menuExpanded by remember(item.stableKey) { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .then(
                                if (isMoving) Modifier.border(
                                    3.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp)
                                ) else Modifier
                            )
                            .onPreviewKeyEvent { event ->
                                if (!isMoving || event.type != KeyEventType.KeyDown || onMoveSuggestion == null) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (index > 0) onMoveSuggestion(item, index - 1)
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        if (index < items.lastIndex) onMoveSuggestion(item, index + 1)
                                        true
                                    }
                                    Key.Back -> {
                                        movingKey = null
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        LumeraCard(
                            title = item.title,
                            posterUrl = item.poster,
                            onClick = {
                                if (isMoving) movingKey = null else onOpenItem(item)
                            },
                            modifier = Modifier.focusRequester(cardFocusRequester),
                            onFocused = { onFocused(item.stableKey) },
                            onLongClick = if (onMoveSuggestion != null || onRemoveSuggestion != null) {
                                { menuExpanded = true }
                            } else null
                        )

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (onMoveSuggestion != null) {
                                DropdownMenuItem(
                                    text = { Text("Move") },
                                    onClick = {
                                        menuExpanded = false
                                        movingKey = item.stableKey
                                    }
                                )
                            }
                            if (onRemoveSuggestion != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove from queue") },
                                    onClick = {
                                        menuExpanded = false
                                        movingKey = null
                                        onRemoveSuggestion(item)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isMoving) "Move with ← → • select to place" else queueSubtitle(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMoving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        actions(item, index)
                    }
                }
            }
        }
    }
}

private fun queueSubtitle''',
    text,
    count=1,
    flags=re.S
)
if count != 1:
    raise RuntimeError(f"QueueScreen: QueueCardRow replacement count={count}")
write(p, text)

print("round2 patch applied")
