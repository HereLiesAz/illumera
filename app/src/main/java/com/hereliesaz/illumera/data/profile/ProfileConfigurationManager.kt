package com.hereliesaz.illumera.data.profile

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ProfileRuntimeSnapshot(
    val addons: List<AddonEntity> = emptyList(),
    val catalogConfigs: List<CatalogConfigEntity> = emptyList(),
    val hubRows: List<HubRowEntity> = emptyList(),
    val hubRowItems: List<HubRowItemEntity> = emptyList(),
    val watchHistory: List<WatchHistoryEntity> = emptyList()
)

@Singleton
class ProfileConfigurationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AddonDao,
    private val stremioAuthManager: StremioAuthManager,
    private val addonRepository: AddonRepository
) {
    companion object {
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

    private val runtimeMutex = Mutex()
    private var startupRuntimeCaptured = false

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
    fun resetStartupCapture() { startupRuntimeCaptured = false }

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
        if (startupRuntimeCaptured) return
        startupRuntimeCaptured = true

        val lastActive = getLastActiveProfileId() ?: return
        if (needsInitialSetup(lastActive)) return
        saveRuntimeState(lastActive)
    }

    suspend fun saveRuntimeState(profileId: Int) {
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
        val existingSnapshot = readSnapshot(profileId)
        val snapshot = existingSnapshot ?: if (!needsInitialSetup(profileId)) {
            captureRuntimeSnapshot().also {
                writeSnapshot(profileId, it)
                stremioAuthManager.saveCredentialsForProfile(profileId)
            }
        } else {
            ProfileRuntimeSnapshot()
        }
        // Merging (keep-newest) watch history only makes sense when reloading the
        // SAME profile that's already active in the DB (e.g. recovering from a
        // crash before the last onStop snapshot was written). On a genuine switch
        // to a different profile, the DB's watch history belongs to the outgoing
        // profile and must be fully replaced, not merged in — merging it would leak
        // that profile's progress/watched-state into the incoming one.
        val sameProfile = profileId == getLastActiveProfileId()
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
        captureStartupRuntimeIfNeeded()
        val sourceSnapshot = readSnapshot(sourceProfileId) ?: captureRuntimeSnapshot().also {
            writeSnapshot(sourceProfileId, it)
            stremioAuthManager.saveCredentialsForProfile(sourceProfileId)
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
        val defaultSnapshot = createDefaultRuntimeSnapshot()
        val loginResult = stremioAuthManager.login(email, password)

        return loginResult.fold(
            onSuccess = {
                stremioAuthManager.saveCredentialsForProfile(profileId)
                val addonsResult = stremioAuthManager.fetchAddons()
                addonsResult.fold(
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
                        writeSnapshot(profileId, defaultSnapshot)
                        clearPendingSetup(profileId)
                        Result.failure(error)
                    }
                )
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

        entries.forEach { entry ->
            val transportUrl = entry.transportUrl.removeSuffix("/manifest.json").trimEnd('/')
            // Every fresh profile already gets the default Cinemeta addon — skip a duplicate.
            if (transportUrl == DEFAULT_CINEMETA_TRANSPORT_URL) return@forEach

            val manifest = runCatching {
                addonRepository.fetchManifest("$transportUrl/manifest.json")
            }.getOrNull() ?: return@forEach
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
                catalogsJson = gson.toJson(catalogs)
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

        return ProfileRuntimeSnapshot(addons = addons, catalogConfigs = configs)
    }

    fun deleteProfileState(profileId: Int) {
        clearPendingSetup(profileId)
        snapshotFile(profileId).delete()
        stremioAuthManager.clearCredentialsForProfile(profileId)

        if (getLastActiveProfileId() == profileId) {
            prefs.edit().remove(KEY_LAST_ACTIVE_PROFILE_ID).apply()
        }
    }

    private suspend fun copyProfileDisplayAndDashboardConfig(targetProfileId: Int, sourceProfileId: Int) {
        val sourceProfile = dao.getProfileById(sourceProfileId) ?: return
        val targetProfile = dao.getProfileById(targetProfileId) ?: return

        dao.insertProfile(
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
        )
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
            catalogsJson = gson.toJson(catalogs)
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
        file.writeText(gson.toJson(snapshot))
    }

    private fun readSnapshot(profileId: Int): ProfileRuntimeSnapshot? {
        val file = snapshotFile(profileId)
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(), ProfileRuntimeSnapshot::class.java)
        }.getOrNull()
    }

    private fun snapshotFile(profileId: Int): File {
        return File(File(context.filesDir, SNAPSHOT_DIR), "profile_$profileId.json")
    }

    private fun getPendingSetupIds(): Set<String> {
        return prefs.getStringSet(KEY_PENDING_SETUP_PROFILES, emptySet()) ?: emptySet()
    }

    fun getLastActiveProfileId(): Int? {
        val value = prefs.getInt(KEY_LAST_ACTIVE_PROFILE_ID, -1)
        return if (value == -1) null else value
    }

    fun clearLastActiveProfileId() {
        prefs.edit().remove(KEY_LAST_ACTIVE_PROFILE_ID).apply()
    }

    private fun setLastActiveProfileId(profileId: Int) {
        prefs.edit().putInt(KEY_LAST_ACTIVE_PROFILE_ID, profileId).apply()
    }
}
