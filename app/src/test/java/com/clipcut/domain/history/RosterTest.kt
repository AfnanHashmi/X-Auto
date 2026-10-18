package com.clipcut.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun who(text: String) = rosterMatch(text)?.name

class RosterTest {
    @Test
    fun everyOfficialHashtagAndFullNameFindsItsContestant() {
        ROSTER.forEach { c ->
            assertEquals(c.name, who(c.hashtag)) // "#KanikaMann"
            assertEquals(c.name, who(c.hashtag.removePrefix("#").lowercase())) // "kanikamann"
            assertEquals(c.name, who(c.name.substringBefore(" (").uppercase())) // "KANIKA MANN"
        }
        assertEquals(18, ROSTER.size); assertEquals(18, ROSTER.map { it.hashtag.lowercase() }.distinct().size)
    }

    @Test
    fun yourRealTaglinesAndBossMeterHashtags() {
        assertEquals("Qazi Touqeer", who("QAZI THE POWERHOUSE OF BB20"))
        assertEquals("Qazi Touqeer", who("STOP NEGATIVE PR AGAINST QAZI"))
        assertEquals("Qazi Touqeer", who("QAZI BORN TO SHINE"))
        assertEquals("Nakul Dhull", who("WE WANT NAKUL BACK"))
        assertEquals("Nakul Dhull", who("NO NAKUL NO BB"))
        assertEquals("Uditi Singh", who("UDITI STANDS BY HER PEOPLE"))
        assertEquals("Kanika Mann", who("#KanikaIsTheBoss"))
        assertEquals("Amrapali Dubey", who("#AamrapaliIsTheBoss")) // the spelling used in Boss Meter
        assertEquals("Kushal Tanwar (Gullu)", who("#Gullu"))
        assertEquals("Kushal Tanwar (Gullu)", who("GULLU ROCKS"))
        assertEquals("Qazi Touqeer", who("#QaziTouqeerForever")) // official hashtag inside a longer one
    }

    @Test
    fun ambiguousOrPlainWordsDoNotMatchByThemselves() {
        assertNull(who("KHAN FOR THE WIN")) // three contestants are Khan
        assertNull(who("LOVE FOR THE SHOW")) // "Love" alone is a plain word
        assertNull(who("MARY HAD A LITTLE LAMB"))
        assertNull(who("WE WANT JUSTICE"))
        assertEquals("Love Gill", who("#LoveGill")); assertEquals("Mary Kom", who("MARY KOM FANS"))
        assertEquals("Yung DSA", who("#YungDSA")); assertEquals("ScoutOP", who("#ScoutOP"))
    }
}
