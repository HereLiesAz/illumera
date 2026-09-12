from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual < count:
        raise SystemExit(f"{path}: expected at least {count} occurrence(s), found {actual}: {old[:100]!r}")
    p.write_text(text.replace(old, new, count))


# PR audit finding 1: do not treat an uncued language word from a media title as audio metadata.
replace(
    "app/src/main/java/com/hereliesaz/illumera/data/stream/StreamSortingService.kt",
    """                    subtitleDistance != null -> false\n                    else -> true\n""",
    """                    subtitleDistance != null -> false\n                    else -> false\n""",
)

# PR audit finding 2: carry the persisted auto-fallback policy into the backend request.
replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/PlayerModels.kt",
    """    val preferredSubtitleTrackId: String? = null,\n    val separateAudioUrl: String? = null\n)\n""",
    """    val preferredSubtitleTrackId: String? = null,\n    val separateAudioUrl: String? = null,\n    val sourceAutoFallbackEnabled: Boolean = true\n)\n""",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/PlayerScreen.kt",
    """    LaunchedEffect(movieId, videoUrl, backendType) {\n""",
    """    LaunchedEffect(movieId, videoUrl, backendType, autoFallbackEnabled) {\n""",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/PlayerScreen.kt",
    """                preferredSubtitleTrackId = preferredSubtitleTrackId,\n                separateAudioUrl = trailerAudioUrl\n""",
    """                preferredSubtitleTrackId = preferredSubtitleTrackId,\n                separateAudioUrl = trailerAudioUrl,\n                sourceAutoFallbackEnabled = autoFallbackEnabled\n""",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/player/base/ExoPlayerBackend.kt",
    """        if (!codeName.contains(\"PARSING\") || _sourceListDisabled.value) return false\n""",
    """        if (\n            !codeName.contains(\"PARSING\") ||\n            _sourceListDisabled.value ||\n            loadRequest?.sourceAutoFallbackEnabled != true\n        ) return false\n""",
)

# PR audit finding 3: support Android system back and Escape while queue move mode is active.
replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt",
    "package com.hereliesaz.illumera.ui.queue\n\n",
    "package com.hereliesaz.illumera.ui.queue\n\nimport androidx.activity.compose.BackHandler\n",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt",
    """    var movingKey by remember { mutableStateOf<String?>(null) }\n\n    Column(modifier = Modifier.fillMaxWidth()) {\n""",
    """    var movingKey by remember { mutableStateOf<String?>(null) }\n    BackHandler(enabled = movingKey != null) { movingKey = null }\n\n    Column(modifier = Modifier.fillMaxWidth()) {\n""",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt",
    """                                    Key.Back -> {\n                                        movingKey = null\n                                        true\n                                    }\n""",
    """                                    Key.Back, Key.Escape -> {\n                                        movingKey = null\n                                        true\n                                    }\n""",
)

# Repository audit finding 1: token refresh must not share the authenticated request dispatcher.
replace(
    "app/src/main/java/com/hereliesaz/illumera/di/NetworkModule.kt",
    "import okhttp3.OkHttpClient\n",
    "import okhttp3.Dispatcher\nimport okhttp3.OkHttpClient\n",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/di/NetworkModule.kt",
    """    fun provideTraktRetrofit(okHttpClient: OkHttpClient): Retrofit {\n        return Retrofit.Builder()\n            .baseUrl(\"https://api.trakt.tv/\")\n            .client(okHttpClient)\n""",
    """    fun provideTraktRetrofit(okHttpClient: OkHttpClient): Retrofit {\n        // Token/device-auth calls must use a dispatcher independent from authenticated\n        // Trakt traffic. Otherwise several authenticated requests blocked in the auth\n        // interceptor can exhaust the shared per-host dispatcher and deadlock the refresh.\n        val authClient = okHttpClient.newBuilder()\n            .dispatcher(Dispatcher())\n            .build()\n        return Retrofit.Builder()\n            .baseUrl(\"https://api.trakt.tv/\")\n            .client(authClient)\n""",
)

# Repository audit finding 3: set scrobbled atomically instead of replacing a stale row.
replace(
    "app/src/main/java/com/hereliesaz/illumera/data/local/AddonDao.kt",
    """    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun upsertHistoryItems(items: List<WatchHistoryEntity>)\n\n    @Query(\"DELETE FROM watch_history\")\n""",
    """    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun upsertHistoryItems(items: List<WatchHistoryEntity>)\n\n    @Query(\"UPDATE watch_history SET scrobbled = 1 WHERE id = :id\")\n    suspend fun markHistoryScrobbled(id: String)\n\n    @Query(\"DELETE FROM watch_history\")\n""",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/data/trakt/TraktScrobbleManager.kt",
    """        val item = dao.getHistoryItem(playbackId)\n        if (item != null && !item.scrobbled) {\n            dao.upsertHistory(item.copy(scrobbled = true))\n        }\n""",
    """        dao.markHistoryScrobbled(playbackId)\n""",
)

# Repository audit finding 4: deleting a profile must also delete its recent searches.
replace(
    "app/src/main/java/com/hereliesaz/illumera/data/local/AddonDao.kt",
    """    @Query(\"DELETE FROM series_next_up WHERE profileId = :profileId\")\n    suspend fun deleteSeriesNextUpForProfile(profileId: Int)\n\n    // A plain sequence of suspend calls has no atomicity of its own — a process death or a\n""",
    """    @Query(\"DELETE FROM series_next_up WHERE profileId = :profileId\")\n    suspend fun deleteSeriesNextUpForProfile(profileId: Int)\n\n    @Query(\"DELETE FROM recent_searches WHERE profileId = :profileId\")\n    suspend fun deleteRecentSearchesForProfile(profileId: Int)\n\n    // A plain sequence of suspend calls has no atomicity of its own — a process death or a\n""",
)
replace(
    "app/src/main/java/com/hereliesaz/illumera/data/local/AddonDao.kt",
    """        deleteProfile(id)\n        deleteWatchlistForProfile(id)\n        deleteSeriesNextUpForProfile(id)\n""",
    """        deleteProfile(id)\n        deleteWatchlistForProfile(id)\n        deleteSeriesNextUpForProfile(id)\n        deleteRecentSearchesForProfile(id)\n""",
)

print("Applied confirmed Glee report fixes")
