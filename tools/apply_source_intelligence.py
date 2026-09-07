from pathlib import Path


def rep(text, old, new, label, count=1):
    if old not in text:
        raise RuntimeError(f"missing anchor: {label}")
    return text.replace(old, new, count)

root = Path(__file__).resolve().parents[1]

# Database version.
p = root / "app/src/main/java/com/hereliesaz/illumera/data/local/LumeraDatabase.kt"
s = p.read_text()
s = rep(s, "    version = 45\n", "    version = 46\n", "database version")
p.write_text(s)

# Migration for source-intelligence profile columns.
p = root / "app/src/main/java/com/hereliesaz/illumera/di/DatabaseModule.kt"
s = p.read_text()
anchor = '''private val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS recent_searches (" +
                "profileId INTEGER NOT NULL, " +
                "query TEXT NOT NULL, " +
                "searchedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(profileId, query))"
        )
    }
}
'''
insert = anchor + '''
private val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceEpisodeTargetSizeMb INTEGER NOT NULL DEFAULT 750")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceMovieTargetSizeMb INTEGER NOT NULL DEFAULT 3000")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceMinimumSeeds INTEGER NOT NULL DEFAULT 5")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceAutoFallback INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE profiles ADD COLUMN sourceDebridMaxWaitSeconds INTEGER NOT NULL DEFAULT 120")
    }
}
'''
s = rep(s, anchor, insert, "migration 45 46")
s = rep(s,
    'MIGRATION_43_44, MIGRATION_44_45)\n',
    'MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46)\n',
    'migration registration')
p.write_text(s)

# Settings VM methods.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/settings/SettingsViewModel.kt"
s = p.read_text()
anchor = '''    fun updateSourceExcludedFormats(profileId: Int, formats: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceExcludedFormats = formats))
        }
    }
}'''
insert = '''    fun updateSourceExcludedFormats(profileId: Int, formats: String) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val profile = dao.getProfileById(profileId)
            if (profile != null) dao.insertProfile(profile.copy(sourceExcludedFormats = formats))
        }
    }

    fun updateSourceEpisodeTargetSizeMb(profileId: Int, sizeMb: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceEpisodeTargetSizeMb = sizeMb)) }
        }
    }

    fun updateSourceMovieTargetSizeMb(profileId: Int, sizeMb: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceMovieTargetSizeMb = sizeMb)) }
        }
    }

    fun updateSourceMinimumSeeds(profileId: Int, seeds: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceMinimumSeeds = seeds)) }
        }
    }

    fun updateSourceAutoFallback(profileId: Int, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceAutoFallback = enabled)) }
        }
    }

    fun updateSourceDebridMaxWaitSeconds(profileId: Int, seconds: Int) {
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            dao.getProfileById(profileId)?.let { dao.insertProfile(it.copy(sourceDebridMaxWaitSeconds = seconds)) }
        }
    }
}'''
s = rep(s, anchor, insert, "settings methods")
p.write_text(s)

# Source preferences UI.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/settings/SettingsSubScreens.kt"
s = p.read_text()
s = rep(s,
    'options = listOf("Quality" to "quality", "File Size" to "size"),',
    'options = listOf("Quality" to "quality", "File Size" to "size", "Seeds" to "seeds"),',
    'sort by seeds')
