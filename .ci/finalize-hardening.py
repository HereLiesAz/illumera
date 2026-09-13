from pathlib import Path
import runpy

ROOT = Path.cwd()
PATCHER = ROOT / ".ci" / "apply-recent-review-fixes.py"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one occurrence, found {count}")
    return text.replace(old, new, 1)


# Reuse PR #94's reviewed source transformations, but discard its stale workflow
# migration section. CI/release are hardened independently on this branch.
patcher = PATCHER.read_text()
start_marker = "# ---------------------------------------------------------------------------\n# GitHub Actions:"
end_marker = "# ---------------------------------------------------------------------------\n# Details:"
start = patcher.find(start_marker)
end = patcher.find(end_marker, start + 1)
if start < 0 or end < 0:
    raise RuntimeError("Could not isolate source-only section of review patcher")
patcher = patcher[:start] + patcher[end:]

old_once = '''def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one occurrence, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))
'''
new_once = '''def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count == 1:
        write(path, text.replace(old, new, 1))
        return
    if count == 0:
        return
    raise RuntimeError(f"{path}: ambiguous occurrence count {count}: {old[:100]!r}")
'''
patcher = replace_once(patcher, old_once, new_once, "replace_once helper")

old_exact = '''def replace_exact_count(path: str, old: str, new: str, expected: int) -> None:
    text = read(path)
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} occurrences, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new))
'''
new_exact = '''def replace_exact_count(path: str, old: str, new: str, expected: int) -> None:
    text = read(path)
    count = text.count(old)
    if count == expected:
        write(path, text.replace(old, new))
        return
    if count == 0:
        return
    raise RuntimeError(f"{path}: ambiguous occurrence count {count}: {old[:100]!r}")
'''
patcher = replace_once(patcher, old_exact, new_exact, "replace_exact_count helper")

# Current IntegrationsScreen has an outer Box that did not exist when the stale
# patcher was authored; retarget only the two Debrid dialog snippets.
old_anchor = "'''                modifier = Modifier\n                    .width(rememberDialogWidth(460))"
new_anchor = "'''            Box(\n                modifier = Modifier\n                    .width(rememberDialogWidth(460))"
patcher = patcher.replace(old_anchor, new_anchor)
PATCHER.write_text(patcher)
runpy.run_path(str(PATCHER), run_name="__main__")

# Never fail open from encrypted preferences to plaintext credential storage.
auth_path = ROOT / "app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt"
auth = auth_path.read_text()
old = '''    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // AEADBadTagException / KeyStore corruption — nuke and rebuild
            Log.e("StremioAuthManager", "EncryptedSharedPreferences corrupted, resetting", e)
            clearCorruptedPrefs()
            try {
                createEncryptedPrefs()
            } catch (e2: Exception) {
                // KeyStore is permanently broken on this device — fall back to plain prefs
                Log.e("StremioAuthManager", "KeyStore permanently broken, using plain prefs", e2)
                context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }
'''
new = '''    @Volatile
    private var secureStorageAvailable = true

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // Recover once from a corrupt encrypted preference/key pair.
            Log.e("StremioAuthManager", "EncryptedSharedPreferences corrupted, resetting", e)
            clearCorruptedPrefs()
            try {
                createEncryptedPrefs()
            } catch (e2: Exception) {
                // Never persist account credentials unencrypted. This empty fallback
                // keeps read/clear paths non-crashing while account login is disabled.
                Log.e("StremioAuthManager", "Secure credential storage unavailable", e2)
                secureStorageAvailable = false
                context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
                    .also { it.edit().clear().apply() }
            }
        }
    }
'''
if old in auth:
    auth = auth.replace(old, new, 1)
elif new not in auth:
    raise RuntimeError("Encrypted preference fallback block changed unexpectedly")

old = '''        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
'''
new = '''        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // Purge any plaintext credentials left by older fail-open builds.
        context.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        return prefs
'''
if old in auth:
    auth = auth.replace(old, new, 1)
elif new not in auth:
    raise RuntimeError("EncryptedSharedPreferences creation block changed unexpectedly")

