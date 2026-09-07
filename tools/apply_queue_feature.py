from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing anchor: {label}")
    return text.replace(old, new, 1)

root = Path(__file__).resolve().parents[1]

# Navigation enum + side rail.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/navigation/NavDrawer.kt"
s = p.read_text()
s = replace_once(s,
    '    Watchlist(R.drawable.watchlist_icon, "Watchlist"),\n    Search(',
    '    Watchlist(R.drawable.watchlist_icon, "Watchlist"),\n    Queue(R.drawable.watchlist_icon, "Queue"),\n    Search(',
    'queue destination')
s = replace_once(s,
    '                DrawerItem(NavDestination.Watchlist)\n\n                Spacer(modifier = Modifier.weight(1f))',
    '                DrawerItem(NavDestination.Watchlist)\n                Spacer(modifier = Modifier.height(4.dp))\n                DrawerItem(NavDestination.Queue)\n\n                Spacer(modifier = Modifier.weight(1f))',
    'drawer queue item')
p.write_text(s)

# Top bar.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/navigation/TopNavigationBar.kt"
s = p.read_text()
s = replace_once(s,
    '        NavDestination.Series,\n        NavDestination.Watchlist\n    )',
    '        NavDestination.Series,\n        NavDestination.Watchlist,\n        NavDestination.Queue\n    )',
    'top queue item')
p.write_text(s)

# Details screen: movie / next episode / whole show queue buttons, plus queued autoplay.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsScreen.kt"
s = p.read_text()
s = replace_once(s,
    'import com.hereliesaz.illumera.data.model.stremio.MetaVideo\n',
    'import com.hereliesaz.illumera.data.model.stremio.MetaVideo\nimport com.hereliesaz.illumera.data.queue.QueueItem\n',
    'queue item import')
s = replace_once(s,
    '    onPlayClick: (String, String, String, String, String, String, Stream, List<AddonSubtitle>, List<Stream>, List<MetaVideo>) -> Unit,\n',
    '    onPlayClick: (String, String, String, String, String, String, Stream, List<AddonSubtitle>, List<Stream>, List<MetaVideo>) -> Unit,\n    onAddToQueue: (QueueItem) -> Unit = {},\n    queueAutoPlayId: String? = null,\n    onQueueAutoPlayConsumed: () -> Unit = {},\n',
    'details queue callbacks')

# Auto-start queued item once metadata exists. A queued episode id ends with :season:episode.
anchor = '                val firstEpisodeNumber = firstEpisode?.episode?.takeIf { it > 0 } ?: 1\n\n                // No onNavigateDown'
insert = '''                val firstEpisodeNumber = firstEpisode?.episode?.takeIf { it > 0 } ?: 1

                LaunchedEffect(queueAutoPlayId, currentMovie.id) {
                    val requested = queueAutoPlayId ?: return@LaunchedEffect
                    if (type == "series") {
                        val requestedEpisode = currentMovie.videos.orEmpty().firstOrNull { ep ->
                            requested == ep.id || requested.endsWith(":${ep.season}:${ep.episode}")
                        } ?: firstEpisode
                        if (requestedEpisode != null) {
                            val trackId = episodePlaybackId(streamId, requestedEpisode)
                            val epStreamId = episodeStreamId(streamId, requestedEpisode)
                            val epTitle = episodeDisplayTitle(requestedEpisode)
                            pendingPlaybackId = trackId
                            pendingPlaybackType = type
                            pendingPlaybackTitle = epTitle
                            viewModel.loadStreams(type, epStreamId, epTitle, sourceSelectionId = trackId, autoSelectSource = true, rememberSourceSelection = rememberSourceSelection)
                        }
                    } else {
                        pendingPlaybackId = streamId
                        pendingPlaybackType = type
                        pendingPlaybackTitle = currentMovie.name
                        viewModel.loadStreams(type, streamId, currentMovie.name, autoSelectSource = true, rememberSourceSelection = rememberSourceSelection)
                    }
                    onQueueAutoPlayConsumed()
                }

                // No onNavigateDown'''
s = replace_once(s, anchor, insert, 'queued autoplay')

