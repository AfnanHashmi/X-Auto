package com.clipcut.domain.gcprune

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistAndAuditTest {
    @Test
    fun theWhitelistRoundTripsAndIsSorted() {
        val ids = setOf("30", "5", "200")
        assertEquals(ids, parseWhitelist(encodeWhitelist(ids)))
        assertEquals("""{"user_ids":["200","30","5"]}""".replace(" ", ""), encodeWhitelist(ids).replace(Regex("\\s"), ""))
    }

    @Test
    fun anEmptyWhitelistIsFine() {
        assertTrue(parseWhitelist(encodeWhitelist(emptySet())).isEmpty())
    }

    @Test
    fun aDamagedWhitelistIsAnErrorNeverAnEmptyList() {
        assertThrows(GcParseException::class.java) { parseWhitelist("not json") }
        assertThrows(GcParseException::class.java) { parseWhitelist("""{"ids":["1"]}""") }
        assertThrows(GcParseException::class.java) { parseWhitelist("""{"user_ids":["1","@someone"]}""") }
        assertThrows(GcParseException::class.java) { parseWhitelist("") }
    }

    @Test
    fun theAuditJsonHoldsEverythingNeededToReAddSomeone() {
        val run = AuditRun("20261005-101500", "g1", "execute", 1_790_000_000_000)
        run.cap = 20
        run.delayMs = 5_000
        run.memberCount = 30
        run.followersFetched = 24_000
        run.followersReported = 24_027
        val ana = AuditPerson("111", "ana", "Ana")
        val gone = AuditPerson("222", null, null)
        run.candidates += ana
        run.removed += AuditEvent(ana, "removed", "removed from the group", 1_790_000_005_000)
        run.skipped += AuditEvent(gone, "skipped", "could not verify", 1_790_000_006_000)
        run.inFlight = AuditPerson("333", "bo", "Bo")
        run.stopReason = "Finished."

        val json = JSONObject(run.toJson())
        assertEquals("g1", json.getString("group_id"))
        assertEquals("execute", json.getString("mode"))
        assertEquals(24_027, json.getInt("followers_reported_by_x"))
        assertEquals("111", json.getJSONArray("removed").getJSONObject(0).getString("user_id"))
        assertEquals("ana", json.getJSONArray("removed").getJSONObject(0).getString("handle"))
        assertTrue(json.getJSONArray("skipped").getJSONObject(0).isNull("handle"))
        assertEquals("333", json.getJSONObject("in_flight").getString("user_id"))
        assertEquals(20, json.getJSONObject("settings").getInt("cap"))
        assertEquals("gc-prune-g1-20261005-101500.json", run.fileName())
    }
}
