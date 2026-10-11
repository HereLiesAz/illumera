package com.hereliesaz.illumera.data.model

import androidx.room.Entity

/**
 * Records that a watch-history row was deleted by a profile, so a crash-recovery merge
 * of a stale profile snapshot does not resurrect it. A tombstone only suppresses a
 * snapshot row whose lastWatched is not newer than [deletedAt] (re-watching wins).
 */
@Entity(tableName = "history_tombstones", primaryKeys = ["profileId", "itemId"])
data class HistoryTombstoneEntity(
    val profileId: Int,
    val itemId: String,
    val deletedAt: Long
)