series_anchor = '''                        ExpandableIconButton(
                            label = "Episodes",
                            icon = Icons.AutoMirrored.Filled.List,
                            modifier = Modifier.focusRequester(episodesButtonFocusRequester),
                            onClick = { viewModel.openEpisodes() }
                        )
'''
series_insert = series_anchor + '''
                        ExpandableIconButton(
                            label = "Add next episode to queue",
                            icon = Icons.Default.Add,
                            onClick = {
                                val ep = resumeEpisode ?: firstEpisode ?: return@ExpandableIconButton
                                onAddToQueue(
                                    QueueItem(
                                        id = episodePlaybackId(streamId, ep),
                                        type = "episode",
                                        title = episodeDisplayTitle(ep),
                                        poster = currentMovie.poster,
                                        seriesId = streamId,
                                        season = ep.season,
                                        episode = ep.episode
                                    )
                                )
                            }
                        )

                        ExpandableIconButton(
                            label = "Queue whole show",
                            icon = Icons.AutoMirrored.Filled.List,
                            onClick = {
                                onAddToQueue(
                                    QueueItem(
                                        id = streamId,
                                        type = "series",
                                        title = currentMovie.name,
                                        poster = currentMovie.poster,
                                        seriesId = streamId,
                                        wholeShow = true
                                    )
                                )
                            }
                        )
'''
s = replace_once(s, series_anchor, series_insert, 'series queue buttons')

movie_anchor = '''                        ExpandableIconButton(
                            label = if (state.isMovieWatched) "Watched" else "Mark as watched",
'''
movie_insert = '''                        ExpandableIconButton(
                            label = "Add to queue",
                            icon = Icons.Default.Add,
                            onClick = {
                                onAddToQueue(
                                    QueueItem(
                                        id = streamId,
                                        type = "movie",
                                        title = currentMovie.name,
                                        poster = currentMovie.poster
                                    )
                                )
                            }
                        )

''' + movie_anchor
s = replace_once(s, movie_anchor, movie_insert, 'movie queue button')
p.write_text(s)

# Main activity navigation + playback integration.
p = root / "app/src/main/java/com/hereliesaz/illumera/MainActivity.kt"
s = p.read_text()
s = replace_once(s,
    'import com.hereliesaz.illumera.ui.watchlist.WatchlistScreen\n',
    'import com.hereliesaz.illumera.ui.watchlist.WatchlistScreen\nimport com.hereliesaz.illumera.ui.queue.QueueScreen\nimport com.hereliesaz.illumera.data.queue.QueueManager\nimport com.hereliesaz.illumera.data.queue.QueueItem\n',
    'main queue imports')
s = replace_once(s,
    '    lateinit var streamSortingService: StreamSortingService\n',
    '    lateinit var streamSortingService: StreamSortingService\n    @Inject\n    lateinit var queueManager: QueueManager\n',
    'queue injection')
s = replace_once(s,
    '            var previousView by rememberSaveable { mutableStateOf("menu") }\n            val playerState = remember { PlayerState() }',
    '            var previousView by rememberSaveable { mutableStateOf("menu") }\n            var queueAutoPlayId by rememberSaveable { mutableStateOf<String?>(null) }\n            var queueWholeShowActive by rememberSaveable { mutableStateOf(false) }\n            val playerState = remember { PlayerState() }',
    'queue playback state')
s = replace_once(s,
    '                        val watchlistEntryRequester = remember { FocusRequester() }\n',
    '                        val watchlistEntryRequester = remember { FocusRequester() }\n                        val queueEntryRequester = remember { FocusRequester() }\n',
    'queue focus requester')
# Focus in all three focus-routing whens.
s = s.replace(
    '                                NavDestination.Watchlist -> {\n                                    delay(200)\n                                    watchlistEntryRequester.requestFocus()\n                                }',
    '                                NavDestination.Watchlist -> {\n                                    delay(200)\n                                    watchlistEntryRequester.requestFocus()\n                                }\n                                NavDestination.Queue -> {\n                                    delay(200)\n                                    queueEntryRequester.requestFocus()\n                                }',
    1)
s = s.replace(
    '                                            NavDestination.Watchlist -> watchlistEntryRequester.requestFocus()\n                                            else -> {}',
    '                                            NavDestination.Watchlist -> watchlistEntryRequester.requestFocus()\n                                            NavDestination.Queue -> queueEntryRequester.requestFocus()\n                                            else -> {}',
    1)
s = s.replace(
    '                                        NavDestination.Watchlist -> watchlistEntryRequester.requestFocus()\n                                        else -> {}',
    '                                        NavDestination.Watchlist -> watchlistEntryRequester.requestFocus()\n                                        NavDestination.Queue -> queueEntryRequester.requestFocus()\n                                        else -> {}',
    1)

queue_branch = '''                                                NavDestination.Queue -> {
                                                    QueueScreen(
                                                        queueManager = queueManager,
                                                        entryRequester = queueEntryRequester,
                                                        onOpenItem = { item ->
                                                            selectedMovieId = item.seriesId ?: item.id
                                                            selectedMovieType = if (item.type == "movie") "movie" else "series"
                                                            selectedMovieTitle = item.title
                                                            selectedMoviePoster = item.poster ?: ""
                                                            selectedMovieBackground = ""
                                                            selectedMovieLogo = ""
                                                            selectedAddonBaseUrl = null
                                                            detailsResumePlaybackHint = null
                                                            selectedPlaybackId = item.id
                                                            selectedPlaybackType = selectedMovieType
                                                            selectedPlaybackTitle = item.title
                                                            selectedPlaybackPoster = item.poster ?: ""
                                                            previousView = "menu"
                                                            activeView = "details"
                                                        }
                                                    )
                                                }
'''
# Add before Settings in both top and left render whens.
settings_marker = '                                                NavDestination.Settings -> {\n'
if s.count(settings_marker) < 2:
    raise RuntimeError('missing settings render anchors')
