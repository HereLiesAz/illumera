from pathlib import Path
import textwrap


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def replace_once(path: str, old: str, new: str, label: str, expected: int = 1) -> None:
    text = read(path)
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(f"{label}: expected {expected} matches, found {actual}")
    write(path, text.replace(old, new, expected))


def replace_section(path: str, start: str, end: str, replacement: str, label: str) -> None:
    text = read(path)
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"{label}: start marker missing")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"{label}: end marker missing")
    write(path, text[:i] + replacement + text[j:])


# Resume-state failures must not leave playback gated forever.
details = "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt"
new_resume = textwrap.indent(textwrap.dedent('''\
private fun refreshResumeStateIfNeeded(meta: MetaItem?) {
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
'''), "    ").rstrip()
replace_section(
    details,
    "    private fun refreshResumeStateIfNeeded(meta: MetaItem?) {",
    "\n    private suspend fun resolveSeriesResumePlaybackId(",
    new_resume,
    "Details resume-state hardening",
)

# Queue focus must recover if the remembered item disappears.
queue = "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt"
replace_once(
    queue,
    "    focusedQueueKey: String? = null,\n    onQueueFocused: (String) -> Unit = {}",
    "    focusedQueueKey: String? = null,\n    restoreEntryFocusWhenFocusedKeyMissing: Boolean = false,\n    onQueueFocused: (String?) -> Unit = {}",
    "QueueSection nullable focus callback",
)
replace_once(
    queue,
    "    LaunchedEffect(state.manualItems, state.suggestions) {\n        queueManager.resolveMissingArtwork()\n    }\n\n    Column(",
    "    LaunchedEffect(state.manualItems, state.suggestions) {\n        queueManager.resolveMissingArtwork()\n    }\n\n"
    "    LaunchedEffect(\n"
    "        state.manualItems,\n"
    "        state.suggestions,\n"
    "        focusedQueueKey,\n"
    "        restoreEntryFocusWhenFocusedKeyMissing\n"
    "    ) {\n"
    "        val key = focusedQueueKey ?: return@LaunchedEffect\n"
    "        val stillExists = state.manualItems.any { it.stableKey == key } ||\n"
    "            state.suggestions.any { it.stableKey == key }\n"
    "        if (!stillExists) {\n"
    "            onQueueFocused(null)\n"
    "            if (restoreEntryFocusWhenFocusedKeyMissing) {\n"
    "                kotlinx.coroutines.delay(50)\n"
    "                runCatching { entryRequester.requestFocus() }\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    Column(",
    "QueueSection missing-item recovery",
)

# Keep Watchlist media and Queue focus ownership coherent.
watchlist = "app/src/main/java/com/hereliesaz/illumera/ui/watchlist/WatchlistScreen.kt"
replace_once(
    watchlist,
    "    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }",
    "    var lastFocusedKey by remember { mutableStateOf(viewModel.lastFocusedKey) }\n"
    "    var lastQueueFocusedKey by remember { mutableStateOf(viewModel.lastQueueFocusedKey) }",
    "Watchlist queue focus state",
)
replace_once(
    watchlist,
    "                        onFocused = { _: MetaItem?, key: String ->\n"
    "                            lastFocusedKey = key\n"
    "                            viewModel.lastFocusedKey = key\n"
    "                        },",
    "                        onFocused = { _: MetaItem?, key: String ->\n"
    "                            lastFocusedKey = key\n"
    "                            viewModel.lastFocusedKey = key\n"
    "                            lastQueueFocusedKey = null\n"
    "                            viewModel.lastQueueFocusedKey = null\n"
    "                        },",
    "Watchlist media focus handlers",
    expected=2,
)
replace_once(
    watchlist,
    "                    requestEntryFocus = !hasWatchlistMedia && viewModel.lastQueueFocusedKey == null,\n"
    "                    focusedQueueKey = viewModel.lastQueueFocusedKey,\n"
    "                    onQueueFocused = { key -> viewModel.lastQueueFocusedKey = key },",
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
    "                    },",
    "Watchlist queue wiring",
)

# Keep Debrid setup usable on compact displays and with the IME open.
integrations_screen = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt"
text = read(integrations_screen)
start = text.find("@Composable\nprivate fun DebridDialog(")
end = text.find("\n@Composable\nprivate fun TraktAuthDialog(", start)
if start < 0 or end < 0:
    raise SystemExit("DebridDialog boundaries missing")
part = text[start:end]
for old, new, label in (
    (
        "    val context = LocalContext.current",
        "    val context = LocalContext.current\n"
        "    val debridScrollState = rememberScrollState()\n"
        "    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp",
        "Debrid scroll state",
    ),
    (
        "                    .width(rememberDialogWidth(460))",
        "                    .width(rememberDialogWidth(460))\n"
        "                    .heightIn(max = maxDialogHeight)",
        "Debrid height cap",
    ),
    (
        "                Column {",
        "                Column(modifier = Modifier.verticalScroll(debridScrollState)) {",
        "Debrid scrolling",
    ),
):
    count = part.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    part = part.replace(old, new, 1)
