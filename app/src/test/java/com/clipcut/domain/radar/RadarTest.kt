package com.clipcut.domain.radar

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(): String = RadarTest::class.java.getResource("/radar/report.json")!!.readText()

private class FakeApi(var configured: Boolean = true) : RadarApi {
    var failWith: Exception? = null
    var reportToReturn: RadarReport? = null
    val calls = mutableListOf<String>()
    override val isConfigured: Boolean get() = configured
    private fun go(call: String) { calls += call; failWith?.let { throw it } }
    override suspend fun report(): RadarReport { go("report"); return reportToReturn ?: parseRadarReport(fixture()) }
    override suspend fun preview(type: RunKind, posts: Int?): RunPreview { go("preview:$type:$posts"); return RunPreview(type, "m", posts ?: 20, 0.1, 0.15, true, null, 0.0, null) }
    override suspend fun session(on: Boolean, budgetUsd: Double?) = go("session:$on:$budgetUsd")
    override suspend fun run(type: RunKind, posts: Int?, focus: String?, dry: Boolean) = go("run:$type:$posts:$focus:$dry")
    override suspend fun config(patch: JSONObject) = go("config:${patch.keys().asSequence().toList().sorted()}")
    override suspend fun exclude(handle: String) = go("exclude:$handle")
}

class RadarTest {
    // ---- the real report from the live Worker ------------------------------------------------------------------------
    @Test
    fun readsTheRealReportTheWorkerSent() {
        val r = parseRadarReport(fixture())
        assertEquals("off", r.session.phase)
        assertFalse(r.session.on)
        assertTrue(r.spend.todayUsd > 0.10 && r.spend.todayUsd < 0.11)
        assertNull(r.spend.creditsLeftUsd)
        assertEquals("grok-4.3", r.config.pulseModel)
        assertEquals("grok-4.7", r.config.deepModel)
        assertEquals(listOf(20, 40, 50), r.config.pulseSizes)
        assertEquals(6.0, r.config.dailyCapUsd, 0.0)
        assertEquals("Bigg Boss 20", r.config.topic.name)
        assertTrue(r.config.topic.keywordsHi.isNotEmpty() && r.config.topic.keywordsHinglish.isNotEmpty())
        assertEquals(listOf("weekday", "weekend"), r.config.profiles.map { it.name })
        assertEquals(listOf("12:00", "18:00", "23:00"), r.config.profiles.first { it.name == "weekday" }.deepTimes)
        assertEquals("weekend", r.config.days["sat"])
        assertEquals(60, r.config.pulseEveryMin)
        assertEquals("21:00", r.config.peakStart)
        assertTrue(r.job.last!!.ok)
        assertFalse(r.job.running)
        assertEquals(0, r.job.queued)
    }

    @Test
    fun theLatestReportHasItsSubTopicsSplitIntoKeywordsAndLinks() {
        val latest = parseRadarReport(fixture()).latest!!
        assertEquals("PULSE", latest.run.type)
        assertEquals("ok", latest.run.status)
        assertEquals(4, latest.subtopics.size)
        assertTrue(latest.subtopics.all { it.exampleUrls.all { u -> u.startsWith("https://x.com/") } })
        assertTrue(latest.subtopics.all { it.keywords.isNotEmpty() && it.keywords.none { k -> k.contains("|") } })
        assertTrue(latest.subtopics.all { it.label in setOf("NEW", "RISING", "PEAKING", "FADING", "STEADY") })
        assertEquals(2, parseRadarReport(fixture()).runs.size)
    }

    private fun mutate(change: (JSONObject) -> Unit): String = JSONObject(fixture()).also(change).toString()

    @Test
    fun aReplyMissingTheSessionSpendJobOrConfigIsRejected() {
        for (key in listOf("session", "spend", "job", "config")) {
            assertThrows(key, RadarParseException::class.java) { parseRadarReport(mutate { it.remove(key) }) }
        }
        assertThrows(RadarParseException::class.java) { parseRadarReport("nonsense") }
        assertThrows(RadarParseException::class.java) { parseRadarReport("{}") }
        assertThrows(RadarParseException::class.java) { parseRadarReport(mutate { it.getJSONObject("session").put("phase", "") }) }
        assertThrows(RadarParseException::class.java) { parseRadarReport(mutate { it.getJSONObject("config").remove("schedule") }) }
    }

    @Test
    fun noLatestReportYetIsFineAndAnUnusableRunIsNot() {
        assertNull(parseRadarReport(mutate { it.put("latest", JSONObject.NULL) }).latest)
        assertThrows(RadarParseException::class.java) { parseRadarReport(mutate { it.getJSONObject("latest").getJSONObject("run").remove("status") }) }
    }