anchor = '''            SettingOptionRow(
                label = "Sort By",
                options = listOf("Quality" to "quality", "File Size" to "size", "Seeds" to "seeds"),
                selectedOption = currentProfile.sourceSortPrimary,
                onOptionSelected = { viewModel.updateSourceSortPrimary(currentProfile.id, it) },
                onBack = onGoBack
            )

            Spacer(Modifier.height(15.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.1f)))
            Spacer(Modifier.height(15.dp))
'''
insert = anchor + '''
            Text(
                "Auto-selection preferences",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                color = Color.White
            )
            Text(
                "These rank sources from best to worst. Missing the target never hides a source. Addon priority follows your order in Addons.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = Color.White.copy(0.6f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            val episodeSizes = listOf(250, 500, 750, 1000, 1500, 2000, 3000)
            val episodeLabel = "${currentProfile.sourceEpisodeTargetSizeMb} MB"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("30-minute episode", color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
                FilterDropdown(
                    currentValue = episodeLabel,
                    options = episodeSizes.map { "$it MB" },
                    modifier = Modifier.width(160.dp),
                    onSelect = { value ->
                        viewModel.updateSourceEpisodeTargetSizeMb(currentProfile.id, value.removeSuffix(" MB").toIntOrNull() ?: 750)
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            val movieSizes = listOf(1000, 2000, 3000, 4000, 5000, 8000, 10000, 15000, 20000)
            val movieLabel = if (currentProfile.sourceMovieTargetSizeMb >= 1000 && currentProfile.sourceMovieTargetSizeMb % 1000 == 0) {
                "${currentProfile.sourceMovieTargetSizeMb / 1000} GB"
            } else "${currentProfile.sourceMovieTargetSizeMb} MB"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Full-length movie", color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
                FilterDropdown(
                    currentValue = movieLabel,
                    options = movieSizes.map { if (it % 1000 == 0) "${it / 1000} GB" else "$it MB" },
                    modifier = Modifier.width(160.dp),
                    onSelect = { value ->
                        val mb = if (value.endsWith(" GB")) (value.removeSuffix(" GB").toIntOrNull() ?: 3) * 1000
                            else value.removeSuffix(" MB").toIntOrNull() ?: 3000
                        viewModel.updateSourceMovieTargetSizeMb(currentProfile.id, mb)
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            val seedOptions = listOf(0, 1, 3, 5, 10, 20, 50, 100)
            val seedLabel = if (currentProfile.sourceMinimumSeeds == 0) "Don't care" else "${currentProfile.sourceMinimumSeeds}+"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Preferred minimum seeds", color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
                FilterDropdown(
                    currentValue = seedLabel,
                    options = seedOptions.map { if (it == 0) "Don't care" else "$it+" },
                    modifier = Modifier.width(160.dp),
                    onSelect = { value ->
                        val seeds = if (value == "Don't care") 0 else value.removeSuffix("+").toIntOrNull() ?: 0
                        viewModel.updateSourceMinimumSeeds(currentProfile.id, seeds)
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            SettingToggleRow(
                label = "Try Sources Automatically",
                subtitle = "If an auto-selected source is bogus, pause it and try the next ranked source",
                isChecked = currentProfile.sourceAutoFallback,
                onCheckedChange = { viewModel.updateSourceAutoFallback(currentProfile.id, it) },
                onBack = onGoBack
            )

            if (currentProfile.sourceAutoFallback) {
                val waitOptions = listOf(15, 30, 60, 90, 120, 180, 300)
                val waitLabel = "${currentProfile.sourceDebridMaxWaitSeconds}s"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Max debrid wait", color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
                    FilterDropdown(
                        currentValue = waitLabel,
                        options = waitOptions.map { "${it}s" },
                        modifier = Modifier.width(160.dp),
                        onSelect = { value ->
                            viewModel.updateSourceDebridMaxWaitSeconds(currentProfile.id, value.removeSuffix("s").toIntOrNull() ?: 120)
                        }
                    )
                }
            }

            Spacer(Modifier.height(15.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.1f)))
            Spacer(Modifier.height(15.dp))
'''
s = rep(s, anchor, insert, "source preference UI")
p.write_text(s)

