package com.clipcut.data.stream

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Refresh once remaining validity drops below this -- a wide margin, so retries have room. */
private const val REFRESH_WHEN_REMAINING_MS = 90 * 60 * 1000L

/** Below this, a refresh has been failing long enough that it's worth telling the user. */
private const val WARN_WHEN_REMAINING_MS = 15 * 60 * 1000L

/**
 * Backoff between failed mint attempts, doubling up to [MAX_RETRY_BACKOFF_MS].
 *
 * This matters more than it looks: the signing interceptor asks for a credential on *every* stream
 * request, and a live player plus the playlist poller generate a steady stream of those. Without
 * backoff a single failure turns into a retry per request -- which is exactly how this first hit
 * the origin's rate limiter ("HTTP 429: Too many attempts") and turned a transient failure into a
 * sustained one.
 */
private const val BASE_RETRY_BACKOFF_MS = 5_000L
private const val MAX_RETRY_BACKOFF_MS = 5 * 60 * 1000L

private const val TAG = "SignedPolicy"

/**
 * Holds the current stream credential and re-mints it before it expires.
 *
 * A broadcast window can run longer than the credential's ~12h life, so exactly one refresh
 * typically lands mid-stream. Refreshing at [REFRESH_WHEN_REMAINING_MS] means it happens while the
 * *existing* credential is still perfectly good -- playback keeps using the old one throughout, and
 * failures just retry on the next call with ~90 minutes of slack. Nothing blocks on a refresh.
 */
@Singleton
class SignedPolicyProvider @Inject constructor(
    private val authenticator: StreamAuthenticator,
) {
    private val mutex = Mutex()

    /**
     * Refreshes run here rather than on the caller's coroutine. Callers reach this from inside the
     * signing interceptor's `runBlocking`, so awaiting a refresh would block a live request for the
     * full four-round-trip mint -- the exact stall this class exists to prevent.
     */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cached: SignedPolicy? = null

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var lastAttemptAtMs = 0L

    @Volatile
    private var consecutiveFailures = 0

    /**
     * The credential to sign requests with, minting one if there is none. Returns the existing
     * (still-valid) credential immediately when a refresh is merely due, so callers never wait on
     * the network for a refresh that isn't urgent.
     */
    suspend fun current(): SignedPolicy? {
        val existing = cached
        if (existing != null && !existing.needsRefresh()) return existing
        if (existing != null && !existing.isExpired()) {
            refreshScope.launch { refreshInBackgroundSafe() }
            return existing // still valid -- hand it back now, refresh lands on a later request.
        }
        return mutex.withLock {
            cached?.takeIf { !it.isExpired() } ?: mintRespectingBackoff()
        }
    }

    /** Forces a re-mint, e.g. after the origin rejects the current credential with a 401. */
    suspend fun invalidateAndRefresh(): SignedPolicy? = mutex.withLock {
        cached = null
        mintRespectingBackoff()
    }

    /**
     * Single mint attempt, unless a recent failure means we're still inside the backoff window --
     * callers arrive once per stream request, so this is the throttle that keeps a failure from
     * becoming a request-rate flood against the origin.
     */
    private suspend fun mintRespectingBackoff(): SignedPolicy? {
        val sinceLastAttempt = System.currentTimeMillis() - lastAttemptAtMs
        if (consecutiveFailures > 0 && sinceLastAttempt < currentBackoffMs()) return null

        lastAttemptAtMs = System.currentTimeMillis()
        return runCatching { authenticator.mint() }
            .onSuccess {
                cached = it
                lastFailure = null
                consecutiveFailures = 0
                Log.i(TAG, "minted, expires in ${it.remainingMs() / 60_000}min")
            }
            .onFailure {
                lastFailure = it.message
                consecutiveFailures++
                Log.e(TAG, "mint failed (attempt $consecutiveFailures, next in ${currentBackoffMs() / 1000}s): ${it.message}")
            }
            .getOrNull()
    }

    private fun currentBackoffMs(): Long {
        val doubled = BASE_RETRY_BACKOFF_MS shl (consecutiveFailures - 1).coerceIn(0, 16)
        return doubled.coerceAtMost(MAX_RETRY_BACKOFF_MS)
    }

    /** Non-null once the credential is close enough to expiry that a user-visible warning is warranted. */
    fun warningOrNull(): String? {
        val remaining = cached?.remainingMs() ?: return null
        if (remaining > WARN_WHEN_REMAINING_MS) return null
        val failure = lastFailure ?: return "Stream access expires in ${remaining / 60_000}min"
        return "Stream access expires in ${remaining / 60_000}min -- refresh failing: $failure"
    }

    private suspend fun refreshInBackgroundSafe() {
        if (!mutex.tryLock()) return // a refresh is already running; don't pile up.
        try {
            mintRespectingBackoff()
        } finally {
            mutex.unlock()
        }
    }

    private fun SignedPolicy.remainingMs() = expiresAtEpochMs - System.currentTimeMillis()
    private fun SignedPolicy.needsRefresh() = remainingMs() < REFRESH_WHEN_REMAINING_MS
    private fun SignedPolicy.isExpired() = remainingMs() <= 0
}
