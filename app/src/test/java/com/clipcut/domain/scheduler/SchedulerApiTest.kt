package com.clipcut.domain.scheduler

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

private val STATUS = """
{"now":"2026-10-22T10:44:43+05:30","timezone":"Asia/Kolkata","recording":false,"state":"idle","obsReachable":true,"streaming":true,
 "morning":{"enabled":true,"time":"10:00","nextRunAt":"2026-10-23T10:00:00+05:30","taskEnabled":true},
 "night":{"enabled":true,"times":null,"days":null,"rules":[{"days":["MON"],"times":["03:00"]},
          {"days":["TUE","WED","THU","FRI","SAT","SUN"],"times":["01:00"]}],"nextRunAt":"2026-10-23T01:00:00+05:30","taskEnabled":true},
 "lastRuns":[{"runId":"a1b2c3","kind":"night","source":"schedule","startedAt":"2026-10-22T01:00:00+05:30","finishedAt":null,"ok":null,"message":"Uploading to R2"},
             {"runId":"d4","kind":"morning","source":"manual","startedAt":"2026-10-21T10:00:00+05:30","finishedAt":"2026-10-21T10:00:20+05:30","ok":false,"message":"Already recording"}],
 "upload":{"lastOkAt":null,"lastError":"rclone failed","filesPending":2}}
""".trimIndent()

class SchedulerApiTest {
    @Test fun `parses the documented status`() {
        val s = parseStatus(STATUS)
        assertEquals("idle", s.state)
        assertTrue(s.streaming)
        assertEquals("10:00", s.morning.time)
        assertEquals(2, s.night.rules.size)
        assertEquals(listOf("MON"), s.night.rules[0].days)
        assertEquals(listOf("01:00"), s.night.rules[1].times)
        assertEquals("a1b2c3", s.activeRun?.runId)
        assertNull(s.lastRuns[0].ok)
        assertEquals(false, s.lastRuns[1].ok)
        assertNull(s.upload.lastOkAt)
        assertEquals("rclone failed", s.upload.lastError)
        assertEquals(2, s.upload.filesPending)
    }

    @Test fun `a single rule can arrive as times and days`() {
        val body = """{"now":"2026-10-22T10:44:43+05:30","morning":{"enabled":true,"time":"10:00"},
            "night":{"enabled":true,"times":["01:00"],"days":["MON","FRI"],"rules":null}}"""
        val s = parseStatus(body)
        assertEquals(listOf(NightRule(listOf("MON", "FRI"), listOf("01:00"))), s.night.rules)
    }

    @Test fun `a reply that is not the status is refused`() {
        try {
            parseStatus("""{"hello":1}""")
            fail("expected an error")
        } catch (e: SchedulerException) {
            assertTrue(e.message!!.contains("not what was expected"))
        }
    }

    @Test fun `no active run when everything finished`() {
        val s = parseStatus(STATUS.replace("\"ok\":null", "\"ok\":true"))
        assertNull(s.activeRun)
    }

    @Test fun `schedule body only holds what changed`() {
        assertEquals("{}", scheduleBody(ScheduleEdit()).toString())
        val b = scheduleBody(ScheduleEdit(morningTime = "10:30"))
        assertEquals("10:30", b.getJSONObject("morning").getString("time"))
        assertFalse(b.getJSONObject("morning").has("enabled"))
        assertFalse(b.has("night"))
    }

    @Test fun `night rules go as the rules list`() {
        val b = scheduleBody(ScheduleEdit(nightRules = listOf(NightRule(listOf("MON"), listOf("03:00")), NightRule(listOf("TUE", "WED"), listOf("01:00", "02:00")))))
        val rules = b.getJSONObject("night").getJSONArray("rules")
        assertEquals(2, rules.length())
        assertEquals("WED", rules.getJSONObject(1).getJSONArray("days").getString(1))
        assertEquals("02:00", rules.getJSONObject(1).getJSONArray("times").getString(1))
    }

    @Test fun `time of day check`() {
        assertTrue(isTimeOfDay("00:00")); assertTrue(isTimeOfDay("23:59")); assertTrue(isTimeOfDay("01:05"))
        assertFalse(isTimeOfDay("24:00")); assertFalse(isTimeOfDay("1:00")); assertFalse(isTimeOfDay("10:60")); assertFalse(isTimeOfDay(""))
        assertEquals("03:05", timeText(3, 5))
    }