# DetailsViewModel: route media type into the ranking target.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt"
s = p.read_text()
old = '''    private suspend fun sortStreams(rawStreams: List<Stream>): List<Stream> {
        val activeProfileId = profileConfigurationManager.getLastActiveProfileId()
        val profile = activeProfileId?.let { dao.getProfileById(it) }
        return if (profile?.sourceSortingEnabled != false) {
            val enabledQualities = StreamSortingService.parseEnabledQualities(profile?.sourceEnabledQualities ?: "4k,1080p,720p,unknown")
            val excludePhrases = StreamSortingService.parseExcludePhrases(profile?.sourceExcludePhrases ?: "")
            val addonSortOrders = dao.getAllAddons().firstOrNull()
                ?.associate { it.transportUrl to it.sortOrder } ?: emptyMap()
            val excludedFormats = StreamSortingService.parseExcludedFormats(profile?.sourceExcludedFormats ?: "")
            streamSortingService.sortAndFilter(rawStreams, enabledQualities, excludePhrases, addonSortOrders, profile?.sourceSortPrimary ?: "quality", profile?.sourceMaxSizeGb ?: 0, excludedFormats)
        } else rawStreams
    }
'''
new = '''    private suspend fun sortStreams(rawStreams: List<Stream>, mediaType: String): List<Stream> {
        val activeProfileId = profileConfigurationManager.getLastActiveProfileId()
        val profile = activeProfileId?.let { dao.getProfileById(it) }
        return if (profile?.sourceSortingEnabled != false) {
            val enabledQualities = StreamSortingService.parseEnabledQualities(profile?.sourceEnabledQualities ?: "4k,1080p,720p,unknown")
            val excludePhrases = StreamSortingService.parseExcludePhrases(profile?.sourceExcludePhrases ?: "")
            val addonSortOrders = dao.getAllAddons().firstOrNull()
                ?.associate { it.transportUrl to it.sortOrder } ?: emptyMap()
            val excludedFormats = StreamSortingService.parseExcludedFormats(profile?.sourceExcludedFormats ?: "")
            val preferredSizeMb = if (mediaType.equals("movie", ignoreCase = true)) {
                profile?.sourceMovieTargetSizeMb ?: 3000
            } else {
                profile?.sourceEpisodeTargetSizeMb ?: 750
            }
            streamSortingService.sortAndFilter(
                rawStreams, enabledQualities, excludePhrases, addonSortOrders,
                profile?.sourceSortPrimary ?: "quality", profile?.sourceMaxSizeGb ?: 0,
                excludedFormats, preferredSizeMb, profile?.sourceMinimumSeeds ?: 5
            )
        } else rawStreams
    }
'''
s = rep(s, old, new, "Details sortStreams")
s = rep(s,
    '''    private suspend fun applyResolvedStreams(
        displayTitle: String,''',
    '''    private suspend fun applyResolvedStreams(
        mediaType: String,
        displayTitle: String,''',
    "apply media type")
s = rep(s, '        val streams = sortStreams(rawStreams)\n', '        val streams = sortStreams(rawStreams, mediaType)\n', 'apply sort call')
s = rep(s, '                    displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,\n', '                    type, displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,\n', 'cached apply call')
s = rep(s, '                    displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,\n', '                    type, displayTitle, sourceSelectionId, forceSourcePicker, autoSelectSource,\n', 'fresh apply call')
s = rep(s, '                    val streams = sortStreams(rawStreams)\n', '                    val streams = sortStreams(rawStreams, type)\n', 'background sort call')
p.write_text(s)

# PlayerScreen: detect placeholder runtimes, pause, and ask MainActivity to recover.
p = root / "app/src/main/java/com/hereliesaz/illumera/ui/player/PlayerScreen.kt"
s = p.read_text()
s = rep(s,
    '    torrentProgress: TorrentProgress? = null,\n    viewModel: PlayerViewModel = hiltViewModel()\n',
    '    torrentProgress: TorrentProgress? = null,\n    autoFallbackEnabled: Boolean = false,\n    onSuspectSource: ((PlaybackDurationStatus) -> Unit)? = null,\n    viewModel: PlayerViewModel = hiltViewModel()\n',
    'PlayerScreen fallback params')
