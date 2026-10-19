package com.clipcut.domain.api

/**
 * How a conversation is presented. It must NOT rewrite: the user writes the lines, and the model only formats and
 * polishes them. Auto switches to it when the caption turns out to be dialogue.
 */
internal const val DIALOGUE_RULES = """
DIALOGUE MODE -- POLISH, DO NOT REWRITE.

This overrides every rewrite instruction in this prompt (sections 1, 3, 4 and 6, checks A and C of the
final check, and any rule about the caption not repeating a heading). The user gives rough dialogue.
Your job is to present it beautifully, not to write new lines.

FORMAT
One spoken line per line, in exactly this shape:
SPEAKER: "What they said"
- The quotation marks around each spoken line are required, whatever the OUTPUT section says about quotes.
- Speaker names in CAPITALS, using exactly the people the user named ("qazi" becomes QAZI).
- If the user gives no names, label the speakers CONTESTANT A, CONTESTANT B, CONTESTANT C and so on,
  in the order they first speak, and give the same person the same label every time.
- Keep the user's order. Never add, drop, merge or split lines.
- Narration that is not a spoken line (for example "then both walked out") stays on its own line,
  polished, without quotes and without a speaker.

POLISH -- ALLOWED
- Capitalization, punctuation, spacing and line breaks.
- Fixing obvious typos in English words. Hinglish words keep the user's own spelling.
- Expressive punctuation (!!, ?!, ...) where the delivery clearly calls for it.
- Emojis that carry how a line is said, placed after the closing quote. At most one per line, and not
  on every line, but a line with clear feeling (anger, laughter, shock, hurt) should get one.

NEVER
- Change, replace, reorder, add or remove any spoken word. Every word stays; only the presentation changes.
- Translate between Hindi and English.
- Invent a speaker, a reply, a reaction or a sound effect.
- Add commentary, a heading or new hashtags. Hashtags the user supplied go on one final line of their own.

If there is a HEADING, polish it the same way and keep its words.

EXAMPLES
Input:
hi there
hello brother
Output:
CONTESTANT A: "Hi there!!" 👋
CONTESTANT B: "Hello, brother."

Input:
qazi: kya bol raha hai tu
arishfa: jo sach hai wahi bol rahi hu
Output:
QAZI: "Kya bol raha hai tu?!" 😤
ARISHFA: "Jo sach hai, wahi bol rahi hu."
"""

/**
 * A caption mood: how the enhancer should EXPRESS the caption (never what happened). [emoji] and
 * [label] are what the picker shows; the rest feeds the prompt (see `CaptionPrompts.buildPrompt`).
 * [feel] is the palette of emojis and expressive characters that make a reader feel the mood.
 */
enum class CaptionMood(
    val emoji: String,
    val label: String,
    val tone: String,
    val intensity: Int,
    val structure: String,
    val emojiLevel: Int,
    val instructions: String,
    val feel: String = "",
) {
    /** The default: the model reads the user's cues and picks one of the moods below (or a blend of two). */
    AUTO(
        "✨", "Auto",
        tone = "chosen automatically", intensity = 0,
        structure = "chosen automatically", emojiLevel = 0,
        instructions = "",
    ),
    FUN(
        "😂", "Fun",
        tone = "funny, witty, playful", intensity = 7,
        structure = "conversational, punchy", emojiLevel = 3,
        instructions = """
Make the caption playful and entertaining.
Use wit, light humor, irony, or a clever observation when appropriate.
Keep the humor natural rather than forcing a joke.
Do not turn serious content into a joke unless the original supports it.
""",
        feel = """
Emojis: 😂 😭 🤣 💀 👀 🙈 (😭 and 💀 also mean "I'm dead laughing"). Put one or two right after the punchline.
Characters: ... for a deadpan beat, ?! for disbelief, a word in CAPS for the joke's stress, !! only when it is truly loud.
""",
    ),
    BASHING(
        "🔥", "Bashing",
        tone = "sharp, critical, savage", intensity = 9,
        structure = "short, cutting, direct", emojiLevel = 3,
        instructions = """
Make the criticism sharper and more impactful.
Highlight the specific behavior or point being criticized.
Use cutting but natural language.
Do not invent accusations, misconduct, or facts.
Keep criticism focused on the content or behavior described by the user.
""",
        feel = """
Emojis: 🤡 💀 🙄 🤦 😒 🗑️ 🚩 (🤡 for hypocrisy, 🙄 for fake behaviour, 🚩 for a warning sign). One to three, at the cutting line.
Characters: ... before the verdict, ?! for "are you serious", CAPS on the one word that stings, > or >> to set one thing against another.
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
When the fight lands as a shock, a short reaction line (😱 ?!) is allowed.
""",
        feel = """
Emojis: ⚔️ 😤 🔥 💥 😡 🥊 🚨 (😱 for a shocking turn). Stack two or three at the peak, or end the hook line with one.
Characters: !! for the hit, >> between the two sides, vs between names, CAPS on the loudest word, ?! for a dare.
""",
    ),
    EMOTIONAL(
        "🥹", "Emotional",
        tone = "heartfelt, sincere, sweet, wholesome", intensity = 7,
        structure = "warm, smooth, reflective", emojiLevel = 3,
        instructions = """
Bring out the feeling already in the moment. Decide which of two notes it carries, or both:
- Emotional: heartfelt, touching, sincere. Relatable rather than melodramatic.
- Cute: sweet, wholesome, charming. Affectionate or adorable, but never childish.
Avoid clichés whenever possible, and never invent sentiment the moment does not have.
""",
        feel = """
Emojis: 🥲 🥺 😭 ❤️ 🫶 🥹 💔 (💔 for hurt, 🫶 and ❤️ for love, 🥹 for "so proud"). Place them at the end of the line that carries the feeling.
Characters: ... for a pause, a single ! at most, soft line breaks. Never shout in this mood.
""",
    ),
    APPRECIATION(
        "👑", "Appreciation",
        tone = "admiring, celebratory, warm", intensity = 8,
        structure = "positive, punchy", emojiLevel = 3,
        instructions = """
Make the caption appreciative and celebratory.
Highlight the quality, effort, personality, or moment already described.
Make the praise feel specific rather than generic.
A win or a comeback gets triumphant energy (but only if the caption establishes it).
""",
        feel = """
Emojis: 👑 🔥 🙌 ✨ 💯 🏆 👏 (🏆 for a win, 👑 for a boss move, 🔥🔥 for hype). Two or three at the peak or after the praise.
Characters: !! for excitement, CAPS on the name or the word of praise, *word* for emphasis, >> for "and that is why".
""",
    );

    companion object {
        /** What the picker starts on. */
        val Default = AUTO
    }
}
