from pathlib import Path
import re
import textwrap


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def must_replace(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{label}: expected {count} matches, found {actual}")
    return text.replace(old, new, count)


def must_sub(text: str, pattern: str, repl: str, label: str, count: int = 1, flags: int = 0) -> str:
    out, actual = re.subn(pattern, repl, text, count=count, flags=flags)
    if actual != count:
        raise SystemExit(f"{label}: expected {count} regex matches, found {actual}")
    return out


def section(text: str, start: str, end: str, label: str):
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"{label}: start marker missing")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"{label}: end marker missing")
    return i, j, text[i:j]


def mutate_function(text: str, start: str, end: str, label: str, transform) -> str:
    i, j, part = section(text, start, end, label)
    changed = transform(part)
    if changed == part:
        raise SystemExit(f"{label}: transform made no change")
    return text[:i] + changed + text[j:]


# Resume-state lookup failures must never leave playback permanently gated.
path = "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt"
text = read(path)
start = "    private fun refreshResumeState(meta: MetaItem) {"
end = "\n    private suspend fun buildEpisodeProgressMap"
i, j, _ = section(text, start, end, "DetailsViewModel.refreshResumeState")
new_fn = textwrap.indent(
    textwrap.dedent(
        '''\
private fun refreshResumeState(meta: MetaItem) {
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
    ),
    "    ",
).rstrip()
write(path, text[:i] + new_fn + text[j:])

# Queue focus must recover when the selected queue item disappears.
path = "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt"
text = read(path)
text = must_replace(
    text,
    "    onQueueFocused: (String) -> Unit = {}",
    "    restoreEntryFocusWhenFocusedKeyMissing: Boolean = false,\n    onQueueFocused: (String?) -> Unit = {}",
    "QueueSection callback",
)
text = must_sub(
    text,
    r'(    LaunchedEffect\(state\.manualItems, state\.suggestions\) \{\n        queueManager\.resolveMissingArtwork\(\)\n    \}\n)',
    r'''\1
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
''',
    "QueueSection missing-key recovery",
)
write(path, text)

# Keep media-row and queue focus state mutually coherent on Watchlist.
path = "app/src/main/java/com/hereliesaz/illumera/ui/watchlist/WatchlistScreen.kt"
text = read(path)
text = must_replace(
    text,
    "    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }",
    "    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }\n    var lastQueueFocusedKey by remember { mutableStateOf(viewModel.lastQueueFocusedKey) }",
    "Watchlist queue focus state",
)
text = must_sub(
    text,
    r'(                        onFocused = \{ _: MetaItem\?, key: String ->\n                            lastFocusedKey = key\n                            viewModel\.lastFocusedKey = key\n)(                        \},)',
    r'\1                            lastQueueFocusedKey = null\n                            viewModel.lastQueueFocusedKey = null\n\2',
    "Watchlist media focus handlers",
    count=2,
)
old = (
    "                    requestEntryFocus = !hasWatchlistMedia && viewModel.lastQueueFocusedKey == null,\n"
    "                    focusedQueueKey = viewModel.lastQueueFocusedKey,\n"
    "                    onQueueFocused = { key -> viewModel.lastQueueFocusedKey = key },"
)
new = (
    "                    requestEntryFocus = !hasWatchlistMedia && lastQueueFocusedKey == null,\n"
    "                    focusedQueueKey = lastQueueFocusedKey,\n"
    "                    restoreEntryFocusWhenFocusedKeyMissing = !hasWatchlistMedia,\n"
    "                    onQueueFocused = { key ->\n"
    "                        lastQueueFocusedKey = key\n"
    "                        viewModel.lastQueueFocusedKey = key\n"
    "                        if (key != null) {\n"
    "                            lastFocusedKey = null\n"
    "                            viewModel.lastFocusedKey = null\n"
    "                        }\n"
    "                    },"
)
text = must_replace(text, old, new, "Watchlist QueueSection wiring")
write(path, text)

# Debrid configuration must remain usable with small displays and an IME open.
path = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt"
text = read(path)
start = "@Composable\nprivate fun DebridDialog("
end = "\n@Composable\nprivate fun TraktAuthDialog("
i, j, part = section(text, start, end, "DebridDialog")
part = must_replace(
    part,
    "    val context = LocalContext.current",
    "    val context = LocalContext.current\n    val debridScrollState = rememberScrollState()\n    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp",
    "DebridDialog scroll state",
)
part = must_replace(
    part,
    "                    .width(rememberDialogWidth(460))",
    "                    .width(rememberDialogWidth(460))\n                    .heightIn(max = maxDialogHeight)",
    "DebridDialog height cap",
)
part = must_replace(
    part,
    "                Column {",
    "                Column(modifier = Modifier.verticalScroll(debridScrollState)) {",
    "DebridDialog scrolling",
)
write(path, text[:i] + part + text[j:])

# Do not poll Facebook OAuth forever.
path = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsViewModel.kt"
text = read(path)
text = must_replace(
    text,
    "import kotlinx.coroutines.launch\n",
    "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withTimeoutOrNull\n",
    "IntegrationsViewModel timeout import",
)
old = (
    "            facebookLoginJob = viewModelScope.launch {\n"
    "                val result = stremioAuthManager.completeFacebookLogin(state)\n"
    "                result.fold("
)
new = (
    "            facebookLoginJob = viewModelScope.launch {\n"
    "                val result = withTimeoutOrNull(5 * 60_000L) {\n"
    "                    stremioAuthManager.completeFacebookLogin(state)\n"
    "                }\n"
    "                if (result == null) {\n"
    "                    _uiState.value = _uiState.value.copy(\n"
    "                        facebookLoginState = FacebookLoginState.Error(\"Facebook login timed out. Please try again.\")\n"
    "                    )\n"
    "                    return@launch\n"
    "                }\n"
    "                result.fold("
)
text = must_replace(text, old, new, "Facebook login timeout")
write(path, text)

# Keystore failure must fail closed, never write account credentials to plaintext preferences.
path = "app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt"
text = read(path)
start = "    private val encryptedPrefs: SharedPreferences by lazy {"
end = "\n    private fun createEncryptedPrefs(): SharedPreferences {"
i, j, _ = section(text, start, end, "encryptedPrefs")
secure_decl = textwrap.indent(
    textwrap.dedent(
        '''\
private val encryptedPrefs: SharedPreferences? by lazy {
    try {
        createEncryptedPrefs()
    } catch (e: Exception) {
        Log.e("StremioAuthManager", "EncryptedSharedPreferences corrupted, resetting", e)
        clearCorruptedPrefs()
        try {
            createEncryptedPrefs()
        } catch (e2: Exception) {
            Log.e("StremioAuthManager", "Secure credential storage unavailable", e2)
            null
        }
    }
}
'''
    ),
    "    ",
).rstrip()
text = text[:i] + secure_decl + text[j:]
text = must_replace(
    text,
    "    private val _connectionState = MutableStateFlow<StremioConnectionState>(StremioConnectionState.Disconnected)",
    "    private fun clearLegacyFallbackPrefs() {\n"
    "        val prefsDir = File(context.applicationInfo.dataDir, \"shared_prefs\")\n"
    "        File(prefsDir, \"${PREFS_FILE}_fallback.xml\").delete()\n"
    "        File(prefsDir, \"${PREFS_FILE}_fallback.xml.bak\").delete()\n"
    "    }\n\n"
    "    private val _connectionState = MutableStateFlow<StremioConnectionState>(StremioConnectionState.Disconnected)",
    "legacy plaintext credential cleanup",
)
text = must_replace(
    text,
    "    init {\n        refreshConnectionState()\n    }",
    "    init {\n        clearLegacyFallbackPrefs()\n        refreshConnectionState()\n    }",
    "auth initialization",
)
text = must_replace(text, "return encryptedPrefs.getString(KEY_AUTH_KEY, null)", "return encryptedPrefs?.getString(KEY_AUTH_KEY, null)", "auth key getter")
text = must_replace(text, "return encryptedPrefs.getString(KEY_EMAIL, null)", "return encryptedPrefs?.getString(KEY_EMAIL, null)", "email getter")
text = must_replace(text, "return encryptedPrefs.getString(KEY_AVATAR, null)", "return encryptedPrefs?.getString(KEY_AVATAR, null)", "avatar getter")


def add_prefs_or_return(part: str, signature: str) -> str:
    return must_replace(
        part,
        signature,
        signature + "\n        val prefs = encryptedPrefs ?: return",
        "secure prefs guard",
    )


text = mutate_function(
    text,
    "    fun saveCredentialsForProfile(",
    "\n    fun loadCredentialsForProfile(",
    "saveCredentialsForProfile",
    lambda p: add_prefs_or_return(p, "    fun saveCredentialsForProfile(profileId: Int) {").replace("encryptedPrefs.edit()", "prefs.edit()"),
)


def patch_load(part: str) -> str:
    part = must_replace(
        part,
        "    fun loadCredentialsForProfile(profileId: Int) {",
        "    fun loadCredentialsForProfile(profileId: Int) {\n"
        "        val prefs = encryptedPrefs ?: run {\n"
        "            _connectionState.value = StremioConnectionState.Disconnected\n"
        "            return\n"
        "        }",
        "loadCredentialsForProfile guard",
    )
    return part.replace("encryptedPrefs.getString", "prefs.getString").replace("encryptedPrefs.edit()", "prefs.edit()")


text = mutate_function(
    text,
    "    fun loadCredentialsForProfile(",
    "\n    fun copyCredentialsBetweenProfiles(",
    "loadCredentialsForProfile",
    patch_load,
)
text = mutate_function(
    text,
    "    fun copyCredentialsBetweenProfiles(",
    "\n    fun clearCredentialsForProfile(",
    "copyCredentialsBetweenProfiles",
    lambda p: add_prefs_or_return(p, "    fun copyCredentialsBetweenProfiles(sourceProfileId: Int, targetProfileId: Int) {").replace("encryptedPrefs.getString", "prefs.getString").replace("encryptedPrefs.edit()", "prefs.edit()"),
)
text = mutate_function(
    text,
    "    fun clearCredentialsForProfile(",
    "\n    /**\n     * Logs in to Stremio",
    "clearCredentialsForProfile",
    lambda p: add_prefs_or_return(p, "    fun clearCredentialsForProfile(profileId: Int) {").replace("encryptedPrefs.edit()", "prefs.edit()"),
)

storage_failure = 'Result.failure<String>(StremioAuthError.UnknownError("Secure credential storage is unavailable"))'


def patch_login(part: str) -> str:
    part = must_replace(
        part,
        "withContext(Dispatchers.IO) {\n        try {",
        f"withContext(Dispatchers.IO) {{\n        val prefs = encryptedPrefs ?: return@withContext {storage_failure}\n        try {{",
        "login secure prefs guard",
    )
    part = part.replace("encryptedPrefs.edit()", "prefs.edit()")
    part = must_replace(
        part,
        "        } catch (e: StremioAuthError.InvalidCredentials) {",
        "        } catch (e: CancellationException) {\n            throw e\n        } catch (e: StremioAuthError.InvalidCredentials) {",
        "login cancellation",
    )
    return part


text = mutate_function(
    text,
    "    suspend fun login(",
    "\n    /**\n     * Creates a Stremio account",
    "login",
    patch_login,
)


def patch_register(part: str) -> str:
    part = must_replace(
        part,
        "withContext(Dispatchers.IO) {\n        try {",
        f"withContext(Dispatchers.IO) {{\n        val prefs = encryptedPrefs ?: return@withContext {storage_failure}\n        try {{",
        "register secure prefs guard",
    )
    part = part.replace("encryptedPrefs.edit()", "prefs.edit()")
    part = must_replace(
        part,
        "        } catch (e: StremioAuthError) {",
        "        } catch (e: CancellationException) {\n            throw e\n        } catch (e: StremioAuthError) {",
        "register cancellation",
    )
    return part


text = mutate_function(
    text,
    "    suspend fun register(",
    "\n    /**\n     * Fetches the user's addon collection",
    "register",
    patch_register,
)


def patch_oauth(part: str, label: str) -> str:
    part = must_replace(
        part,
        "withContext(Dispatchers.IO) {\n        try {",
        f"withContext(Dispatchers.IO) {{\n        val prefs = encryptedPrefs ?: return@withContext {storage_failure}\n        try {{",
        f"{label} secure prefs guard",
    )
    return part.replace("encryptedPrefs.edit()", "prefs.edit()")


text = mutate_function(
    text,
    "    suspend fun completeFacebookLogin(",
    "\n    /** Starts Stremio's browser-based Sign in with Apple flow.",
    "completeFacebookLogin",
    lambda p: patch_oauth(p, "Facebook OAuth"),
)
text = mutate_function(
    text,
    "    suspend fun completeAppleLogin(",
    "\n    /**\n     * Pushes the given addons",
    "completeAppleLogin",
    lambda p: patch_oauth(p, "Apple OAuth"),
)
text = must_replace(
    text,
    "        encryptedPrefs.edit()\n            .remove(KEY_AUTH_KEY)\n            .remove(KEY_EMAIL)\n            .remove(KEY_AVATAR)\n            .apply()",
    "        encryptedPrefs?.edit()\n            ?.remove(KEY_AUTH_KEY)\n            ?.remove(KEY_EMAIL)\n            ?.remove(KEY_AVATAR)\n            ?.apply()",
    "disconnect secure prefs",
)
write(path, text)

# AGP owns native-lib extraction policy; keep it out of the manifest.
path = "app/src/main/AndroidManifest.xml"
text = read(path)
text = must_replace(text, "        android:extractNativeLibs=\"true\"\n", "", "manifest native library packaging")
write(path, text)

# Never hand a repository secret to mutable workflow code.
path = ".github/workflows/jules-glee.yml"
text = read(path)
text = must_replace(
    text,
    "HereLiesAz/workflows-starter-template/.github/workflows/jules-glee-reusable.yml@main",
    "HereLiesAz/workflows-starter-template/.github/workflows/jules-glee-reusable.yml@72b4e445b76a540a31689cfa07eb811db0031da6",
    "Jules reusable workflow pin",
)
write(path, text)

for path in (".github/workflows/glee-review.yml", ".github/workflows/glee-review-antigravity.yml"):
    text = read(path)
    text = must_replace(
        text,
        "actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4",
        "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1",
        f"{path} checkout pin",
    )
    write(path, text)

checks = {
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt": ["Resume-state lookup failed; falling back to normal playback"],
    "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt": ["restoreEntryFocusWhenFocusedKeyMissing", "onQueueFocused(null)"],
    "app/src/main/java/com/hereliesaz/illumera/ui/watchlist/WatchlistScreen.kt": ["lastQueueFocusedKey by remember", "restoreEntryFocusWhenFocusedKeyMissing = !hasWatchlistMedia"],
    "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsViewModel.kt": ["withTimeoutOrNull(5 * 60_000L)"],
    "app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt": ["Secure credential storage unavailable", "SharedPreferences? by lazy", "clearLegacyFallbackPrefs()"],
    ".github/workflows/jules-glee.yml": ["@72b4e445b76a540a31689cfa07eb811db0031da6"],
}
for check_path, needles in checks.items():
    body = read(check_path)
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"{check_path}: missing semantic assertion {needle!r}")

print("Direct production hardening applied and semantically verified.")
