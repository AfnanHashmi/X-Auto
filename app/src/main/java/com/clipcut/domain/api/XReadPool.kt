package com.clipcut.domain.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The three X developer apps, each its own X project with its own prepaid balance. DK's keys are the `X_MEDIA_*` entries.
 * Only public reads (counts) are shared between them; posting, uploading and deleting always stay with their own account.
 */
enum class XApp(val label: String) { MAIN("Main"), RW("RW"), DK("DK") }

/** Below this a balance is shown in red and the app stops preferring that account for shared reads. */
const val LOW_BALANCE_USD = 1.0

/** Which account each shared read prefers first. Another one is used only when the first is low or out of credits. */
val TAGLINE_ORDER = listOf(XApp.RW, XApp.MAIN, XApp.DK)

sealed interface Balance {
    data class Known(val usd: Double, val atMs: Long) : Balance
    data class Unavailable(val reason: String) : Balance
    data object NotSetUp : Balance
    /** Keys are there, but no balance has been read yet. */
    data object Unchecked : Balance
}

data class Balances(val byApp: Map<XApp, Balance> = emptyMap(), val refreshing: Boolean = false, val atMs: Long? = null) {
    fun usd(app: XApp): Double? = (byApp[app] as? Balance.Known)?.usd
    fun isLow(app: XApp): Boolean = usd(app)?.let { it < LOW_BALANCE_USD } ?: false
}

/** An app-only GET with one account's app. Throws [XReadException]. */
interface XAppGateway {
    fun isConfigured(app: XApp): Boolean
    suspend fun get(app: XApp, url: String): String
}

const val CREDITS_URL = "https://api.x.com/2/usage/credits"

/** `GET /2/usage/credits`: `data.total_balance` in USD. */
fun parseCredits(body: String): Double {
    val data = JSONObject(body).optJSONObject("data") ?: throw XReadException("X's balance reply has no data")
    if (!data.has("total_balance")) throw XReadException("X's balance reply has no total")
    return data.getDouble("total_balance")
}

/**
 * Shares public, paid reads (tweet counts) between the accounts so one running low does not stop the Tagline
 * Counter or the Trend check.
 *
 * - Each read goes to the first account in the feature's order whose balance is at least [LOW_BALANCE_USD]. When every
 *   account is low, the one with the most left is used. When no balance is known (the check failed), the order is used as is.
 * - If X answers 402 (out of credits), that account is skipped for the rest of the session's balances and the same read is
 *   retried once on each other account. Any other error is passed on unchanged: nothing else is retried here.
 * - Balances come from X's own `usage/credits` endpoint, are re-read at most every [ttlMs], and are lowered locally by
 *   each call's price in between, so a long run moves on as soon as an account gets low.
 */
class XReadPool(
    private val gateway: XAppGateway,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 2 * 60_000L,
) {
    private val _balances = MutableStateFlow(Balances(XApp.entries.associateWith { if (gateway.isConfigured(it)) Balance.Unchecked else Balance.NotSetUp }))
    val balances: StateFlow<Balances> = _balances.asStateFlow()

    private val _calls = MutableStateFlow<Map<XApp, Int>>(emptyMap())

    /** Paid calls made through the pool so far, per account. A screen diffs two snapshots to say who paid for a run. */
    val calls: StateFlow<Map<XApp, Int>> = _calls.asStateFlow()

    private val refreshLock = Mutex()

    fun isAnyConfigured(order: List<XApp>): Boolean = order.any(gateway::isConfigured)

    /** Re-reads every balance (free). Skipped when the last read is newer than [ttlMs], unless [force]. */
    suspend fun refreshBalances(force: Boolean = false) = refreshLock.withLock {
        val last = _balances.value.atMs
        if (!force && last != null && nowMs() - last < ttlMs) return@withLock
        _balances.update { it.copy(refreshing = true) }
        val fresh = XApp.entries.associateWith { app ->
            if (!gateway.isConfigured(app)) {
                Balance.NotSetUp
            } else {
                try {
                    Balance.Known(parseCredits(gateway.get(app, CREDITS_URL)), nowMs())
                } catch (e: XReadException) {
                    Balance.Unavailable(e.httpCode?.let { "X answered HTTP $it" } ?: "No reply from X")
                } catch (e: org.json.JSONException) {
                    Balance.Unavailable("Unreadable reply from X")
                }
            }
        }
        _balances.value = Balances(fresh, refreshing = false, atMs = nowMs())
    }

    /** The account the next read in [order] would use, from what is known now, skipping [skip]. Null if none is set up. */
    fun pick(order: List<XApp>, skip: Set<XApp> = emptySet()): XApp? {
        val b = _balances.value
        val candidates = order.filter { gateway.isConfigured(it) && it !in skip }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { (b.usd(it) ?: Double.NaN) >= LOW_BALANCE_USD }?.let { return it }
        // Nothing comfortably funded: an unchecked account before one known to be low, then the most money left.
        candidates.firstOrNull { b.usd(it) == null }?.let { return it }
        return candidates.maxBy { b.usd(it) ?: 0.0 }
    }

    /** For the cost box: which account would pay, and what it has left. */
    fun payerText(order: List<XApp>): String {
        val app = pick(order) ?: return "No X app keys are set up."
        val left = _balances.value.usd(app)?.let { "$${"%.2f".format(it)} left" } ?: "balance not checked"
        val switched = if (app != order.firstOrNull { gateway.isConfigured(it) }) " (${order.first().label} is low)" else ""
        return "Paid from ${app.label}'s X credits ($left)$switched."
    }

    /**
     * One paid read for a feature that prefers [order], priced at [costUsd]. Returns the reply body and the account that paid.
     * A 402 moves on to the next account; every other failure is thrown as is.
     */
    suspend fun read(order: List<XApp>, url: String, costUsd: Double): Pair<XApp, String> {
        refreshBalances()
        val tried = mutableSetOf<XApp>()
        var last402: XReadException? = null
        while (true) {
            val app = pick(order, tried) ?: throw (last402 ?: XReadException("No X app keys are set up in local.properties for this feature."))
            try {
                val body = gateway.get(app, url)
                spent(app, costUsd)
                return app to body
            } catch (e: XReadException) {
                if (e.httpCode != 402) throw e
                tried += app
                last402 = e
                _balances.update { b -> b.copy(byApp = b.byApp + (app to Balance.Known(0.0, nowMs()))) }
            }
        }
    }

    private fun spent(app: XApp, costUsd: Double) {
        _calls.update { it + (app to (it[app] ?: 0) + 1) }
        _balances.update { b ->
            val now = b.byApp[app] as? Balance.Known ?: return@update b
            b.copy(byApp = b.byApp + (app to now.copy(usd = (now.usd - costUsd).coerceAtLeast(0.0))))
        }
    }
}

/** "RW (6 calls)" or "RW (4 calls), Main (2 calls)" from two snapshots of [XReadPool.calls]. Empty when nothing was paid. */
fun paidByText(before: Map<XApp, Int>, after: Map<XApp, Int>): String =
    XApp.entries.mapNotNull { app ->
        val n = (after[app] ?: 0) - (before[app] ?: 0)
        if (n > 0) "${app.label} ($n ${if (n == 1) "call" else "calls"})" else null
    }.joinToString(", ")
