package com.clipcut.domain.api

/**
 * A caption mood: how the enhancer should EXPRESS the caption (never what happened). [emoji] and
 * [label] are what the picker shows; the rest feeds the prompt (see `CaptionPrompts.buildPrompt`).
 */
enum class CaptionMood(
    val emoji: String,
    val label: String,
    val tone: String,
    val intensity: Int,
    val structure: String,
    val emojiLevel: Int,
    val instructions: String,
) {
    DIALOGUE(
        "🎬", "Dialogue",
        tone = "dramatic, cinematic, memorable", intensity = 8,
        structure = "short, quote-driven, dramatic", emojiLevel = 2,
        instructions = """
Make the caption feel like a memorable dialogue or cinematic moment.
Emphasize the strongest line or statement already present in the input.
Use dramatic pauses and punchy sentence structure.
Do not fabricate dialogue.
""",
    ),
    FIGHT(
        "⚔️", "Fight",
        tone = "heated, confrontational, intense", intensity = 9,
        structure = "short, aggressive, punchy", emojiLevel = 3,
        instructions = """
Make the caption feel intense and confrontational.
Highlight the clash, disagreement, tension, or verbal battle already present.
Use sharp wording and strong rhythm.
Do not exaggerate the actual events beyond the user's input.
""",
    ),
    SINGING(
        "🎤", "Singing",
        tone = "musical, playful, energetic", intensity = 7,
        structure = "rhythmic, light, performance-focused", emojiLevel = 3,
        instructions = """
Make the caption feel musical and entertaining.
Highlight the singing, performance, voice, or musical moment.
Use playful language and natural musical energy.
Avoid generic praise unless supported by the original caption.
""",
    ),
    FUN(
        "😂", "Fun",
        tone = "funny, witty, playful", intensity = 7,
        structure = "conversational, punchy", emojiLevel = 2,
        instructions = """
Make the caption playful and entertaining.
Use wit, light humor, irony, or a clever observation when appropriate.
Keep the humor natural rather than forcing a joke.
Do not turn serious content into a joke unless the original supports it.
""",
    ),
    BASHING(
        "🔥", "Bashing",
        tone = "sharp, critical, savage", intensity = 9,
        structure = "short, cutting, direct", emojiLevel = 2,
        instructions = """
Make the criticism sharper and more impactful.
Highlight the specific behavior or point being criticized.
Use cutting but natural language.
Do not invent accusations, misconduct, or facts.
Keep criticism focused on the content or behavior described by the user.
""",
    ),
    EXPOSE(
        "👀", "Expose",
        tone = "revealing, suspenseful, investigative", intensity = 8,
        structure = "hook-first, suspenseful", emojiLevel = 2,
        instructions = """
Create a revealing and attention-grabbing tone.
Make the strongest existing detail feel significant.
Use suspense and curiosity without inventing hidden information.
Never imply an undisclosed fact unless it exists in the input.
""",
    ),
    EMOTIONAL(
        "❤️", "Emotional",
        tone = "heartfelt, sincere, touching", intensity = 8,
        structure = "smooth, emotional, reflective", emojiLevel = 2,
        instructions = """
Bring out the emotional meaning already present.
Make the caption heartfelt and relatable.
Use sincere language rather than melodrama.
Avoid clichés whenever possible.
""",
    ),
    APPRECIATION(
        "👑", "Appreciation",
        tone = "admiring, celebratory, warm", intensity = 7,
        structure = "positive, punchy", emojiLevel = 3,
        instructions = """
Make the caption appreciative and celebratory.
Highlight the quality, effort, personality, or moment already described.
Make the praise feel specific rather than generic.
""",
    ),
    SAVAGE(
        "💀", "Savage",
        tone = "witty, ruthless, cutting", intensity = 10,
        structure = "one-liner friendly, punchy", emojiLevel = 2,
        instructions = """
Make the caption sharper, wittier, and more cutting.
Use clever phrasing, irony, contrast, or a strong punchline.
Keep it grounded in the original input.
Do not invent insults or factual accusations.
""",
    ),
    SHOCK(
        "😱", "Shock",
        tone = "surprised, dramatic, reaction-heavy", intensity = 9,
        structure = "hook-first, reaction-driven", emojiLevel = 3,
        instructions = """
Maximize the feeling of surprise or disbelief already present.
Lead with the strongest reaction.
Use short sentences and dramatic punctuation when appropriate.
Do not manufacture shock by inventing information.
""",
    ),
    CUTE(
        "🥹", "Cute",
        tone = "sweet, wholesome, charming", intensity = 6,
        structure = "short, warm, playful", emojiLevel = 3,
        instructions = """
Make the caption sweet, wholesome, and charming.
Highlight adorable, innocent, funny, or affectionate aspects already present.
Keep it natural rather than overly childish.
""",
    ),
    VICTORY(
        "🏆", "Victory",
        tone = "triumphant, celebratory, powerful", intensity = 9,
        structure = "bold, energetic", emojiLevel = 3,
        instructions = """
Make the caption feel triumphant and celebratory.
Emphasize achievement, winning energy, dominance, comeback, or success when present.
Use strong, confident wording.
Do not claim victory if the original caption doesn't establish it.
""",
    ),
    FRUSTRATION(
        "😤", "Frustration",
        tone = "annoyed, expressive, relatable", intensity = 8,
        structure = "conversational, reaction-heavy", emojiLevel = 2,
        instructions = """
Make the frustration feel authentic and relatable.
Use expressive language and natural X-style reactions.
Do not unnecessarily escalate the situation beyond the original caption.
""",
    ),
    CONFESSION(
        "🗣️", "Confession",
        tone = "candid, personal, honest", intensity = 7,
        structure = "personal, conversational", emojiLevel = 1,
        instructions = """
Make the caption feel candid and personal.
Create the feeling of someone openly expressing a thought or admission.
Keep it conversational and emotionally authentic.
Do not invent personal information.
""",
    ),
    EPISODE(
        "📺", "Episode",
        tone = "reality-show commentary, energetic, observational", intensity = 8,
        structure = "headline + commentary", emojiLevel = 2,
        instructions = """
Make the caption feel like sharp reality-show episode commentary.
Focus on the moment, development, reaction, or game dynamics already present.
Use concise commentary language suitable for X.
The result should feel like it belongs to a live reality-show conversation.
""",
    ),

    /** Not a mood of its own: the model picks whichever of the 15 fits the caption best. */
    AUTO(
        "✨", "Auto",
        tone = "chosen automatically", intensity = 0,
        structure = "chosen automatically", emojiLevel = 0,
        instructions = "",
    );

    companion object {
        /** What the picker starts on. */
        val Default = DIALOGUE
    }
}
