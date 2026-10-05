package com.clipcut.domain.gcprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GcJsonTest {
    // ---- the group ------------------------------------------------------------------------------------
    private fun conversation(id: String = "g1", extra: String = "") =
        """{"data":{"id":"$id","type":"group","member_ids":["11","22","33"],"admin_ids":["11"]}$extra}"""

    @Test
    fun readsMembersAndAdmins() {
        val c = parseConversation(conversation(), "g1")
        assertEquals(listOf("11", "22", "33"), c.memberIds)
        assertEquals(listOf("11"), c.adminIds)
        assertEquals("group", c.type)
    }

    @Test
    fun aReplyAboutADifferentConversationIsRejected() {
        assertThrows(GcParseException::class.java) { parseConversation(conversation(id = "g999"), "g1") }
    }

    @Test
    fun aMissingMemberOrAdminListIsAnErrorNotAnEmptyGroup() {
        assertThrows(GcParseException::class.java) {
            parseConversation("""{"data":{"id":"g1","type":"group","admin_ids":["11"]}}""", "g1")
        }
        assertThrows(GcParseException::class.java) {
            parseConversation("""{"data":{"id":"g1","type":"group","member_ids":["11"]}}""", "g1")
        }
    }

    @Test
    fun anErrorsArrayAlwaysFailsEvenNextToData() {
        assertThrows(GcParseException::class.java) {
            parseConversation(conversation(extra = ""","errors":[{"title":"Forbidden","detail":"nope"}]"""), "g1")
        }
    }

    @Test
    fun aMemberIdThatIsNotNumericIsRejected() {
        assertThrows(GcParseException::class.java) {
            parseConversation("""{"data":{"id":"g1","type":"group","member_ids":["11","abc"],"admin_ids":[]}}""", "g1")
        }
    }

    // ---- followers ---------------------------------------------------------------------------------
    @Test
    fun readsAFollowerPageAndItsNextToken() {
        val p = parseFollowersPage("""{"data":[{"id":"1"},{"id":"2"}],"meta":{"result_count":2,"next_token":"abc"}}""")
        assertEquals(listOf("1", "2"), p.ids)
        assertEquals("abc", p.nextToken)
    }

    @Test
    fun theLastPageHasNoNextToken() {
        val p = parseFollowersPage("""{"data":[{"id":"1"}],"meta":{"result_count":1}}""")
        assertNull(p.nextToken)
    }

    @Test
    fun anEmptyEndOfListIsOnlyAcceptedWhenXSaysZero() {
        assertTrue(parseFollowersPage("""{"meta":{"result_count":0}}""").ids.isEmpty())
        assertThrows(GcParseException::class.java) { parseFollowersPage("""{"meta":{"result_count":5}}""") }
        assertThrows(GcParseException::class.java) { parseFollowersPage("""{}""") }
    }

    @Test
    fun aPageShorterThanItClaimsIsRejectedBecauseAShortListFlagsRealFollowers() {
        assertThrows(GcParseException::class.java) {
            parseFollowersPage("""{"data":[{"id":"1"}],"meta":{"result_count":3}}""")
        }
    }

    @Test
    fun aFollowerPageWithErrorsOrBadIdsIsRejected() {
        assertThrows(GcParseException::class.java) {
            parseFollowersPage("""{"data":[{"id":"1"}],"meta":{"result_count":1},"errors":[{"title":"x"}]}""")
        }
        assertThrows(GcParseException::class.java) {
            parseFollowersPage("""{"data":[{"id":"x1"}],"meta":{"result_count":1}}""")
        }
        assertThrows(GcParseException::class.java) { parseFollowersPage("not json") }
    }

    // ---- profiles ----------------------------------------------------------------------------------
    @Test
    fun missingAccountsAreSimplyAbsentFromALookup() {
        val users = parseUsers("""{"data":[{"id":"1","username":"a","name":"A"}],"errors":[{"title":"Not Found Error"}]}""")
        assertEquals(listOf(GcUserInfo("1", "a", "A")), users)
        assertTrue(parseUsers("""{"errors":[{"title":"Not Found Error"}]}""").isEmpty())
    }

    // ---- the per-person follow check ---------------------------------------------------------------------
    @Test
    fun followedByMeansTheyFollow() {
        val body = """{"data":{"id":"7","connection_status":["followed_by"]}}"""
        assertEquals(FollowCheck.Follows, parseFollowCheck(body, "7"))
        assertEquals(
            FollowCheck.Follows,
            parseFollowCheck("""{"data":{"id":"7","connection_status":["following","followed_by"]}}""", "7"),
        )
    }

    @Test
    fun anAbsentFieldIsHowXReportsAStrangerAndCountsAsNotFollowing() {
        // Measured against the real API: a non-follower comes back with no connection_status at all.
        assertEquals(FollowCheck.NotFollowing, parseFollowCheck("""{"data":{"id":"7","name":"x"}}""", "7"))
        assertEquals(FollowCheck.NotFollowing, parseFollowCheck("""{"data":{"id":"7","connection_status":["following"]}}""", "7"))
    }

    @Test
    fun anythingXDidNotPositivelyAnswerIsUnverifiableNeverNotFollowing() {
        assertTrue(parseFollowCheck("""{"errors":[{"title":"Not Found Error"}]}""", "7") is FollowCheck.Unverifiable)
        assertTrue(parseFollowCheck("""{"data":{"id":"8"}}""", "7") is FollowCheck.Unverifiable) // someone else
        assertTrue(parseFollowCheck("""{"data":{"id":"7"},"errors":[{"title":"x"}]}""", "7") is FollowCheck.Unverifiable)
        assertTrue(parseFollowCheck("garbage", "7") is FollowCheck.Unverifiable)
        assertTrue(parseFollowCheck("{}", "7") is FollowCheck.Unverifiable)
    }

    // ---- the removal reply ----------------------------------------------------------------------------------
    @Test
    fun onlyRemovedTrueWithNoErrorsCountsAsRemoved() {
        assertEquals(RemoveOutcome.Removed, parseRemoveResponse("""{"data":{"removed":true}}"""))
        assertTrue(parseRemoveResponse("""{"data":{"removed":false}}""") is RemoveOutcome.NotRemoved)
        assertTrue(parseRemoveResponse("""{"data":{}}""") is RemoveOutcome.NotRemoved)
        assertTrue(parseRemoveResponse("""{}""") is RemoveOutcome.NotRemoved)
        assertTrue(parseRemoveResponse("nonsense") is RemoveOutcome.NotRemoved)
        assertTrue(parseRemoveResponse("""{"data":{"removed":true},"errors":[{"title":"partial"}]}""") is RemoveOutcome.NotRemoved)
    }

    // ---- own account -------------------------------------------------------------------------------------------
    @Test
    fun readsAnAccountAndItsFollowerCount() {
        val me = parseSelf("""{"data":{"id":"5","username":"me","public_metrics":{"followers_count":24027}}}""")
        assertEquals(SelfInfo("5", "me", 24027), me)
        assertNull(parseSelf("""{"data":{"id":"5","username":"me"}}""").followersCount)
    }
}
