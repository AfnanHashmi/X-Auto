package com.clipcut.domain.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val COUNT_URL = "https://api.x.com/2/tweets/counts/recent?query=x"

/** Balances in dollars per app; null = the balance check fails. Records every count call. */
private class FakeGateway(
    val balances: MutableMap<XApp, Double?> = mutableMapOf(XApp.MAIN to 4.33, XApp.RW to 3.69, XApp.DK to 2.21),
    val configured: Set<XApp> = XApp.entries.toSet(),
) : XAppGateway {
    val counts = mutableListOf<XApp>()
    val creditChecks = mutableListOf<XApp>()
    var failWith: Map<XApp, Int> = emptyMap()

    override fun isConfigured(app: XApp) = app in configured

    override suspend fun get(app: XApp, url: String): String {
        if (url == CREDITS_URL) {
            creditChecks += app
            val usd = balances[app] ?: throw XReadException("down", 503)
            return """{"data":{"free_balance":0.0,"prepaid_balance":$usd,"total_balance":$usd}}"""
        }
        failWith[app]?.let { throw XReadException("no", it) }
        counts += app
        return "{}"
    }
}

class XReadPoolTest {
    private var now = 1_000_000L
    private fun pool(g: FakeGateway) = XReadPool(g, nowMs = { now })

    @Test
    fun eachFeatureUsesItsOwnAccountFirst() = runTest {
        val g = FakeGateway()
        val p = pool(g)
        assertEquals(XApp.MAIN, p.read(BOSS_METER_ORDER, COUNT_URL, 0.005).first)
        assertEquals(XApp.RW, p.read(TAGLINE_ORDER, COUNT_URL, 0.005).first)
        assertEquals(listOf(XApp.MAIN, XApp.RW), g.counts)
    }

    @Test
    fun aLowAccountIsPassedOverForTheNextFundedOne() = runTest {
        val g = FakeGateway(mutableMapOf(XApp.MAIN to 4.33, XApp.RW to 0.80, XApp.DK to 2.21))
        val p = pool(g)
        assertEquals(XApp.MAIN, p.read(TAGLINE_ORDER, COUNT_URL, 0.005).first) // RW under $1: Main is next in the tagline order
        assertTrue(p.payerText(TAGLINE_ORDER), p.payerText(TAGLINE_ORDER).startsWith("Paid from Main's X credits ($4.3") && p.payerText(TAGLINE_ORDER).endsWith("(RW is low)."))
    }

    @Test
    fun whenEveryAccountIsLowTheOneWithMostLeftPays() = runTest {
        val g = FakeGateway(mutableMapOf(XApp.MAIN to 0.20, XApp.RW to 0.90, XApp.DK to 0.50))
        assertEquals(XApp.RW, pool(g).read(BOSS_METER_ORDER, COUNT_URL, 0.005).first)
    }

    @Test
    fun withNoBalanceKnownTheOrderIsUsedAsIs() = runTest {
        val g = FakeGateway(mutableMapOf(XApp.MAIN to null, XApp.RW to null, XApp.DK to null))
        assertEquals(XApp.MAIN, pool(g).read(BOSS_METER_ORDER, COUNT_URL, 0.005).first)
        // An unchecked account is tried before one known to be low.
        val mixed = FakeGateway(mutableMapOf(XApp.MAIN to 0.10, XApp.RW to null, XApp.DK to 0.50))
        assertEquals(XApp.RW, pool(mixed).read(BOSS_METER_ORDER, COUNT_URL, 0.005).first)
    }

    @Test
    fun outOfCreditsMovesTheSameCallToTheNextAccount() = runTest {
        val g = FakeGateway().apply { failWith = mapOf(XApp.RW to 402) }
        val p = pool(g)
        assertEquals(XApp.MAIN, p.read(TAGLINE_ORDER, COUNT_URL, 0.005).first)
        assertEquals(0.0, p.balances.value.usd(XApp.RW)!!, 0.0) // marked empty, so the next call goes straight to Main
        assertEquals(XApp.MAIN, p.read(TAGLINE_ORDER, COUNT_URL, 0.005).first)
        assertEquals(listOf(XApp.MAIN, XApp.MAIN), g.counts)
    }

