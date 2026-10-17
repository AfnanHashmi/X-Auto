package com.clipcut.domain.llm

import com.clipcut.domain.api.real.parseVariants
import com.clipcut.domain.taglines.parseAnalysis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val XAI_KEY = "xai-secret-123"
private const val OPENAI_KEY = "sk-openai-456"

private fun reply(text: String, inTok: Long = 100, outTok: Long = 50) = JSONObject()
    .put("status", "completed")
    .put("output", org.json.JSONArray().put(JSONObject().put("type", "reasoning")).put(
        JSONObject().put("type", "message").put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text").put("text", text))),
    ))
    .put("usage", JSONObject().put("input_tokens", inTok).put("output_tokens", outTok))
    .toString()

private class FakeHttp : LlmHttp {
    val calls = mutableListOf<Triple<String, String, JSONObject>>() // url, key, body
    var answers: (String) -> Pair<Int, String> = { 200 to reply("ok") }
    override suspend fun post(url: String, key: String, json: String): Pair<Int, String> {
        calls += Triple(url, key, JSONObject(json))
        return answers(url)
    }
}

class LlmClientTest {
    private val logs = mutableListOf<String>()
    private fun client(config: LlmConfig, http: FakeHttp) = LlmClient(config, http, log = { logs += it }, nowMs = { 0L })
    private fun both(provider: String = "xai") = LlmConfig.of(provider, XAI_KEY, OPENAI_KEY)

