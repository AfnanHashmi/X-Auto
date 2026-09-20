package com.clipcut.domain.broadcast

import com.clipcut.data.broadcast.AdminLinkClient
import com.clipcut.data.db.BroadcastLogDao
import com.clipcut.data.db.BroadcastLogEntry
import com.clipcut.data.db.BroadcastTarget
import com.clipcut.data.db.BroadcastTargetDao
import com.clipcut.data.telegram.TelegramClient
import com.clipcut.data.x.XApiClient
import kotlinx.coroutines.delay
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.random.Random

data class BroadcastResult(
    val link: String?,
    val sent: Int,
    val failed: Int,
    val skippedAlreadySentToday: Boolean = false,
    val error: String? = null,
)

/**
 * Sends the current viewing link to every enabled group chat, on X and on Telegram.
 *
 * This is the one thing in the app that acts without anyone watching, so it is deliberately timid:
 *  - the link is fetched ONCE up front, and if that fails nothing is sent at all (better a silent
 *    morning than a broken link in every group);
 *  - targets are delivered to one at a time with a gap between them, never in parallel -- a burst of
 *    identical messages is what automated-spam detection looks for;
 *  - one target failing never stops the others, and nothing is ever retried inside a run, because a
 *    retry that cannot tell "failed" from "no reply" is how a group gets the same link twice;
 *  - every attempt is written to [BroadcastLogDao], success or failure.
 */
@Singleton
class BroadcastService @Inject constructor(
    private val adminLink: AdminLinkClient,
    private val telegram: TelegramClient,
    @Named("media") private val x: XApiClient,
    private val targets: BroadcastTargetDao,
    private val log: BroadcastLogDao,
    private val settings: BroadcastSettings,
) {
    /**
     * @param manual true when you pressed Send now, which skips the once-a-day guard and marks the
     *   log rows so a hand-made send is never mistaken for the morning run.
     */
    suspend fun run(manual: Boolean, nowMs: Long = System.currentTimeMillis()): BroadcastResult {
        val list = targets.enabled()
        if (list.isEmpty()) return BroadcastResult(null, 0, 0, error = "No groups are set up yet.")

        // A re-delivered alarm (reboot, a retried worker) must not send the link a second time.
        if (!manual && log.sentAutomaticallySince(nowMs - ONCE_A_DAY_GUARD_MS) > 0) {
            return BroadcastResult(null, 0, 0, skippedAlreadySentToday = true)
        }

        val link = try {
            adminLink.currentLink()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return BroadcastResult(null, 0, 0, error = "Could not get today's link: ${e.message}")
        }
        val text = settings.message(link)

        var sent = 0
        var failed = 0
        for ((index, target) in list.withIndex()) {
            if (index > 0) delay(GAP_MS + Random.nextLong(-JITTER_MS, JITTER_MS + 1))
            val outcome = try {
                val id = when (target.platform) {
                    BroadcastTarget.PLATFORM_TELEGRAM -> telegram.sendMessage(target.chatId, text).toString()
                    BroadcastTarget.PLATFORM_X -> x.sendDirectMessage(target.chatId, text)
                    else -> error("Unknown platform ${target.platform}")
                }
                sent++
                BroadcastLogEntry.SENT to "message $id"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed++
                BroadcastLogEntry.FAILED to (e.message ?: e::class.simpleName.orEmpty())
            }
            log.insert(
                BroadcastLogEntry(
                    runAtEpochMs = nowMs,
                    platform = target.platform,
                    label = target.label,
                    outcome = outcome.first,
                    detail = outcome.second.take(300),
                    link = link,
                    manual = manual,
                ),
            )
        }
        log.trim()
        return BroadcastResult(link, sent, failed)
    }

    /** Proves the pieces work without sending anything: fetches the link and checks the bot token. */
    suspend fun dryRun(): Pair<String, String?> {
        val link = adminLink.currentLink()
        val bot = if (telegram.isConfigured) {
            try {
                "@" + telegram.me()
            } catch (e: IOException) {
                "bot token rejected: ${e.message}"
            }
        } else {
            null
        }
        return link to bot
    }

    private companion object {
        const val GAP_MS = 4_000L
        const val JITTER_MS = 1_500L
        const val ONCE_A_DAY_GUARD_MS = 18 * 60 * 60 * 1000L
    }
}
