package com.clipcut.domain.taglines

import com.clipcut.domain.llm.LlmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 24 * HOUR_MS
private const val NOW = 1_792_000_000_000L
private const val TAG = "QAZI RISING ABOVE HATE"

private fun author(id: String, name: String = "user$id", ageDays: Long = 900, followers: Long = 500, tweets: Long = 3000, defaultAvatar: Boolean = false, bio: String = "", location: String = "") =
    TcAuthor(id, name, "Name $id", NOW - ageDays * DAY, followers, 100, tweets, false, defaultAvatar, bio, location)

private fun post(id: String, by: String, text: String, type: PostType = PostType.ORIGINAL, likes: Long = 3, original: String? = null, originalId: String? = null, lang: String = "hi", hashtags: List<String> = emptyList()) =
    TcPost(id, by, NOW, text, lang, type, likes, 0, hashtags, emptyList(), false, original, originalId)

class TrendSampleTest {
    @Test
    fun aSearchReplyBecomesPostsWithTheirAuthorsAndRetweetedAuthors() {
        val body = """
        {"data":[
          {"id":"1","author_id":"10","created_at":"2026-10-20T14:30:00.000Z","text":"Qazi ! #BiggBoss20 @JioHotstar","lang":"hi",
           "public_metrics":{"like_count":4,"retweet_count":2},"entities":{"hashtags":[{"tag":"BiggBoss20"}],"mentions":[{"username":"JioHotstar"}]},
           "attachments":{"media_keys":["3_1"]}},
          {"id":"2","author_id":"11","created_at":"2026-10-20T14:31:00.000Z","text":"RT @boss: x","lang":"en",
           "referenced_tweets":[{"type":"retweeted","id":"99"}],"public_metrics":{"like_count":0,"retweet_count":0}}],
         "includes":{"users":[
           {"id":"10","username":"fan10","name":"Fan","created_at":"2026-10-01T00:00:00.000Z","public_metrics":{"followers_count":5,"following_count":900,"tweet_count":40},"profile_image_url":"https://pbs.twimg.com/profile_images/default_profile_normal.png","description":"Qazi FC","location":"Mumbai"},
           {"id":"11","username":"rt11","name":"R","public_metrics":{"followers_count":1,"following_count":1,"tweet_count":1}},
           {"id":"50","username":"boss","name":"Boss","public_metrics":{"followers_count":9000,"following_count":10,"tweet_count":900}}],
         "tweets":[{"id":"99","author_id":"50"}]},
         "meta":{"result_count":2,"next_token":"abc"}}
        """.trimIndent()
        val page = parseSearch(body)
        assertEquals(2, page.posts.size); assertEquals("abc", page.nextToken)
        val p = page.posts[0]
        assertEquals(listOf("biggboss20"), p.hashtags); assertEquals(listOf("jiohotstar"), p.mentions)
        assertTrue(p.hasMedia); assertEquals(4, p.likes); assertEquals(PostType.ORIGINAL, p.type)
        val rt = page.posts[1]
        assertEquals(PostType.RETWEET, rt.type); assertEquals("50", rt.original); assertEquals("99", rt.originalId)
        assertTrue(page.authors.getValue("10").defaultAvatar); assertEquals(5, page.authors.getValue("10").followers)
        assertEquals("boss", page.authors.getValue("50").username)
    }

    @Test
    fun anEmptyPageIsFineAndGarbageIsRefused() {
        assertTrue(parseSearch("""{"meta":{"result_count":0}}""").posts.isEmpty())
        assertThrows(TrendParseException::class.java) { parseSearch("<html>") }
        assertThrows(TrendParseException::class.java) { parseSearch("""{"data":[{"id":"1","author_id":"2","text":"x"}]}""") } // no time
    }

    @Test
    fun theTextComparedForCopyPasteIgnoresLinksMentionsHashtagsAndTheTagline() {
        assertEquals("support him always", normalizedText("$TAG!! Support him ALWAYS https://t.co/x @someone #BiggBoss20", TAG))
        assertEquals("", normalizedText("@a $TAG", TAG))
    }

    @Test
    fun accountFlagsNeedTwoSignalsToBeBotLike() {
        val brandNew = author("1", ageDays = 3, followers = 2)            // new + low reach
        val justNew = author("2", ageDays = 3, followers = 900, tweets = 20) // new only
        val grinder = author("3", ageDays = 100, followers = 4000, tweets = 100 * 300) // 300 a day only
        assertTrue(accountFlags(brandNew, NOW).botLike)
        assertEquals(1, accountFlags(justNew, NOW).count); assertFalse(accountFlags(justNew, NOW).botLike)
        assertTrue(accountFlags(grinder, NOW).extremeRate); assertFalse(accountFlags(grinder, NOW).botLike)
        assertFalse(accountFlags(null, NOW).botLike)
        assertTrue(accountFlags(author("4", defaultAvatar = true, ageDays = 5), NOW).botLike) // default picture counts as low reach
    }