    @Test fun `night rule problems`() {
        val ok = listOf(NightRule(listOf("MON"), listOf("03:00")), NightRule(listOf("TUE", "WED", "THU", "FRI", "SAT", "SUN"), listOf("01:00")))
        assertNull(nightRulesProblem(ok))
        assertNotNull(nightRulesProblem(emptyList()))
        assertNotNull(nightRulesProblem(listOf(NightRule(emptyList(), listOf("01:00")))))
        assertNotNull(nightRulesProblem(listOf(NightRule(listOf("MON"), emptyList()))))
        assertNotNull(nightRulesProblem(listOf(NightRule(listOf("MON"), listOf("25:00")))))
        assertNotNull(nightRulesProblem(listOf(NightRule(listOf("MON"), listOf("01:00", "01:00")))))
        assertNotNull(nightRulesProblem(listOf(NightRule(listOf("MON"), listOf("01:00")), NightRule(listOf("MON", "TUE"), listOf("02:00")))))
        assertNotNull(nightRulesProblem(listOf(NightRule(listOf("MON"), listOf("01:00", "02:00", "03:00")), NightRule(listOf("TUE"), listOf("04:00", "05:00")))))
    }

    @Test fun `rules read back in plain words`() {
        val text = nightRulesText(listOf(NightRule(listOf("MON"), listOf("03:00")), NightRule(listOf("TUE", "WED", "THU", "FRI", "SAT", "SUN"), listOf("01:00"))))
        assertEquals("Mon: 03:00\nTue to Sun: 01:00", text)
        assertEquals("Every day: 01:00", nightRulesText(listOf(NightRule(ALL_DAYS, listOf("01:00")))))
        assertEquals("Mon, Fri: 01:00, 02:30", nightRulesText(listOf(NightRule(listOf("FRI", "MON"), listOf("02:30", "01:00")))))
    }

    @Test fun `times show in the phone zone`() {
        assertEquals("Fri 23 Oct, 01:00", whenText("2026-10-23T01:00:00+05:30", ZoneId.of("Asia/Kolkata")))
        assertEquals("-", whenText(null, ZoneId.of("UTC")))
        assertEquals("garbage", whenText("garbage", ZoneId.of("UTC")))
    }

    // ---- The HTTP client, against a fake network ------------------------------------------------------------------------

    private class Seen { var method = ""; var path = ""; var auth: String? = null; var body = "" }

    private fun api(seen: Seen, code: Int = 200, reply: String = "{}", token: String = "tok"): RealSchedulerApi {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val r = chain.request()
            seen.method = r.method
            seen.path = r.url.encodedPath
            seen.auth = r.header("Authorization")
            seen.body = r.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(reply.toResponseBody(null)).build()
        }).build()
        return RealSchedulerApi(client, "http://pc.example:8787", token)
    }

    @Test fun `status sends the bearer token`() = runBlocking {
        val seen = Seen()
        api(seen, reply = STATUS).status()
        assertEquals("GET", seen.method); assertEquals("/status", seen.path); assertEquals("Bearer tok", seen.auth)
    }

    @Test fun `morning trigger asks for streaming by default of the caller`() = runBlocking {
        val seen = Seen()
        val id = api(seen, 202, """{"runId":"r1","accepted":true}""").triggerMorning(requireStreaming = false)
        assertEquals("r1", id)
        assertEquals("/trigger/morning", seen.path)
        assertFalse(JSONObject(seen.body).getBoolean("requireStreaming"))
    }

    @Test fun `night trigger carries the shutdown choice`() = runBlocking {
        val seen = Seen()
        api(seen, 202, """{"runId":"r2","accepted":true}""").triggerNight(shutdown = false)
        assertEquals("/trigger/night", seen.path)
        assertFalse(JSONObject(seen.body).getBoolean("shutdown"))
    }

    @Test fun `schedule is a PUT with only the changes`() = runBlocking {
        val seen = Seen()
        api(seen).putSchedule(ScheduleEdit(morningTime = "09:45"))
        assertEquals("PUT", seen.method); assertEquals("/schedule", seen.path)
        assertEquals("09:45", JSONObject(seen.body).getJSONObject("morning").getString("time"))
    }

    @Test fun `cancel returns the laptop's message`() = runBlocking {
        val seen = Seen()
        assertEquals("Shutdown cancelled", api(seen, reply = """{"cancelled":true,"message":"Shutdown cancelled"}""").cancel())
    }

    @Test fun `errors say what the laptop said`() {
        val seen = Seen()
        try { runBlocking { api(seen, 409, """{"error":"Already recording"}""").triggerMorning(true) }; fail() } catch (e: SchedulerException) {
            assertEquals(409, e.httpCode); assertTrue(e.message!!.contains("Already recording"))
        }
        try { runBlocking { api(seen, 401, """{"error":"nope"}""").status() }; fail() } catch (e: SchedulerException) {
            assertTrue(e.message!!.contains("token was refused"))
        }
        try { runBlocking { api(seen, 400, """{"error":"Bad time 25:00"}""").putSchedule(ScheduleEdit(morningTime = "25:00")) }; fail() } catch (e: SchedulerException) {
            assertTrue(e.message!!.contains("Bad time 25:00"))
        }
    }

    @Test fun `no token means not configured and nothing is sent`() {
        val seen = Seen()
        val a = api(seen, token = "")
        assertFalse(a.isConfigured)
        try { runBlocking { a.status() }; fail() } catch (e: SchedulerException) { assertTrue(e.message!!.contains("not set up")) }
        assertEquals("", seen.path)
    }
}
