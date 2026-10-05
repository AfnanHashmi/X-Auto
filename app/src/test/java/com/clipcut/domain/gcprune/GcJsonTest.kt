package com.clipcut.domain.gcprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- looking up @handles ---------------------------------------------------------------------------------
    private val ana = """{"id":"1","username":"Ana","name":"Ana A","connection_status":["followed_by"]}"""
    private val bo = """{"id":"2","username":"bo","name":"Bo B"}"""

    @Test
    fun aFoundAccountWithFollowedByFollowsYou() {
        val r = parseUsersByName("""{"data":[$ana]}""", listOf("ana"))
        assertEquals(FoundUser("1", "Ana", "Ana A", followsMain = true), r.single().found)
        assertEquals("ana", r.single().requested) // keyed by what was asked, whatever the capitalisation
    }

    @Test
    fun anAbsentConnectionStatusIsHowXReportsAStrangerAndCountsAsNotFollowing() {
        // Measured against the real API: a non-follower comes back with no connection_status at all.
        assertFalse(parseUsersByName("""{"data":[$bo]}""", listOf("bo")).single().found!!.followsMain)
        val following = """{"data":[{"id":"2","username":"bo","name":"B","connection_status":["following"]}]}"""
        assertFalse(parseUsersByName(following, listOf("bo")).single().found!!.followsMain)
    }

    @Test
    fun everyNameAskedAboutComesBackOnceInTheOrderAsked() {
        val body = """{"data":[$bo,$ana]}"""
        assertEquals(listOf("ana", "bo"), parseUsersByName(body, listOf("ana", "bo")).map { it.requested })
        assertEquals(listOf("1", "2"), parseUsersByName(body, listOf("ana", "bo")).map { it.found!!.id })
    }

    @Test
    fun aNameXCouldNotFindComesBackNotFoundWithXsReason() {
        val body = """{"data":[$ana],"errors":[{"detail":"Could not find user with usernames: [ghost].","value":"ghost","resource_id":"ghost","title":"Not Found Error"}]}"""
        val r = parseUsersByName(body, listOf("ana", "ghost"))
        assertNull(r[1].found)
        assertTrue(r[1].notFoundReason!!.contains("Could not find"))
        assertTrue(r[0].found != null)
    }

    @Test
    fun everyNameCanBeNotFoundWithNoDataAtAll() {
        val body = """{"errors":[{"detail":"d","value":"a","title":"Not Found Error"},{"detail":"d","value":"b","title":"Not Found Error"}]}"""
        assertTrue(parseUsersByName(body, listOf("a", "b")).all { it.found == null })
    }

    @Test
    fun aNameXSaidNothingAboutIsNotFoundNeverGuessed() {
        val r = parseUsersByName("""{"data":[$ana]}""", listOf("ana", "silent"))
        assertNull(r[1].found)
        assertTrue(r[1].notFoundReason!!.contains("no answer"))
    }

    @Test
    fun anAnswerAboutAnAccountNobodyAskedAboutFailsTheWholeLookup() {
        assertThrows(GcParseException::class.java) { parseUsersByName("""{"data":[$bo]}""", listOf("ana")) }
    }

    @Test
    fun anErrorThatNamesNoneOfTheAskedHandlesFailsTheWholeLookup() {
        assertThrows(GcParseException::class.java) {
            parseUsersByName("""{"data":[$ana],"errors":[{"title":"Forbidden","detail":"nope"}]}""", listOf("ana"))
        }
        assertThrows(GcParseException::class.java) { parseUsersByName("""{"errors":[{"title":"Unauthorized"}]}""", listOf("ana")) }
    }

    @Test
    fun anAccountWithoutAValidIdOrGarbageFailsTheLookup() {
        assertThrows(GcParseException::class.java) {
            parseUsersByName("""{"data":[{"id":"abc","username":"ana","name":"A"}]}""", listOf("ana"))
        }
        assertThrows(GcParseException::class.java) { parseUsersByName("not json", listOf("ana")) }
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
    fun readsAnAccount() {
        assertEquals(SelfInfo("5", "me"), parseSelf("""{"data":{"id":"5","username":"me"}}"""))
    }

    @Test
    fun anAccountWithoutAValidIdIsRejected() {
        assertThrows(GcParseException::class.java) { parseSelf("""{"data":{"id":"x","username":"me"}}""") }
        assertThrows(GcParseException::class.java) { parseSelf("""{"errors":[{"title":"x"}]}""") }
    }
}
