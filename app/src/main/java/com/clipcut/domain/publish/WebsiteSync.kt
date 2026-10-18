package com.clipcut.domain.publish

import com.clipcut.domain.bossmeter.BossMeterStore
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.taglines.DeepLookStore
import com.clipcut.domain.taglines.TaglineStore
import com.clipcut.domain.taglines.regulars
import java.time.ZoneId
import javax.inject.Inject

/** What happened to one re-send: queued (the banner then shows the result) or why nothing was sent. */
sealed interface SyncResult {
    data object Queued : SyncResult
    data class Nothing(val reason: String) : SyncResult
}

/**
 * Re-sends what the app already holds to the website, without counting anything on X (no X calls, no cost). The latest
 * Tagline Counter run goes with whatever AI read-out and Deep looks exist for it now; the latest Boss Meter run goes with
 * its stage, final flag and insights. Both use the run's own timestamp, so the website updates that run in place.
 */
class WebsiteSync @Inject constructor(
    private val taglines: TaglineStore,
    private val deepLooks: DeepLookStore,
    private val history: RunHistory,
    private val boss: BossMeterStore,
    private val publisher: WebsitePublisher,
) {
    fun sendTagline(): SyncResult {
        val report = taglines.lastReport() ?: return SyncResult.Nothing("No Tagline Counter run yet. Count first.")
        val looks = deepLooks.looksFor(report.fetchedAtMs)
        val steady = deepLooks.allLooks().groupBy { it.tagline }.mapValues { regulars(it.value) }
        return try {
            publisher.publish(trendPayload(report, ZoneId.systemDefault(), deepLooks.readoutFor(report.fetchedAtMs), looks, steady))
            SyncResult.Queued
        } catch (e: PayloadException) {
            val why = e.message ?: "The website cannot take this result."
            publisher.reject(MeterKind.TREND, why)
            SyncResult.Nothing(why)
        }
    }

    /**
     * Re-sends the latest Boss Meter run. With [stage] and [final] given (the choices on the Boss Meter screen) the run is
     * sent as that: marking the saved run as the final result needs no recount. They are saved with the run, so later
     * re-sends (the home buttons) keep them. Without them, the run goes as it was counted.
     */
    fun sendBoss(stage: BossStage? = null, final: Boolean? = null): SyncResult {
        val result = boss.lastResult() ?: return SyncResult.Nothing("No Boss Meter run yet. Count first.")
        val run = history.bossRuns().lastOrNull { it.result.fetchedAtMs == result.fetchedAtMs }
        val link = run?.link?.takeIf { it.isNotBlank() } ?: boss.link
        val useStage = stage ?: BossStage.from(run?.stage) ?: BossStage.from(boss.stage)
        val useFinal = final ?: (run?.final == true)
        return try {
            val payload = bossPayload(result, link, useStage, useFinal, withInsights = true)
            history.saveBoss(result, link, useStage?.wire.orEmpty(), useFinal)
            publisher.publish(payload)
            SyncResult.Queued
        } catch (e: PayloadException) {
            val why = e.message ?: "The website cannot take this result."
            publisher.reject(MeterKind.BOSS, why)
            SyncResult.Nothing(why)
        }
    }

    /** Both, for the master button. The Top 5 is not included: it is published on purpose from its own screen. */
    fun sendAll(): Map<MeterKind, SyncResult> = mapOf(MeterKind.TREND to sendTagline(), MeterKind.BOSS to sendBoss())
}
