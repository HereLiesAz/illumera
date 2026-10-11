package com.hereliesaz.illumera.data.profile

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.hereliesaz.illumera.crash.AppErrors
import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.model.CatalogConfigEntity
import com.hereliesaz.illumera.data.model.HubRowEntity
import com.hereliesaz.illumera.data.model.HubRowItemEntity
import com.hereliesaz.illumera.data.model.WatchHistoryEntity
import com.hereliesaz.illumera.data.model.stremio.CatalogManifest
import com.hereliesaz.illumera.data.remote.StremioAddonEntry
import com.hereliesaz.illumera.data.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ProfileRuntimeSnapshot(
    val addons: List<AddonEntity> = emptyList(),
    val catalogConfigs: List<CatalogConfigEntity> = emptyList(),
    val hubRows: List<HubRowEntity> = emptyList(),
    val hubRowItems: List<HubRowItemEntity> = emptyList(),
    val watchHistory: List<WatchHistoryEntity> = emptyList()
)

/** Result of reading a profile's snapshot file: absent, unreadable, or parsed. */
private sealed class SnapshotRead {
    object Missing : SnapshotRead()
    object Corrupt : SnapshotRead()
    data class Loaded(val snapshot: ProfileRuntimeSnapshot) : SnapshotRead()
}

