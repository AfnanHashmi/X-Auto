package com.clipcut.domain.publish

import com.clipcut.domain.taglines.CountReport
import com.clipcut.domain.taglines.CountSeries
import com.clipcut.domain.taglines.DeepLook
import com.clipcut.domain.taglines.HourBucket
import com.clipcut.domain.taglines.TaglineAnalysis
import com.clipcut.domain.taglines.TaglineCount
import com.clipcut.domain.taglines.Voice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private val IST = ZoneId.of("Asia/Kolkata")
private const val END = 1_792_271_838_261L

private fun t(name: String, n: Long) = TaglineCount(name, CountSeries(n, listOf(HourBucket(END - 3_600_000L, n))), CountSeries(n / 10, emptyList()))
private val RUN = CountReport(END - 24 * 3_600_000L, END, END + 30_000, listOf(t("QAZI THE POWERHOUSE OF BB20", 49_107), t("WE WANT NAKUL BACK", 16_361)))

private val READOUT = TaglineAnalysis("Qazi leads with 49,107 tweets", listOf("Leads #2 by 32,746", "Most retweet-driven"), "Fading fast", "Frame the lead", "OpenAI · gpt-5.6-terra", 0.02, END + 600_000)
private val LOOK = DeepLook(
    "QAZI THE POWERHOUSE OF BB20", 18, listOf("Fans defend him", "Powerhouse of the house"),
    listOf(Voice("@fan_account_one", "1,646 likes"), Voice("not a handle", "x"), Voice("@example_account", "")),
    listOf("https://x.com/fan_account_one/status/2111503685482344508", "https://evil.example/1"), 0.105, "xAI · grok-4.3", END + 900_000,
    changes = "@example_account is new", reportAtMs = END + 30_000,
)

class AiPublishTest {
    private fun send(readout: TaglineAnalysis? = null, looks: Map<String, DeepLook> = emptyMap(), steady: Map<String, List<Pair<String, Int>>> = emptyMap()) =
        JSONObject(trendPayload(RUN, IST, readout, looks, steady).json)

    @Test
    fun withNoAiTheRunIsExactlyAsBefore() {
        val j = send()
        assertFalse(j.has("readout"))
        for (i in 0 until j.getJSONArray("items").length()) assertFalse(j.getJSONArray("items").getJSONObject(i).has("deepLook"))
    }

    @Test
    fun aReadoutIsAddedToTheSameRun() {
        val j = send(readout = READOUT)
        assertEquals(JSONObject(trendPayload(RUN, IST).json).getString("refreshedAt"), j.getString("refreshedAt")) // same run
        val r = j.getJSONObject("readout")
        assertEquals("Qazi leads with 49,107 tweets", r.getString("headline"))
        assertEquals(2, r.getJSONArray("insights").length()); assertEquals("Fading fast", r.getString("momentum"))
        assertEquals("openai", r.getString("provider")); assertEquals("gpt-5.6-terra", r.getString("model"))
        assertTrue(r.has("writtenAt")); assertFalse(r.has("captionAngle")); assertFalse(r.toString().contains("Frame the lead"))
    }

    @Test
    fun deepLooksGoOnlyOnTheTaglinesThatHaveOne() {
        val j = send(looks = mapOf(LOOK.tagline to LOOK), steady = mapOf(LOOK.tagline to listOf("@fan_account_one" to 3, "@once" to 1)))
        val items = j.getJSONArray("items")
        val d = items.getJSONObject(0).getJSONObject("deepLook")
        assertFalse(items.getJSONObject(1).has("deepLook")) // Nakul had no Deep look
        assertEquals(2, d.getJSONArray("themes").length())
        assertEquals(2, d.getJSONArray("voices").length()) // the fake handle is dropped
        assertEquals("1,646 likes", d.getJSONArray("voices").getJSONObject(0).getString("note"))
        assertFalse(d.getJSONArray("voices").getJSONObject(1).has("note")) // no empty note
        assertEquals(1, d.getJSONArray("posts").length()) // only real x.com post links
        assertEquals("@example_account is new", d.getString("changes"))
        assertEquals(1, d.getJSONArray("steadyVoices").length()); assertEquals(3, d.getJSONArray("steadyVoices").getJSONObject(0).getInt("looks"))
        assertEquals(18, d.getInt("postsRead")); assertEquals("xai", d.getString("provider")); assertEquals("grok-4.3", d.getString("model"))
        assertFalse(j.has("readout")) // a Deep look without a read-out
    }

    @Test
    fun richerDeepLookFieldsGoAlong() {
        val rich = LOOK.copy(starters = listOf(Voice("@first_fan", "6:31 PM")), rivals = listOf("UDITI STANDS BY HER PEOPLE"), pairedHashtags = listOf("#BiggBoss20"), size = "Max")
        val d = deepLookJson(rich)!!
        assertEquals("@first_fan", d.getJSONArray("starters").getJSONObject(0).getString("handle"))
        assertEquals("6:31 PM", d.getJSONArray("starters").getJSONObject(0).getString("when"))
        assertEquals("UDITI STANDS BY HER PEOPLE", d.getJSONArray("rivalTaglines").getString(0))
        assertEquals("#BiggBoss20", d.getJSONArray("pairedHashtags").getString(0))
        assertEquals("max", d.getString("size"))
        assertFalse(deepLookJson(LOOK)!!.has("starters")) // left out when there are none
    }

    @Test
    fun bothTogetherAndNothingEmptyIsEverSent() {
        val j = send(readout = READOUT, looks = mapOf(LOOK.tagline to LOOK))
        assertTrue(j.has("readout")); assertTrue(j.getJSONArray("items").getJSONObject(0).has("deepLook"))
        val bare = deepLookJson(LOOK.copy(voices = emptyList(), examplePosts = emptyList(), changes = "", postsRead = 0, label = "", atMs = 0))!!
        assertEquals(setOf("themes"), bare.keys().asSequence().toSet())
        assertNull(deepLookJson(LOOK.copy(themes = listOf(" "))))
        assertNull(readoutJson(READOUT.copy(headline = "  ")))
        assertFalse(readoutJson(READOUT.copy(insights = emptyList()))!!.has("insights"))
    }
}
