package com.clipcut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UndoEntryDao {
    @Insert
    suspend fun push(entry: UndoEntry): Long

    @Query("SELECT * FROM undo_entries WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun peekLatest(sessionId: String): UndoEntry?

    @Query("DELETE FROM undo_entries WHERE id = :id")
    suspend fun pop(id: Long)

    @Query("DELETE FROM undo_entries WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
