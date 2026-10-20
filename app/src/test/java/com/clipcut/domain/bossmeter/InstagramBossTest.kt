package com.clipcut.domain.bossmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val REPLY = """{"source":"instagram","shortcode":"Deq0sOhqJnu","untilTs":1792484705,"excludeHidden":false,"empty":false,
"total":54450,"tracked":34856,"uncounted":19594,"uncountedPct":36.0,"trackedStrict":34854,"doubleCounted":1,
"items":[{"label":"#ArishfaIsTheBoss","count":20910,"share":60.0,"strictCount":20909,"strictShare":60.0,"lookalike":{"tag":"#arishfalstheboss","count":4}},
{"label":"#GulluIsTheBoss","count":7434,"share":21.3,"strictCount":7434,"strictShare":21.3,"lookalike":null},
{"label":"#AasifIsTheBoss","count":3340,"share":9.6,"strictCount":3340,"strictShare":9.6,"lookalike":null},
{"label":"#UditiIsTheBoss","count":2994,"share":8.6,"strictCount":2994,"strictShare":8.6,"lookalike":null},
{"label":"#LoveIsTheBoss","count":178,"share":0.5,"strictCount":178,"strictShare":0.5,"lookalike":null}],
"lead":{"first":"#ArishfaIsTheBoss","second":"#GulluIsTheBoss","by":13476},
"topUntracked":[{"tag":"#biggboss20","count":900}],"breakdown":{"A":1,"B":2,"C":3,"D":4,"E":5,"F":6},
"checks":{"countsWithinTotal":true,"trackedPlusUncountedIsTotal":true,"sharesSumTo100":true},
"freshness":{"lastCommentTs":1792484700,"lastCommentAtIst":"2026-10-20T13:55:00+05:30","fetched":54450,"reported":57653}}"""

private fun x(vararg t: Pair<String, Long>, total: Long = 1000) = BossMeterResult("1", total, t.map { TagCount(it.first, it.second) }, 5_000L)

class InstagramBossTest {
    @Test
    fun theServersReplyIsReadWithEveryPart() {
        val r = parseIgBoss(REPLY, 9L)
        assertFalse(r.empty)
        assertEquals(54450L, r.total); assertEquals(34856L, r.tracked); assertEquals(19594L, r.uncounted)
        assertEquals("#ArishfaIsTheBoss", r.items[0].label); assertEquals(20910L, r.items[0].count)
        assertEquals("#arishfalstheboss", r.items[0].lookalikeTag); assertEquals(4L, r.items[0].lookalikeCount)
        assertNull(r.items[1].lookalikeTag)
        assertEquals("#ArishfaIsTheBoss leads #GulluIsTheBoss by 13,476", r.leadText)
        assertEquals(listOf("#biggboss20" to 900L), r.topUntracked)
        assertEquals(6L, r.breakdown["F"]); assertTrue(r.checksOk)
        assertEquals(57653L, r.reported); assertEquals(1792484705L, r.untilTs); assertEquals(9L, r.fetchedAtMs)
        assertEquals(REPLY, r.raw)
    }

    @Test
    fun anEmptyWindowIsEmptyNotZeros() {
        val r = parseIgBoss("""{"source":"instagram","shortcode":"S","untilTs":null,"excludeHidden":false,"empty":true}""", 1L)
        assertTrue(r.empty); assertTrue(r.items.isEmpty()); assertNull(r.untilTs)
    }

    @Test
    fun badRepliesAreRefused() {
        assertThrows(IgException::class.java) { parseIgBoss("<html>", 1L) }
        assertThrows(IgException::class.java) { parseIgBoss("""{"empty":false,"total":1}""", 1L) }
    }

    @Test
    fun shortcodesComeFromLinksOrBareCodes() {
        assertEquals("Deq0sOhqJnu", shortcodeFrom("https://www.instagram.com/p/Deq0sOhqJnu/?igsh=abc"))
        assertEquals("Deq0sOhqJnu", shortcodeFrom("https://instagram.com/reel/Deq0sOhqJnu/"))
        assertEquals("Deq0sOhqJnu", shortcodeFrom("https://www.instagram.com/someone/p/Deq0sOhqJnu/"))
        assertEquals("Deq0sOhqJnu", shortcodeFrom("Deq0sOhqJnu"))
        assertNull(shortcodeFrom("https://x.com/a/status/2099369474948563035"))
        assertNull(shortcodeFrom(""))
    }

    @Test
    fun theWatcherStatusKnowsAnExpiredSession() {
        assertTrue(IgWatch("S", "session expired", null, 5).sessionExpired)
        assertTrue(IgWatch("S", "blocked: HTTP 429 after retries", null, 5).blocked)
        assertFalse(IgWatch("S", "watching", null, 5).sessionExpired)
    }

    @Test
    fun overallIsXPlusInstagramPerHashtagAndRecomputedFromTheSums() {
        val ig = parseIgBoss(REPLY, 1L)
        val o = overall(x("#arishfaistheboss" to 100L, "#GulluIsTheBoss" to 300L, "#AasifIsTheBoss" to 50L, total = 1000), ig)!!
        val by = o.rows.associateBy { it.label.lowercase().trimStart('#') }
        // case-insensitive matching: X's "#arishfaistheboss" and Instagram's "#ArishfaIsTheBoss" are one hashtag
        assertEquals(20_910L + 100L, by["arishfaistheboss"]!!.total)
        assertEquals(100L, by["arishfaistheboss"]!!.x); assertEquals(20_910L, by["arishfaistheboss"]!!.ig)
        assertEquals(7_434L + 300L, by["gullu" + "istheboss"]!!.total)
        // a hashtag X did not count is 0 on X (it was counted), while a source with no data stays null
        assertEquals(0L, by["uditiistheboss"]!!.x); assertEquals(2_994L, by["uditiistheboss"]!!.ig)
        assertEquals(54_450L + 1_000L, o.total)
        assertEquals(o.rows.sumOf { it.total }, o.tracked)
        assertEquals(o.total - o.tracked, o.uncounted)
        assertEquals(100.0, o.rows.sumOf { it.share }, 1e-9)
        assertEquals(o.uncounted * 100.0 / o.total, o.uncountedPct, 1e-9)
        assertEquals(o.rows.sortedByDescending { it.total }, o.rows)
        assertTrue(o.hasX && o.hasIg)
    }

    @Test
    fun aMissingSourceIsNullNotZero() {
        val onlyX = overall(x("#A" to 10L, "#B" to 5L, total = 20), null)!!
        assertFalse(onlyX.hasIg); assertNull(onlyX.rows[0].ig); assertEquals(10L, onlyX.rows[0].total); assertEquals(20L, onlyX.total)
        val emptyIg = parseIgBoss("""{"empty":true,"shortcode":"S"}""", 1L)
        assertFalse(overall(x("#A" to 10L), emptyIg)!!.hasIg)
        val onlyIg = overall(null, parseIgBoss(REPLY, 1L))!!
        assertFalse(onlyIg.hasX); assertNull(onlyIg.rows[0].x); assertEquals(54_450L, onlyIg.total)
        assertNull(overall(null, null)); assertNull(overall(null, emptyIg))
    }
}