    @Test
    fun theSessionStatesAreReadIncludingTimesAndAReasonItEnded() {
        val on = parseRadarReport(
            mutate {
                it.getJSONObject("session").put("phase", "running").put("on", true).put("window_start", 1000L).put("window_end", 9000L)
                    .put("budget", 4.0).put("spent", 1.25).put("deep_slots", org.json.JSONArray(listOf(1000L, 5000L))).put("deep_done", org.json.JSONArray(listOf(1000L)))
                    .put("spike_queue", org.json.JSONArray(listOf("kitchen fight")))
            },
        ).session
        assertTrue(on.on)
        assertEquals(1000L, on.windowStartMs)
        assertEquals(4.0, on.budgetUsd!!, 0.0)
        assertEquals(1.25, on.spentUsd, 0.0)
        assertEquals(listOf(1000L), on.deepDone)
        assertEquals(listOf("kitchen fight"), on.spikeQueue)
        val ended = parseRadarReport(mutate { it.getJSONObject("session").put("phase", "ended").put("ended_reason", "the window ended").put("ended_at", 5L) }).session
        assertEquals("the window ended", ended.endedReason)
    }

    @Test
    fun theCostPreviewIsReadWithItsRefusalWhenThereIsOne() {
        val ok = parsePreview("""{"type":"DEEP","model":"grok-4.7","posts":90,"likely":0.84,"worst":1.26,"guard":{"ok":true,"spentToday":0},"spent_today":0,"credits_left":null}""")
        assertEquals(RunKind.DEEP, ok.type)
        assertEquals(0.84, ok.likelyUsd, 0.0)
        assertTrue(ok.allowed)
        assertNull(ok.creditsLeftUsd)
        val no = parsePreview("""{"type":"PULSE","model":"m","posts":20,"likely":0.1,"worst":0.15,"guard":{"ok":false,"reason":"Today's limit would be passed"},"spent_today":5.9,"credits_left":1.0}""")
        assertFalse(no.allowed)
        assertEquals("Today's limit would be passed", no.refusal)
        assertEquals(1.0, no.creditsLeftUsd!!, 0.0)
        assertThrows(RadarParseException::class.java) { parsePreview("""{"type":"PULSE"}""") }
    }

    @Test
    fun onlyRisingOrNewTopicsWithWeightCountAsRising() {
        fun row(label: String, m: Int) = SubtopicRow("k", "n", m, 0.2, "mixed", emptyList(), emptyList(), label, 0.0, 0.0)
        assertTrue(row("RISING", 1).isSpike)
        assertTrue(row("NEW", 3).isSpike)
        assertFalse(row("NEW", 2).isSpike)
        assertFalse(row("STEADY", 9).isSpike)
        assertFalse(row("FADING", 9).isSpike)
    }

    @Test
    fun theHomeCardSummaryIgnoresSampleReportsWhenCountingRising() {
        val base = parseRadarReport(fixture())
        val s = RadarSummary.of(base)
        assertEquals("off", s.phase)
        assertFalse(s.sample)
        val sample = parseRadarReport(mutate { it.getJSONObject("latest").getJSONObject("run").put("status", "dry_run") })
        val ss = RadarSummary.of(sample)
        assertTrue(ss.sample)
        assertEquals(0, ss.rising) // a saved sample never makes the card light up
    }

    // ---- the service turns failures into readable reasons --------------------------------------------------------------
    @Test
    fun withoutTheAddressAndTokenNothingIsCalled() = runTest {
        val api = FakeApi(configured = false)
        val out = RadarService(api).report() as Outcome.Failed
        assertTrue(out.reason.contains("TREND_URL") && out.reason.contains("TREND_TOKEN"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun anUnauthorisedReplyTellsYouToCheckTheToken() = runTest {
        val api = FakeApi().apply { failWith = RadarApiException("HTTP 401", 401) }
        assertTrue((RadarService(api).report() as Outcome.Failed).reason.contains("TREND_TOKEN"))
    }

    @Test
    fun theWorkersOwnReasonIsShownForARefusal() = runTest {
        val api = FakeApi().apply { failWith = RadarApiException("Today's limit of \$6.00 would be passed.", 409) }
        assertEquals("Today's limit of \$6.00 would be passed.", (RadarService(api).run(RunKind.DEEP) as Outcome.Failed).reason)
    }

    @Test
    fun noReplyAndUntrustedRepliesAreSaidPlainly() = runTest {
        val gone = FakeApi().apply { failWith = RadarApiException("timeout", null) }
        assertTrue((RadarService(gone).report() as Outcome.Failed).reason.contains("did not reply"))
        val bad = FakeApi().apply { failWith = RadarParseException("no session") }
        assertTrue((RadarService(bad).report() as Outcome.Failed).reason.contains("could not be trusted"))
        val io = FakeApi().apply { failWith = java.io.IOException("reset") }
        assertTrue((RadarService(io).report() as Outcome.Failed).reason.contains("did not reply"))
    }

    @Test
    fun theActionsReachTheApiWithTheRightArguments() = runTest {
        val api = FakeApi()
        val svc = RadarService(api)
        svc.setSession(true, 4.0)
        svc.setSession(false)
        svc.run(RunKind.PULSE, 40, "kitchen fight")
        svc.sample(RunKind.DEEP)
        svc.exclude("@spammer")
        svc.saveConfig(JSONObject().put("daily_cap_usd", 8).put("deep_model", "grok-4.3"))
        svc.preview(RunKind.DEEP, null)
        assertEquals(
            listOf("session:true:4.0", "session:false:null", "run:PULSE:40:kitchen fight:false", "run:DEEP:null:null:true", "exclude:spammer", "config:[daily_cap_usd, deep_model]", "preview:DEEP:null"),
            api.calls,
        )
    }
}
