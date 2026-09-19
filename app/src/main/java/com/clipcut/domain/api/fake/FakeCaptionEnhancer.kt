package com.clipcut.domain.api.fake

import com.clipcut.domain.api.CaptionEnhancer
import com.clipcut.domain.api.CaptionMood
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake used until a real LLM API key is wired in. Appends a marker so the round-trip is visible. */
class FakeCaptionEnhancer @Inject constructor() : CaptionEnhancer {
    override suspend fun enhance(text: String, mood: CaptionMood): Result<String> {
        delay(600)
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Nothing to enhance"))
        return Result.success("$text ✨ [${mood.label}]")
    }

    override suspend fun enhanceTemplate(
        heading: String,
        caption: String,
        mood: CaptionMood,
        fixedLabel: String,
    ): Result<Pair<String, String>> {
        delay(600)
        if (heading.isBlank() && caption.isBlank()) return Result.failure(IllegalArgumentException("Nothing to enhance"))
        return Result.success(
            (if (heading.isBlank()) heading else "$heading ✨") to
                (if (caption.isBlank()) caption else "$caption ✨ [${mood.label}]"),
        )
    }
}
