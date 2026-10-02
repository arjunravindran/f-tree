package com.vibethroughcode.ftree.kutumb.geo

import kotlin.math.abs

enum class Zodiac { ARIES, TAURUS, GEMINI, CANCER, LEO, VIRGO, LIBRA, SCORPIO, SAGITTARIUS, CAPRICORN, AQUARIUS, PISCES }

/** What a fun fact needs of a person: whatever part of a birth date is known. Year is optional. */
data class BirthFacts(val year: Int? = null, val month: Int? = null, val day: Int? = null)

sealed interface FunFact {
    data class SameBirthMonth(val month: Int) : FunFact
    data class AgeGap(val years: Int) : FunFact
    data class SameZodiac(val sign: Zodiac) : FunFact

    /** Born close enough that "the same hometown" is fair. */
    data class BornNearby(val km: Double) : FunFact
}

/** Shared-ground facts between two people, computed only from what is recorded. */
object FunFacts {
    /** Birthplaces this close count as the same hometown. */
    const val NEARBY_KM = 50.0

    fun zodiac(month: Int, day: Int): Zodiac? {
        if (month !in 1..12 || day !in 1..31) return null
        // The first day of each sign, in calendar order starting from 1 January (Capricorn).
        val cusps = intArrayOf(20, 19, 21, 20, 21, 21, 23, 23, 23, 23, 22, 22)
        val signs = arrayOf(
            Zodiac.AQUARIUS, Zodiac.PISCES, Zodiac.ARIES, Zodiac.TAURUS, Zodiac.GEMINI, Zodiac.CANCER,
            Zodiac.LEO, Zodiac.VIRGO, Zodiac.LIBRA, Zodiac.SCORPIO, Zodiac.SAGITTARIUS, Zodiac.CAPRICORN,
        )
        val previous = arrayOf(
            Zodiac.CAPRICORN, Zodiac.AQUARIUS, Zodiac.PISCES, Zodiac.ARIES, Zodiac.TAURUS, Zodiac.GEMINI,
            Zodiac.CANCER, Zodiac.LEO, Zodiac.VIRGO, Zodiac.LIBRA, Zodiac.SCORPIO, Zodiac.SAGITTARIUS,
        )
        return if (day >= cusps[month - 1]) signs[month - 1] else previous[month - 1]
    }

    /**
     * Facts that hold for both. Birth month and zodiac need a month (and a day, for the sign) on both
     * sides; the age gap needs a year on both, and is left out when it is zero.
     * [birthplaceKm] is the distance between their birthplaces, when both are known and shared.
     */
    fun between(a: BirthFacts, b: BirthFacts, birthplaceKm: Double? = null): List<FunFact> = buildList {
        if (a.month != null && a.month == b.month) add(FunFact.SameBirthMonth(a.month))
        if (a.year != null && b.year != null && a.year != b.year) add(FunFact.AgeGap(abs(a.year - b.year)))
        val za = if (a.month != null && a.day != null) zodiac(a.month, a.day) else null
        val zb = if (b.month != null && b.day != null) zodiac(b.month, b.day) else null
        if (za != null && za == zb) add(FunFact.SameZodiac(za))
        if (birthplaceKm != null && birthplaceKm <= NEARBY_KM) add(FunFact.BornNearby(birthplaceKm))
    }
}
