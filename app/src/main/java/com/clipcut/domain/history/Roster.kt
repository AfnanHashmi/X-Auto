package com.clipcut.domain.history

/** One contestant: the name shown everywhere, their official hashtag, and other spellings fans use. */
data class Contestant(val name: String, val hashtag: String, val aliases: List<String> = emptyList())

/** Bigg Boss 20 contestants with their hashtags, as given by the user on 2026-10-18. */
val ROSTER = listOf(
    Contestant("Kanika Mann", "#KanikaMann"),
    Contestant("Aasif Khan", "#AasifKhan", listOf("Asif")),
    Contestant("Qazi Touqeer", "#QaziTouqeer", listOf("Qazi", "Kazi")),
    Contestant("Amrapali Dubey", "#AmrapaliDubey", listOf("Aamrapali", "Amrapali")),
    Contestant("Love Gill", "#LoveGill"),
    Contestant("Yung DSA", "#YungDSA", listOf("YungDSA")),
    Contestant("Mary Kom", "#MaryKom"),
    Contestant("ScoutOP", "#ScoutOP", listOf("Scout")),
    Contestant("Arishfa Khan", "#ArishfaKhan", listOf("Arishfa")),
    Contestant("Kushal Tanwar (Gullu)", "#Gullu", listOf("Gullu", "Kushal")),
    Contestant("Aman Gandhi", "#AmanGandhi"),
    Contestant("Uditi Singh", "#UditiSingh", listOf("Uditi")),
    Contestant("Mahhi Vij", "#MahhiVij", listOf("Mahhi", "Mahi")),
    Contestant("Rohed Khan", "#RohedKhan", listOf("Rohed")),
    Contestant("Isha Rikhi", "#IshaRikhi"),
    Contestant("Rhiti Tiwari", "#RhitiTiwari", listOf("Rhiti")),
    Contestant("Manish Goel", "#ManishGoel"),
    Contestant("Nakul Dhull", "#NakulDhull", listOf("Nakul")),
)

/** Lowercase letters and digits only, so "#Kanika_Mann", "kanika mann" and "KanikaMann" are the same. */
private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** Common words that are also a contestant's first name: only matched as part of the full name or hashtag. */
private val PLAIN_WORDS = setOf("love", "mary", "aman", "scout", "isha")

private data class Key(val contestant: Contestant, val single: Set<String>, val squashed: Set<String>)

private val KEYS: List<Key> = ROSTER.map { c ->
    val words = c.name.substringBefore(" (").split(" ").map { it.lowercase() }
    val firstNames = words.take(1) + c.aliases.filter { ' ' !in it }.map { it.lowercase() }
    val squashedForms = (listOf(c.name.substringBefore(" ("), c.hashtag.removePrefix("#")) + c.aliases.filter { ' ' in it }).map(::squash)
    Key(c, firstNames.filter { it.length >= 4 && it !in PLAIN_WORDS }.toSet(), squashedForms.toSet())
}

/** The words of a tagline or hashtag: camel case is split ("KanikaIsTheBoss" → Kanika, Is, The, Boss). */
private fun wordsOf(text: String): List<String> {
    val t = text.trim().removePrefix("#")
    val spaced = if (' ' !in t) t.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ") else t
    return spaced.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
}

/**
 * Which roster contestant a tagline or hashtag is for, or null. The official hashtag or full name anywhere in it
 * ("#QaziTouqeerForever", "JUSTICE FOR QAZI TOUQEER") wins; then a first name or fan spelling as a whole word
 * ("WE WANT NAKUL BACK", "#KanikaIsTheBoss"), earliest word first. Surnames are not used on their own (three
 * contestants are Khan), and plain-word first names (Love, Mary, Aman, Scout, Isha) only match as the full name.
 */
fun rosterMatch(text: String): Contestant? {
    val whole = squash(text)
    KEYS.firstOrNull { k -> k.squashed.any { it.length >= 6 && it in whole } }?.let { return it.contestant }
    for (w in wordsOf(text).map { it.lowercase() }) {
        KEYS.firstOrNull { w in it.single }?.let { return it.contestant }
    }
    return null
}
