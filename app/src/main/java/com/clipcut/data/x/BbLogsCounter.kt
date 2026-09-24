package com.clipcut.data.x

import android.content.Context
import androidx.core.content.edit
import com.clipcut.domain.api.MediaAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates the shared "BB LOGS - <counter>" caption used for every media-account post: counter
 * is zero-padded to 3 digits, 000..999, wrapping back to 000, and persisted across app restarts
 * (SharedPreferences -- a single int per account, no need for Room).
 *
 * Each account numbers its own posts: DK's count is the original one and continues where it was, and
 * BB starts its own series at 001. Two accounts sharing one counter would leave gaps in both.
 */
@Singleton
class BbLogsCounter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("bb_logs_counter", Context.MODE_PRIVATE)

    init {
        // 001 and 002 were posted by hand, so numbering continues at 003. The phone already held a
        // stale counter from earlier testing (value 4), so a changed default alone would not apply;
        // seed it exactly once, then never touch it again.
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            prefs.edit {
                putInt(keyFor(MediaAccount.DK), FIRST_COUNTER_DK)
                putBoolean(KEY_SEEDED, true)
            }
        }
    }

    /** Reserves and returns the next caption; call [commit] only after the post using it succeeds. */
    @Synchronized
    fun nextCaption(account: MediaAccount): String = "BB LOGS - %03d".format(current(account))

    /** Advances [account]'s counter past the value last returned by [nextCaption]. A failed post never calls this. */
    @Synchronized
    fun commit(account: MediaAccount) {
        prefs.edit { putInt(keyFor(account), (current(account) + 1) % 1000) }
    }

    private fun current(account: MediaAccount): Int = prefs.getInt(keyFor(account), firstFor(account))

    private fun firstFor(account: MediaAccount): Int = if (account == MediaAccount.DK) 0 else FIRST_COUNTER_OTHER

    // DK keeps the original key, so its stored count carries over untouched.
    private fun keyFor(account: MediaAccount): String =
        if (account == MediaAccount.DK) KEY_COUNTER else "${KEY_COUNTER}_${account.name.lowercase()}"

    private companion object {
        const val KEY_COUNTER = "counter"
        const val KEY_SEEDED = "seeded_v1"
        const val FIRST_COUNTER_DK = 3
        const val FIRST_COUNTER_OTHER = 1
    }
}
