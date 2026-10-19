package com.clipcut.domain.bossmeter

import com.clipcut.domain.share.X_POST_LIMIT
import com.clipcut.domain.share.bossMeterShare
import com.clipcut.domain.share.fitCaption
import com.clipcut.domain.share.xLength
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeApi(private val answers: Map<String, Long>) : BossMeterApi {
    var configured = true
    val queries = mutableListOf<String>()
    var rateLimitOnce: String? = null
    var failOn: String? = null
    override val isConfigured get() = configured
    var postJson = ""
    var postCalls = 0
    override suspend fun post(id: String): String { postCalls++; return postJson }
    override suspend fun count(query: String): Long {
        if (query == rateLimitOnce) { rateLimitOnce = null; throw BossMeterException("slow down", 429, retryAtEpochSec = 1_000L + 20) }
        if (query == failOn) throw BossMeterException("Forbidden", 403)
        queries += query
        return answers[query] ?: 0L
    }
}

private const val LINK = "https://x.com/BiggBoss/status/2099369474948563035?s=20"
private const val ID = "2099369474948563035"

class BossMeterTest {
    @Test
    fun theLinkGivesThePostId() {
        assertEquals(ID, postIdFrom(LINK))
        assertEquals(ID, postIdFrom("https://twitter.com/a_b/status/$ID"))
        assertEquals(ID, postIdFrom("  $ID "))
        assertNull(postIdFrom("https://x.com/BiggBoss"))
        assertNull(postIdFrom("hello"))
    }

    @Test
    fun hashtagsAreReadFromFreeText() {
        assertEquals(listOf("#QaziIsTheBoss", "#KanikaIsTheBoss", "#gullu"), hashtagsFrom("#QaziIsTheBoss, KanikaIsTheBoss\n#gullu  #qaziistheboss"))
        assertEquals(emptyList<String>(), hashtagsFrom("  , #  "))
    }

    @Test
    fun countsLikeTheScriptThreadFirstThenEachHashtag() = runTest {
        val api = FakeApi(mapOf("conversation_id:$ID" to 1000L, "conversation_id:$ID #A" to 500L, "conversation_id:$ID #B" to 300L))
        val pauses = mutableListOf<Long>()
        val out = BossMeterService(api, nowMs = { 1_000_000L }, pause = { pauses += it }).run(LINK, listOf("#A", "#B")) as BossMeterOutcome.Done
        assertEquals(listOf("conversation_id:$ID", "conversation_id:$ID #A", "conversation_id:$ID #B"), api.queries)
        assertEquals(listOf(500L, 500L, 500L), pauses) // half a second after each call, as the script sleeps
        val r = out.result
        assertEquals(800L, r.trackedSum)
        assertEquals(200L, r.uncounted)
        assertEquals(62.5, r.percent(r.tags[0]), 1e-9) // of the tracked sum, not of the thread
        assertEquals(20.0, r.uncountedPercentOfThread!!, 1e-9)
        assertEquals(listOf("#A", "#B"), r.ranked.map { it.tag })
    }

    @Test
    fun theScriptsTotalIsReadWithZeroWhenMissing() {
        assertEquals(42L, parseTotal("""{"data":[],"meta":{"total_tweet_count":42}}"""))
        assertEquals(0L, parseTotal("""{"meta":{}}"""))
        assertEquals(0L, parseTotal("{}"))
        assertThrows(BossMeterException::class.java) { parseTotal("nope") }
    }

    @Test
    fun aRateLimitWaitsUntilTheResetAndRetries() = runTest {
        val api = FakeApi(mapOf("conversation_id:$ID" to 10L)).apply { rateLimitOnce = "conversation_id:$ID" }
        val pauses = mutableListOf<Long>()
        val out = BossMeterService(api, nowMs = { 1_000_000L }, pause = { pauses += it }).run(LINK, listOf("#A"))
        assertTrue(out is BossMeterOutcome.Done)
        assertEquals(20_000L, pauses.first())
    }

    @Test
    fun nothingIsSentWithoutKeysALinkOrHashtags() = runTest {
        val api = FakeApi(emptyMap())
        api.configured = false
        assertTrue(BossMeterService(api).run(LINK, listOf("#A")) is BossMeterOutcome.Failed)
        api.configured = true
        assertTrue(BossMeterService(api).run("not a link", listOf("#A")) is BossMeterOutcome.Failed)
        assertTrue(BossMeterService(api).run(LINK, emptyList()) is BossMeterOutcome.Failed)
        assertTrue(BossMeterService(api).run(LINK, List(MAX_HASHTAGS + 1) { "#t$it" }) is BossMeterOutcome.Failed)
        assertTrue(api.queries.isEmpty())
    }

