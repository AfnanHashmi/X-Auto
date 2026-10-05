package com.clipcut.domain.gcprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrunePlannerTest {
    private val members = setOf("1", "2", "3", "4", "ME", "ADMIN", "DK")
    private val admins = setOf("ME", "ADMIN")
    private val own = setOf("ME", "DK", "MAIN")

    private fun found(id: String, handle: String = "h$id", follows: Boolean = false) =
        NameLookup(handle, FoundUser(id, handle, "Name $id", follows), null)

    private fun classify(lookups: List<NameLookup>, whitelist: Set<String> = emptySet()) =
        PrunePlanner.classify(lookups, members, admins, own, whitelist)

    private fun List<PrunePlanner.Row>.of(id: String) = first { it.userId == id }.status

    @Test
    fun aMemberWhoDoesNotFollowIsRemovable() {
        assertEquals(PrunePlanner.Status.Removable, classify(listOf(found("1"))).of("1"))
    }

    @Test
    fun aMemberWhoFollowsIsFlaggedNotRemovable() {
        assertEquals(PrunePlanner.Status.FollowsYou, classify(listOf(found("1", follows = true))).of("1"))
    }

    @Test
    fun aFoundAccountOutsideTheGroupCannotBeRemoved() {
        val row = classify(listOf(found("99"))).single()
        assertEquals(PrunePlanner.Status.NotInGroup, row.status)
        assertFalse(row.selectable)
    }

    @Test
    fun anAccountXCouldNotFindHasNoIdAndCannotBeRemoved() {
        val row = classify(listOf(NameLookup("ghost", null, "no such user"))).single()
        assertNull(row.userId)
        assertTrue(row.status is PrunePlanner.Status.NotFound)
        assertFalse(row.selectable)
    }

    @Test
    fun ownAccountsAdminsAndTheWhitelistAreProtectedWhetherOrNotTheyFollow() {
        val rows = classify(
            listOf(found("ME"), found("DK", follows = true), found("ADMIN"), found("2", follows = true), found("3")),
            whitelist = setOf("2", "3"),
        )
        assertEquals(PrunePlanner.Status.Protected("one of your own accounts"), rows.of("ME"))
        assertEquals(PrunePlanner.Status.Protected("one of your own accounts"), rows.of("DK"))
        assertEquals(PrunePlanner.Status.Protected("a group admin"), rows.of("ADMIN"))
        assertEquals(PrunePlanner.Status.Protected("on your whitelist"), rows.of("2"))
        assertEquals(PrunePlanner.Status.Protected("on your whitelist"), rows.of("3"))
        assertTrue(rows.none { it.selectable })
    }

    @Test
    fun protectionWinsOverNotBeingInTheGroup() {
        // Your own account is protected even when it is not in this group, so it can never read as removable.
        assertEquals(PrunePlanner.Status.Protected("one of your own accounts"), classify(listOf(found("MAIN"))).of("MAIN"))
    }

    @Test
    fun twoNamesForTheSameAccountGiveOneRow() {
        val rows = classify(listOf(found("1", "old_name"), found("1", "new_name")))
        assertEquals(1, rows.size)
        assertEquals("old_name", rows.single().requested)
    }

    @Test
    fun rowsKeepTheOrderTheNamesWerePasted() {
        val rows = classify(listOf(found("3"), NameLookup("ghost", null, "x"), found("1")))
        assertEquals(listOf("h3", "ghost", "h1"), rows.map { it.requested })
    }

    @Test
    fun onlyRemovableAndFollowsYouRowsCanBeTicked() {
        val rows = classify(listOf(found("1"), found("2", follows = true), found("99"), found("ADMIN")))
        assertEquals(listOf("1", "2"), rows.filter { it.selectable }.mapNotNull { it.userId })
    }

    @Test
    fun isProtectedCoversEachKind() {
        assertTrue(PrunePlanner.isProtected("ME", admins, own, emptySet()))
        assertTrue(PrunePlanner.isProtected("ADMIN", admins, own, emptySet()))
        assertTrue(PrunePlanner.isProtected("7", admins, own, setOf("7")))
        assertFalse(PrunePlanner.isProtected("1", admins, own, setOf("7")))
    }

    @Test
    fun theAccountDoingTheRemovingCanNeverBeTheOneRemoved() {
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { requireNotRemover("9000", "9000") }
        requireNotRemover("19", "9000") // anyone else is fine
    }
}
