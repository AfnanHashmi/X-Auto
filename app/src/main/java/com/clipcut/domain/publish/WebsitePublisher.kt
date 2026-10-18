package com.clipcut.domain.publish

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val PUBLISH_URL = "https://biggboss24hrs.com/api/meters/publish"

/** What the website answered. [code] is null when no reply came back. [detail] is its own short message, if any. */
data class WebsiteReply(val code: Int?, val detail: String = "")

/** One POST of [json] with the key. Implementations must never log or return the key. */
interface WebsiteHttp {
    suspend fun post(json: String, key: String): WebsiteReply
}

/** Where the publish key lives: encrypted on the phone. */
interface KeyVault {
    fun get(): String?
    fun set(key: String)
    fun clear()
}

/** Payloads that have not reached the website yet, so Retry still works after the app is closed. */
interface PendingStore {
    fun load(kind: MeterKind): String?
    fun save(kind: MeterKind, json: String?)
}

sealed interface PublishStatus {
    data object Idle : PublishStatus
    data object Sending : PublishStatus
    data class Done(val atMs: Long) : PublishStatus
    data class Failed(val reason: String) : PublishStatus
}

/**
 * Sends the app's results to the website in the background. The app's own results never wait for it and never depend on
 * it: [publish] returns at once and any failure only changes the status shown beside the results.
 */
class WebsitePublisher(
    private val http: WebsiteHttp,
    private val vault: KeyVault,
    private val pending: PendingStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val jobs = HashMap<MeterKind, Job>()
    private val last = HashMap<MeterKind, String>()

    private val _status = MutableStateFlow(MeterKind.entries.associateWith { restored(it) })
    val status: StateFlow<Map<MeterKind, PublishStatus>> = _status.asStateFlow()

    private fun restored(kind: MeterKind): PublishStatus =
        pending.load(kind)?.let { last[kind] = it; PublishStatus.Failed("The last update did not reach the website.") } ?: PublishStatus.Idle

    val hasKey: Boolean get() = !vault.get().isNullOrBlank()

    /** Queues [payload] and returns immediately. A newer payload of the same kind replaces one still on its way. */
    fun publish(payload: PublishPayload) {
        last[payload.kind] = payload.json
        pending.save(payload.kind, payload.json)
        send(payload.kind)
    }

    /** The payload could not be built (the website would refuse it), so nothing is sent. Shown beside the results. */
    fun reject(kind: MeterKind, reason: String) {
        jobs[kind]?.cancel()
        last.remove(kind)
        pending.save(kind, null)
        setStatus(kind, PublishStatus.Failed(reason))
    }

    /** Resends exactly what was last built for [kind]. It never re-runs the X lookup. */
    fun retry(kind: MeterKind) {
        if (last[kind] == null) return
        send(kind)
    }

    private fun send(kind: MeterKind) {
        val json = last[kind] ?: return
        jobs[kind]?.cancel()
        setStatus(kind, PublishStatus.Sending)
        jobs[kind] = scope.launch {
            val key = vault.get()?.trim()
            if (key.isNullOrEmpty()) {
                setStatus(kind, PublishStatus.Failed("No publish key yet. Add WEBSITE_PUBLISH_KEY to local.properties, rebuild, then tap Retry."))
                return@launch
            }
            try {
                val reply = http.post(json, key)
                if (last[kind] != json) return@launch // a newer payload took over while this one was on its way
                if (reply.code in 200..299) {
                    pending.save(kind, null)
                    setStatus(kind, PublishStatus.Done(nowMs()))
                } else {
                    setStatus(kind, PublishStatus.Failed(explain(reply, key)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(kind, PublishStatus.Failed("Could not reach the website."))
            }
        }
    }

    private fun setStatus(kind: MeterKind, s: PublishStatus) = _status.update { it + (kind to s) }

    /** A short reason. The site's own message is shown only with the key removed from it. */
    private fun explain(r: WebsiteReply, key: String): String {
        val base = when (val c = r.code) {
            401, 403 -> "The website rejected the key (HTTP $c)."
            400, 422 -> "The website refused the data (HTTP $c)."
            429 -> "The website says too many requests (HTTP 429)."
            in 300..399 -> "The website redirected the request (HTTP $c); check the address."
            in 500..599 -> "The website had a problem (HTTP $c)."
            null -> "Could not reach the website."
            else -> "The website answered HTTP $c."
        }
        val d = r.detail.replace(key, "***").trim().take(200)
        return if (d.isEmpty()) base else "$base $d"
    }
}
