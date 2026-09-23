package com.clipcut.domain.api

/**
 * Sends a draft caption to an LLM to enhance/rewrite it.
 *
 * STUB: implement against the real LLM API (see the `claude-api` reference for model IDs,
 * request/response shape, and API-key setup once you're ready to wire this up). Swap the
 * binding in `di/ApiModule.kt`; no caller changes.
 */
interface CaptionEnhancer {
    /** Rewrites [text] in the given [mood]; the mood changes how it reads, never what it says. */
    suspend fun enhance(text: String, mood: CaptionMood): Result<String>

    /**
     * Enhances ONLY the [heading] and [caption] of a post, returning the pair. The layout's
     * [fixedLabel] (blank when the post has none) is never sent as text to rewrite and is never
     * returned. A blank part is left untouched (returned as given).
     */
    suspend fun enhanceTemplate(heading: String, caption: String, mood: CaptionMood, fixedLabel: String): Result<Pair<String, String>>
}
