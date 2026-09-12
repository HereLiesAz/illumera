from pathlib import Path

ROOT = Path.cwd()


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one occurrence, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


def replace_exact_count(path: str, old: str, new: str, expected: int) -> None:
    text = read(path)
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} occurrences, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new))


def replace_block(path: str, start: str, end: str, replacement: str) -> None:
    text = read(path)
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"{path}: start marker not found: {start!r}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"{path}: end marker not found: {end!r}")
    write(path, text[:start_index] + replacement + text[end_index:])


# ---------------------------------------------------------------------------
# GitHub Actions: move every repository-owned Node action pin off Node 20.
# Exact target SHAs were verified against the tagged releases' action.yml.
# ---------------------------------------------------------------------------
workflow_dir = ROOT / ".github" / "workflows"
node_action_replacements = {
    "11d5960a326750d5838078e36cf38b85af677262": "3d3c42e5aac5ba805825da76410c181273ba90b1",  # checkout v7.0.1 / node24
    "0057852bfaa89a56745cba8c7296529d2fc39830": "55cc8345863c7cc4c66a329aec7e433d2d1c52a9",  # cache v6.1.0 / node24
    "cf277c60eb25467037889841efdb72551f06f6c3": "de7274f081f381c8f8158605e0321c36c376e2e6",  # setup-java v6.0.1 / node24
    "9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407": "40fd30fb8d7440372e1316f5d1809ec01dcd3699",  # setup-android v4.0.1 / node24
    "3bb12739c298aeb8a4eeaf626c5b8d85266b0e65": "efb35369e0ad2afab669f228072c1b0d510eae64",  # action-gh-release v3.0.3 / node24
}
seen = {old: 0 for old in node_action_replacements}
for workflow in workflow_dir.glob("*.yml"):
    text = workflow.read_text()
    for old, new in node_action_replacements.items():
        count = text.count(old)
        seen[old] += count
        text = text.replace(old, new)
    text = text.replace("# v4\n", "# v7.0.1\n") if "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v4" in text else text
    text = text.replace("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v4", "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1")
    text = text.replace("actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v4", "actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0")
    text = text.replace("actions/cache/save@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v4", "actions/cache/save@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0")
    text = text.replace("actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v4", "actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1")
    text = text.replace("android-actions/setup-android@40fd30fb8d7440372e1316f5d1809ec01dcd3699 # v3", "android-actions/setup-android@40fd30fb8d7440372e1316f5d1809ec01dcd3699 # v4.0.1")
    text = text.replace("softprops/action-gh-release@efb35369e0ad2afab669f228072c1b0d510eae64 # v2", "softprops/action-gh-release@efb35369e0ad2afab669f228072c1b0d510eae64 # v3.0.3")
    workflow.write_text(text)

for old, count in seen.items():
    if count == 0:
        raise RuntimeError(f"Expected old action SHA was not present in any workflow: {old}")

release_path = ".github/workflows/release.yml"
release_anchor = """      - name: Create GitHub Release\n        uses: softprops/action-gh-release@efb35369e0ad2afab669f228072c1b0d510eae64 # v3.0.3\n"""
release_tag_step = """      - name: Create release tag\n        env:\n          GH_TOKEN: ${{ github.token }}\n          RELEASE_TAG: ${{ steps.version.outputs.tag }}\n          RELEASE_SHA: ${{ github.sha }}\n        run: |\n          set -euo pipefail\n          if TAG_JSON=$(gh api \"repos/${GITHUB_REPOSITORY}/git/ref/tags/${RELEASE_TAG}\" 2>/dev/null); then\n            TAG_SHA=$(jq -r '.object.sha' <<<\"$TAG_JSON\")\n            test \"$TAG_SHA\" = \"$RELEASE_SHA\" || {\n              echo \"Tag $RELEASE_TAG already points to $TAG_SHA, expected $RELEASE_SHA\" >&2\n              exit 1\n            }\n          else\n            gh api --method POST \"repos/${GITHUB_REPOSITORY}/git/refs\" \\\n              -f ref=\"refs/tags/${RELEASE_TAG}\" \\\n              -f sha=\"$RELEASE_SHA\" >/dev/null\n          fi\n\n      - name: Create GitHub Release\n        uses: softprops/action-gh-release@efb35369e0ad2afab669f228072c1b0d510eae64 # v3.0.3\n"""
replace_once(release_path, release_anchor, release_tag_step)
replace_once(release_path, "          target_commitish: ${{ github.sha }}\n", "")

