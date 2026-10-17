package com.clipcut.domain.llm

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** xAI's X search for one call: posts from [fromDate] up to [toDate] (YYYY-MM-DD), at most [maxTurns] search rounds. */
data class XSearch(val fromDate: String, val toDate: String, val maxTurns: Int = 2)

/** One POST with a bearer key. Returns the status code and body; throws only when no reply came back. */
interface LlmHttp {
    suspend fun post(url: String, key: String, json: String): Pair<Int, String>
}

/**
 * The one way the app talks to an LLM. Tries the primary provider (`LLM_PROVIDER`, xAI by default) and, if that call
 * fails for any reason, the other provider when it has a key. Each attempt is logged with provider, model, task, result
 * and time taken; keys and the text sent are never logged.
 */
class LlmClient(
    private val config: LlmConfig,
    private val http: LlmHttp,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    val isConfigured: Boolean get() = config.order.isNotEmpty()

    /** Who would answer a [task] now, for cost boxes and labels: "xAI · grok-4.3". */
    fun plannedLabel(task: LlmTask, highQuality: Boolean = false): String? =
        config.order.firstOrNull()?.let { "${it.label} · ${config.models.getValue(it).forTask(task, highQuality)}" }

    /** Whether "higher quality" would use a different model than the normal one (for OpenAI both are the same by default). */
    fun hasHigherTier(task: LlmTask): Boolean = config.order.firstOrNull()?.let { p ->
        config.models.getValue(p).let { it.forTask(task, true) != it.forTask(task, false) }
    } ?: false

    /** A rough cost for one call of about [inTokens] in and [outTokens] out (reasoning included), or null for an unpriced model. */
    fun estimateUsd(task: LlmTask, highQuality: Boolean, inTokens: Long, outTokens: Long): Double? =
        config.order.firstOrNull()?.let { p -> MODEL_PRICES[config.models.getValue(p).forTask(task, highQuality)] }
            ?.let { (i, o) -> (inTokens * i + outTokens * o) / 1_000_000.0 }

    /**
     * Sends [system] and [user] for a [task]. With [schema], asks for JSON matching it (structured output) and returns
     * that JSON as text. [highQuality] uses the provider's higher model (grok-4.7 by default).
     * [xSearch] adds xAI's X search tool (`x_search`, limited to [xSearch]'s dates and [maxTurns] tool rounds). Only xAI
     * has it, so such a call never falls back to OpenAI.
     */
    suspend fun respond(
        task: LlmTask,
        system: String,
        user: String,
        schema: JSONObject? = null,
        highQuality: Boolean = false,
        xSearch: XSearch? = null,
    ): LlmReply {
        val order = if (xSearch != null) config.order.filter { it == LlmProvider.XAI } else config.order
        if (order.isEmpty()) {
            throw LlmException(if (xSearch != null) "X search needs XAI_API_KEY in local.properties." else "No LLM key is set. Add XAI_API_KEY (or OPENAI_API_KEY) to local.properties.")
        }
        var firstFailure: Pair<LlmProvider, Exception>? = null
        for (provider in order) {
            val model = config.models.getValue(provider).forTask(task, highQuality)
            val started = nowMs()
            try {
                val reply = call(provider, model, system, user, schema, xSearch)
                val tokens = reply.usage?.let { " in=${it.inputTokens} out=${it.outputTokens}" }.orEmpty() + (reply.costUsd?.let { " cost=$" + "%.5f".format(it) } ?: "")
                log("task=${task.wire} provider=${provider.wire} model=$model ok ${nowMs() - started}ms$tokens" + (firstFailure?.let { " (fallback from ${it.first.wire})" } ?: ""))
                return reply.copy(fellBackFrom = firstFailure?.first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("task=${task.wire} provider=${provider.wire} model=$model failed ${nowMs() - started}ms: ${safe(e)}")
                if (firstFailure == null) firstFailure = provider to e
            }
        }
        val (p, e) = firstFailure!!
        throw LlmException(
            "${p.label} failed: ${safe(e)}" + if (order.size > 1) " The fallback failed too." else "",
            (e as? LlmException)?.httpCode,
        )
    }

    private suspend fun call(provider: LlmProvider, model: String, system: String, user: String, schema: JSONObject?, xSearch: XSearch?): LlmReply {
        val key = config.key(provider) ?: throw LlmException("No key for ${provider.label}")
        val body = JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("store", false)
        if (xSearch != null) {
            body.put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "x_search").put("from_date", xSearch.fromDate).put("to_date", xSearch.toDate)
                        .put("enable_image_understanding", false).put("enable_video_understanding", false),
                ),
            ).put("max_turns", xSearch.maxTurns)
        }
        if (schema != null) {
            body.put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", "result").put("schema", schema).put("strict", true)))
        }
        val (code, text) = http.post("${provider.baseUrl}/responses", key, body.toString())
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") ?: JSONObject(text).optString("error") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: text.take(160)
            throw LlmException("HTTP $code: ${detail.replace(key, "***")}", code)
        }
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw LlmException("The reply was not JSON")
        }
        return LlmReply(extractResponseText(json), provider, model, extractUsage(json))
    }

    /** An error message fit for the screen and the log: one line, no key. */
    private fun safe(e: Exception): String {
        var m = (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(200)
        config.keys.values.filter { it.isNotBlank() }.forEach { m = m.replace(it.trim(), "***") }
        return m
    }
}
