package com.clipcut.domain.publish

import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.taglines.TrendCheckStore
import com.clipcut.domain.taglines.TaglineStore
import java.time.ZoneId
import javax.inject.Inject

/** What happened to one re-send: queued (the banner then shows the result) or why nothing was sent. */
sealed interface SyncResult {
    data object Queued : SyncResult
    data class Nothing(val reason: String) : SyncResult
}

/**
 * Re-sends what the app already holds to the website, without counting anything on X (no X calls, no cost). The latest
 * Tagline Counter run goes with whatever AI read-out and Trend checks exist for it now. It uses the run's own timestamp,
 * so the website updates that run in place.
 */
class WebsiteSync @Inject constructor(
    private val taglines: TaglineStore,
    private val trendChecks: TrendCheckStore,
    private val history: RunHistory,
    private val publisher: WebsitePublisher,
) {
    fun sendTagline(): SyncResult {
        val report = taglines.lastReport() ?: return SyncResult.Nothing("No Tagline Counter run yet. Count first.")
        val checks = report.counts.mapNotNull { c -> trendChecks.forTagline(c.tagline).firstOrNull()?.let { c.tagline to it } }.toMap()
        return try {
            publisher.publish(trendPayload(report, ZoneId.systemDefault(), trendChecks.readoutFor(report.fetchedAtMs), checks))
            SyncResult.Queued
        } catch (e: PayloadException) {
            val why = e.message ?: "The website cannot take this result."
            publisher.reject(MeterKind.TREND, why)
            SyncResult.Nothing(why)
        }
    }

}
