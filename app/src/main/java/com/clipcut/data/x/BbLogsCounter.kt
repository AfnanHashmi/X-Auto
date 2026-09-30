package com.clipcut.data.x

import android.content.Context
import androidx.core.content.edit
import com.clipcut.domain.api.MediaAccount
import com.clipcut.domain.api.postCaption
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates the "<label> - <counter>" caption used for every media-account post (label per account, e.g.
 * "BB LOGS - 014" for DK): counter
 * is zero-padded (three digits for DK, four for RW), wrapping back to zero, and persisted across app restarts
 * (SharedPreferences -- a single int per account, no need for Room).
 *
 * Each account numbers its own posts: DK's count is the original one and continues where it was, and
 * RW starts its own series at 0000, with four digits. Two accounts sharing one counter would leave gaps in both.
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
    fun nextCaption(account: MediaAccount): String = postCaption(account.captionLabel, current(account), account.numberDigits)

    /** Advances [account]'s counter past the value last returned by [nextCaption]. A failed post never calls this. */
    @Synchronized
    fun commit(account: MediaAccount) {
        prefs.edit { putInt(keyFor(account), (current(account) + 1) % account.counterModulus) }
    }

    private fun current(account: MediaAccount): Int = prefs.getInt(keyFor(account), firstFor(account))

    // Only reached before an account has posted anything: RW's first post is "BB LOGS - 0000".
    private fun firstFor(account: MediaAccount): Int = 0

    // DK keeps the original key, so its stored count carries over untouched.
    private fun keyFor(account: MediaAccount): String =
        if (account == MediaAccount.DK) KEY_COUNTER else "${KEY_COUNTER}_${account.name.lowercase()}"

    private companion object {
        const val KEY_COUNTER = "counter"
        const val KEY_SEEDED = "seeded_v1"
        const val FIRST_COUNTER_DK = 3
    }
}