write(integrations_screen, text[:start] + part + text[end:])

# Bound Facebook OAuth polling instead of leaving a background job alive indefinitely.
integrations_vm = "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsViewModel.kt"
replace_once(
    integrations_vm,
    "import kotlinx.coroutines.launch\n",
    "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withTimeoutOrNull\n",
    "OAuth timeout import",
)
replace_once(
    integrations_vm,
    "        facebookLoginJob = viewModelScope.launch {\n"
    "            val result = stremioAuthManager.completeFacebookLogin(state)\n"
    "            result.fold(",
    "        facebookLoginJob = viewModelScope.launch {\n"
    "            val result = withTimeoutOrNull(5 * 60_000L) {\n"
    "                stremioAuthManager.completeFacebookLogin(state)\n"
    "            }\n"
    "            if (result == null) {\n"
    "                _uiState.value = _uiState.value.copy(\n"
    "                    facebookLoginState = FacebookLoginState.Error(\"Facebook login timed out. Please try again.\")\n"
    "                )\n"
    "                return@launch\n"
    "            }\n"
    "            result.fold(",
    "Facebook login timeout",
)

# Fail closed if Android Keystore is unavailable; never persist Stremio secrets in plaintext.
auth = "app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt"
text = read(auth)
start = text.find("    private val encryptedPrefs: SharedPreferences by lazy {")
end = text.find("\n    private fun createEncryptedPrefs(): SharedPreferences {", start)
if start < 0 or end < 0:
    raise SystemExit("StremioAuthManager encryptedPrefs boundaries missing")
secure_decl = textwrap.indent(textwrap.dedent('''\
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
'''), "    ").rstrip()
text = text[:start] + secure_decl + text[end:]

old = "    private val _connectionState = MutableStateFlow<StremioConnectionState>(StremioConnectionState.Disconnected)"
new = (
    "    private fun clearLegacyFallbackPrefs() {\n"
    "        val prefsDir = File(context.applicationInfo.dataDir, \"shared_prefs\")\n"
    "        File(prefsDir, \"${PREFS_FILE}_fallback.xml\").delete()\n"
    "        File(prefsDir, \"${PREFS_FILE}_fallback.xml.bak\").delete()\n"
    "    }\n\n"
    + old
)
if text.count(old) != 1:
    raise SystemExit("connection state marker missing")
text = text.replace(old, new, 1)

old = "    init {\n        refreshConnectionState()\n    }"
new = "    init {\n        clearLegacyFallbackPrefs()\n        refreshConnectionState()\n    }"
if text.count(old) != 1:
    raise SystemExit("auth init marker missing")
text = text.replace(old, new, 1)

for old, new, label in (
    ("return encryptedPrefs.getString(KEY_AUTH_KEY, null)", "return encryptedPrefs?.getString(KEY_AUTH_KEY, null)", "auth key getter"),
    ("return encryptedPrefs.getString(KEY_EMAIL, null)", "return encryptedPrefs?.getString(KEY_EMAIL, null)", "email getter"),
    ("return encryptedPrefs.getString(KEY_AVATAR, null)", "return encryptedPrefs?.getString(KEY_AVATAR, null)", "avatar getter"),
):
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected 1 match")
    text = text.replace(old, new, 1)


def transform_between(source: str, begin: str, finish: str, label: str, transform):
    i = source.find(begin)
    j = source.find(finish, i)
    if i < 0 or j < 0:
        raise SystemExit(f"{label}: boundaries missing")
    part = source[i:j]
    changed = transform(part)
    if changed == part:
        raise SystemExit(f"{label}: no change")
    return source[:i] + changed + source[j:]


def require_one(part: str, old: str, new: str, label: str) -> str:
    if part.count(old) != 1:
        raise SystemExit(f"{label}: expected 1 match, found {part.count(old)}")
    return part.replace(old, new, 1)


def guard_unit(part: str, signature: str) -> str:
    return require_one(part, signature, signature + "\n        val prefs = encryptedPrefs ?: return", "secure prefs guard")


text = transform_between(
    text,
    "    fun saveCredentialsForProfile(",
    "\n    fun loadCredentialsForProfile(",
    "saveCredentialsForProfile",
    lambda p: guard_unit(p, "    fun saveCredentialsForProfile(profileId: Int) {").replace("encryptedPrefs.edit()", "prefs.edit()"),
)


def patch_load(part: str) -> str:
    part = require_one(
        part,
        "    fun loadCredentialsForProfile(profileId: Int) {",
        "    fun loadCredentialsForProfile(profileId: Int) {\n"
        "        val prefs = encryptedPrefs ?: run {\n"
        "            _connectionState.value = StremioConnectionState.Disconnected\n"
        "            return\n"
        "        }",
        "load profile guard",
    )
    return part.replace("encryptedPrefs.getString", "prefs.getString").replace("encryptedPrefs.edit()", "prefs.edit()")