s = s.replace(settings_marker, queue_branch + settings_marker, 2)

# Pass queue callbacks to every DetailsScreen call (there is one central call).
details_marker = '                                        onPlayClick = onPlayClick,\n'
details_insert = '''                                        onPlayClick = onPlayClick,
                                        onAddToQueue = { queueManager.add(it) },
                                        queueAutoPlayId = queueAutoPlayId,
                                        onQueueAutoPlayConsumed = { queueAutoPlayId = null },
'''
s = replace_once(s, details_marker, details_insert, 'details queue wiring')

# Whole-show queue playback temporarily forces normal episode autoplay machinery on.
s = s.replace(
    '                            val shouldAutoplay = currentProfile?.autoplayNextEpisode == true && isSeries',
    '                            val shouldAutoplay = (currentProfile?.autoplayNextEpisode == true || queueWholeShowActive) && isSeries',
    1)
s = s.replace(
    '                                    autoplayNextEpisode = currentProfile?.autoplayNextEpisode ?: false,',
    '                                    autoplayNextEpisode = (currentProfile?.autoplayNextEpisode == true || queueWholeShowActive),',
    1)
s = s.replace(
    '                                            val autoplay = currentProfile?.autoplayNextEpisode == true',
    '                                            val autoplay = currentProfile?.autoplayNextEpisode == true || queueWholeShowActive',
    1)

# End-of-playback: advance queue only on completed non-trailer playback; otherwise preserve old return.
back_anchor = '''                                    if (selectedPlaybackId.startsWith("trailer_")) {
                                        trailerReturnToken++
                                    }
                                    activeView = "details"
'''
back_insert = '''                                    if (selectedPlaybackId.startsWith("trailer_")) {
                                        trailerReturnToken++
                                        activeView = "details"
                                    } else if (sessionResult.isCompleted && queueManager.state.value.preferences.enabled) {
                                        val next = queueManager.advanceAfterPlayback(selectedPlaybackId)
                                        if (next != null) {
                                            selectedMovieId = next.seriesId ?: next.id
                                            selectedMovieType = if (next.type == "movie") "movie" else "series"
                                            selectedMovieTitle = next.title
                                            selectedMoviePoster = next.poster ?: ""
                                            selectedMovieBackground = ""
                                            selectedMovieLogo = ""
                                            selectedAddonBaseUrl = null
                                            selectedPlaybackId = next.id
                                            selectedPlaybackType = selectedMovieType
                                            selectedPlaybackTitle = next.title
                                            selectedPlaybackPoster = next.poster ?: ""
                                            queueAutoPlayId = next.id
                                            queueWholeShowActive = next.wholeShow
                                            previousView = "menu"
                                            activeView = "details"
                                            uiScope.launch { queueManager.ensureSuggestions() }
                                        } else {
                                            queueWholeShowActive = false
                                            activeView = "details"
                                        }
                                    } else {
                                        activeView = "details"
                                    }
'''
s = replace_once(s, back_anchor, back_insert, 'queue playback advance')
p.write_text(s)

# Queue manager helpers used by MainActivity.
p = root / "app/src/main/java/com/hereliesaz/illumera/data/queue/QueueManager.kt"
s = p.read_text()
anchor = '''    suspend fun refreshSuggestions() {
'''
insert = '''    @Synchronized
    fun advanceAfterPlayback(playbackId: String): QueueItem? {
        val current = _state.value
        if (!current.preferences.enabled) return null
        val index = current.manualItems.indexOfFirst { item ->
            item.id == playbackId ||
                (item.type == "episode" && playbackId.endsWith(":${item.season}:${item.episode}"))
        }
        val remaining = if (index >= 0) current.manualItems.toMutableList().also { it.removeAt(index) } else current.manualItems
        if (index >= 0) commit(current.copy(manualItems = remaining))
        return remaining.firstOrNull() ?: current.suggestions.firstOrNull()
    }

    suspend fun ensureSuggestions() {
        if (_state.value.suggestions.size < SUGGESTION_COUNT) refreshSuggestions()
    }

''' + anchor
s = replace_once(s, anchor, insert, 'queue advance helpers')
p.write_text(s)

print('queue integration applied')