# ---------------------------------------------------------------------------
# Details: never leave playback permanently gated if optional resume DAO work fails.
# ---------------------------------------------------------------------------
details_path = "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt"
details_replacement = '''    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {
        if (meta == null) {
            if (_state.value.resumePlaybackId != null) {
                _state.value = _state.value.copy(resumePlaybackId = null)
            }
            return
        }

        _state.value = _state.value.copy(isResumeStateReady = false)
        viewModelScope.launch {
            try {
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (_state.value.meta?.id == meta.id && _state.value.meta?.type == meta.type) {
                    Log.w("DetailsViewModel", "Resume-state lookup failed; falling back to normal playback", e)
                    _state.value = _state.value.copy(
                        resumePlaybackId = null,
                        isResumeStateReady = true,
                        isMovieWatched = false,
                        autoPlayStream = null,
                        episodeProgressMap = emptyMap()
                    )
                }
            }
        }
    }

'''
replace_block(
    details_path,
    "    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {",
    "    private suspend fun resolveSeriesResumePlaybackId(",
    details_replacement,
)

# ---------------------------------------------------------------------------
# Watchlist/Queue: keep one current focus target and invalidate removed queue keys.
# ---------------------------------------------------------------------------
watchlist_path = "app/src/main/java/com/hereliesaz/illumera/ui/watchlist/WatchlistScreen.kt"
replace_once(
    watchlist_path,
    "    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }\n",
    "    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }\n    var lastQueueFocusedKey by remember { mutableStateOf(viewModel.lastQueueFocusedKey) }\n",
)
watchlist_focus_old = '''                        onFocused = { _: MetaItem?, key: String ->
                            lastFocusedKey = key
                            viewModel.lastFocusedKey = key
                        },'''
watchlist_focus_new = '''                        onFocused = { _: MetaItem?, key: String ->
                            lastFocusedKey = key
                            viewModel.lastFocusedKey = key
                            lastQueueFocusedKey = null
                            viewModel.lastQueueFocusedKey = null
                        },'''
replace_exact_count(watchlist_path, watchlist_focus_old, watchlist_focus_new, 2)
replace_once(
    watchlist_path,
    '''                    requestEntryFocus = !hasWatchlistMedia && viewModel.lastQueueFocusedKey == null,
                    focusedQueueKey = viewModel.lastQueueFocusedKey,
                    onQueueFocused = { key -> viewModel.lastQueueFocusedKey = key },''',
    '''                    requestEntryFocus = !hasWatchlistMedia && lastQueueFocusedKey == null,
                    focusedQueueKey = lastQueueFocusedKey,
                    restoreEntryFocusWhenFocusedKeyMissing = !hasWatchlistMedia,
                    onQueueFocused = { key ->
                        lastQueueFocusedKey = key
                        viewModel.lastQueueFocusedKey = key
                        if (key != null) {
                            lastFocusedKey = null
                            viewModel.lastFocusedKey = null
                        }
                    },''',
)

