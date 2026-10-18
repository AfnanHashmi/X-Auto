package com.clipcut.domain.taglines

import com.clipcut.domain.llm.LlmClient
import com.clipcut.domain.llm.LlmConfig
import com.clipcut.domain.llm.LlmException
import com.clipcut.domain.llm.LlmHttp
import com.clipcut.domain.share.taglineShareCard
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private val IST = ZoneId.of("Asia/Kolkata")
private const val START = 1_792_185_438_261L // 2026-10-16T21:17:18Z (a real count's window)
private const val END = START + 24 * 3_600_000L
private const val NOW = END + 5 * 3_600_000L

private val GOOD = """{"posts_read":18,
 "themes":["Qazi as unstoppable force","Fans defend him from hate",""],
 "top_voices":[{"handle":"@fan_account_one","why":"1,646 likes"},{"handle":"example_account","why":"495 likes"},
               {"handle":"not a handle!","why":"x"},{"handle":"@fan_account_one","why":"dup"}],
 "example_posts":["https://x.com/fan_account_one/status/2111503685482344508?s=20","https://evil.example/status/1","x.com/a/status/123"]}"""

private fun xaiReply(text: String, ticks: Long = 1_046_493_500L) = JSONObject()
    .put("output", JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))
    .put("usage", JSONObject().put("input_tokens", 9433).put("output_tokens", 1197).put("cost_in_usd_ticks", ticks))
    .toString()

private class Http(var body: String = xaiReply(GOOD)) : LlmHttp {
    val sent = mutableListOf<Pair<String, JSONObject>>()
    override suspend fun post(url: String, key: String, json: String): Pair<Int, String> {
        sent += url to JSONObject(json)
        return 200 to body
    }
}

private class MemLedger : DeepLookLedger {
    val spent = HashMap<LocalDate, Double>()
    override fun spentOn(day: LocalDate) = spent[day] ?: 0.0
    override fun add(day: LocalDate, usd: Double) { spent[day] = spentOn(day) + usd }
}

private fun report() = CountReport(START, END, NOW, listOf(TaglineCount("QAZI THE POWERHOUSE OF BB20", CountSeries(0, emptyList()), CountSeries(0, emptyList()))))

class DeepLookTest {
    private fun service(http: Http, ledger: DeepLookLedger = MemLedger(), config: LlmConfig = LlmConfig.of("xai", "xai-k", "sk-k")) =
        DeepLookService(LlmClient(config, http), ledger, zone = { IST }, nowMs = { NOW })

    @Test
    fun onlyRealLookingHandlesAndPostLinksSurvive() {
        val d = parseDeepLook(GOOD, "T")
        assertEquals(18, d.postsRead)
        assertEquals(listOf("Qazi as unstoppable force", "Fans defend him from hate"), d.themes)
        assertEquals(listOf("@fan_account_one", "@example_account"), d.voices.map { it.handle }) // junk and a case-duplicate dropped
        assertEquals(listOf("https://x.com/fan_account_one/status/2111503685482344508"), d.examplePosts) // other sites and bare text dropped
        assertThrows(LlmException::class.java) { parseDeepLook("""{"posts_read":0,"themes":[],"top_voices":[],"example_posts":[]}""", "T") }
        assertThrows(LlmException::class.java) { parseDeepLook("no json", "T") }
    }

    @Test
    fun theSearchCoversTheCountedDaysOnXaiOnlyWithTwoRounds() = runTest {
        val http = Http()
        val look = service(http).look("QAZI THE POWERHOUSE OF BB20", report())
        val (url, body) = http.sent.single()
        assertEquals("https://api.x.ai/v1/responses", url)
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("x_search", tool.getString("type"))
        assertEquals("2026-10-16", tool.getString("from_date"))
        assertEquals("2026-10-18", tool.getString("to_date")) // the end day (17th) is always included
        assertEquals(DeepLookSize.STANDARD.searches, body.getInt("max_turns"))
        assertEquals(0.10464935, look.costUsd!!, 1e-9) // xAI's own figure
        assertEquals("xAI · grok-4.3", look.label)
    }

