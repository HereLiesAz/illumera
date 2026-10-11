package com.hereliesaz.illumera.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row marker naming the profile whose data currently occupies the shared runtime
 * tables (addons, catalogs, hub rows, watch_history). Written in the same transaction as
 * those tables, so it can never disagree with them after a crash.
 */
@Entity(tableName = "runtime_owner")
data class RuntimeOwnerEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val profileId: Int
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
