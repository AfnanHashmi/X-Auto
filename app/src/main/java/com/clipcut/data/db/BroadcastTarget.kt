package com.clipcut.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Where the daily link goes. One row per group chat, on either platform. */
@Entity(tableName = "broadcast_targets")
data class BroadcastTarget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [PLATFORM_X] or [PLATFORM_TELEGRAM]. */
    val platform: String,
    /** X: the dm_conversation_id. Telegram: the chat id (groups are negative) or @channelname. */
    val chatId: String,
    /** Your own name for the group, shown in the UI and the log. */
    val label: String,
    val enabled: Boolean = true,
) {
    companion object {
        const val PLATFORM_X = "X"
        const val PLATFORM_TELEGRAM = "TELEGRAM"
    }
}

/** One attempt to deliver to one target: the audit trail for something that runs while you sleep. */
@Entity(tableName = "broadcast_log")
data class BroadcastLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runAtEpochMs: Long,
    val platform: String,
    val label: String,
    val outcome: String,
    /** The error, or the sent message's id. Never contains a credential. */
    val detail: String,
    val link: String,
    /** True when the run was started by hand from the screen rather than by the 7am alarm. */
    val manual: Boolean = false,
) {
    companion object {
        const val SENT = "SENT"
        const val FAILED = "FAILED"
    }
}