    @Test
    fun copyPasteOnlyCountsTextPostedByThreeOrMoreDifferentAccounts() {
        val same = (1..4).map { post("c$it", "a$it", "Qazi has always been the real winner of this house $TAG") }
        val oneAccountSpamming = (1..3).map { post("s$it", "z", "Qazi has always been the real winner of this house again") } // only 1 account
        val unique = (1..4).map { post("u$it", "b$it", "completely different thought number $it about the game") }
        val bareTagline = (1..5).map { post("t$it", "d$it", TAG) } // just saying the tagline is the campaign, not copy-paste
        val authors = (listOf("z") + (1..4).flatMap { listOf("a$it", "b$it", "d$it") } + "d5").associateWith { author(it) }
        val s = sampleStats(same + oneAccountSpamming + unique + bareTagline, authors, TAG)!!
        // 4 of the 11 posts with real text are copies (the 3 from one account are not)
        assertEquals(36, s.copyPastePct)
    }

    @Test
    fun naturalIsWhatIsNeitherCopyPastedNorFromBotLikeAccounts() {
        val real = (1..6).map { post("r$it", "n$it", "my own words number $it about this contestant") }
        val bots = (1..4).map { post("b$it", "x$it", "word $it") }
        val authors = (1..6).associate { "n$it" to author("n$it") } + (1..4).associate { "x$it" to author("x$it", ageDays = 2, followers = 1) }
        val s = sampleStats(real + bots, authors, TAG)!!
        assertEquals(10, s.sample); assertEquals(40, s.botLikePct); assertEquals(60, s.naturalPct); assertEquals(40, s.newAccountPct)
    }

    @Test
    fun organizersAreTheAccountsWhoseOwnPostsWereRetweetedMost() {
        val authors = mapOf("1" to author("1"), "50" to author("50", name = "boss"), "51" to author("51", name = "other"))
        val posts = listOf(
            post("a", "1", "RT", PostType.RETWEET, original = "50", originalId = "p1"),
            post("b", "1", "RT", PostType.RETWEET, original = "50", originalId = "p1"),
            post("c", "1", "RT", PostType.RETWEET, original = "50", originalId = "p1"),
            post("d", "1", "RT", PostType.RETWEET, original = "51", originalId = "p2"),
            post("e", "1", "own words", hashtags = listOf("biggboss20", "qazirisingabovehate")),
        )
        val s = sampleStats(posts, authors, TAG)!!
        assertEquals(listOf("@boss" to 3, "@other" to 1), s.organizers)
        assertEquals(listOf("#biggboss20" to 1), s.pairedHashtags) // the tagline's own hashtag form is left out
    }

    @Test
    fun fanClubAndEngagementAndConcentration() {
        val authors = mapOf(
            "1" to author("1", bio = "Qazi FC | updates"), "2" to author("2", name = "plain"), "3" to author("3", name = "plain3"),
        )
        val posts = listOf(post("a", "1", "one two three four", likes = 0), post("b", "2", "five six seven eight", likes = 10), post("c", "3", "nine ten eleven twelve", likes = 0), post("d", "3", "again words here", likes = 4))
        val s = sampleStats(posts, authors, TAG)!!
        assertEquals(25, s.fanClubPct); assertEquals(50, s.zeroLikePct); assertEquals(4, s.medianLikes)
        assertEquals(100, s.top10AuthorsPct) // only 3 accounts at all
        assertNull(sampleStats(emptyList(), authors, TAG))
    }

    // ---- sentiment --------------------------------------------------------------------------------------------------

    @Test
    fun verdictsForUnknownPostsAndUnknownContestantsAreDroppedOrBlanked() {
        val json = """{"verdicts":[
            {"id":"1","about":"qazi jee","mood":"positive"},
            {"id":"2","about":"someone not listed","mood":"negative"},
            {"id":"zzz","about":"Nakul","mood":"negative"},
            {"id":"3","about":"Nakul","mood":"maybe"}]}"""
        val v = parseVerdicts(json, setOf("1", "2", "3"), listOf("Qazi Jee", "Nakul"))
        assertEquals(2, v.size)
        assertEquals("Qazi Jee", v[0].about); assertEquals(Mood.POSITIVE, v[0].mood)
        assertNull(v[1].about); assertEquals(Mood.NEGATIVE, v[1].mood) // judged, but about nobody on the list
        assertThrows(LlmException::class.java) { parseVerdicts("not json", setOf("1"), emptyList()) }
    }

    @Test
    fun aRetweetCountsWithTheVerdictOfThePostItRepeats() {
        val posts = listOf(
            post("1", "a", "love him"), post("2", "b", "hate him"),
            post("3", "c", "RT", PostType.RETWEET, original = "a", originalId = "1"),
            post("4", "d", "RT", PostType.RETWEET, original = "q", originalId = "not judged"),
        )
        val t = tallySentiment(posts, listOf(Verdict("1", "Qazi Jee", Mood.POSITIVE), Verdict("2", "Qazi Jee", Mood.NEGATIVE)), "OpenAI", 0.01)
        assertEquals(2, t.positive); assertEquals(1, t.negative); assertEquals(0, t.neutral); assertEquals(3, t.judged)
        assertEquals(Triple(2, 1, 0), t.byContestant.getValue("Qazi Jee"))
    }
}
