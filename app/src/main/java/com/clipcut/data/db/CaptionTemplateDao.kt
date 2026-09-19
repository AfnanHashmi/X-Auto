package com.clipcut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptionTemplateDao {
    @Query("SELECT * FROM caption_templates ORDER BY title ASC")
    fun observeAll(): Flow<List<CaptionTemplate>>

    @Insert
    suspend fun insert(template: CaptionTemplate): Long

    @Query("DELETE FROM caption_templates WHERE id = :id")
    suspend fun delete(id: Long)
}
