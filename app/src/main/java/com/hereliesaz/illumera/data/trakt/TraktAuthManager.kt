package com.hereliesaz.illumera.data.trakt

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.BuildConfig
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.trakt.TraktDeviceCodeResponse
import com.hereliesaz.illumera.data.model.trakt.TraktTokenResponse
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.profile.ProfileMutationCoordinator
import com.hereliesaz.illumera.data.remote.TraktApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed class DeviceAuthState {
    data object Idle : DeviceAuthState()
    data class WaitingForUser(val userCode: String, val verificationUrl: String) : DeviceAuthState()
    data object Success : DeviceAuthState()
    data class Error(val message: String) : DeviceAuthState()
    data object Expired : DeviceAuthState()
    /** This build has no Trakt Client ID baked in (see ci/README.md — Trakt now
     *  gates even registering a developer app behind Trakt VIP). Distinct from
     *  [Error] so the UI can point users at the free alternative (syncing the
     *  official Trakt catalogs via a connected Stremio account) instead of
     *  just offering a pointless "Retry". */
    data object NotConfigured : DeviceAuthState()
}

@Singleton
class TraktAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApiService,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val profileMutationCoordinator: ProfileMutationCoordinator,
    private val dao: AddonDao,
    private val watchlistPendingStore: TraktWatchlistPendingStore
) {
    companion object {
        private const val TAG = "TraktAuthManager"
        private const val PREFS_NAME = "trakt_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _authState = MutableStateFlow<DeviceAuthState>(DeviceAuthState.Idle)
    val authState: StateFlow<DeviceAuthState> = _authState

    // Owns the device-auth polling loop so it can be cancelled on retry/dismiss
    // instead of racing a new attempt against an abandoned one.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deviceAuthJob: Job? = null

    private fun activeProfileId(): Int? = profileConfigurationManager.getLastActiveProfileId()

    private fun profileKey(key: String, profileId: Int) = "${key}_$profileId"

    init {
        migrateGlobalTokensToProfile()
        _isConnected.value = getAccessToken() != null
    }

    /**
     * One-time migration: move tokens saved without a profile suffix to the active profile.
     * If no profile is active yet, leave the legacy keys untouched and retry on login.
     */
    private fun migrateGlobalTokensToProfile() {
        val profileId = activeProfileId() ?: return
        val globalToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return
        val globalRefresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        val globalExpiry = prefs.getLong(KEY_EXPIRES_AT, 0L)

        prefs.edit()
            .putString(profileKey(KEY_ACCESS_TOKEN, profileId), globalToken)
            .apply {
                if (globalRefresh != null) {
                    putString(profileKey(KEY_REFRESH_TOKEN, profileId), globalRefresh)
                }
                putLong(profileKey(KEY_EXPIRES_AT, profileId), globalExpiry)
            }
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()

        Log.i(TAG, "Migrated Trakt tokens to profile $profileId")
    }

    /** Refresh connection state for the current profile (call after profile switch). */
    fun refreshConnectionState() {
        migrateGlobalTokensToProfile()
        needsRefresh = false
        _isConnected.value = getAccessToken() != null
    }

    /**
     * Signing in merges the local watchlist into Trakt's. Saving the tokens starts a sync at
     * once, and that sync deletes local items Trakt lacks, so every local item is queued as a
     * pending add first: the sync pushes them instead of deleting them.
     */
    private suspend fun markLocalWatchlistForMerge(profileId: Int) {
        try {
            dao.getWatchlistOnce(profileId)
                .filter { it.id.startsWith("tt") }
                .forEach { watchlistPendingStore.mark(profileId, it.id, it.type, TraktWatchlistPendingStore.Op.ADD) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.w(TAG, "Couldn't queue the local watchlist for Trakt", e)
        }
    }

    // ── Token Storage (per-profile) ──

    private suspend fun saveTokens(response: TraktTokenResponse, profileId: Int): Boolean {
        return profileMutationCoordinator.withExistingProfile(profileId) {
            prefs.edit()
                .putString(profileKey(KEY_ACCESS_TOKEN, profileId), response.accessToken)
                .putString(profileKey(KEY_REFRESH_TOKEN, profileId), response.refreshToken)
                .putLong(profileKey(KEY_EXPIRES_AT, profileId), response.createdAt + response.expiresIn)
                .apply()
            if (activeProfileId() == profileId) {
                _isConnected.value = true
            }
            true
        } == true
    }

    fun getAccessToken(): String? {
        val profileId = activeProfileId()
        if (profileId == null) {
            needsRefresh = false
            return null
        }
        return getAccessToken(profileId)
    }

    private fun getAccessToken(profileId: Int): String? {
        val token = prefs.getString(profileKey(KEY_ACCESS_TOKEN, profileId), null)
        if (token == null) {
            if (activeProfileId() == profileId) needsRefresh = false
            return null
        }
        // Proactive expiry check: flag as needing refresh if within 60 seconds of expiry
        val expiresAt = prefs.getLong(profileKey(KEY_EXPIRES_AT, profileId), 0L)
        if (activeProfileId() == profileId) {
            needsRefresh = expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - 60
            if (needsRefresh) {
                Log.d(TAG, "Token expired or expiring soon, needs refresh")
            }
        }
        return token
    }

    /** True if the token is expired or about to expire. Checked by the interceptor. */
    @Volatile var needsRefresh = false
        private set

    private fun getRefreshToken(profileId: Int): String? =
        prefs.getString(profileKey(KEY_REFRESH_TOKEN, profileId), null)

    private fun clearTokens(profileId: Int?) {
        if (profileId != null) {
            prefs.edit()
                .remove(profileKey(KEY_ACCESS_TOKEN, profileId))
                .remove(profileKey(KEY_REFRESH_TOKEN, profileId))
                .remove(profileKey(KEY_EXPIRES_AT, profileId))
                .apply()
        }
        if (profileId == null || activeProfileId() == profileId) {
            _isConnected.value = false
            needsRefresh = false
        }
    }

    /** Clear tokens for a specific profile (e.g., when deleting the profile). */
    fun clearTokensForProfile(profileId: Int) {
        prefs.edit()
            .remove(profileKey(KEY_ACCESS_TOKEN, profileId))
            .remove(profileKey(KEY_REFRESH_TOKEN, profileId))
            .remove(profileKey(KEY_EXPIRES_AT, profileId))
            .apply()
        if (activeProfileId() == profileId) {
            _isConnected.value = false
            needsRefresh = false
        }
    }

    // ── Device Code Auth Flow ──

    fun startDeviceAuth() {
        // Capture the profile before any network work begins. A device-code attempt must
        // never finish into whichever profile happens to be active minutes later.
        val profileId = activeProfileId()
        if (profileId == null) {
            _authState.value = DeviceAuthState.Error("No active profile")
            return
        }

        // Cancel any still-running poll from a previous attempt (e.g. the user
        // dismissed the dialog and tapped Connect again) so it can't complete
        // later and silently overwrite this attempt's result.
        deviceAuthJob?.cancel()
        deviceAuthJob = managerScope.launch {
            _authState.value = DeviceAuthState.Idle
            if (BuildConfig.TRAKT_CLIENT_ID.isBlank()) {
                _authState.value = DeviceAuthState.NotConfigured
                return@launch
            }
            try {
                val response = traktApi.getDeviceCode(
                    mapOf("client_id" to BuildConfig.TRAKT_CLIENT_ID)
                )
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    _authState.value = DeviceAuthState.Error(
                        "Failed to get device code (HTTP ${response.code()})"
                    )
                    return@launch
                }

                _authState.value = DeviceAuthState.WaitingForUser(
                    userCode = body.userCode,
                    verificationUrl = body.verificationUrl
                )

                pollForToken(body, profileId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Superseded by a newer attempt; it owns the auth state now.
                throw cancelled
            } catch (e: Exception) {
                com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Device auth failed", e)
                _authState.value = DeviceAuthState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun pollForToken(deviceCode: TraktDeviceCodeResponse, profileId: Int) {
        val deadline = System.currentTimeMillis() + (deviceCode.expiresIn * 1000L)
        var interval = deviceCode.interval * 1000L

        while (System.currentTimeMillis() < deadline) {
            if (activeProfileId() != profileId) {
                _authState.value = DeviceAuthState.Idle
                return
            }
            delay(interval)

            try {
                val response = traktApi.pollToken(
                    mapOf(
                        "code" to deviceCode.deviceCode,
                        "client_id" to BuildConfig.TRAKT_CLIENT_ID,
                        "client_secret" to BuildConfig.TRAKT_CLIENT_SECRET
                    )
                )

                when (response.code()) {
                    200 -> {
                        val tokenBody = response.body()
                        if (tokenBody != null) {
                            markLocalWatchlistForMerge(profileId)
                            if (!saveTokens(tokenBody, profileId)) {
                                _authState.value = DeviceAuthState.Idle
                                return
                            }
                            if (activeProfileId() != profileId) {
                                _authState.value = DeviceAuthState.Idle
                                return
                            }
                            _authState.value = DeviceAuthState.Success
                            return
                        }
                    }
                    400 -> { /* Pending — keep polling */ }
                    404 -> { _authState.value = DeviceAuthState.Error("Invalid device code"); return }
                    409 -> { _authState.value = DeviceAuthState.Error("Code already used"); return }
                    410 -> { _authState.value = DeviceAuthState.Expired; return }
                    418 -> { _authState.value = DeviceAuthState.Error("Authorization denied"); return }
                    429 -> { interval += 1000L }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Poll attempt failed: ${e.message}")
            }
        }

        _authState.value = DeviceAuthState.Expired
    }

    // ── Token Refresh ──

    /**
     * Refresh the access token using the stored refresh token.
     * Returns the new access token, or null if refresh failed.
     * Called by TraktAuthInterceptor on 401 responses.
     */
    suspend fun refreshAccessToken(): String? {
        val profileId = activeProfileId() ?: return null
        val refreshToken = getRefreshToken(profileId) ?: return null
        return try {
            val response = traktApi.refreshToken(
                mapOf(
                    "refresh_token" to refreshToken,
                    "client_id" to BuildConfig.TRAKT_CLIENT_ID,
                    "client_secret" to BuildConfig.TRAKT_CLIENT_SECRET,
                    "redirect_uri" to "urn:ietf:wg:oauth:2.0:oob",
                    "grant_type" to "refresh_token"
                )
            )
            val body = response.body()
            if (response.isSuccessful && body != null) {
                // A successful OAuth refresh may rotate the refresh token. Persist the
                // replacement pair to the initiating profile even if the UI switched
                // profiles while the request was in flight, otherwise that profile can
                // be permanently stranded with a consumed refresh token.
                if (!saveTokens(body, profileId)) return null
                if (activeProfileId() != profileId) return null
                needsRefresh = false
                Log.i(TAG, "Token refreshed successfully")
                body.accessToken
            } else {
                Log.w(TAG, "Token refresh failed: ${response.code()}")
                if (response.code() == 401 || response.code() == 403) {
                    // Refresh token is also invalid — user needs to re-authenticate
                    clearTokens(profileId)
                }
                null
            }
        } catch (e: Exception) {
            com.hereliesaz.illumera.crash.AppErrors.e(TAG, "Token refresh error", e)
            null
        }
    }

    // ── Disconnect ──

    suspend fun disconnect() {
        val profileId = activeProfileId()
        val token = profileId?.let(::getAccessToken)
        if (token != null) {
            try {
                traktApi.revokeToken(
                    mapOf(
                        "token" to token,
                        "client_id" to BuildConfig.TRAKT_CLIENT_ID,
                        "client_secret" to BuildConfig.TRAKT_CLIENT_SECRET
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Token revocation failed: ${e.message}")
            }
        }
        clearTokens(profileId)
        deviceAuthJob?.cancel()
        _authState.value = DeviceAuthState.Idle
    }

    fun resetAuthState() {
        deviceAuthJob?.cancel()
        _authState.value = DeviceAuthState.Idle
    }
}
