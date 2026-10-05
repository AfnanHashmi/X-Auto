package com.clipcut.domain.gcprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrunePlannerTest {
    private val members = (1..20).map { it.toString() }

    private fun input(
        members: List<String> = this.members,
        admins: Set<String> = emptySet(),
        followers: Set<String> = (1..15).map { it.toString() }.toSet(),
        reported: Int = followers.size,
        own: Set<String> = emptySet(),
        whitelist: Set<String> = emptySet(),
    ) = PrunePlanner.Input(members, admins, followers, reported, own, whitelist)

    private fun ready(i: PrunePlanner.Input) = PrunePlanner.plan(i) as PrunePlanner.Result.Ready

    @Test
    fun candidatesAreExactlyTheMembersWhoDoNotFollow() {
        assertEquals(listOf("16", "17", "18", "19", "20"), ready(input()).candidates)
    }

    @Test
    fun aFollowerIsNeverACandidate() {
        val r = ready(input(followers = (1..20).map { it.toString() }.toSet()))
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun youAreNeverACandidateEvenIfYouDoNotFollowYourself() {
        val r = ready(input(own = setOf("16", "99")))
        assertFalse("16" in r.candidates)
        assertEquals(1, r.exempt.own)
    }

    @Test
    fun adminsAreNeverCandidates() {
        val r = ready(input(admins = setOf("17", "18")))
        assertFalse("17" in r.candidates || "18" in r.candidates)
        assertEquals(2, r.exempt.admins)
    }

    @Test
    fun whitelistedPeopleAreNeverCandidates() {
        val r = ready(input(whitelist = setOf("19")))
        assertFalse("19" in r.candidates)
        assertEquals(1, r.exempt.whitelisted)
    }

    @Test
    fun theProtectionsStackWithoutDoubleCounting() {
        val r = ready(input(own = setOf("16"), admins = setOf("16", "17"), whitelist = setOf("17", "18")))
        assertEquals(listOf("19", "20"), r.candidates)
        // 16 is own and an admin (counted once, as own); 17 is an admin and whitelisted (counted once, as an admin); 18 is whitelisted.
        assertEquals(PrunePlanner.Exempt(own = 1, admins = 1, whitelisted = 1), r.exempt)
    }

    @Test
    fun aDuplicatedMemberIsOnlyConsideredOnce() {
        val r = ready(input(members = members + listOf("20", "20")))
        assertEquals(1, r.candidates.count { it == "20" })
    }

    @Test
    fun anEmptyGroupAborts() {
        assertTrue(PrunePlanner.plan(input(members = emptyList())) is PrunePlanner.Result.Abort)
    }

    // ---- fail closed ----------------------------------------------------------------------------------------
    @Test
    fun aFollowerListThatFallsShortOfWhatXReportsAborts() {
        // 15 fetched against 100 reported: a partial list would wrongly flag real followers.
        val r = PrunePlanner.plan(input(reported = 100))
        assertTrue(r is PrunePlanner.Result.Abort)
    }

    @Test
    fun theCoverageThresholdIsNinetySevenPercentOfWhatXReports() {
        val followers = (1..97).map { "f$it" }.toSet() + (1..15).map { it.toString() }
        // 112 fetched. Reported 115 -> 97.4%: allowed. Reported 116 -> 96.6%: aborts.
        assertTrue(PrunePlanner.plan(input(followers = followers, reported = 115)) is PrunePlanner.Result.Ready)
        assertTrue(PrunePlanner.plan(input(followers = followers, reported = 116)) is PrunePlanner.Result.Abort)
    }

    @Test
    fun anEmptyFollowerListAbortsWhenXReportsFollowers() {
        assertTrue(PrunePlanner.plan(input(followers = emptySet(), reported = 500)) is PrunePlanner.Result.Abort)
    }

    @Test
    fun aReportedCountOfZeroAborts() {
        assertTrue(PrunePlanner.plan(input(reported = 0)) is PrunePlanner.Result.Abort)
    }

    @Test
    fun overHalfOfTheGroupFlaggedAborts() {
        // 5 followers of 20 members -> 15 candidates (75%).
        val r = PrunePlanner.plan(input(followers = (1..5).map { it.toString() }.toSet()))
        assertTrue(r is PrunePlanner.Result.Abort)
    }

    @Test
    fun exactlyHalfIsStillAllowedButOneMoreIsNot() {
        // 10 followers of 20 -> 10 candidates (exactly 50%): allowed.
        assertTrue(PrunePlanner.plan(input(followers = (1..10).map { it.toString() }.toSet())) is PrunePlanner.Result.Ready)
        // 9 followers -> 11 candidates (55%): aborts.
        assertTrue(PrunePlanner.plan(input(followers = (1..9).map { it.toString() }.toSet())) is PrunePlanner.Result.Abort)
    }

    @Test
    fun exemptPeopleDoNotCountTowardsTheHalfLimit() {
        // 10 followers; 10 non-followers of which 6 are admins -> only 4 candidates.
        val r = ready(input(followers = (1..10).map { it.toString() }.toSet(), admins = (11..16).map { it.toString() }.toSet()))
        assertEquals(4, r.candidates.size)
    }

    @Test
    fun isProtectedCoversEveryKindOfExemption() {
        assertTrue(PrunePlanner.isProtected("1", admins = setOf("1"), ownIds = emptySet(), whitelist = emptySet()))
        assertTrue(PrunePlanner.isProtected("1", admins = emptySet(), ownIds = setOf("1"), whitelist = emptySet()))
        assertTrue(PrunePlanner.isProtected("1", admins = emptySet(), ownIds = emptySet(), whitelist = setOf("1")))
        assertFalse(PrunePlanner.isProtected("1", admins = setOf("2"), ownIds = setOf("3"), whitelist = setOf("4")))
    }
}