    @Test
    fun aFailureSaysHowManyCallsWentThrough() = runTest {
        val api = FakeApi(emptyMap()).apply { failOn = "conversation_id:$ID #B" }
        val out = BossMeterService(api, pause = {}).run(LINK, listOf("#A", "#B")) as BossMeterOutcome.Failed
        assertEquals(2, out.callsMade)
        assertTrue(out.reason.contains("403") && out.reason.contains("2 call"))
    }

    @Test
    fun aLowercaseLWhereACapitalIBelongsIsFlaggedNotSilentlyChanged() {
        val fixes = lookalikeFixes(listOf("#KanikalsTheBoss", "#AamrapalilsTheBoss", "#KanikaIsTheBoss", "#CoolBoss", "#Hello"))
        assertEquals(mapOf("#KanikalsTheBoss" to "#KanikaIsTheBoss", "#AamrapalilsTheBoss" to "#AamrapaliIsTheBoss"), fixes)
        assertTrue(lookalikeFixes(listOf("#QaziIsTheBoss")).isEmpty())
        // the list the user typed is kept as typed
        assertEquals(listOf("#KanikalsTheBoss"), hashtagsFrom("KanikalsTheBoss"))
    }

    @Test
    fun costIsOneCallPerHashtagPlusTheThread() {
        assertEquals(0.02, bossMeterCostUsd(3), 1e-9)
    }

    @Test
    fun theShareCaptionAlwaysFitsInOnePost() {
        val many = BossMeterResult(ID, 99_999, List(20) { TagCount("#SomeVeryLongHashtagNumber$it", 1000L - it) }, 0)
        val c = bossMeterShare(many, "16 Oct 2026, 9:00 PM")
        assertTrue(xLength(c.caption) <= X_POST_LIMIT)
        assertTrue(c.caption.contains("more"))
        assertTrue(c.caption.contains("X API"))
        val few = bossMeterShare(BossMeterResult(ID, 10, listOf(TagCount("#A", 6), TagCount("#B", 3)), 0), "today")
        assertTrue(few.caption.contains("#A: 6 (66.7%)"))
        assertEquals("a\n\nb\n\nc", fitCaption("a", listOf("b"), "c"))
    }

    @Test
    fun theHashtagsOfAPostAreReadInOrderWithoutRepeats() {
        val json = """{"data":{"id":"1","text":"x","entities":{"hashtags":[{"tag":"BiggBoss20"},{"tag":"KanikaMann"},{"tag":"kanikamann"},{"tag":"AmrapaliDubey"}]}}}"""
        assertEquals(listOf("#BiggBoss20", "#KanikaMann", "#AmrapaliDubey"), hashtagsFromPost(json))
    }

    @Test
    fun aLongPostUsesTheFullHashtagsOfItsNoteTweet() {
        val json = """{"data":{"id":"1","text":"cut","entities":{"hashtags":[{"tag":"Short"}]},"note_tweet":{"text":"long","entities":{"hashtags":[{"tag":"A"},{"tag":"B"},{"tag":"C"}]}}}}"""
        assertEquals(listOf("#A", "#B", "#C"), hashtagsFromPost(json))
    }

    @Test
    fun aPostWithoutHashtagsOrData_givesNothing() {
        assertEquals(emptyList<String>(), hashtagsFromPost("""{"data":{"id":"1","text":"x"}}"""))
        assertEquals(emptyList<String>(), hashtagsFromPost("""{"errors":[{"title":"Not Found"}]}"""))
        assertThrows(BossMeterException::class.java) { hashtagsFromPost("<html>") }
    }

    @Test
    fun showTagsAreDroppedInAnyCase() {
        assertEquals(listOf("#KanikaMann", "#AmrapaliDubey"), voteTags(listOf("#BiggBoss20", "#bb20", "#KanikaMann", "#BiggBoss", "#AmrapaliDubey")))
    }

    @Test
    fun readingAPostReturnsItsVoteTagsAndFailuresAreMessages() = runTest {
        val api = FakeApi(emptyMap()).apply { postJson = """{"data":{"entities":{"hashtags":[{"tag":"BiggBoss20"},{"tag":"KanikaMann"}]}}}""" }
        val service = BossMeterService(api, nowMs = { 1_000_000L }, pause = {})
        assertEquals(listOf("#KanikaMann"), service.readHashtags(LINK).getOrThrow())
        assertEquals(1, api.postCalls)
        assertTrue(service.readHashtags("nonsense").isFailure)
        api.configured = false
        assertTrue(service.readHashtags(LINK).exceptionOrNull()!!.message!!.contains("local.properties"))
    }
}