anchor = '''    val uiState by playbackController.uiState.collectAsState()
    val shouldKeepScreenOn = uiState.playWhenReady || uiState.isPlaying || uiState.isBuffering
'''
insert = '''    val uiState by playbackController.uiState.collectAsState()
    var suspectHandledForUrl by remember(videoUrl) { mutableStateOf(false) }

    LaunchedEffect(uiState.isReady, uiState.durationMs, videoUrl, autoFallbackEnabled) {
        if (!autoFallbackEnabled || suspectHandledForUrl || !uiState.isReady || uiState.durationMs <= 0L) return@LaunchedEffect
        if (movieId.startsWith("trailer_") || movieId.startsWith("debrid_")) return@LaunchedEffect
        val status = viewModel.classifyDuration(mediaType, uiState.durationMs)
        if (status != PlaybackDurationStatus.NORMAL) {
            suspectHandledForUrl = true
            playbackController.pause()
            onSuspectSource?.invoke(status)
        }
    }

    val shouldKeepScreenOn = uiState.playWhenReady || uiState.isPlaying || uiState.isBuffering
'''
s = rep(s, anchor, insert, 'placeholder detection')
s = rep(s,
    '        val completed = duration != null && viewModel.isCompleted(position, duration)\n',
    '        val completed = duration != null && viewModel.isCompleted(position, duration, mediaType)\n',
    'media aware completion')
p.write_text(s)

# MainActivity: inject debrid coordination, use soft ranking everywhere, and retry invalid sources.
p = root / "app/src/main/java/com/hereliesaz/illumera/MainActivity.kt"
s = p.read_text()
s = rep(s,
    'import com.hereliesaz.illumera.data.queue.QueueItem\n',
    'import com.hereliesaz.illumera.data.queue.QueueItem\nimport com.hereliesaz.illumera.data.debrid.DebridManager\n',
    'debrid import')
s = rep(s,
    'import com.hereliesaz.illumera.ui.player.PlayerSessionResult\n',
    'import com.hereliesaz.illumera.ui.player.PlayerSessionResult\nimport com.hereliesaz.illumera.ui.player.PlaybackDurationStatus\n',
    'duration status import')
s = rep(s,
    '''    @Inject
    lateinit var streamSortingService: StreamSortingService
''',
    '''    @Inject
    lateinit var streamSortingService: StreamSortingService
    @Inject
    lateinit var debridManager: DebridManager
''',
    'debrid injection')

old_call = 'streamSortingService.sortAndFilter(rawStreams, enabledQ, excludeP, addonOrders, currentProfile?.sourceSortPrimary ?: "quality", currentProfile?.sourceMaxSizeGb ?: 0, excludedF)'
new_call = 'streamSortingService.sortAndFilter(rawStreams, enabledQ, excludeP, addonOrders, currentProfile?.sourceSortPrimary ?: "quality", currentProfile?.sourceMaxSizeGb ?: 0, excludedF, currentProfile?.sourceEpisodeTargetSizeMb ?: 750, currentProfile?.sourceMinimumSeeds ?: 5)'
s = rep(s, old_call, new_call, 'next episode sorting', count=1)
old_call2 = 'streamSortingService.sortAndFilter(rawStreams2, enabledQ, excludeP, addonOrders, currentProfile?.sourceSortPrimary ?: "quality", currentProfile?.sourceMaxSizeGb ?: 0, excludedF)'
new_call2 = 'streamSortingService.sortAndFilter(rawStreams2, enabledQ, excludeP, addonOrders, currentProfile?.sourceSortPrimary ?: "quality", currentProfile?.sourceMaxSizeGb ?: 0, excludedF, currentProfile?.sourceEpisodeTargetSizeMb ?: 750, currentProfile?.sourceMinimumSeeds ?: 5)'
s = rep(s, old_call2, new_call2, 'episode selection sorting')

