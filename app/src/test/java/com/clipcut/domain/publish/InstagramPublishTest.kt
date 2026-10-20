package com.clipcut.domain.publish

import com.clipcut.domain.bossmeter.BossMeterResult
import com.clipcut.domain.bossmeter.TagCount
import com.clipcut.domain.bossmeter.overall
import com.clipcut.domain.bossmeter.parseIgBoss
import com.clipcut.domain.history.ContestantWeek
import com.clipcut.domain.history.RunHistory
import com.clipcut.domain.history.mentionKeys
import com.clipcut.domain.history.relevance
import com.clipcut.domain.history.weekStats
import com.clipcut.domain.share.instagramShare
import com.clipcut.domain.share.overallShare
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val IG = """{"source":"instagram","shortcode":"Deq0sOhqJnu","untilTs":null,"excludeHidden":false,"empty":false,
"total":1000,"tracked":600,"uncounted":400,"uncountedPct":40.0,"trackedStrict":600,"doubleCounted":0,
"items":[{"label":"#ArishfaIsTheBoss","count":400,"share":66.7,"strictCount":400,"strictShare":66.7,"lookalike":null},
{"label":"#GulluIsTheBoss","count":200,"share":33.3,"strictCount":200,"strictShare":33.3,"lookalike":null}],
"lead":{"first":"#ArishfaIsTheBoss","second":"#GulluIsTheBoss","by":200},"topUntracked":[],"breakdown":{},"checks":{},
"freshness":{"lastCommentTs":1,"lastCommentAtIst":"2026-10-20T13:55:00+05:30","fetched":1000,"reported":1100}}"""

private val AT = 1_792_500_000_000L
private fun x() = BossMeterResult("2110272039504511091", 500, listOf(TagCount("#ArishfaIsTheBoss", 100), TagCount("#GulluIsTheBoss", 250)), AT)
private const val X_LINK = "https://x.com/BiggBoss/status/2110272039504511091"

class InstagramPublishTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theInstagramViewIsKindBossWithSourceInstagramAndTheXRunsTime() {
        val p = instagramPayload(parseIgBoss(IG, 1L), AT, "Deq0sOhqJnu", BossStage.CONTESTANTS)
        assertEquals(MeterKind.BOSS_IG, p.kind)
        val o = JSONObject(p.json)
        assertEquals("boss", o.getString("kind")); assertEquals("instagram", o.getString("source"))
        assertEquals("https://www.instagram.com/p/Deq0sOhqJnu/", o.getString("postUrl"))
        assertEquals(java.time.Instant.ofEpochMilli(AT).toString(), o.getString("refreshedAt"))
        assertEquals(1000, o.getInt("total")); assertEquals(600, o.getInt("tracked")); assertEquals(400, o.getInt("uncounted"))
        assertEquals("contestants", o.getString("stage"))
        val items = o.getJSONArray("items")
        assertEquals("#ArishfaIsTheBoss", items.getJSONObject(0).getString("label")); assertEquals(400, items.getJSONObject(0).getInt("count"))
        assertFalse(items.getJSONObject(0).has("x")) // x and ig are Overall only
        assertTrue(o.getJSONArray("insights").length() in 1..5)
    }

    @Test
    fun theOverallViewCarriesTheXAndInstagramPartOfEveryItem() {
        val o = overall(x(), parseIgBoss(IG, 1L))!!
        val p = overallPayload(o, AT, X_LINK, "Deq0sOhqJnu", BossStage.FINALISTS, final = true)
        assertEquals(MeterKind.BOSS_OVERALL, p.kind)
        val j = JSONObject(p.json)
        assertEquals("overall", j.getString("source")); assertEquals(X_LINK, j.getString("postUrl"))
        assertEquals("https://www.instagram.com/p/Deq0sOhqJnu/", j.getString("igPostUrl")); assertTrue(j.getBoolean("final"))
        assertEquals(1500, j.getInt("total")); assertEquals(950, j.getInt("tracked"))
        val first = j.getJSONArray("items").getJSONObject(0)
        assertEquals("#ArishfaIsTheBoss", first.getString("label"))
        assertEquals(500, first.getInt("count")); assertEquals(100, first.getInt("x")); assertEquals(400, first.getInt("ig"))
    }

    @Test
    fun aSourceWithNoDataIsLeftOutOfAnItemNeverZero() {
        val onlyX = overall(x(), null)!!
        val j = JSONObject(overallPayload(onlyX, AT, X_LINK, "Deq0sOhqJnu").json)
        assertTrue(j.getJSONArray("items").getJSONObject(0).has("x"))
        assertFalse(j.getJSONArray("items").getJSONObject(0).has("ig"))
    }

    @Test
    fun finalIsOnlyForTheFinalistsAndAnEmptyInstagramRunIsNotSent() {
        assertThrows(PayloadException::class.java) { instagramPayload(parseIgBoss(IG, 1L), AT, "S", BossStage.CONTESTANTS, final = true) }
        assertThrows(PayloadException::class.java) { instagramPayload(parseIgBoss("""{"empty":true,"shortcode":"S"}""", 1L), AT, "S") }
        assertThrows(PayloadException::class.java) { overallPayload(overall(x(), parseIgBoss(IG, 1L))!!, AT, "not a link", "S") }
    }

    @Test
    fun theThreeViewsHaveTheirOwnSlotsSoTheyNeverReplaceEachOther() {
        assertEquals(MeterKind.entries.size, MeterKind.entries.map { it.wire }.toSet().size)
    }

    @Test
    fun everyCommentThatMentionsAContestantCountsInTheWeeklyScore() {
        val h = RunHistory(tmp.newFolder(), nowMs = { AT })
        h.saveBoss(x(), X_LINK, "contestants")
        val of: (String) -> String? = { t -> if (t.contains("Arishfa", true)) "Arishfa Khan" else if (t.contains("Gullu", true)) "Kushal Tanwar (Gullu)" else null }
        val without = relevance(weekStats(h, of, AT - 1000, AT + 1000))
        val with = relevance(weekStats(h, of, AT - 1000, AT + 1000, mapOf("Arishfa Khan" to 300L, "Kushal Tanwar (Gullu)" to 1_200L, "Isha Rikhi" to 50L)))
        // Gullu has the most Instagram mentions; Isha only exists on Instagram and still appears.
        val g = with.first { it.week.name.startsWith("Kushal") }
        val a = with.first { it.week.name == "Arishfa Khan" }
        assertEquals(1_200L, g.week.igMentions); assertEquals(300L, a.week.igMentions)
        assertTrue(with.any { it.week.name == "Isha Rikhi" && it.week.igMentions == 50L })
        assertTrue("Instagram mentions" in g.parts && g.parts.getValue("Instagram mentions") > a.parts.getValue("Instagram mentions"))
        assertTrue(g.factsLine.contains("Instagram mentions"))
        assertNull(without.first().week.igMentions) // Instagram not included: null, not zero
    }

    @Test
    fun theMentionKeysCoverEveryRosterContestantWithTheIsTheBossHashtag() {
        val keys = mentionKeys()
        assertEquals(18, keys.size)
        val arishfa = keys.first { it.name == "Arishfa Khan" }
        assertTrue("ArishfaIsTheBoss" in arishfa.strong) // capital I kept: the server also checks the lowercase-L spelling
        assertTrue("arishfakhan" in arishfa.strong && "arishfa" in arishfa.single)
        assertTrue(keys.first { it.name == "Love Gill" }.single.none { it == "love" }) // a plain word, never alone
    }

    @Test
    fun theInstagramAndOverallImagesAreBuiltFromTheirNumbers() {
        val i = instagramShare(parseIgBoss(IG, 1L), "20 Oct 2026, 7:00 PM")
        assertEquals("BOSS METER · INSTAGRAM", i.title); assertFalse(i.twoColor)
        assertTrue(i.caption.startsWith("🔥 Boss Meter : Update (Instagram)"))
        assertTrue(i.rows[0].label == "#ArishfaIsTheBoss" && i.rows[0].fraction == 1f)
        val o = overallShare(overall(x(), parseIgBoss(IG, 1L))!!, "20 Oct 2026, 7:00 PM")
        assertTrue(o.twoColor && o.caption.startsWith("🔥 Boss Meter : Update (X + Instagram)"))
        // Arishfa: X 100 + Instagram 400 -> the X part of the bar is 20%
        assertEquals(0.2f, o.rows[0].split!!, 1e-6f)
        assertEquals(1f, o.rows[0].fraction, 1e-6f)
        assertTrue(o.caption.contains("X 100 · IG 400"))
    }
}
