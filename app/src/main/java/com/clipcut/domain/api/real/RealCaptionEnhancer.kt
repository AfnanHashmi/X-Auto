package com.clipcut.domain.api.real

import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.CaptionMood
import com.clipcut.domain.api.CaptionVariants
import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.llm.LlmTask
import javax.inject.Inject

/**
 * Rewrites captions through [LlmClient]: xAI (Grok) by default, OpenAI as the fallback. Never posts anything: the result
 * only replaces the text in the caption box (with an undo step), and the user still reviews it before posting.
 */
class RealCaptionEnhancer @Inject constructor(
    private val llm: LlmClient,
) : CaptionEnhancer {
    @Volatile
    override var lastUsedLabel: String? = null
        private set

    override suspend fun enhance(text: String, mood: CaptionMood): Result<String> = runCatching {
        llm.respond(LlmTask.CAPTION, system = buildPrompt(mood), user = text).also { lastUsedLabel = it.label }.text
    }

    override suspend fun enhanceTemplate(
        heading: String,
        caption: String,
        mood: CaptionMood,
        fixedLabel: String,
    ): Result<Pair<String, String>> = runCatching {
        val wantHeading = heading.isNotBlank()
        val wantCaption = caption.isNotBlank()
        require(wantHeading || wantCaption) { "Nothing to enhance" }

        // Only the heading and caption text is sent; the fixed label never reaches the model.
        val user = buildString {
            if (wantHeading) append("HEADING: ").append(heading.trim()).append('\n')
            if (wantCaption) append("CAPTION: ").append(caption.trim())
        }.trim()
        val reply = llm.respond(LlmTask.CAPTION, system = buildPrompt(mood, templateMode = true), user = user)
        lastUsedLabel = reply.label
        val parsed = parseTemplateReply(reply.text, wantHeading, wantCaption, fixedLabel)
        (parsed.heading ?: heading) to (parsed.caption ?: caption)
    }

    override suspend fun variants(text: String, mood: CaptionMood, count: Int, trending: String, highQuality: Boolean): Result<CaptionVariants> = runCatching {
        require(text.isNotBlank()) { "Nothing to rewrite" }
        val n = count.coerceIn(MIN_VARIANTS, MAX_VARIANTS)
        val reply = llm.respond(
            LlmTask.CAPTION,
            system = buildVariantsPrompt(mood, n),
            user = variantsUserText(text, trending),
            schema = variantsSchema(n),
            highQuality = highQuality,
        )
        lastUsedLabel = reply.label
        CaptionVariants(parseVariants(reply.text, n), reply.label)
    }
}
