package com.clipcut.domain.radar

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.IOException

/** A failed call. [message] is the Worker's own reason when it gave one. [httpCode] is null when no reply arrived. */
class RadarApiException(message: String, val httpCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** The Trend Radar Worker (Cloudflare). It reads X through xAI; this app never talks to xAI or X for it. */
interface RadarApi {
    val isConfigured: Boolean

    suspend fun report(): RadarReport

    suspend fun preview(type: RunKind, posts: Int?): RunPreview

    /** Switch the day session on (with a spending limit) or off. */
    suspend fun session(on: Boolean, budgetUsd: Double?)

    /** Queues a real run (picked up within a minute), or replays a saved sample at once when [dry]. */
    suspend fun run(type: RunKind, posts: Int?, focus: String?, dry: Boolean)

    /** Changes settings. [patch] holds only the keys to change. */
    suspend fun config(patch: JSONObject)

    suspend fun exclude(handle: String)
}

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val reason: String) : Outcome<Nothing>
}

/** Turns the API's failures into reasons a person can read. Nothing here changes anything by itself. */
class RadarService(private val api: RadarApi) {
    val isConfigured: Boolean get() = api.isConfigured

    private suspend fun <T> guarded(block: suspend () -> T): Outcome<T> {
        if (!api.isConfigured) return Outcome.Failed("The Trend Radar address or token is not set in local.properties (TREND_URL, TREND_TOKEN).")
        return try {
            Outcome.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RadarApiException) {
            Outcome.Failed(
                when (e.httpCode) {
                    401 -> "The Trend Radar refused the token. Check TREND_TOKEN in local.properties matches the Worker's TREND_APP_TOKEN."
                    null -> "The Trend Radar did not reply (${e.message}). Nothing was changed."
                    else -> e.message ?: "The Trend Radar returned an error (HTTP ${e.httpCode})."
                },
            )
        } catch (e: RadarParseException) {
            Outcome.Failed("The Trend Radar's reply could not be trusted: ${e.message}")
        } catch (e: IOException) {
            Outcome.Failed("The Trend Radar did not reply (${e.message}). Nothing was changed.")
        } catch (e: Exception) {
            Outcome.Failed("That did not go through: ${e.message}")
        }
    }

    suspend fun report() = guarded { api.report() }

    suspend fun preview(type: RunKind, posts: Int?) = guarded { api.preview(type, posts) }

    suspend fun setSession(on: Boolean, budgetUsd: Double? = null) = guarded { api.session(on, budgetUsd) }

    suspend fun run(type: RunKind, posts: Int? = null, focus: String? = null) = guarded { api.run(type, posts, focus, dry = false) }

    suspend fun sample(type: RunKind) = guarded { api.run(type, null, null, dry = true) }

    suspend fun saveConfig(patch: JSONObject) = guarded { api.config(patch) }

    suspend fun exclude(handle: String) = guarded { api.exclude(handle.trim().removePrefix("@")) }
}

/** What the home card shows. */
data class RadarSummary(val phase: String, val rising: Int, val lastRunAtMs: Long?, val spentUsd: Double, val budgetUsd: Double?, val sample: Boolean) {
    companion object {
        val EMPTY = RadarSummary("off", 0, null, 0.0, null, false)

        fun of(r: RadarReport) = RadarSummary(
            phase = r.session.phase,
            rising = if (r.latest?.run?.status == "ok") r.risingCount else 0,
            lastRunAtMs = r.latest?.run?.startedAtMs,
            spentUsd = r.session.spentUsd,
            budgetUsd = r.session.budgetUsd,
            sample = r.latest?.run?.status == "dry_run",
        )
    }
}
