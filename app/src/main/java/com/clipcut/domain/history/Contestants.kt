package com.clipcut.domain.history

import android.content.Context
import org.json.JSONObject

/** The key a tagline or hashtag is linked under: case, spaces and a leading # do not matter. */
fun contestantKey(text: String): String = text.trim().removePrefix("#").replace(Regex("\\s+"), " ").lowercase()

/** Words in campaign taglines that are never a contestant's name. */
private val NOT_NAMES = setOf(
    "we", "want", "no", "stop", "the", "a", "an", "of", "for", "in", "on", "and", "to", "is", "are", "be", "by", "with",
    "bb", "bb20", "bigg", "boss", "biggboss", "negative", "pr", "against", "justice", "support", "vote", "votes", "save",
    "love", "our", "my", "always", "back", "rising", "above", "hate", "born", "shine", "powerhouse", "stands", "her",
    "his", "people", "queen", "king", "winner", "real", "true", "fans", "army", "team", "only", "one", "all", "time",
    "trend", "trending", "proud", "deserves", "deserve", "zindabad", "jai", "hai", "ka", "ki", "ke", "the boss",
)

/** "#KanikaIsTheBoss" → Kanika, Is, The, Boss; "WE WANT NAKUL BACK" → WE, WANT, NAKUL, BACK. */
private fun words(text: String): List<String> {
    val t = text.trim().removePrefix("#")
    val spaced = if (' ' !in t) t.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ") else t
    return spaced.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
}

private fun titleCase(w: String) = w.lowercase().replaceFirstChar { it.uppercase() }

/**
 * Who a tagline or hashtag is probably for. A name already linked before wins ([known], matched as a whole word); otherwise
 * the first word that is not a common campaign word, title-cased. Null when there is no good guess. Always a suggestion:
 * the user confirms it.
 */
fun suggestContestant(text: String, known: Collection<String>): String? {
    val ws = words(text)
    known.firstOrNull { name -> ws.any { it.equals(name, ignoreCase = true) } }?.let { return it }
    return ws.firstOrNull { it.length >= 3 && it.lowercase() !in NOT_NAMES && !it.all(Char::isDigit) }?.let(::titleCase)
}

/** Which contestant each tagline and Boss Meter hashtag is for, kept on the phone. Applied when data is read, never baked in. */
class ContestantStore(context: Context) {
    private val prefs = context.getSharedPreferences("contestants", Context.MODE_PRIVATE)

    private fun all(): JSONObject = prefs.getString("map", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun contestantFor(text: String): String? = all().optString(contestantKey(text)).takeIf { it.isNotBlank() }

    /** Links [text] to [name]; a blank name removes the link. */
    fun set(text: String, name: String) {
        val m = all()
        val n = name.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty()) m.remove(contestantKey(text)) else m.put(contestantKey(text), n)
        prefs.edit().putString("map", m.toString()).apply()
    }

    /** The roster first, then any other name used so far: quick picks and suggestions. */
    fun knownNames(): List<String> =
        (ROSTER.map { it.name } + all().let { m -> m.keys().asSequence().map { m.getString(it) }.toList() }).distinct()

    /** Who [text] is for: a link the user made, else the roster, else a guess from the words. Nothing is saved. */
    fun resolve(text: String): String? = contestantFor(text) ?: rosterMatch(text)?.name ?: suggestContestant(text, knownNames())

    /** Links [text] to [resolve]'s answer if it has no link yet. Returns the name it is linked to now, if any. */
    fun linkIfNew(text: String): String? = contestantFor(text) ?: resolve(text)?.also { set(text, it) }

    /** Old links used short names ("Qazi"); where the roster knows the person, they now use the full name. */
    private fun migrate() {
        val m = all()
        var changed = false
        for (k in m.keys().asSequence().toList()) {
            val old = m.getString(k)
            val full = rosterMatch(old)?.name
            if (full != null && full != old && ROSTER.none { it.name == old }) { m.put(k, full); changed = true }
        }
        if (changed) prefs.edit().putString("map", m.toString()).apply()
    }

    init {
        migrate()
    }

    /** Every link, for reading the history by contestant. */
    fun snapshot(): Map<String, String> = all().let { m -> m.keys().asSequence().associateWith { m.getString(it) } }
}