queue_path = "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt"
replace_once(
    queue_path,
    '''    requestEntryFocus: Boolean = false,
    focusedQueueKey: String? = null,
    onQueueFocused: (String) -> Unit = {}
) {''',
    '''    requestEntryFocus: Boolean = false,
    focusedQueueKey: String? = null,
    restoreEntryFocusWhenFocusedKeyMissing: Boolean = false,
    onQueueFocused: (String?) -> Unit = {}
) {''',
)
replace_once(
    queue_path,
    '''    LaunchedEffect(state.manualItems, state.suggestions) {
        queueManager.resolveMissingArtwork()
    }

    Column(''',
    '''    LaunchedEffect(state.manualItems, state.suggestions) {
        queueManager.resolveMissingArtwork()
    }

    LaunchedEffect(
        state.manualItems,
        state.suggestions,
        focusedQueueKey,
        restoreEntryFocusWhenFocusedKeyMissing
    ) {
        val key = focusedQueueKey ?: return@LaunchedEffect
        val stillExists = state.manualItems.any { it.stableKey == key } ||
            state.suggestions.any { it.stableKey == key }
        if (!stillExists) {
            onQueueFocused(null)
            if (restoreEntryFocusWhenFocusedKeyMissing) {
                kotlinx.coroutines.delay(50)
                runCatching { entryRequester.requestFocus() }
            }
        }
    }

    Column(''',
)

# ---------------------------------------------------------------------------
# Facebook login: give browser/2FA users enough time and make polling cancellable.
# ---------------------------------------------------------------------------
auth_service_path = "app/src/main/java/com/hereliesaz/illumera/data/remote/StremioAuthService.kt"
replace_once(
    auth_service_path,
    "suspend fun pollFacebookLogin(state: String, maxAttempts: Int = 25)",
    "suspend fun pollFacebookLogin(state: String, maxAttempts: Int = 300)",
)

auth_manager_path = "app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt"
replace_once(
    auth_manager_path,
    "import kotlinx.coroutines.CoroutineScope\n",
    "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CoroutineScope\n",
)
replace_once(
    auth_manager_path,
    '''        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Pushes the given addons up to the account's addon collection,''',
    '''        } catch (ce: CancellationException) {
            throw ce
        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Pushes the given addons up to the account's addon collection,''',
)

integrations_vm_path = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsViewModel.kt"
replace_once(
    integrations_vm_path,
    "import kotlinx.coroutines.Dispatchers\n",
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\n",
)
replace_once(
    integrations_vm_path,
    '''    private val _events = Channel<IntegrationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
''',
    '''    private val _events = Channel<IntegrationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var facebookLoginJob: Job? = null
''',
)
replace_once(
    integrations_vm_path,
    '''        viewModelScope.launch {
            val result = stremioAuthManager.completeFacebookLogin(state)
            result.fold(''',
    '''        facebookLoginJob?.cancel()
        facebookLoginJob = viewModelScope.launch {
            val result = stremioAuthManager.completeFacebookLogin(state)
            result.fold(''',
)
replace_once(
    integrations_vm_path,
    '''    fun resetFacebookLoginState() {
        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.Idle)
    }
''',
    '''    fun resetFacebookLoginState() {
        facebookLoginJob?.cancel()
        facebookLoginJob = null
        _uiState.value = _uiState.value.copy(facebookLoginState = FacebookLoginState.Idle)
    }
''',
)

# ---------------------------------------------------------------------------
# Debrid TV dialog: constrain to viewport and scroll all contents.
# ---------------------------------------------------------------------------
integrations_screen_path = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt"
replace_once(
    integrations_screen_path,
    '''    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
''',
    '''    val accentColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val debridScrollState = rememberScrollState()
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
''',
)
replace_once(
    integrations_screen_path,
    '''                modifier = Modifier
                    .width(rememberDialogWidth(460))
                    .clip(RoundedCornerShape(16.dp))''',
    '''                modifier = Modifier
                    .width(rememberDialogWidth(460))
                    .heightIn(max = maxDialogHeight)
                    .clip(RoundedCornerShape(16.dp))''',
)
replace_once(
    integrations_screen_path,
    '''            ) {
                Column {
                    Text(
                        "Debrid Service",''',
    '''            ) {
                Column(modifier = Modifier.verticalScroll(debridScrollState)) {
                    Text(
                        "Debrid Service",''',
)

