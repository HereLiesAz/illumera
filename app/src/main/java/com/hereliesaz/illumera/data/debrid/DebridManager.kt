package com.hereliesaz.illumera.data.debrid

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hereliesaz.illumera.data.debrid.providers.AllDebridService
import com.hereliesaz.illumera.data.debrid.providers.DebridLinkService
import com.hereliesaz.illumera.data.debrid.providers.EasyDebridService
import com.hereliesaz.illumera.data.debrid.providers.OffcloudService
import com.hereliesaz.illumera.data.debrid.providers.PremiumizeService
import com.hereliesaz.illumera.data.debrid.providers.RealDebridService
import com.hereliesaz.illumera.data.debrid.providers.TorBoxService
import com.hereliesaz.illumera.data.model.debrid.DebridAccountInfo
import com.hereliesaz.illumera.data.model.debrid.DebridItem
import com.hereliesaz.illumera.data.model.debrid.DebridProvider
import com.hereliesaz.illumera.data.model.debrid.DebridResult
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileConfigurationManager: ProfileConfigurationManager,
    private val realDebrid: RealDebridService,
    private val allDebrid: AllDebridService,
    private val premiumize: PremiumizeService,
    private val torBox: TorBoxService,
    private val debridLink: DebridLinkService,
    private val offcloud: OffcloudService,
    private val easyDebrid: EasyDebridService
) {
    companion object {
        private const val TAG = "DebridManager"
        private const val PREFS_NAME = "debrid_auth"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_USERNAME = "username"
        private const val POLL_MS = 5_000L
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

    private fun activeProfileId(): Int = profileConfigurationManager.getLastActiveProfileId() ?: 1
    private fun profileKey(key: String, profileId: Int = activeProfileId()) = "${key}_$profileId"

    private val _connectedProvider = MutableStateFlow<DebridProvider?>(null)
    val connectedProvider: StateFlow<DebridProvider?> = _connectedProvider

    private val _connectedUsername = MutableStateFlow<String?>(null)
    val connectedUsername: StateFlow<String?> = _connectedUsername

    init { refreshConnectionState() }

    fun refreshConnectionState() {
        _connectedProvider.value = DebridProvider.fromId(prefs.getString(profileKey(KEY_PROVIDER), null))
        _connectedUsername.value = prefs.getString(profileKey(KEY_USERNAME), null)
    }

    private fun serviceFor(provider: DebridProvider): DebridService = when (provider) {
        DebridProvider.REAL_DEBRID -> realDebrid
        DebridProvider.ALL_DEBRID -> allDebrid
        DebridProvider.PREMIUMIZE -> premiumize
        DebridProvider.TORBOX -> torBox
        DebridProvider.DEBRID_LINK -> debridLink
        DebridProvider.OFFCLOUD -> offcloud
        DebridProvider.EASY_DEBRID -> easyDebrid
    }

    fun getApiKey(): String? = prefs.getString(profileKey(KEY_API_KEY), null)

    suspend fun connect(provider: DebridProvider, apiKey: String): DebridResult<DebridAccountInfo> {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) return DebridResult.Failure("API key cannot be empty")

        return when (val result = serviceFor(provider).validateApiKey(trimmedKey)) {
            is DebridResult.Success -> {
                val pid = activeProfileId()
                prefs.edit()
                    .putString(profileKey(KEY_PROVIDER, pid), provider.id)
                    .putString(profileKey(KEY_API_KEY, pid), trimmedKey)
                    .putString(profileKey(KEY_USERNAME, pid), result.value.username)
                    .apply()
                _connectedProvider.value = provider
                _connectedUsername.value = result.value.username
                result
            }
            is DebridResult.Failure -> {
                Log.w(TAG, "Failed to connect to ${provider.displayName}: ${result.message}")
                result
            }
        }
    }

    fun disconnect() {
        val pid = activeProfileId()
        prefs.edit()
            .remove(profileKey(KEY_PROVIDER, pid))
            .remove(profileKey(KEY_API_KEY, pid))
            .remove(profileKey(KEY_USERNAME, pid))
            .apply()
        _connectedProvider.value = null
        _connectedUsername.value = null
    }

    fun clearForProfile(profileId: Int) {
        prefs.edit()
            .remove(profileKey(KEY_PROVIDER, profileId))
            .remove(profileKey(KEY_API_KEY, profileId))
            .remove(profileKey(KEY_USERNAME, profileId))
            .apply()
    }

    suspend fun listLibrary(): DebridResult<List<DebridItem>> {
        val provider = _connectedProvider.value ?: return DebridResult.Failure("No debrid service connected")
        val apiKey = getApiKey() ?: return DebridResult.Failure("No debrid service connected")
        return serviceFor(provider).listLibrary(apiKey)
    }

    suspend fun getStreamUrl(item: DebridItem): DebridResult<String> {
        val apiKey = getApiKey() ?: return DebridResult.Failure("No debrid service connected")
        return serviceFor(item.provider).getStreamUrl(apiKey, item)
    }

    suspend fun deleteItem(item: DebridItem): DebridResult<Unit> {
        val apiKey = getApiKey() ?: return DebridResult.Failure("No debrid service connected")
        return serviceFor(item.provider).deleteItem(apiKey, item)
    }

    /**
     * Polls the connected debrid service for the torrent/file represented by a placeholder
     * clip. We wait only while the observed progress rate says completion is plausible
     * inside [maxWaitSeconds]; otherwise the caller can immediately try the next source.
     */
    suspend fun awaitPlayableSource(
        infoHash: String?,
        fileName: String?,
        maxWaitSeconds: Int
    ): String? {
        if (maxWaitSeconds <= 0) return null
        val provider = _connectedProvider.value ?: return null
        val apiKey = getApiKey() ?: return null
        val service = serviceFor(provider)
        val wantedHash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val wantedName = normalizeFileName(fileName)
        if (wantedHash == null && wantedName == null) return null

        val started = System.currentTimeMillis()
        val deadline = started + maxWaitSeconds * 1_000L
        var previousProgress: Int? = null
        var previousSampleAt = started
        var stagnantPolls = 0

        while (System.currentTimeMillis() < deadline) {
            val library = when (val result = service.listLibrary(apiKey)) {
                is DebridResult.Success -> result.value
                is DebridResult.Failure -> return null
            }
            val item = library.firstOrNull { candidate ->
                val hashMatches = wantedHash != null && candidate.infoHash?.lowercase() == wantedHash
                val candidateName = normalizeFileName(candidate.name)
                val nameMatches = wantedName != null && candidateName != null &&
                    (candidateName.contains(wantedName) || wantedName.contains(candidateName))
                hashMatches || nameMatches
            }

            if (item != null) {
                val progress = item.progress?.coerceIn(0, 100)
                val status = item.status?.lowercase().orEmpty()
                val ready = progress == 100 || status in setOf(
                    "downloaded", "ready", "finished", "completed", "cached", "seeding", "available"
                )
                if (ready) {
                    when (val stream = service.getStreamUrl(apiKey, item)) {
                        is DebridResult.Success -> return stream.value
                        is DebridResult.Failure -> Unit
                    }
                }

                if (progress != null) {
                    val now = System.currentTimeMillis()
                    val old = previousProgress
                    if (old != null) {
                        if (progress <= old) stagnantPolls++ else stagnantPolls = 0
                        val elapsedSampleSeconds = ((now - previousSampleAt).coerceAtLeast(1L)) / 1000.0
                        val rate = (progress - old).coerceAtLeast(0) / elapsedSampleSeconds
                        val secondsRemaining = ((deadline - now).coerceAtLeast(0L)) / 1000.0
                        if (rate > 0.0) {
                            val estimatedSeconds = (100 - progress) / rate
                            if (estimatedSeconds > secondsRemaining * 1.15) return null
                        } else if (stagnantPolls >= 3 && progress < 90) {
                            return null
                        }
                    }
                    previousProgress = progress
                    previousSampleAt = now
                }
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            delay(minOf(POLL_MS, remaining))
        }
        return null
    }

    private fun normalizeFileName(name: String?): String? {
        val value = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return value.substringAfterLast('/').substringBeforeLast('.', value.substringAfterLast('/'))
            .replace(Regex("[^a-z0-9]+"), "")
            .takeIf { it.length >= 6 }
    }
}