anchor = '''    private val _connectionState = MutableStateFlow<StremioConnectionState>(StremioConnectionState.Disconnected)
'''
helper = '''    private fun secureStorageReady(): Boolean {
        encryptedPrefs // force lazy initialization before checking the flag
        return secureStorageAvailable
    }

    private fun secureStorageFailure(): Result<String> = Result.failure(
        StremioAuthError.UnknownError("Secure credential storage is unavailable on this device")
    )

''' + anchor
if "private fun secureStorageReady()" not in auth:
    auth = replace_once(auth, anchor, helper, "connection state anchor")

write_guards = {
    '''    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
''': '''    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        if (!secureStorageReady()) return@withContext secureStorageFailure()
        try {
''',
    '''    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val loginResult = stremioAuthService.register(email, password, marketing)
''': '''    ): Result<String> = withContext(Dispatchers.IO) {
        if (!secureStorageReady()) return@withContext secureStorageFailure()
        try {
            val loginResult = stremioAuthService.register(email, password, marketing)
''',
    '''    suspend fun completeFacebookLogin(state: String): Result<String> = withContext(Dispatchers.IO) {
        try {
''': '''    suspend fun completeFacebookLogin(state: String): Result<String> = withContext(Dispatchers.IO) {
        if (!secureStorageReady()) return@withContext secureStorageFailure()
        try {
''',
    '''    suspend fun completeAppleLogin(state: String): Result<String> = withContext(Dispatchers.IO) {
        try {
''': '''    suspend fun completeAppleLogin(state: String): Result<String> = withContext(Dispatchers.IO) {
        if (!secureStorageReady()) return@withContext secureStorageFailure()
        try {
''',
}
for old, new in write_guards.items():
    if old in auth:
        auth = auth.replace(old, new, 1)
    elif new not in auth:
        raise RuntimeError(f"Auth write guard anchor changed: {old[:80]!r}")
auth_path.write_text(auth)

# Give browser-based Facebook auth the same practical completion window as Apple.
service_path = ROOT / "app/src/main/java/com/hereliesaz/illumera/data/remote/StremioAuthService.kt"
service = service_path.read_text()
service = service.replace(
    "pollFacebookLogin(state: String, maxAttempts: Int = 25)",
    "pollFacebookLogin(state: String, maxAttempts: Int = 300)",
    1,
)
service_path.write_text(service)

# Fail the finalizer if any intended source behavior is absent.
checks = {
    "app/src/main/java/com/hereliesaz/illumera/ui/details/DetailsViewModel.kt": [
        "Resume-state lookup failed; falling back to normal playback",
    ],
    "app/src/main/java/com/hereliesaz/illumera/data/remote/StremioAuthService.kt": [
        "pollFacebookLogin(state: String, maxAttempts: Int = 300)",
    ],
    "app/src/main/java/com/hereliesaz/illumera/data/stream/StreamSortingService.kt": [
        "canonicalLanguageTag",
        "containsAuthoritativeAudioLanguage",
    ],
    "app/src/main/java/com/hereliesaz/illumera/ui/queue/QueueScreen.kt": [
        "restoreEntryFocusWhenFocusedKeyMissing",
    ],
    "app/src/main/java/com/hereliesaz/illumera/ui/watchlist/WatchlistScreen.kt": [
        "lastQueueFocusedKey by remember",
    ],
    "app/src/main/java/com/hereliesaz/illumera/ui/settings/IntegrationsScreen.kt": [
        "debridScrollState",
        "heightIn(max = maxDialogHeight)",
    ],
    "app/src/main/java/com/hereliesaz/illumera/data/auth/StremioAuthManager.kt": [
        "Secure credential storage unavailable",
        "secureStorageReady()",
        "catch (e: CancellationException)",
    ],
}
for relative, needles in checks.items():
    text = (ROOT / relative).read_text()
    missing = [needle for needle in needles if needle not in text]
    if missing:
        raise RuntimeError(f"{relative}: missing expected hardening markers: {missing}")

print("Source hardening finalized and semantically verified.")
