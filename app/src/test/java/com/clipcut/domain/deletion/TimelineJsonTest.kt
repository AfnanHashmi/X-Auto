package com.clipcut.domain.deletion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineJsonTest {
    @Test
    fun parsesPostsAndTheNextToken() {
        val page = parseTimelinePage(
            """{"data":[
                {"id":"1","text":"BB LOGS - 012 https://t.co/a","created_at":"2026-09-19T16:35:27.000Z"},
                {"id":"2","text":"BB LOGS - 011 https://t.co/b","created_at":"2026-09-19T16:20:11.000Z"}],
               "meta":{"result_count":2,"next_token":"abc123"}}""",
        )
        assertEquals(listOf("1", "2"), page.posts.map { it.id })
        assertEquals(Instant.parse("2026-09-19T16:35:27Z"), page.posts[0].createdAt)
        assertEquals("abc123", page.nextToken)
        assertFalse(page.posts[0].isRetweet)
    }

    @Test
    fun anEmptyWindowHasNoDataKeyAndIsNotAnError() {
        val page = parseTimelinePage("""{"meta":{"result_count":0}}""")
        assertTrue(page.posts.isEmpty())
        assertNull(page.nextToken)
    }

    @Test
    fun onlyARepostIsFlaggedNotAReply() {
        val page = parseTimelinePage(
            """{"data":[{"id":"9","text":"x","created_at":"2026-09-19T16:35:27.000Z",
                "referenced_tweets":[{"type":"replied_to","id":"5"}]}]}""",
        )
        assertFalse(page.posts.single().isRetweet) // a reply is a post of the account's own and is deletable
    }

    @Test
    fun aPlainRepostIsFlagged() {
        val page = parseTimelinePage(
            """{"data":[{"id":"9","text":"RT @x: hi","created_at":"2026-09-19T16:35:27.000Z",
                "referenced_tweets":[{"type":"retweeted","id":"5"}]}]}""",
        )
        assertTrue(page.posts.single().isRetweet)
    }

    @Test
    fun aPostWithoutATimestampIsDroppedNotGuessed() {
        val page = parseTimelinePage("""{"data":[{"id":"9","text":"BB LOGS - 001"}]}""")
        assertTrue(page.posts.isEmpty())
    }
}
