package com.clipcut.domain.llm

import org.json.JSONObject

/**
 * The LLM providers. Both speak the same OpenAI-style Responses API (`POST {baseUrl}/responses`), so one request shape
 * and one parser serve both; only the address, the key and the model differ. X itself (posting, counting, reading)
 * never goes through here: that stays on the official X API.
 */
enum class LlmProvider(val wire: String, val label: String, val baseUrl: String) {
    XAI("xai", "xAI", "https://api.x.ai/v1"),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1"),
    ;

    companion object {
        /** `LLM_PROVIDER` from local.properties; anything unknown or blank means xAI. */
        fun from(value: String): LlmProvider = entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) } ?: XAI
    }
}

/** What a call is for. Each has its own model per provider, so analysis and captions can be tuned apart. */
enum class LlmTask(val wire: String) { ANALYSIS("analysis"), CAPTION("caption") }

/** The models one provider uses: one per task, plus [high] for when a better answer is worth the extra cost. */
data class ProviderModels(val analysis: String, val caption: String, val high: String) {
    fun forTask(task: LlmTask, highQuality: Boolean): String = when {
        highQuality -> high
        task == LlmTask.ANALYSIS -> analysis
        else -> caption
    }
}

/**
 * Everything the client needs, read once from local.properties (via BuildConfig). [primary] is tried first; the other
 * provider is the fallback, used only when it has a key.
 */
data class LlmConfig(
    val primary: LlmProvider,
    val keys: Map<LlmProvider, String>,
    val models: Map<LlmProvider, ProviderModels>,
) {
    fun key(p: LlmProvider): String? = keys[p]?.trim()?.takeIf { it.isNotEmpty() }

    /** The providers to try, in order: the primary, then the other, each only if it has a key. */
    val order: List<LlmProvider> get() = (listOf(primary) + LlmProvider.entries.filter { it != primary }).filter { key(it) != null }

    companion object {
        const val DEFAULT_XAI_MODEL = "grok-4.3"
        const val DEFAULT_XAI_HIGH = "grok-4.7"
        const val DEFAULT_OPENAI_MODEL = "gpt-5.6-terra"

        fun of(
            provider: String,
            xaiKey: String,
            openAiKey: String,
            xaiAnalysis: String = "",
            xaiCaption: String = "",
            xaiHigh: String = "",
            openAiAnalysis: String = "",
            openAiCaption: String = "",
            openAiHigh: String = "",
        ): LlmConfig {
            fun or(v: String, d: String) = v.trim().ifEmpty { d }
            return LlmConfig(
                primary = LlmProvider.from(provider),
                keys = mapOf(LlmProvider.XAI to xaiKey, LlmProvider.OPENAI to openAiKey),
                models = mapOf(
                    LlmProvider.XAI to ProviderModels(or(xaiAnalysis, DEFAULT_XAI_MODEL), or(xaiCaption, DEFAULT_XAI_MODEL), or(xaiHigh, DEFAULT_XAI_HIGH)),
                    LlmProvider.OPENAI to ProviderModels(or(openAiAnalysis, DEFAULT_OPENAI_MODEL), or(openAiCaption, DEFAULT_OPENAI_MODEL), or(openAiHigh, DEFAULT_OPENAI_MODEL)),
                ),
            )
        }
    }
}

/** [reportedCostUsd] is the provider's own figure when it sends one (xAI does, as `cost_in_usd_ticks`, 10^10 ticks per dollar). */
data class LlmUsage(val inputTokens: Long, val outputTokens: Long, val reportedCostUsd: Double? = null)

/** One answer: the text, and who produced it. [fellBackFrom] is set when the primary failed and the other provider answered. */
data class LlmReply(
    val text: String,
    val provider: LlmProvider,
    val model: String,
    val usage: LlmUsage?,
    val fellBackFrom: LlmProvider? = null,
) {
    /** For the screen: "xAI · grok-4.3", with the fallback noted. */
    val label: String get() = "${provider.label} · $model" + (fellBackFrom?.let { " (${it.label} failed)" } ?: "")

    /** Dollars: the provider's own figure when it reports one, otherwise from the published per-token prices (null if unknown). */
    val costUsd: Double?
        get() = usage?.let { u -> u.reportedCostUsd ?: MODEL_PRICES[model]?.let { (inp, out) -> (u.inputTokens * inp + u.outputTokens * out) / 1_000_000.0 } }
}

/** Published prices per million tokens (input, output), checked on 2026-10-18. Unknown models show no cost. */
val MODEL_PRICES: Map<String, Pair<Double, Double>> = mapOf(
    "grok-4.3" to (1.25 to 2.50),
    "grok-4.7" to (2.00 to 6.00),
    "grok-build-0.1" to (1.00 to 2.00),
    // OpenAI does not report a cost, so its calls are priced from this list (developers.openai.com, 2026-10-18).
    "gpt-5.6-terra" to (2.00 to 12.00),
)

class LlmException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * The text of a Responses API reply. It lives in `output[]` items of type `message`, in `content[]` parts of type
 * `output_text`; reasoning items are skipped. An error, a refusal, an incomplete or an empty reply is an exception,
 * never an empty string: replacing the user's text with nothing would be worse than failing.
 */
fun extractResponseText(json: JSONObject): String {
    json.optJSONObject("error")?.let { throw LlmException("The model returned an error: ${it.optString("message", it.toString())}") }
    if (json.optString("status") == "incomplete") throw LlmException("The reply was cut off: ${json.optJSONObject("incomplete_details")}")
    val output = json.optJSONArray("output") ?: throw LlmException("The reply had no output")
    val text = StringBuilder()
    for (i in 0 until output.length()) {
        val item = output.getJSONObject(i)
        if (item.optString("type") != "message") continue
        val content = item.optJSONArray("content") ?: continue
        for (j in 0 until content.length()) {
            val part = content.getJSONObject(j)
            when (part.optString("type")) {
                "output_text" -> text.append(part.optString("text"))
                "refusal" -> throw LlmException("The model declined: ${part.optString("refusal")}")
            }
        }
    }
    return text.toString().trim().ifEmpty { throw LlmException("The model returned no text") }
}

fun extractUsage(json: JSONObject): LlmUsage? = json.optJSONObject("usage")?.let {
    LlmUsage(
        it.optLong("input_tokens"),
        it.optLong("output_tokens"),
        if (it.has("cost_in_usd_ticks")) it.optLong("cost_in_usd_ticks") / 1e10 else null,
    )
}
