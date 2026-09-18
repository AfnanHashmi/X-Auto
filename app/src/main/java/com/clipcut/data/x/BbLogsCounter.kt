package com.clipcut.data.x

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates the shared "BB LOGS - <counter>" caption used for every media-account post: counter
 * is zero-padded to 3 digits, 000..999, wrapping back to 000, and persisted across app restarts
 * (SharedPreferences -- a single int, no need for Room).
 */
@Singleton
class BbLogsCounter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("bb_logs_counter", Context.MODE_PRIVATE)

    @Synchronized
    fun nextCaption(): String {
        val current = prefs.getInt(KEY_COUNTER, 0)
        prefs.edit { putInt(KEY_COUNTER, (current + 1) % 1000) }
        return "BB LOGS - %03d".format(current)
    }

    private companion object {
        const val KEY_COUNTER = "counter"
    }
}