@Singleton
class ProfileConfigurationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AddonDao,
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository,
    private val profileMutationCoordinator: ProfileMutationCoordinator
) {
    companion object {
        private const val TAG = "ProfileConfigManager"
        private const val PREFS_FILE = "profile_configuration_prefs"
        private const val KEY_PENDING_SETUP_PROFILES = "pending_setup_profiles"
        private const val KEY_LAST_ACTIVE_PROFILE_ID = "last_active_profile_id"
        private const val SNAPSHOT_DIR = "profile_snapshots"
        private const val DEFAULT_CINEMETA_MANIFEST_URL = "https://v3-cinemeta.strem.io/manifest.json"
        private const val DEFAULT_CINEMETA_TRANSPORT_URL = "https://v3-cinemeta.strem.io"
    }

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    private val _activeProfileId = MutableStateFlow(readLastActiveProfileId())
    val activeProfileId: StateFlow<Int?> = _activeProfileId.asStateFlow()

    private val runtimeMutex = Mutex()
    private val startupRuntimeCaptured = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Runs profile-scoped background work while preventing a profile switch from
     * replacing the shared runtime tables underneath it. If the requested profile
     * is no longer active when the lock is acquired, the work is cancelled rather
     * than writing into another profile's runtime state.
     */
    suspend fun <T> withActiveProfileRuntime(profileId: Int, block: suspend () -> T): T =
        runtimeMutex.withLock {
            if (getLastActiveProfileId() != profileId) {
                throw CancellationException("Active profile changed before refresh started")
            }
            block()
        }

    /**
     * Allows captureStartupRuntimeIfNeeded() to run again. Call after runtime
     * state changes (e.g. an addon sync) that happen before the one-time
     * startup capture would otherwise permanently skip re-snapshotting.
     */
    fun resetStartupCapture() { startupRuntimeCaptured.set(false) }

    fun markPendingSetup(profileId: Int) {
        val updated = getPendingSetupIds().toMutableSet().apply { add(profileId.toString()) }
        prefs.edit().putStringSet(KEY_PENDING_SETUP_PROFILES, updated).apply()
    }

    fun needsInitialSetup(profileId: Int): Boolean {
        return getPendingSetupIds().contains(profileId.toString())
    }

    fun clearPendingSetup(profileId: Int) {
        val updated = getPendingSetupIds().toMutableSet().apply { remove(profileId.toString()) }
        prefs.edit().putStringSet(KEY_PENDING_SETUP_PROFILES, updated).apply()
    }

    suspend fun captureStartupRuntimeIfNeeded() {
        if (!startupRuntimeCaptured.compareAndSet(false, true)) return

        val lastActive = getLastActiveProfileId() ?: return
        if (needsInitialSetup(lastActive)) return
        saveRuntimeState(lastActive)
    }

    suspend fun saveRuntimeState(profileId: Int) = runtimeMutex.withLock {
        if (getLastActiveProfileId() != profileId) {
            throw CancellationException("Active profile changed before runtime save")
        }
        saveRuntimeStateLocked(profileId)
    }

    /**
     * Persists runtime state from code that already owns [runtimeMutex] through
     * [withActiveProfileRuntime]. This avoids trying to re-enter the non-reentrant mutex.
     */
    internal suspend fun saveRuntimeStateWithinActiveRuntime(profileId: Int) {
        if (getLastActiveProfileId() != profileId) {
            throw CancellationException("Active profile changed before runtime save")
        }
        saveRuntimeStateLocked(profileId)
    }

    private suspend fun saveRuntimeStateLocked(profileId: Int) {
        val snapshot = captureRuntimeSnapshot()
        writeSnapshot(profileId, snapshot)
        stremioAuthManager.saveCredentialsForProfile(profileId)
        setLastActiveProfileId(profileId)
    }

    suspend fun saveActiveRuntimeState() {
        val activeId = getLastActiveProfileId() ?: return
        saveRuntimeState(activeId)
    }

    suspend fun loadRuntimeState(profileId: Int) = runtimeMutex.withLock {
        val sameProfile = profileId == getLastActiveProfileId()
        val snapshot = when (val read = readSnapshot(profileId)) {
            is SnapshotRead.Loaded -> read.snapshot
            // No snapshot yet (legacy profile from before snapshots existed): adopt the
            // live tables, which is how such profiles were stored.
            SnapshotRead.Missing -> if (!needsInitialSetup(profileId)) {
                captureRuntimeSnapshot().also {
                    writeSnapshot(profileId, it)
                    stremioAuthManager.saveCredentialsForProfile(profileId)
                }
            } else {
                ProfileRuntimeSnapshot()
            }
            // An unreadable snapshot must never be "repaired" from the live tables on a
            // switch: those hold the OUTGOING profile's data and Stremio session, which
            // would leak into this one. Only the same, already-active profile may
            // re-adopt the live DB (it is its own data); otherwise fall back to setup.
            SnapshotRead.Corrupt -> if (sameProfile) {
                captureRuntimeSnapshot().also { writeSnapshot(profileId, it) }
            } else {
                AppErrors.e(TAG, "Profile snapshot unreadable; profile reset to initial setup")
                markPendingSetup(profileId)
                ProfileRuntimeSnapshot()
            }
        }
        // Merging (keep-newest) watch history only makes sense when reloading the
        // SAME profile that's already active in the DB (e.g. recovering from a
        // crash before the last onStop snapshot was written). On a genuine switch
        // to a different profile, the DB's watch history belongs to the outgoing
        // profile and must be fully replaced, not merged in — merging it would leak
        // that profile's progress/watched-state into the incoming one.
        dao.replaceRuntimeState(
            addons = snapshot.addons,
            catalogConfigs = snapshot.catalogConfigs,
            hubRows = snapshot.hubRows,
            hubRowItems = snapshot.hubRowItems,
            watchHistory = snapshot.watchHistory,
            mergeWatchHistory = sameProfile
        )
        stremioAuthManager.loadCredentialsForProfile(profileId)
        setLastActiveProfileId(profileId)
    }

    suspend fun initializeFromScratch(profileId: Int) {
        writeSnapshot(profileId, createDefaultRuntimeSnapshot())
        stremioAuthManager.clearCredentialsForProfile(profileId)
        clearPendingSetup(profileId)
    }

    suspend fun initializeByCopying(targetProfileId: Int, sourceProfileId: Int) {
        // The live tables only ever belong to the active profile. If the source is
        // active, persist its live state first so the copy isn't of a stale snapshot;
        // otherwise copy only its own snapshot — never the active profile's tables.
        if (sourceProfileId == getLastActiveProfileId()) {
            saveRuntimeState(sourceProfileId)
        }
        val sourceSnapshot = when (val read = readSnapshot(sourceProfileId)) {
            is SnapshotRead.Loaded -> read.snapshot
            else -> {
                AppErrors.e(TAG, "Copy source profile has no readable snapshot; using defaults")
                createDefaultRuntimeSnapshot()
            }
        }
        writeSnapshot(targetProfileId, sourceSnapshot.copy(watchHistory = emptyList()))
        stremioAuthManager.copyCredentialsBetweenProfiles(sourceProfileId, targetProfileId)
        copyProfileDisplayAndDashboardConfig(targetProfileId, sourceProfileId)
        clearPendingSetup(targetProfileId)
    }

    /**
     * Initializes a fresh profile from scratch (default Cinemeta addon, same as
     * [initializeFromScratch]), then logs in to Stremio with the given credentials and
     * folds the account's addon collection into the profile's snapshot so it's ready
     * as soon as the profile is selected. On login/fetch failure the profile still ends
     * up initialized with just the default addons — [Result.failure] only signals that
     * the Stremio side didn't complete, not that setup as a whole failed.
     */
    suspend fun initializeFromScratchWithStremio(profileId: Int, email: String, password: String): Result<Int> {
        val loginResult = stremioAuthManager.login(email, password)

        return loginResult.fold(
            onSuccess = { initializeFromScratchWithConnectedStremio(profileId) },
            onFailure = { error ->
                val defaultSnapshot = createDefaultRuntimeSnapshot()
                stremioAuthManager.clearCredentialsForProfile(profileId)
                writeSnapshot(profileId, defaultSnapshot)
                clearPendingSetup(profileId)
                Result.failure(error)
            }
        )
    }

    /**
     * Completes fresh-profile initialization when Stremio authentication was already
     * completed through a hosted flow such as Facebook or Apple.
     */
    suspend fun initializeFromScratchWithConnectedStremio(profileId: Int): Result<Int> {
        val defaultSnapshot = createDefaultRuntimeSnapshot()
        stremioAuthManager.saveCredentialsForProfile(profileId)
        val addonsResult = stremioAuthManager.fetchAddons()

        return addonsResult.fold(
            onSuccess = { entries ->
                val fetched = buildSnapshotFromStremioAddons(entries)
                writeSnapshot(
                    profileId,
                    defaultSnapshot.copy(
                        addons = defaultSnapshot.addons + fetched.addons,
                        catalogConfigs = defaultSnapshot.catalogConfigs + fetched.catalogConfigs
                    )
                )
                clearPendingSetup(profileId)
                Result.success(fetched.addons.size)
            },
            onFailure = { error ->
                stremioAuthManager.clearCredentialsForProfile(profileId)
                writeSnapshot(profileId, defaultSnapshot)
                clearPendingSetup(profileId)
                Result.failure(error)
            }
        )
    }

    /** Fetches the full manifest for each Stremio addon entry and builds installable snapshot rows. */
    private suspend fun buildSnapshotFromStremioAddons(entries: List<StremioAddonEntry>): ProfileRuntimeSnapshot {
        val addons = mutableListOf<AddonEntity>()
        val configs = mutableListOf<CatalogConfigEntity>()
        var failed = 0

        entries.forEach { entry ->
            val transportUrl = entry.transportUrl.removeSuffix("/manifest.json").trimEnd('/')
            // Every fresh profile already gets the default Cinemeta addon — skip a duplicate.
            if (transportUrl == DEFAULT_CINEMETA_TRANSPORT_URL) return@forEach

            val manifest = try {
                addonRepository.fetchManifest("$transportUrl/manifest.json")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                return@forEach
            }
            val catalogs = manifest.catalogs.orEmpty()
            val addonName = manifest.name.ifBlank { entry.manifest?.name ?: "Unknown Addon" }

            addons += AddonEntity(
                transportUrl = transportUrl,
                id = manifest.id,
                name = addonName,
                version = manifest.version,
                description = manifest.description,
                iconUrl = manifest.logo,
                isTrusted = false,
                isEnabled = true,
                nickname = null,
                catalogsJson = gson.toJson(catalogs),
                supportsStream = manifest.supportsResource("stream")
            )

            configs += catalogs.mapIndexed { index, catalog ->
                val isMovie = catalog.type == "movie"
                val isSeries = catalog.type == "series"
                CatalogConfigEntity(
                    uniqueId = "$transportUrl/${catalog.type}/${catalog.id}",
                    transportUrl = transportUrl,
                    addonName = addonName,
                    catalogType = catalog.type,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    customTitle = null,
                    showInHome = true,
                    showInMovies = isMovie,
                    showInSeries = isSeries,
                    homeOrder = 999,
                    moviesOrder = if (isMovie) index else 999,
                    seriesOrder = if (isSeries) index else 999
                )
            }
        }

        if (failed > 0) {
            // The returned snapshot (and the count reported to the caller) only contains
            // addons whose manifest loaded; surface the ones that were dropped.
            AppErrors.e(TAG, "Skipped $failed of ${entries.size} Stremio addons: manifest fetch failed")
        }
        return ProfileRuntimeSnapshot(addons = addons, catalogConfigs = configs)
    }

    fun deleteProfileState(profileId: Int) {
        clearPendingSetup(profileId)
        snapshotFile(profileId).delete()
        stremioAuthManager.clearCredentialsForProfile(profileId)

        if (getLastActiveProfileId() == profileId) {
            clearLastActiveProfileId()
        }
    }

    private suspend fun copyProfileDisplayAndDashboardConfig(targetProfileId: Int, sourceProfileId: Int) {
        val sourceProfile = dao.getProfileById(sourceProfileId) ?: return

        // Routed through the coordinator so a concurrent settings write to the target
        // row can't be lost to (or clobber) this read-modify-write.
        profileMutationCoordinator.update(targetProfileId) { targetProfile ->
            targetProfile.copy(
                roundCorners = sourceProfile.roundCorners,
                hubRoundCorners = sourceProfile.hubRoundCorners,
                navPosition = sourceProfile.navPosition,
                menuMoviesEnabled = sourceProfile.menuMoviesEnabled,
                menuSeriesEnabled = sourceProfile.menuSeriesEnabled,
                menuWatchlistEnabled = sourceProfile.menuWatchlistEnabled,
                homeTabLayout = sourceProfile.homeTabLayout,
                moviesTabLayout = sourceProfile.moviesTabLayout,
                seriesTabLayout = sourceProfile.seriesTabLayout,
                homeHeroCategory = sourceProfile.homeHeroCategory,
                homeHeroPosterCount = sourceProfile.homeHeroPosterCount,
                homeHeroAutoScrollSeconds = sourceProfile.homeHeroAutoScrollSeconds,
                moviesHeroCategory = sourceProfile.moviesHeroCategory,
                moviesHeroPosterCount = sourceProfile.moviesHeroPosterCount,
                moviesHeroAutoScrollSeconds = sourceProfile.moviesHeroAutoScrollSeconds,
                seriesHeroCategory = sourceProfile.seriesHeroCategory,
                seriesHeroPosterCount = sourceProfile.seriesHeroPosterCount,
                seriesHeroAutoScrollSeconds = sourceProfile.seriesHeroAutoScrollSeconds
            )
        }
    }

    private suspend fun captureRuntimeSnapshot(): ProfileRuntimeSnapshot {
        return ProfileRuntimeSnapshot(
            addons = dao.getAllAddons().firstOrNull() ?: emptyList(),
            catalogConfigs = dao.getAllCatalogConfigs().firstOrNull() ?: emptyList(),
            hubRows = dao.getAllHubRows().firstOrNull() ?: emptyList(),
            hubRowItems = dao.getAllHubRowItems().firstOrNull() ?: emptyList(),
            watchHistory = dao.getWatchHistory().firstOrNull() ?: emptyList()
        )
    }

    private suspend fun createDefaultRuntimeSnapshot(): ProfileRuntimeSnapshot {
        val fallbackCatalogs = listOf(
            CatalogManifest(type = "movie", id = "top", name = "Top"),
            CatalogManifest(type = "series", id = "top", name = "Top")
        )

        val manifest = runCatching { addonRepository.fetchManifest(DEFAULT_CINEMETA_MANIFEST_URL) }.getOrNull()
        val catalogs = manifest?.catalogs
            ?.filter { it.type == "movie" || it.type == "series" }
            ?.ifEmpty { fallbackCatalogs }
            ?: fallbackCatalogs

        val addonName = manifest?.name ?: "Cinemeta"
        val addonEntity = AddonEntity(
            transportUrl = DEFAULT_CINEMETA_TRANSPORT_URL,
            id = manifest?.id ?: "org.stremio.cinemeta",
            name = addonName,
            version = manifest?.version ?: "1.0.0",
            description = manifest?.description ?: "Official Stremio metadata addon",
            iconUrl = manifest?.logo,
            isTrusted = false,
            isEnabled = true,
            nickname = null,
            catalogsJson = gson.toJson(catalogs),
            // Cinemeta is metadata only; asking it for streams always 404s.
            supportsStream = manifest?.supportsResource("stream") ?: false
        )

        val configs = catalogs.mapIndexed { index, catalog ->
            val isMovie = catalog.type == "movie"
            val isSeries = catalog.type == "series"
            CatalogConfigEntity(
                uniqueId = "${DEFAULT_CINEMETA_TRANSPORT_URL}/${catalog.type}/${catalog.id}",
                transportUrl = DEFAULT_CINEMETA_TRANSPORT_URL,
                addonName = addonName,
                catalogType = catalog.type,
                catalogId = catalog.id,
                catalogName = catalog.name,
                customTitle = null,
                showInHome = true,
                showInMovies = isMovie,
                showInSeries = isSeries,
                homeOrder = index,
                moviesOrder = if (isMovie) index else 999,
                seriesOrder = if (isSeries) index else 999
            )
        }

        return ProfileRuntimeSnapshot(
            addons = listOf(addonEntity),
            catalogConfigs = configs,
            hubRows = emptyList(),
            hubRowItems = emptyList(),
            watchHistory = emptyList()
        )
    }

    private fun writeSnapshot(profileId: Int, snapshot: ProfileRuntimeSnapshot) {
        val file = snapshotFile(profileId)
        file.parentFile?.mkdirs()
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmpFile).use { out ->
            out.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
            out.flush()
            // Durable before the rename publishes it, so a power loss can't leave an
            // empty/partial file under the real name.
            out.fd.sync()
        }
        if (!tmpFile.renameTo(file)) {
            // rename can fail (e.g. target briefly locked); fall back to copy + delete.
            try {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            } catch (e: Exception) {
                AppErrors.e(TAG, "Failed to persist profile snapshot", e)
                throw e
            }
        }
    }

    private fun readSnapshot(profileId: Int): SnapshotRead {
        val file = snapshotFile(profileId)
        // Clean up any leftover temp file from a previous interrupted write
        File(file.parentFile, "${file.name}.tmp").takeIf { it.exists() }?.delete()
        if (!file.exists()) return SnapshotRead.Missing
        val parsed = try {
            gson.fromJson(file.readText(), ProfileRuntimeSnapshot::class.java)
        } catch (e: Exception) {
            AppErrors.w(TAG, "Profile snapshot could not be parsed", e)
            null
        }
        if (parsed == null) {
            // Keep the bad file aside for diagnosis instead of overwriting it silently.
            file.renameTo(File(file.parentFile, "${file.name}.corrupt"))
            return SnapshotRead.Corrupt
        }
        return SnapshotRead.Loaded(parsed)
    }

    private fun snapshotFile(profileId: Int): File {
        return File(File(context.filesDir, SNAPSHOT_DIR), "profile_$profileId.json")
    }

    private fun getPendingSetupIds(): Set<String> {
        return prefs.getStringSet(KEY_PENDING_SETUP_PROFILES, emptySet()) ?: emptySet()
    }

    fun getLastActiveProfileId(): Int? = _activeProfileId.value

    fun clearLastActiveProfileId() {
        prefs.edit().remove(KEY_LAST_ACTIVE_PROFILE_ID).apply()
        _activeProfileId.value = null
    }

    private fun setLastActiveProfileId(profileId: Int) {
        prefs.edit().putInt(KEY_LAST_ACTIVE_PROFILE_ID, profileId).apply()
        _activeProfileId.value = profileId
    }

    private fun readLastActiveProfileId(): Int? {
        val value = prefs.getInt(KEY_LAST_ACTIVE_PROFILE_ID, -1)
        return if (value == -1) null else value
    }
}
