package com.clipcut.domain.api

/** Several takes on one caption, and who wrote them ("xAI · grok-4.3"). */
data class CaptionVariants(val variants: List<String>, val label: String)

/**
 * Sends a draft caption to an LLM to enhance or rewrite it. Never posts anything: results only replace text in the
 * caption box (with an undo step), and the user reviews them before posting.
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

    /**
     * [count] different rewrites of [text] in [mood] to choose from. [trending] is optional context about what is
     * trending now; it is used only where it fits the clip, never to invent facts. [highQuality] uses the higher model.
     */
    suspend fun variants(text: String, mood: CaptionMood, count: Int, trending: String, highQuality: Boolean): Result<CaptionVariants>

    /** Who answered the last successful call ("xAI · grok-4.3"), for the status line. */
    val lastUsedLabel: String?
}