    @Test
    fun xaiIsTheDefaultWithGrok43ForAnalysisAndCaptions() = runTest {
        val http = FakeHttp()
        val r = client(both(""), http).respond(LlmTask.CAPTION, "sys", "hello")
        assertEquals(LlmProvider.XAI, r.provider); assertEquals("grok-4.3", r.model)
        val (url, key, body) = http.calls.single()
        assertEquals("https://api.x.ai/v1/responses", url); assertEquals(XAI_KEY, key)
        assertEquals("grok-4.3", body.getString("model"))
        assertEquals("system", body.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals("ok", r.text)
        assertEquals("xAI · grok-4.3", r.label)
    }

    @Test
    fun theProviderSwitchAndModelOverridesWork() = runTest {
        val http = FakeHttp()
        val r = client(both("OpenAI"), http).respond(LlmTask.ANALYSIS, "s", "u")
        assertEquals(LlmProvider.OPENAI, r.provider); assertEquals("https://api.openai.com/v1/responses", http.calls.single().first)
        val custom = LlmConfig.of("xai", XAI_KEY, "", xaiAnalysis = "grok-4.7", xaiCaption = "grok-build-0.1")
        assertEquals("grok-4.7", client(custom, FakeHttp()).respond(LlmTask.ANALYSIS, "s", "u").model)
        assertEquals("grok-build-0.1", client(custom, FakeHttp()).respond(LlmTask.CAPTION, "s", "u").model)
        assertEquals("grok-4.7", client(both(), FakeHttp()).respond(LlmTask.CAPTION, "s", "u", highQuality = true).model)
    }

    @Test
    fun aFailingPrimaryFallsBackToTheOtherProvider() = runTest {
        val http = FakeHttp().apply { answers = { url -> if ("x.ai" in url) 503 to """{"error":{"message":"overloaded"}}""" else 200 to reply("from openai") } }
        val r = client(both(), http).respond(LlmTask.CAPTION, "s", "u")
        assertEquals("from openai", r.text); assertEquals(LlmProvider.XAI, r.fellBackFrom)
        assertEquals("OpenAI · gpt-5.6-terra (xAI failed)", r.label)
        assertTrue(logs[0], logs[0].startsWith("task=caption provider=xai model=grok-4.3 failed") && logs[0].contains("HTTP 503: overloaded"))
        assertTrue(logs[1], logs[1].startsWith("task=caption provider=openai model=gpt-5.6-terra ok") && logs[1].endsWith("(fallback from xai)"))
    }

    @Test
    fun withOnlyOneKeyThereIsNoFallbackAndTheErrorSaysWhy() {
        val onlyXai = LlmConfig.of("xai", XAI_KEY, "")
        val http = FakeHttp().apply { answers = { 401 to """{"error":"bad key $XAI_KEY"}""" } }
        val e = assertThrows(LlmException::class.java) { runBlocking { client(onlyXai, http).respond(LlmTask.CAPTION, "s", "u") } }
        assertEquals(401, e.httpCode)
        assertFalse(e.message!!.contains(XAI_KEY)) // a key echoed by the server never reaches the screen or the log
        assertTrue(logs.none { XAI_KEY in it })
        assertEquals(1, http.calls.size)
    }

    @Test
    fun aMissingPrimaryKeyGoesStraightToTheOther() = runTest {
        val noXai = LlmConfig.of("xai", "", OPENAI_KEY)
        val http = FakeHttp()
        assertEquals(LlmProvider.OPENAI, client(noXai, http).respond(LlmTask.CAPTION, "s", "u").provider)
        assertNull(client(noXai, http).respond(LlmTask.CAPTION, "s", "u").fellBackFrom) // not a failure, just the only one set up
        assertFalse(client(LlmConfig.of("xai", " ", ""), http).isConfigured)
        assertThrows(LlmException::class.java) { runBlocking { client(LlmConfig.of("xai", "", ""), http).respond(LlmTask.CAPTION, "s", "u") } }
    }

    @Test
    fun openAiPrimaryHasNoSeparateHighTierAndIsPricedFromTheList() {
        val openAi = client(both("openai"), FakeHttp())
        assertFalse(openAi.hasHigherTier(LlmTask.ANALYSIS)) // gpt-5.6-terra both ways
        assertEquals(0.022, openAi.estimateUsd(LlmTask.ANALYSIS, false, 2_000, 1_500)!!, 1e-9) // 2,000 × $2 + 1,500 × $12 per M
        val grok = client(both("xai"), FakeHttp())
        assertTrue(grok.hasHigherTier(LlmTask.ANALYSIS))
        assertEquals(0.00625, grok.estimateUsd(LlmTask.ANALYSIS, false, 2_000, 1_500)!!, 1e-9)
    }

    @Test
    fun structuredOutputIsRequestedWithTheSchema() = runTest {
        val http = FakeHttp()
        val schema = JSONObject().put("type", "object")
        client(both(), http).respond(LlmTask.ANALYSIS, "s", "u", schema)
        val format = http.calls.single().third.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type")); assertTrue(format.getBoolean("strict"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
    }

    @Test
    fun badRepliesAreErrorsNeverEmptyText() {
        assertThrows(LlmException::class.java) { extractResponseText(JSONObject().put("output", org.json.JSONArray())) }
        assertThrows(LlmException::class.java) { extractResponseText(JSONObject().put("status", "incomplete").put("output", org.json.JSONArray())) }
        val refusal = JSONObject().put("output", org.json.JSONArray().put(JSONObject().put("type", "message").put("content", org.json.JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "no")))))
        assertThrows(LlmException::class.java) { extractResponseText(refusal) }
        val http = FakeHttp().apply { answers = { 200 to "not json" } }
        assertThrows(LlmException::class.java) { runBlocking { client(LlmConfig.of("xai", XAI_KEY, ""), http).respond(LlmTask.CAPTION, "s", "u") } }
    }

    @Test
    fun costComesFromTheReportedTokens() = runTest {
        val http = FakeHttp().apply { answers = { 200 to reply("ok", inTok = 2_000, outTok = 1_000) } }
        val r = client(both(), http).respond(LlmTask.ANALYSIS, "s", "u")
        assertEquals(0.005, r.costUsd!!, 1e-9) // 2,000 × $1.25/M + 1,000 × $2.50/M
        assertNull(r.copy(model = "unknown-model").costUsd)
        // xAI's own figure wins when it is sent: 18,031,000 ticks = $0.0018031 (a real reply from grok-4.3).
        val ticks = JSONObject(reply("ok")).apply { getJSONObject("usage").put("cost_in_usd_ticks", 18_031_000L) }
        assertEquals(0.0018031, extractUsage(ticks)!!.reportedCostUsd!!, 1e-12)
    }

    @Test
    fun variantsAndAnalysisParseStrictly() {
        assertEquals(listOf("A", "B"), parseVariants("""{"variants":[" A ","B","A",""]}""", 3))
        assertEquals(listOf("A", "B", "C"), parseVariants("""```json
{"variants":["A","B","C","D"]}
```""", 3))
        assertThrows(IllegalStateException::class.java) { parseVariants("""{"variants":[]}""", 3) }
        assertThrows(IllegalStateException::class.java) { parseVariants("1. A\n2. B", 3) }

        val a = parseAnalysis("""{"headline":"H","insights":["one","two"],"momentum":"M","caption_angle":"C"}""")
        assertEquals("H", a.headline); assertEquals(listOf("one", "two"), a.insights)
        assertThrows(LlmException::class.java) { parseAnalysis("""{"headline":"H","insights":[],"momentum":"M","caption_angle":"C"}""") }
        assertThrows(LlmException::class.java) { parseAnalysis("""{"headline":"","insights":["x"],"momentum":"M","caption_angle":"C"}""") }
    }
}
