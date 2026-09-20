package com.clipcut.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BroadcastTargetDao {
    @Insert
    suspend fun insert(target: BroadcastTarget): Long

    @Update
    suspend fun update(target: BroadcastTarget)

    @Delete
    suspend fun delete(target: BroadcastTarget)

    @Query("SELECT * FROM broadcast_targets ORDER BY platform, id")
    fun observeAll(): Flow<List<BroadcastTarget>>

    @Query("SELECT * FROM broadcast_targets WHERE enabled = 1 ORDER BY platform, id")
    suspend fun enabled(): List<BroadcastTarget>
}

@Dao
interface BroadcastLogDao {
    @Insert
    suspend fun insert(entry: BroadcastLogEntry)

    @Query("SELECT * FROM broadcast_log ORDER BY id DESC LIMIT 200")
    fun observeRecent(): Flow<List<BroadcastLogEntry>>

    @Query("SELECT * FROM broadcast_log ORDER BY id DESC LIMIT 1")
    suspend fun latest(): BroadcastLogEntry?

    /** Guards against a double run: did we already deliver to this target since [sinceEpochMs]? */
    @Query("SELECT COUNT(*) FROM broadcast_log WHERE outcome = 'SENT' AND manual = 0 AND runAtEpochMs >= :sinceEpochMs")
    suspend fun sentAutomaticallySince(sinceEpochMs: Long): Int

    @Query("DELETE FROM broadcast_log WHERE id NOT IN (SELECT id FROM broadcast_log ORDER BY id DESC LIMIT 500)")
    suspend fun trim()
}