    @Test
    fun everyAccountOutOfCreditsFailsWith402() = runTest {
        val g = FakeGateway().apply { failWith = XApp.entries.associateWith { 402 } }
        val e = assertThrows(XReadException::class.java) { kotlinx.coroutines.runBlocking { pool(g).read(BOSS_METER_ORDER, COUNT_URL, 0.005) } }
        assertEquals(402, e.httpCode)
        assertTrue(g.counts.isEmpty())
    }

    @Test
    fun otherErrorsAreNotRetriedOnAnotherAccount() = runTest {
        val g = FakeGateway().apply { failWith = mapOf(XApp.MAIN to 429) }
        val e = assertThrows(XReadException::class.java) { kotlinx.coroutines.runBlocking { pool(g).read(BOSS_METER_ORDER, COUNT_URL, 0.005) } }
        assertEquals(429, e.httpCode) // Boss Meter's own wait-and-retry handles rate limits
        assertTrue(g.counts.isEmpty())
    }

    @Test
    fun accountsWithoutKeysAreNeverUsed() = runTest {
        val g = FakeGateway(configured = setOf(XApp.DK))
        val p = pool(g)
        assertEquals(XApp.DK, p.read(BOSS_METER_ORDER, COUNT_URL, 0.005).first)
        assertEquals(Balance.NotSetUp, p.balances.value.byApp[XApp.MAIN])
        assertEquals(listOf(XApp.DK), g.creditChecks) // no balance check for an app with no keys
        assertNull(pool(FakeGateway(configured = emptySet())).pick(BOSS_METER_ORDER))
    }

    @Test
    fun aLongRunMovesOnOnceTheLocalBalanceDropsBelowOneDollar() = runTest {
        val g = FakeGateway(mutableMapOf(XApp.MAIN to 1.012, XApp.RW to 3.0, XApp.DK to 2.0))
        val p = pool(g)
        val used = List(4) { p.read(BOSS_METER_ORDER, COUNT_URL, 0.005).first }
        assertEquals(listOf(XApp.MAIN, XApp.MAIN, XApp.MAIN, XApp.RW), used) // 1.012 → 1.007 → 1.002 → 0.997
        assertEquals(mapOf(XApp.MAIN to 3, XApp.RW to 1), p.calls.value)
    }

    @Test
    fun balancesAreReadAtMostEveryTwoMinutesUnlessForced() = runTest {
        val g = FakeGateway()
        val p = pool(g)
        p.read(BOSS_METER_ORDER, COUNT_URL, 0.005)
        p.read(BOSS_METER_ORDER, COUNT_URL, 0.005)
        assertEquals(3, g.creditChecks.size) // one check of each app
        now += 60_000
        p.refreshBalances()
        assertEquals(3, g.creditChecks.size)
        p.refreshBalances(force = true)
        assertEquals(6, g.creditChecks.size)
        now += 121_000
        g.balances[XApp.MAIN] = 0.5
        p.refreshBalances()
        assertTrue(p.balances.value.isLow(XApp.MAIN))
        assertEquals(Balance.Unavailable("X answered HTTP 503"), pool(FakeGateway(mutableMapOf(XApp.MAIN to null, XApp.RW to 1.0, XApp.DK to 1.0))).also { it.refreshBalances() }.balances.value.byApp[XApp.MAIN])
    }

    @Test
    fun whoPaidIsSummedPerRun() {
        assertEquals("RW (6 calls)", paidByText(mapOf(XApp.RW to 2), mapOf(XApp.RW to 8)))
        assertEquals("Main (1 call), RW (4 calls)", paidByText(emptyMap(), mapOf(XApp.RW to 4, XApp.MAIN to 1)))
        assertEquals("", paidByText(mapOf(XApp.RW to 2), mapOf(XApp.RW to 2)))
        assertEquals(3.69, parseCredits("""{"data":{"total_balance":3.69}}"""), 0.0)
        assertThrows(XReadException::class.java) { parseCredits("""{"data":{}}""") }
    }
}