# ---------------------------------------------------------------------------
# Stream language filtering: canonical subtitle codes, "subbed", and explicit
# audio tags in source name/filename without reintroducing two-letter title hits.
# ---------------------------------------------------------------------------
stream_sort_path = "app/src/main/java/com/hereliesaz/illumera/data/stream/StreamSortingService.kt"
replace_block(
    stream_sort_path,
    "    private fun matchesAudioLanguageRequirement(stream: Stream, profile: ProfileEntity?): Boolean {",
    "    private fun matchesSubtitleLanguageRequirement(stream: Stream, profile: ProfileEntity?): Boolean {",
    '''    private fun matchesAudioLanguageRequirement(stream: Stream, profile: ProfileEntity?): Boolean {
        val languages = requiredLanguages(
            mode = profile?.sourceAudioLanguageRequirement.orEmpty(),
            primary = profile?.preferredAudioLanguage.orEmpty(),
            secondary = profile?.preferredAudioLanguageSecondary.orEmpty()
        )
        if (languages.isEmpty()) return true

        val authoritativeFields = listOfNotNull(
            stream.name?.takeIf { it.isNotBlank() },
            stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }
        )
        val descriptiveText = listOfNotNull(
            stream.title?.takeIf { it.isNotBlank() },
            stream.description?.takeIf { it.isNotBlank() }
        ).joinToString(" ")

        return languages.any { language ->
            authoritativeFields.any { field -> containsAuthoritativeAudioLanguage(field, language) } ||
                containsAudioLanguage(descriptiveText, language)
        }
    }

''',
)
replace_once(
    stream_sort_path,
    '''    private fun languageCodesMatch(advertised: String?, required: String): Boolean {
        val actual = normalizeLanguageTag(advertised)
        if (actual.isEmpty()) return false
        val wanted = normalizeLanguageTag(required)
        if (wanted.isEmpty()) return false
        return actual == wanted || actual.substringBefore('-') == wanted.substringBefore('-')
    }

    private fun containsAudioLanguage''',
    '''    private fun languageCodesMatch(advertised: String?, required: String): Boolean {
        val actual = canonicalLanguageTag(advertised)
        if (actual.isEmpty()) return false
        val wanted = canonicalLanguageTag(required)
        if (wanted.isEmpty()) return false
        return actual == wanted || actual.substringBefore('-') == wanted.substringBefore('-')
    }

    private fun canonicalLanguageTag(raw: String?): String {
        val normalized = normalizeLanguageTag(raw)
        if (normalized.isEmpty()) return ""
        LANGUAGE_CODE_ALIASES[normalized]?.let { return it }

        val base = normalized.substringBefore('-')
        val canonicalBase = LANGUAGE_ALIASES.entries
            .firstOrNull { (_, aliases) -> base in aliases }
            ?.key
            ?: LANGUAGE_CODE_ALIASES[base]
            ?: base
        val suffix = normalized.substringAfter('-', "")
        return if (suffix.isNotEmpty()) "$canonicalBase-$suffix" else canonicalBase
    }

    private fun containsAuthoritativeAudioLanguage(text: String, language: String): Boolean {
        return languageAliases(language)
            .asSequence()
            .filter { alias -> alias.length > 2 }
            .any { alias -> languageMatches(text, alias).any() }
    }

    private fun containsAudioLanguage''',
)
replace_once(
    stream_sort_path,
    '''        private val SUBTITLE_CUE_REGEX = Regex("(?i)\\\\b(sub(?:title)?s?|cc|captions?)\\\\b")

        private val LANGUAGE_ALIASES''',
    '''        private val SUBTITLE_CUE_REGEX = Regex("(?i)\\\\b(sub(?:title)?s?|subbed|cc|captions?)\\\\b")

        private val LANGUAGE_CODE_ALIASES = mapOf(
            "pob" to "pt-br",
            "iw" to "he",
            "in" to "id",
            "fil" to "tl"
        )

        private val LANGUAGE_ALIASES''',
)

# Final guard: none of the Node-20 action SHAs that motivated this cleanup may remain.
for workflow in workflow_dir.glob("*.yml"):
    text = workflow.read_text()
    for old in node_action_replacements:
        if old in text:
            raise RuntimeError(f"{workflow}: obsolete action SHA remains: {old}")

print("Recent review and Node 24 patches applied successfully")
