package com.clipcut.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The permanent deletion log: one row per post the app tried to delete. The primary key is the post
 * id, which makes it double as a blocklist -- an id in here is never offered for deletion again.
 *
 * [outcome] is written as [IN_FLIGHT] *before* the DELETE request goes out (the same idiom as
 * `ClipRecord.uploadAttempted`), then updated to [DELETED] / [ALREADY_GONE]. A row left at
 * [IN_FLIGHT] means the app died or lost the connection mid-request and nobody knows whether X
 * processed it; it is shown as such rather than guessed at.
 */
@Entity(tableName = "deleted_posts")
data class DeletedPost(
    @PrimaryKey val tweetId: String,
    val text: String,
    val bbNumber: Int?,
    val postedAtEpochMs: Long,
    val deletedAtEpochMs: Long,
    val outcome: String,
    val runId: String,
) {
    companion object {
        const val IN_FLIGHT = "IN_FLIGHT"
        const val DELETED = "DELETED"
        const val ALREADY_GONE = "ALREADY_GONE"
    }
}
