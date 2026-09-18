package com.clipcut.data.stream

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.clipcut.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
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

private const val KEY_POLICY = "policy"
private const val KEY_SIGNATURE = "signature"
private const val KEY_EXPIRES_AT = "expires_at"
private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"

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
    @ApplicationContext context: Context,
    private val authenticator: StreamAuthenticator,
) {
    // Persists the cached credential and the backoff state across process restarts. Without this,
    // a process restart (Android backgrounding the app, or -- as happened in practice -- several
    // manual reinstalls during testing) resets consecutiveFailures to 0, so each restart fires a
    // fresh "attempt 1" at the admin endpoint regardless of how recently a previous process's
    // attempt failed. Against a rate-limited endpoint, a handful of restarts in a short window is
    // enough to trip it again even though each individual process respected its own backoff.
    private val prefs = context.getSharedPreferences("signed_policy", Context.MODE_PRIVATE)

    private val mutex = Mutex()

    /**
     * Refreshes run here rather than on the caller's coroutine. Callers reach this from inside the
     * signing interceptor's `runBlocking`, so awaiting a refresh would block a live request for the
     * full four-round-trip mint -- the exact stall this class exists to prevent.
     */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cached: SignedPolicy? = loadCachedPolicy()

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var lastAttemptAtMs = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L)

    @Volatile
    private var consecutiveFailures = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0)

    /**
     * The manually-minted URL baked into `local.properties`/`BuildConfig.STREAM_URL`, parsed once
     * as a last-resort credential. Used only when live minting is unavailable (rate-limited, origin
     * down, network issue) AND there's no cached credential either -- e.g. right after a fresh
     * install/reinstall, before any live mint has ever succeeded. Without this, that combination
     * left playback completely broken with nothing to fall back to, even when a perfectly valid
     * backup signature was sitting right here the whole time.
     */
    private val buildConfigFallback: SignedPolicy? = SignedPolicy.parseFromUrl(BuildConfig.STREAM_URL)

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
            cached?.takeIf { !it.isExpired() }
                ?: mintRespectingBackoff()
                ?: buildConfigFallback?.takeIf { !it.isExpired() }
                    ?.also { Log.w(TAG, "live mint unavailable, using BuildConfig.STREAM_URL fallback") }
        }
    }

    /** Forces a re-mint, e.g. after the origin rejects the current credential with a 401. */
    suspend fun invalidateAndRefresh(): SignedPolicy? = mutex.withLock {
        cached = null
        mintRespectingBackoff() ?: buildConfigFallback?.takeIf { !it.isExpired() }
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
        persistBackoffState()
        return runCatching { authenticator.mint() }
            .onSuccess {
                cached = it
                lastFailure = null
                consecutiveFailures = 0
                persistBackoffState()
                persistPolicy(it)
                Log.i(TAG, "minted, expires in ${it.remainingMs() / 60_000}min")
            }
            .onFailure {
                lastFailure = it.message
                consecutiveFailures++
                persistBackoffState()
                Log.e(TAG, "mint failed (attempt $consecutiveFailures, next in ${currentBackoffMs() / 1000}s): ${it.message}")
            }
            .getOrNull()
    }

    private fun loadCachedPolicy(): SignedPolicy? {
        val policy = prefs.getString(KEY_POLICY, null) ?: return null
        val signature = prefs.getString(KEY_SIGNATURE, null) ?: return null
        val expiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, 0L)
        // Don't restore one that's already dead -- current() would just mint a fresh one anyway,
        // and doing it here would mean carrying a useless value through unnecessarily.
        if (expiresAtEpochMs <= System.currentTimeMillis()) return null
        return SignedPolicy(policy = policy, signature = signature, expiresAtEpochMs = expiresAtEpochMs)
    }

    private fun persistPolicy(policy: SignedPolicy) = prefs.edit {
        putString(KEY_POLICY, policy.policy)
        putString(KEY_SIGNATURE, policy.signature)
        putLong(KEY_EXPIRES_AT, policy.expiresAtEpochMs)
    }

    private fun persistBackoffState() = prefs.edit {
        putLong(KEY_LAST_ATTEMPT_AT, lastAttemptAtMs)
        putInt(KEY_CONSECUTIVE_FAILURES, consecutiveFailures)
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
