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
    val mediaRefUrl: String? = null,
    val posted: Boolean = false,
    // Set true immediately before calling the post API, cleared right after the call returns
    // (success or failure). Left true only if the app died mid-call, so a clean failure still
    // allows a normal retry -- this narrowly flags "we don't actually know what happened".
    val postAttempted: Boolean = false,
)
