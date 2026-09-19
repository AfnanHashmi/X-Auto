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
}
