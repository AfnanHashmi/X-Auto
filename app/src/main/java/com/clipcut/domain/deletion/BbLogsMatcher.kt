package com.clipcut.domain.deletion

/**
 * The single gate that decides whether a post is one of ours and may be deleted.
 *
 * X returns a native-video post's text as the caption followed by the media's t.co link, so what the
 * app posted as "BB LOGS - 012" comes back as "BB LOGS - 012 https://t.co/AbC123". The accepted shape
 * is therefore exactly: start of text, "BB LOGS - ", three digits, then only whitespace and t.co links
 * to the end. Anything else -- free text after the number, a non-t.co URL, a leading word ("RT ..."),
 * different case or spacing -- is NOT ours and is never deleted. Loosening this to startsWith/contains
 * is how over-matching gets in; add cases to the test instead.
 */
private val BB_LOGS = Regex("""^BB LOGS - (\d{3})(?:\s+https://t\.co/[A-Za-z0-9]+)*\s*$""")

fun isBbLogsPost(text: String): Boolean = BB_LOGS.matches(text)

/** The NNN of a BB LOGS post, or null if [text] is not one. */
fun bbNumber(text: String): Int? = BB_LOGS.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
