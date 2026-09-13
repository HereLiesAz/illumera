package com.hereliesaz.illumera.data.profile

import com.hereliesaz.illumera.data.local.AddonDao
import com.hereliesaz.illumera.data.model.ProfileEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes read/transform/write mutations of existing profile rows.
 *
 * Profile settings are stored as one Room entity. Without a process-wide lock,
 * two independent ViewModels can both read the same old row, change different
 * fields, and then overwrite one another when each writes its full copy back.
 */
@Singleton
class ProfileMutationCoordinator @Inject constructor(
    private val dao: AddonDao
) {
    private val mutex = Mutex()

    suspend fun update(
        profileId: Int,
        transform: (ProfileEntity) -> ProfileEntity
    ): ProfileEntity? = mutex.withLock {
        val current = dao.getProfileById(profileId) ?: return@withLock null
        val updated = transform(current)
        dao.updateProfile(updated)
        updated
    }
}
