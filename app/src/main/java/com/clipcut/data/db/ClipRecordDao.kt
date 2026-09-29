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

    @Query("SELECT * FROM clip_records WHERE filePath = :filePath LIMIT 1")
    suspend fun findByPath(filePath: String): ClipRecord?
}

/**
 * Returns the record for [filePath], creating it if absent. Nothing else ever inserted these rows,
 * which silently disabled every duplicate-publish guard that keys off them. Export writes
 * `lan_clip_<startEpochMs>.mp4`, so the start time is recovered from the name; the end time is not
 * known here and nothing reads it, so it is stored as 0.
 */
suspend fun ClipRecordDao.getOrCreate(filePath: String): ClipRecord {
    findByPath(filePath)?.let { return it }
    val startEpochMs = Regex("""lan_clip_(\d+)""").find(java.io.File(filePath).name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val id = insert(ClipRecord(filePath = filePath, startEpochMs = startEpochMs, endEpochMs = 0L))
    return findByPath(filePath) ?: ClipRecord(id = id, filePath = filePath, startEpochMs = startEpochMs, endEpochMs = 0L)
}
