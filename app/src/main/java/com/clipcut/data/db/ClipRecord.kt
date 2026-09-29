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
    // Same idea as postAttempted, for the media-account upload+tweet: set true (and persisted)
    // BEFORE the request, cleared only on success or a definitive rejection. If it is still true on
    // the next open, the app died (or the reply was lost) mid-request and the media post may
    // already exist, so re-uploading blind would publish the same clip twice.
    val uploadAttempted: Boolean = false,
)
