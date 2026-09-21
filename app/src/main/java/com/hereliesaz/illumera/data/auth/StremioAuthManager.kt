package com.hereliesaz.illumera.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.data.remote.StremioAddonDescriptor
import com.hereliesaz.illumera.data.remote.StremioAddonEntry
import com.hereliesaz.illumera.data.remote.StremioAddonFlags
import com.hereliesaz.illumera.data.remote.StremioAuthError
import com.hereliesaz.illumera.data.remote.StremioAuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for the Stremio integration.
 */
sealed class StremioConnectionState {
    object Disconnected : StremioConnectionState()
    data class Connected(val email: String) : StremioConnectionState()
}

/**
 * Manages Stremio authentication state using EncryptedSharedPreferences.
 * Provides secure storage for auth tokens and handles login/logout operations.
 */
@Singleton
class StremioAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stremioAuthService: StremioAuthService
) {
    companion object {
        private const val PREFS_FILE = "stremio_secure_prefs"
        private const val KEY_AUTH_KEY = "stremio_auth_key"
        private const val KEY_EMAIL = "stremio_email"
        private const val KEY_AVATAR = "stremio_avatar"
    }

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
    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearCorruptedPrefs() {
        val prefsDir = "shared_prefs"
        File(context.filesDir.parent, "$prefsDir/$PREFS_FILE.xml").delete()
        File(context.filesDir.parent, "$prefsDir/$PREFS_FILE.xml.bak").delete()

        // Remove the master key from Android KeyStore
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            keyStore.deleteEntry("_androidx_security_master_key")
        } catch (e: Exception) {
            Log.e("StremioAuthManager", "Failed to clear master key", e)
        }
    }

    private fun clearLegacyFallbackPrefs() {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        File(prefsDir, "${PREFS_FILE}_fallback.xml").delete()
        File(prefsDir, "${PREFS_FILE}_fallback.xml.bak").delete()
    }

    // Reused scope for fire-and-forget background work (e.g. best-effort logout).
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<StremioConnectionState>(StremioConnectionState.Disconnected)
    val connectionState: StateFlow<StremioConnectionState> = _connectionState.asStateFlow()

    init {
        clearLegacyFallbackPrefs()
        // refreshConnectionState() is intentionally NOT called here to avoid forcing
        // EncryptedSharedPreferences initialisation (disk I/O + crypto) on the
        // constructing thread, which may be the main thread during Application.onCreate.
        // Callers must invoke refreshConnectionState() on a background dispatcher, or
        // rely on the first access to encryptedPrefs triggering the lazy initialiser
        // off-main-thread (e.g. from a suspend function dispatched on Dispatchers.IO).
    }

    /**
     * Refreshes the connection state from stored credentials.
     */
    fun refreshConnectionState() {
        val authKey = getStoredAuthKey()
        val email = getStoredEmail()

        _connectionState.value = if (authKey != null && email != null) {
            StremioConnectionState.Connected(email)
        } else {
            StremioConnectionState.Disconnected
        }
    }

    /**
     * Gets the stored auth key (for API calls).
     */
    fun getStoredAuthKey(): String? {
        return encryptedPrefs?.getString(KEY_AUTH_KEY, null)
    }

    /**
     * Gets the stored email (for display purposes).
     */
    fun getStoredEmail(): String? {
        return encryptedPrefs?.getString(KEY_EMAIL, null)
    }

    /**
     * Gets the connected account's avatar URL, if it has one (Facebook-created
     * accounts carry their FB photo here — see [StremioAuthService.login]).
     */
    fun getStoredAvatarUrl(): String? {
        return encryptedPrefs?.getString(KEY_AVATAR, null)
    }

    suspend fun getDataExportUrl(): Result<String> = withContext(Dispatchers.IO) {
        val authKey = getStoredAuthKey()
            ?: return@withContext Result.failure(StremioAuthError.UnknownError("Not connected to Stremio"))
        runCatching { stremioAuthService.requestDataExport(authKey) }
    }

    suspend fun getCalendarUrl(): Result<String> = withContext(Dispatchers.IO) {
        val authKey = getStoredAuthKey()
            ?: return@withContext Result.failure(StremioAuthError.UnknownError("Not connected to Stremio"))
        runCatching {
            val user = stremioAuthService.getUser(authKey)
            val userId = user.id?.takeIf { it.isNotBlank() }
                ?: throw StremioAuthError.UnknownError("Stremio account id is unavailable")
            "https://www.strem.io/calendar/${android.net.Uri.encode(userId)}.ics"
        }
    }

    private fun profileScopedAuthKey(profileId: Int): String = "${KEY_AUTH_KEY}_profile_$profileId"
    private fun profileScopedEmail(profileId: Int): String = "${KEY_EMAIL}_profile_$profileId"
    private fun profileScopedAvatar(profileId: Int): String = "${KEY_AVATAR}_profile_$profileId"

    fun saveCredentialsForProfile(profileId: Int) {
        val prefs = encryptedPrefs ?: return
        val authKey = getStoredAuthKey()
        val email = getStoredEmail()
        val avatar = getStoredAvatarUrl()
        val authProfileKey = profileScopedAuthKey(profileId)
        val emailProfileKey = profileScopedEmail(profileId)
        val avatarProfileKey = profileScopedAvatar(profileId)

        prefs.edit().apply {
            if (authKey != null && email != null) {
                putString(authProfileKey, authKey)
                putString(emailProfileKey, email)
                if (avatar != null) putString(avatarProfileKey, avatar) else remove(avatarProfileKey)
            } else {
                remove(authProfileKey)
                remove(emailProfileKey)
                remove(avatarProfileKey)
            }
        }.apply()
    }

    fun loadCredentialsForProfile(profileId: Int) {
        val prefs = encryptedPrefs ?: run {
            _connectionState.value = StremioConnectionState.Disconnected
            return
        }
        val authKey = prefs.getString(profileScopedAuthKey(profileId), null)
        val email = prefs.getString(profileScopedEmail(profileId), null)
        val avatar = prefs.getString(profileScopedAvatar(profileId), null)

        prefs.edit().apply {
            if (authKey != null && email != null) {
                putString(KEY_AUTH_KEY, authKey)
                putString(KEY_EMAIL, email)
                if (avatar != null) putString(KEY_AVATAR, avatar) else remove(KEY_AVATAR)
            } else {
                remove(KEY_AUTH_KEY)
                remove(KEY_EMAIL)
                remove(KEY_AVATAR)
            }
        }.apply()

        refreshConnectionState()
    }

    fun copyCredentialsBetweenProfiles(sourceProfileId: Int, targetProfileId: Int) {
        val prefs = encryptedPrefs ?: return
        val authKey = prefs.getString(profileScopedAuthKey(sourceProfileId), null)
        val email = prefs.getString(profileScopedEmail(sourceProfileId), null)
        val avatar = prefs.getString(profileScopedAvatar(sourceProfileId), null)
        val authProfileKey = profileScopedAuthKey(targetProfileId)
        val emailProfileKey = profileScopedEmail(targetProfileId)
        val avatarProfileKey = profileScopedAvatar(targetProfileId)

        prefs.edit().apply {
            if (authKey != null && email != null) {
                putString(authProfileKey, authKey)
                putString(emailProfileKey, email)
                if (avatar != null) putString(avatarProfileKey, avatar) else remove(avatarProfileKey)
            } else {
                remove(authProfileKey)
                remove(emailProfileKey)
                remove(avatarProfileKey)
            }
        }.apply()
    }

    fun clearCredentialsForProfile(profileId: Int) {
        val prefs = encryptedPrefs ?: return
        prefs.edit()
            .remove(profileScopedAuthKey(profileId))
            .remove(profileScopedEmail(profileId))
            .remove(profileScopedAvatar(profileId))
            .apply()
    }

    /**
     * Logs in to Stremio and stores the credentials securely.
     * Returns the auth key on success.
     */
    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        val prefs = encryptedPrefs ?: return@withContext Result.failure<String>(StremioAuthError.UnknownError("Secure credential storage is unavailable"))
        try {
            val loginResult = stremioAuthService.login(email, password)

            prefs.edit().apply {
                putString(KEY_AUTH_KEY, loginResult.authKey)
                putString(KEY_EMAIL, email)
                if (loginResult.avatarUrl != null) putString(KEY_AVATAR, loginResult.avatarUrl) else remove(KEY_AVATAR)
            }.apply()

            _connectionState.value = StremioConnectionState.Connected(email)

            Result.success(loginResult.authKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StremioAuthError.InvalidCredentials) {
            Result.failure(e)
        } catch (e: StremioAuthError.NetworkError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Creates a Stremio account and stores its auth key exactly like a normal login.
     */
    suspend fun register(
        email: String,
        password: String,
        marketing: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val prefs = encryptedPrefs ?: return@withContext Result.failure<String>(StremioAuthError.UnknownError("Secure credential storage is unavailable"))
        try {
            val loginResult = stremioAuthService.register(email, password, marketing)

            prefs.edit().apply {
                putString(KEY_AUTH_KEY, loginResult.authKey)
                putString(KEY_EMAIL, email)
                if (loginResult.avatarUrl != null) putString(KEY_AVATAR, loginResult.avatarUrl) else remove(KEY_AVATAR)
            }.apply()

            _connectionState.value = StremioConnectionState.Connected(email)
            Result.success(loginResult.authKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Fetches the user's addon collection using the stored auth key.
     */
    suspend fun fetchAddons(): Result<List<StremioAddonEntry>> = withContext(Dispatchers.IO) {
        val authKey = getStoredAuthKey()
            ?: return@withContext Result.failure(StremioAuthError.InvalidCredentials("Not logged in"))

        try {
            val addons = stremioAuthService.getAddonCollection(authKey)
            Result.success(addons)
        } catch (e: StremioAuthError) {
            // If auth fails, the token might be expired - clear it
            if (e is StremioAuthError.InvalidCredentials) {
                disconnect()
            }
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.NetworkError(e.message ?: "Network error"))
        }
    }

    /**
     * Starts a Facebook login: returns the URL to show as a QR code / link for
     * the user to open in a browser (their phone, typically — a TV usually has
     * none). Call [completeFacebookLogin] with the same state afterward.
     */
    fun startFacebookLogin(): Pair<String, String> = stremioAuthService.startFacebookLogin()

    /**
     * Polls for Facebook login completion and, once the user finishes the
     * OAuth flow in their browser, logs in and stores the credentials —
     * mirroring [login] but sourced from Facebook instead of a typed password.
     */
    suspend fun completeFacebookLogin(state: String): Result<String> = withContext(Dispatchers.IO) {
        val prefs = encryptedPrefs ?: return@withContext Result.failure<String>(StremioAuthError.UnknownError("Secure credential storage is unavailable"))
        try {
            val (email, fbToken) = stremioAuthService.pollFacebookLogin(state)
                ?: return@withContext Result.failure(StremioAuthError.NetworkError("Facebook login timed out or was not completed"))

            val loginResult = stremioAuthService.login(email, fbToken, facebook = true)

            prefs.edit().apply {
                putString(KEY_AUTH_KEY, loginResult.authKey)
                putString(KEY_EMAIL, email)
                if (loginResult.avatarUrl != null) putString(KEY_AVATAR, loginResult.avatarUrl) else remove(KEY_AVATAR)
            }.apply()

            _connectionState.value = StremioConnectionState.Connected(email)
            Result.success(loginResult.authKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /** Starts Stremio's browser-based Sign in with Apple flow. */
    fun startAppleLogin(): Pair<String, String> = stremioAuthService.startAppleLogin()

    /** Completes Apple OAuth and stores the resulting Stremio credentials. */
    suspend fun completeAppleLogin(state: String): Result<String> = withContext(Dispatchers.IO) {
        val prefs = encryptedPrefs ?: return@withContext Result.failure<String>(StremioAuthError.UnknownError("Secure credential storage is unavailable"))
        try {
            val credentials = stremioAuthService.pollAppleLogin(state)
                ?: return@withContext Result.failure(
                    StremioAuthError.NetworkError("Apple login timed out or was not completed")
                )
            val loginResult = stremioAuthService.loginWithApple(credentials)
            val accountLabel = credentials.email.ifBlank { "Apple account" }

            prefs.edit().apply {
                putString(KEY_AUTH_KEY, loginResult.authKey)
                putString(KEY_EMAIL, accountLabel)
                if (loginResult.avatarUrl != null) putString(KEY_AVATAR, loginResult.avatarUrl) else remove(KEY_AVATAR)
            }.apply()

            _connectionState.value = StremioConnectionState.Connected(accountLabel)
            Result.success(loginResult.authKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Pushes the given addons up to the account's addon collection, replacing
     * it there. Fetches each addon's manifest fresh from its transport URL
     * (rather than any locally-cached/trimmed copy) to avoid corrupting the
     * account's collection with an incomplete manifest.
     */
    suspend fun pushAddonCollection(transportUrls: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        val authKey = getStoredAuthKey()
            ?: return@withContext Result.failure(StremioAuthError.InvalidCredentials("Not logged in"))

        try {
            val descriptors = transportUrls.map { url ->
                StremioAddonDescriptor(
                        manifest = stremioAuthService.fetchRawManifest(url),
                        transportUrl = if (url.endsWith("manifest.json")) url else "${url.trimEnd('/')}/manifest.json",
                        flags = StremioAddonFlags()
                    )
            }
            stremioAuthService.setAddonCollection(authKey, descriptors)
            Result.success(Unit)
        } catch (e: StremioAuthError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StremioAuthError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Disconnects from Stremio: invalidates the authKey server-side (best
     * effort) and clears stored credentials.
     */
    fun disconnect() {
        val authKey = getStoredAuthKey()
        encryptedPrefs?.edit()
            ?.remove(KEY_AUTH_KEY)
            ?.remove(KEY_EMAIL)
            ?.remove(KEY_AVATAR)
            ?.apply()

        _connectionState.value = StremioConnectionState.Disconnected

        if (authKey != null) {
            // Best-effort server-side invalidation. Uses the class-owned scope so this
            // coroutine is not an untracked orphan.
            managerScope.launch {
                stremioAuthService.logout(authKey)
            }
        }
    }
}
