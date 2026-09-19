package com.clipcut.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One snapshot of the caption text before a mutating action (manual edit, template applied,
 * AI enhance). Popped by the Undo button to revert the most recent change for a given session.
 */
@Entity(tableName = "undo_entries")
data class UndoEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val previousText: String,
    val createdAtEpochMs: Long,
)