player_anchor = '''                            PlayerScreen(
                                videoUrl = selectedVideoUrl,
'''
fallback = '''                            val tryNextRankedSource: suspend () -> Unit = nextSource@{
                                val pending = playerState.pendingSourceSelection
                                val candidates = pending?.candidateStreams.orEmpty()
                                val current = playerState.currentStream
                                val currentIndex = candidates.indexOfFirst { candidate ->
                                    candidate === current || resolvePlayableSourceUrl(candidate) == selectedVideoUrl ||
                                        (current != null && candidate.infoHash != null && candidate.infoHash == current.infoHash && candidate.addonTransportUrl == current.addonTransportUrl)
                                }
                                val nextStream = candidates.drop((currentIndex + 1).coerceAtLeast(0))
                                    .firstOrNull { !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank() }
                                if (nextStream == null) {
                                    playerState.pendingSourceSelection = null
                                    activeView = "details"
                                    return@nextSource
                                }

                                val nextUrl = resolvePlayableSourceUrl(nextStream)
                                if (nextUrl == null) {
                                    activeView = "details"
                                    return@nextSource
                                }
                                playerState.currentStream = nextStream
                                playerState.pendingSourceSelection = PendingSourceSelection(
                                    playbackId = selectedPlaybackId,
                                    launchedStream = nextStream,
                                    candidateStreams = candidates
                                )
                                playerState.selectedPlayerSources = buildSourcePayload(candidates, nextStream)

                                if (nextUrl.startsWith("magnet:")) {
                                    selectedVideoUrl = ""
                                    torrentProgress = TorrentProgress("Trying next source…")
                                    TorrentService.onStreamReady = { localUrl ->
                                        torrentProgress = null
                                        selectedVideoUrl = localUrl
                                    }
                                    TorrentService.onStreamError = {
                                        torrentProgress = null
                                        uiScope.launch { tryNextRankedSource() }
                                    }
                                    TorrentService.onStreamProgress = { torrentProgress = it }
                                    startService(Intent(this@MainActivity, TorrentService::class.java).apply {
                                        putExtra("MAGNET_LINK", nextUrl)
                                        putExtra("FILE_IDX", nextStream.fileIdx ?: -1)
                                        putExtra("FILE_NAME", nextStream.behaviorHints?.filename ?: "")
                                    })
                                } else {
                                    stopService(Intent(this@MainActivity, TorrentService::class.java))
                                    selectedVideoUrl = nextUrl
                                }
                            }

                            PlayerScreen(
                                videoUrl = selectedVideoUrl,
'''
s = rep(s, player_anchor, fallback, 'fallback helper')

param_anchor = '''                                torrentProgress = torrentProgress,
                                onBack = { sessionResult ->
'''
param_insert = '''                                torrentProgress = torrentProgress,
                                autoFallbackEnabled = currentProfile?.autoSelectSource == true && currentProfile?.sourceAutoFallback != false,
                                onSuspectSource = { status ->
                                    uiScope.launch {
                                        val currentStream = playerState.currentStream
                                        if (status == PlaybackDurationStatus.DEBRID_DOWNLOADING && currentStream != null) {
                                            val readyUrl = debridManager.awaitPlayableSource(
                                                infoHash = currentStream.infoHash,
                                                fileName = currentStream.behaviorHints?.filename,
                                                maxWaitSeconds = currentProfile?.sourceDebridMaxWaitSeconds ?: 120
                                            )
                                            if (!readyUrl.isNullOrBlank()) {
                                                selectedVideoUrl = readyUrl
                                                playerState.currentStream = currentStream.copy(url = readyUrl)
                                                return@launch
                                            }
                                        }
                                        tryNextRankedSource()
                                    }
                                },
                                onBack = { sessionResult ->
'''
s = rep(s, param_anchor, param_insert, 'PlayerScreen suspect callback')
p.write_text(s)

print('source intelligence integration applied')