    @Test
    fun searchNeverFallsBackToOpenAi() {
        val http = Http()
        val openAiOnly = LlmConfig.of("openai", "", "sk-k")
        assertThrows(LlmException::class.java) { runBlocking { service(http, config = openAiOnly).look("T", report()) } }
        assertTrue(http.sent.isEmpty())
    }

    @Test
    fun theDailyCapStopsBeforeSpendingAndCountsRealCosts() = runTest {
        val ledger = MemLedger()
        val s = service(Http(), ledger)
        repeat(26) { s.look("T", report()) } // 26 × $0.105 = $2.72
        assertEquals(2.721, s.spentToday(), 0.001)
        assertTrue(s.blockedReason()!!.startsWith("A Standard Deep look")) // $2.72 + $0.30 > $3.00
        assertTrue(s.blockedReason(DeepLookSize.FULL)!!.contains("Try a smaller size"))
        val http = Http()
        assertThrows(LlmException::class.java) { runBlocking { service(http, ledger).look("T", report()) } }
        assertTrue(http.sent.isEmpty()) // refused before any call
        // A reply without a cost figure still counts, at the estimate.
        val noCost = MemLedger()
        service(Http(xaiReply(GOOD).let { JSONObject(it).apply { getJSONObject("usage").remove("cost_in_usd_ticks") }.toString() }), noCost).look("T", report())
        assertEquals(DEEP_LOOK_ESTIMATE_USD, noCost.spentOn(LocalDate.of(2026, 10, 18)), 1e-9)
    }

    @Test
    fun biggerSizesAskForMoreAndAFullLookIsBlockedNearTheCap() = runTest {
        val http = Http()
        val ledger = MemLedger()
        service(http, ledger).look("T", report(), size = DeepLookSize.FULL)
        val body = http.sent.single().second
        assertEquals(18, body.getInt("max_turns"))
        assertTrue(body.getJSONArray("input").getJSONObject(0).getString("content").contains("Read about 170 posts"))
        ledger.add(LocalDate.of(2026, 10, 18), 2.0) // $2.10 spent: a Full ($1.00) would pass $3, a Max ($0.50) would not
        val s = service(Http(), ledger)
        assertTrue(s.blockedReason(DeepLookSize.FULL) != null); assertNull(s.blockedReason(DeepLookSize.MAX))
    }

    @Test
    fun theRicherFieldsAreCheckedLikeTheRest() {
        val d = parseDeepLook(
            """{"posts_read":52,"themes":["T"],"top_voices":[],"example_posts":[],"changes":"",
               "starters":[{"handle":"@first_fan","when":"6:31 PM"},{"handle":"bad handle!","when":"x"}],
               "rival_taglines":["UDITI STANDS BY HER PEOPLE","T"],"paired_hashtags":["#BiggBoss20","BB20","#not valid"]}""",
            "T",
        )
        assertEquals(listOf(Voice("@first_fan", "6:31 PM")), d.starters)
        assertEquals(listOf("UDITI STANDS BY HER PEOPLE"), d.rivals) // the tagline itself is not its own rival
        assertEquals(listOf("#BiggBoss20", "#BB20"), d.pairedHashtags)
    }

    @Test
    fun theShareCardCarriesLooksAndTheReadoutOnlyWhenPresent() {
        val r = report()
        val look = parseDeepLook(GOOD, "QAZI THE POWERHOUSE OF BB20").copy(label = "xAI · grok-4.3")
        val readout = TaglineAnalysis("Qazi leads", listOf("a", "b", "c", "d"), "fading", "angle", "xAI · grok-4.3", 0.004)
        val card = taglineShareCard(r, "today", IST, looks = mapOf(look.tagline to look), readout = readout)
        val s = card.stats.single()
        assertEquals(listOf("@fan_account_one", "@example_account"), s.voices); assertEquals(2, s.themes.size); assertEquals(18, s.samplePosts)
        assertEquals(listOf("Qazi leads", "a", "b", "c"), card.readout) // headline + three findings
        assertTrue(card.aiNote!!.startsWith("AI notes by xAI · grok-4.3"))
        val plain = taglineShareCard(r, "today", IST)
        assertTrue(plain.readout.isEmpty()); assertNull(plain.aiNote); assertTrue(plain.stats.single().voices.isEmpty())
    }
}
