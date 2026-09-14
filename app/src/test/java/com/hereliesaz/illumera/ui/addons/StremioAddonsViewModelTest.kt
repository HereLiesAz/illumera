package com.hereliesaz.illumera.ui.addons

import com.hereliesaz.illumera.data.auth.StremioAuthManager
import com.hereliesaz.illumera.data.auth.StremioConnectionState
import com.hereliesaz.illumera.data.model.AddonEntity
import com.hereliesaz.illumera.data.profile.ProfileConfigurationManager
import com.hereliesaz.illumera.data.remote.StremioAddonEntry
import com.hereliesaz.illumera.data.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StremioAddonsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var authManager: StremioAuthManager
    private lateinit var addonRepository: AddonRepository
    private lateinit var profileManager: ProfileConfigurationManager
    private lateinit var connectionState: MutableStateFlow<StremioConnectionState>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        authManager = mockk()
        addonRepository = mockk()
        profileManager = mockk()
        connectionState = MutableStateFlow(StremioConnectionState.Connected("user@example.com"))
        every { authManager.connectionState } returns connectionState
        every { profileManager.getLastActiveProfileId() } returns 7
        coEvery { profileManager.withActiveProfileRuntime<Unit>(7, any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
        }
        coEvery { profileManager.saveRuntimeState(7) } returns Unit
        every { profileManager.resetStartupCapture() } returns Unit
        coEvery { addonRepository.updateAddons(any()) } returns Unit
        coEvery { addonRepository.installAddon(any()) } returns Unit
        coEvery { addonRepository.deleteAddon(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun transportUrlNormalizationRemovesManifestSuffixAndTrailingSlash() {
        assertEquals("https://example.com/addon", normalizeTransportUrl(" https://example.com/addon/manifest.json "))
        assertEquals("https://example.com/addon", normalizeTransportUrl("https://example.com/addon/"))
        assertEquals("https://example.com/addon", normalizeTransportUrl("https://example.com/addon"))
    }

    @Test
    fun disconnectedAccountDoesNotAttemptRemoteSync() = runTest(dispatcher) {
        connectionState.value = StremioConnectionState.Disconnected
        val viewModel = newViewModel()

        viewModel.syncFromStremio()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSyncing)
        assertTrue(viewModel.uiState.value.error?.contains("not connected", ignoreCase = true) == true)
        coVerify(exactly = 0) { authManager.fetchAddons() }
        coVerify(exactly = 0) { addonRepository.updateAddons(any()) }
    }

    @Test
    fun repeatedSyncRequestWhileFirstIsPendingIsIgnored() = runTest(dispatcher) {
        coEvery { authManager.fetchAddons() } returns Result.success(emptyList())
        every { addonRepository.getAddons() } returns flowOf(emptyList())
        val viewModel = newViewModel()

        viewModel.syncFromStremio()
        assertTrue(viewModel.uiState.value.isSyncing)
        viewModel.syncFromStremio()
        advanceUntilIdle()

        coVerify(exactly = 1) { authManager.fetchAddons() }
        coVerify(exactly = 1) { profileManager.saveRuntimeState(7) }
        assertFalse(viewModel.uiState.value.isSyncing)
    }

    @Test
    fun syncUsesRemoteCollectionAsAuthorityAndPreservesCinemetaFallback() = runTest(dispatcher) {
        val cinemeta = addon("https://v3-cinemeta.strem.io", "Cinemeta", 0)
        val keep = addon("https://keep.example", "Keep", 1)
        val remove = addon("https://remove.example", "Remove", 2)
        val added = addon("https://add.example", "Added", 99)

        coEvery { authManager.fetchAddons() } returns Result.success(
            listOf(
                StremioAddonEntry("https://add.example/manifest.json", null),
                StremioAddonEntry("https://keep.example/manifest.json", null)
            )
        )
        every { addonRepository.getAddons() } returnsMany listOf(
            flowOf(listOf(cinemeta, keep, remove)),
            flowOf(listOf(cinemeta, keep, added))
        )

        val viewModel = newViewModel()
        viewModel.syncFromStremio()
        advanceUntilIdle()

        coVerify(exactly = 1) { addonRepository.installAddon("https://add.example/manifest.json") }
        coVerify(exactly = 1) { addonRepository.deleteAddon("https://remove.example") }
        coVerify(exactly = 0) { addonRepository.deleteAddon("https://v3-cinemeta.strem.io") }

        val orderedSlot = slot<List<AddonEntity>>()
        coVerify(exactly = 1) { addonRepository.updateAddons(capture(orderedSlot)) }
        assertEquals(
            listOf("https://add.example", "https://keep.example", "https://v3-cinemeta.strem.io"),
            orderedSlot.captured.map { it.transportUrl }
        )
        assertEquals(listOf(0, 1, 2), orderedSlot.captured.map { it.sortOrder })
        coVerify(exactly = 1) { profileManager.saveRuntimeState(7) }
        assertTrue(viewModel.uiState.value.error == null)
        assertTrue(viewModel.uiState.value.message?.contains("Synced 2 Stremio addons") == true)
    }

    @Test
    fun fetchFailureLeavesLocalCollectionUntouched() = runTest(dispatcher) {
        coEvery { authManager.fetchAddons() } returns Result.failure(IllegalStateException("network down"))
        val viewModel = newViewModel()

        viewModel.syncFromStremio()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSyncing)
        assertEquals("network down", viewModel.uiState.value.error)
        coVerify(exactly = 0) { addonRepository.getAddons() }
        coVerify(exactly = 0) { addonRepository.installAddon(any()) }
        coVerify(exactly = 0) { addonRepository.deleteAddon(any()) }
        coVerify(exactly = 0) { profileManager.saveRuntimeState(any()) }
    }

    @Test
    fun profileChangeBeforeRuntimeLockStopsSyncWithoutMutation() = runTest(dispatcher) {
        coEvery { profileManager.withActiveProfileRuntime<Unit>(7, any()) } throws
            CancellationException("Active profile changed before refresh started")
        val viewModel = newViewModel()

        viewModel.syncFromStremio()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSyncing)
        assertTrue(viewModel.uiState.value.error?.contains("Profile changed") == true)
        coVerify(exactly = 0) { authManager.fetchAddons() }
        coVerify(exactly = 0) { addonRepository.installAddon(any()) }
        coVerify(exactly = 0) { addonRepository.deleteAddon(any()) }
        coVerify(exactly = 0) { addonRepository.updateAddons(any()) }
        coVerify(exactly = 0) { profileManager.saveRuntimeState(any()) }
    }

    @Test
    fun oneFailedInstallDoesNotPreventOtherRemoteChangesFromBeingPersisted() = runTest(dispatcher) {
        val cinemeta = addon("https://v3-cinemeta.strem.io", "Cinemeta", 0)
        val old = addon("https://old.example", "Old", 1)
        val good = addon("https://good.example", "Good", 99)

        coEvery { authManager.fetchAddons() } returns Result.success(
            listOf(
                StremioAddonEntry("https://good.example/manifest.json", null),
                StremioAddonEntry("https://broken.example/manifest.json", null)
            )
        )
        every { addonRepository.getAddons() } returnsMany listOf(
            flowOf(listOf(cinemeta, old)),
            flowOf(listOf(cinemeta, good))
        )
        coEvery { addonRepository.installAddon("https://broken.example/manifest.json") } throws
            IllegalStateException("bad manifest")

        val viewModel = newViewModel()
        viewModel.syncFromStremio()
        advanceUntilIdle()

        coVerify(exactly = 1) { addonRepository.installAddon("https://good.example/manifest.json") }
        coVerify(exactly = 1) { addonRepository.installAddon("https://broken.example/manifest.json") }
        coVerify(exactly = 1) { addonRepository.deleteAddon("https://old.example") }
        coVerify(exactly = 1) { profileManager.saveRuntimeState(7) }
        assertTrue(viewModel.uiState.value.error?.contains("Some addons") == true)
        assertTrue(viewModel.uiState.value.message?.contains("1 could not be reconciled") == true)
    }

    private fun newViewModel() = StremioAddonsViewModel(authManager, addonRepository, profileManager)

    private fun addon(url: String, name: String, sortOrder: Int) = AddonEntity(
        transportUrl = url,
        id = name.lowercase(),
        name = name,
        version = "1.0.0",
        description = null,
        iconUrl = null,
        sortOrder = sortOrder
    )
}
