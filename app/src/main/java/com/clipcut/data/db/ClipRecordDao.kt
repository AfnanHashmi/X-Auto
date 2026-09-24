package com.clipcut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipRecordDao {
    @Insert
    suspend fun insert(record: ClipRecord): Long

    @Update
    suspend fun update(record: ClipRecord)

    @Query("SELECT * FROM clip_records ORDER BY id DESC")
    fun observeAll(): Flow<List<ClipRecord>>

    @Query("SELECT * FROM clip_records WHERE filePath = :filePath AND account = :account LIMIT 1")
    suspend fun findByPath(filePath: String, account: String): ClipRecord?

    /** Marks the clip's main-account step done for the post that was actually made (a post id is unique). */
    @Query("UPDATE clip_records SET posted = 1 WHERE filePath = :filePath AND mediaRefId = :mediaRefId")
    suspend fun markPosted(filePath: String, mediaRefId: String)
}

/**
 * Returns the record for [filePath], creating it if absent. Nothing else ever inserted these rows,
 * which silently disabled every duplicate-publish guard that keys off them. Export writes
 * `lan_clip_<startEpochMs>.mp4`, so the start time is recovered from the name; the end time is not
 * known here and nothing reads it, so it is stored as 0.
 */
suspend fun ClipRecordDao.getOrCreate(filePath: String, account: String): ClipRecord {
    findByPath(filePath, account)?.let { return it }
    val startEpochMs = Regex("""lan_clip_(\d+)""").find(java.io.File(filePath).name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val id = insert(ClipRecord(filePath = filePath, account = account, startEpochMs = startEpochMs, endEpochMs = 0L))
    return findByPath(filePath, account)
        ?: ClipRecord(id = id, filePath = filePath, account = account, startEpochMs = startEpochMs, endEpochMs = 0L)
}
