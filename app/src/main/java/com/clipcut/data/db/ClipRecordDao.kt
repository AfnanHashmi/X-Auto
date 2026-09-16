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
}
