package com.clipcut.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local record of an exported clip, tracking its path and upload/post status. */
@Entity(tableName = "clip_records")
data class ClipRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val mediaRefId: String? = null,
    val posted: Boolean = false,
)