text = transform_between(text, "    fun loadCredentialsForProfile(", "\n    fun copyCredentialsBetweenProfiles(", "loadCredentialsForProfile", patch_load)
text = transform_between(
    text,
    "    fun copyCredentialsBetweenProfiles(",
    "\n    fun clearCredentialsForProfile(",
    "copyCredentialsBetweenProfiles",
    lambda p: guard_unit(p, "    fun copyCredentialsBetweenProfiles(sourceProfileId: Int, targetProfileId: Int) {").replace("encryptedPrefs.getString", "prefs.getString").replace("encryptedPrefs.edit()", "prefs.edit()"),
)
text = transform_between(
    text,
    "    fun clearCredentialsForProfile(",
    "\n    /**\n     * Logs in to Stremio",
    "clearCredentialsForProfile",
    lambda p: guard_unit(p, "    fun clearCredentialsForProfile(profileId: Int) {").replace("encryptedPrefs.edit()", "prefs.edit()"),
)

storage_failure = 'Result.failure<String>(StremioAuthError.UnknownError("Secure credential storage is unavailable"))'


def patch_auth_result(part: str, first_error_catch: str, label: str) -> str:
    part = require_one(
        part,
        "withContext(Dispatchers.IO) {\n        try {",
        f"withContext(Dispatchers.IO) {{\n        val prefs = encryptedPrefs ?: return@withContext {storage_failure}\n        try {{",
        f"{label} secure prefs guard",
    )
    part = part.replace("encryptedPrefs.edit()", "prefs.edit()")
    if first_error_catch:
        part = require_one(
            part,
            first_error_catch,
            "        } catch (e: CancellationException) {\n            throw e\n" + first_error_catch,
            f"{label} cancellation",
        )
    return part


text = transform_between(
    text,
    "    suspend fun login(",
    "\n    /**\n     * Creates a Stremio account",
    "login",
    lambda p: patch_auth_result(p, "        } catch (e: StremioAuthError.InvalidCredentials) {", "login"),
)
text = transform_between(
    text,
    "    suspend fun register(",
    "\n    /**\n     * Fetches the user's addon collection",
    "register",
    lambda p: patch_auth_result(p, "        } catch (e: StremioAuthError) {", "register"),
)
text = transform_between(
    text,
    "    suspend fun completeFacebookLogin(",
    "\n    /** Starts Stremio's browser-based Sign in with Apple flow.",
    "completeFacebookLogin",
    lambda p: patch_auth_result(p, "", "facebook oauth"),
)
text = transform_between(
    text,
    "    suspend fun completeAppleLogin(",
    "\n    /**\n     * Pushes the given addons",
    "completeAppleLogin",
    lambda p: patch_auth_result(p, "", "apple oauth"),
)

old = (
    "        encryptedPrefs.edit()\n"
    "            .remove(KEY_AUTH_KEY)\n"
    "            .remove(KEY_EMAIL)\n"
    "            .remove(KEY_AVATAR)\n"
    "            .apply()"
)
new = (
    "        encryptedPrefs?.edit()\n"
    "            ?.remove(KEY_AUTH_KEY)\n"
    "            ?.remove(KEY_EMAIL)\n"
    "            ?.remove(KEY_AVATAR)\n"
    "            ?.apply()"
)
if text.count(old) != 1:
    raise SystemExit("disconnect secure prefs marker missing")
text = text.replace(old, new, 1)
write(auth, text)

# AGP owns native-library extraction policy.
manifest = "app/src/main/AndroidManifest.xml"
replace_once(manifest, "        android:extractNativeLibs=\"true\"\n", "", "manifest native library packaging")

# Pin secret-bearing reusable workflow code and remaining checkout actions.
jules = ".github/workflows/jules-glee.yml"
replace_once(
    jules,
    "HereLiesAz/workflows-starter-template/.github/workflows/jules-glee-reusable.yml@main",
    "HereLiesAz/workflows-starter-template/.github/workflows/jules-glee-reusable.yml@72b4e445b76a540a31689cfa07eb811db0031da6",
    "Jules reusable workflow pin",
)
for workflow in (".github/workflows/glee-review.yml", ".github/workflows/glee-review-antigravity.yml"):
    replace_once(
        workflow,
        "actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4",
        "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1",
        f"{workflow} checkout pin",
    )

# Refuse to proceed if any intended behavior disappeared during transformation.
assertions = {
    details: ["Resume-state lookup failed; falling back to normal playback", "catch (ce: CancellationException)"],
    queue: ["restoreEntryFocusWhenFocusedKeyMissing", "onQueueFocused(null)"],
    watchlist: ["lastQueueFocusedKey by remember", "restoreEntryFocusWhenFocusedKeyMissing = !hasWatchlistMedia"],
    integrations_screen: ["debridScrollState", ".heightIn(max = maxDialogHeight)"],
    integrations_vm: ["withTimeoutOrNull(5 * 60_000L)"],
    auth: ["SharedPreferences? by lazy", "Secure credential storage unavailable", "clearLegacyFallbackPrefs()"],
    jules: ["@72b4e445b76a540a31689cfa07eb811db0031da6"],
}
for path, needles in assertions.items():
    body = read(path)
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"{path}: missing semantic assertion {needle!r}")

print("Current-tree production hardening applied and verified.")
