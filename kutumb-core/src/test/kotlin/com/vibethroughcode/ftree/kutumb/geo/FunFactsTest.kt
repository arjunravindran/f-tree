package com.vibethroughcode.ftree.kutumb.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunFactsTest {

    @Test
    fun zodiacBoundariesFallOnTheRightSide() {
        assertEquals(Zodiac.CAPRICORN, FunFacts.zodiac(1, 19))
        assertEquals(Zodiac.AQUARIUS, FunFacts.zodiac(1, 20))
        assertEquals(Zodiac.PISCES, FunFacts.zodiac(2, 19))
        assertEquals(Zodiac.PISCES, FunFacts.zodiac(3, 20))
        assertEquals(Zodiac.ARIES, FunFacts.zodiac(3, 21))
        assertEquals(Zodiac.LEO, FunFacts.zodiac(8, 22))
        assertEquals(Zodiac.VIRGO, FunFacts.zodiac(8, 23))
        assertEquals(Zodiac.SAGITTARIUS, FunFacts.zodiac(12, 21))
        assertEquals(Zodiac.CAPRICORN, FunFacts.zodiac(12, 22))
        assertEquals(Zodiac.CAPRICORN, FunFacts.zodiac(12, 31))
        assertNull(FunFacts.zodiac(13, 1))
    }

    @Test
    fun everyDayOfTheYearHasASign() {
        for (m in 1..12) for (d in 1..28) assertTrue("$m-$d", FunFacts.zodiac(m, d) != null)
    }

    @Test
    fun sharedMonthAgeGapAndSignAreAllReported() {
        val facts = FunFacts.between(BirthFacts(1990, 3, 22), BirthFacts(1976, 3, 30))
        assertEquals(
            listOf(FunFact.SameBirthMonth(3), FunFact.AgeGap(14), FunFact.SameZodiac(Zodiac.ARIES)),
            facts,
        )
    }

    @Test
    fun onlyWhatIsRecordedOnBothSidesIsClaimed() {
        // No year: no age gap. No day: no sign. The month alone still matches.
        assertEquals(listOf(FunFact.SameBirthMonth(3)), FunFacts.between(BirthFacts(month = 3), BirthFacts(1980, 3, 5)))
        assertTrue(FunFacts.between(BirthFacts(), BirthFacts(1980, 3, 5)).isEmpty())
        // Born the same year says nothing about an age gap.
        assertTrue(FunFacts.between(BirthFacts(year = 1980), BirthFacts(year = 1980)).isEmpty())
    }

    @Test
    fun hometownMeansBornWithinFiftyKilometres() {
        assertEquals(listOf(FunFact.BornNearby(12.0)), FunFacts.between(BirthFacts(), BirthFacts(), birthplaceKm = 12.0))
        assertEquals(listOf(FunFact.BornNearby(50.0)), FunFacts.between(BirthFacts(), BirthFacts(), birthplaceKm = 50.0))
        assertTrue(FunFacts.between(BirthFacts(), BirthFacts(), birthplaceKm = 50.1).isEmpty())
        assertTrue(FunFacts.between(BirthFacts(), BirthFacts(), birthplaceKm = null).isEmpty())
    }
}
