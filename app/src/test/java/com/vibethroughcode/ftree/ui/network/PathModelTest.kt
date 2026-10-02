package com.vibethroughcode.ftree.ui.network

import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.graph.FamilySnapshot
import com.vibethroughcode.ftree.kutumb.geo.BirthFacts
import com.vibethroughcode.ftree.kutumb.geo.FunFact
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.kutumb.geo.LocationSource
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.geo.Zodiac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathModelTest {

    private fun person(id: String, birth: String? = null, gender: Gender = Gender.UNSPECIFIED) =
        Person(id = id, name = id.replaceFirstChar { it.uppercase() }, birthDate = birth, gender = gender)

    // me -- mum -- nani -- asha -- devika: devika is my mother's sister's daughter.
    private val snapshot = FamilySnapshot(
        people = listOf(
            person("me", "1990-03-22"), person("mum", gender = Gender.FEMALE), person("nani", gender = Gender.FEMALE),
            person("asha", gender = Gender.FEMALE), person("devika", "1976-03-30", Gender.FEMALE), person("stranger"),
        ).associateBy { it.id },
        parentEdges = listOf("mum" to "me", "nani" to "mum", "nani" to "asha", "asha" to "devika"),
        spouseEdges = emptyList(),
        siblingEdges = emptyList(),
    )

    private fun loc(id: String, lat: Double, lon: Double, label: LocationLabel = LocationLabel.CURRENT, share: Boolean = true) =
        PersonLocation(id, label, lat, lon, LocationSource.MANUAL, 1, share)

    @Test
    fun birthFactsReadWhateverPartOfTheDateIsKnown() {
        assertEquals(BirthFacts(1938, 4, 17), PathModel.birthFacts(person("a", "1938-04-17")))
        assertEquals(BirthFacts(1938, 4, null), PathModel.birthFacts(person("a", "1938-04")))
        assertEquals(BirthFacts(1938, null, null), PathModel.birthFacts(person("a", "1938")))
        assertEquals(BirthFacts(null, 4, 17), PathModel.birthFacts(person("a", "--04-17")))
        assertEquals(BirthFacts(), PathModel.birthFacts(person("a", null)))
    }

    @Test
    fun thePathListsEveryoneBetweenTheTwoEnds() {
        val ui = PathModel.build("me", "devika", snapshot, emptyList())!!
        assertEquals("me", ui.people.first().id)
        assertEquals("devika", ui.people.last().id)
        assertNotNull(ui.relation)
        assertTrue(ui.people.size >= 3)
    }

    @Test
    fun factsAndDistanceComeFromTheRecordAndTheSharedLocations() {
        val locations = listOf(loc("me", 51.5074, -0.1278), loc("devika", 48.8566, 2.3522))
        val ui = PathModel.build("me", "devika", snapshot, locations)!!
        assertEquals(344.0, ui.distanceKm!!, 2.0)
        assertEquals(listOf(FunFact.SameBirthMonth(3), FunFact.AgeGap(14), FunFact.SameZodiac(Zodiac.ARIES)), ui.facts)
    }

    @Test
    fun aPersonWhoStopsSharingTakesTheDistanceAndHometownWithThem() {
        val locations = listOf(
            loc("me", 51.5, -0.1), loc("devika", 48.9, 2.35, share = false),
            loc("me", 13.08, 80.27, LocationLabel.BIRTHPLACE), loc("devika", 13.0, 80.2, LocationLabel.BIRTHPLACE, share = false),
        )
        val ui = PathModel.build("me", "devika", snapshot, locations)!!
        assertNull(ui.distanceKm)
        assertTrue(ui.facts.none { it is FunFact.BornNearby })
    }

    @Test
    fun sharedBirthplacesCloseTogetherMakeAHometownFact() {
        val locations = listOf(loc("me", 13.08, 80.27, LocationLabel.BIRTHPLACE), loc("devika", 13.0, 80.2, LocationLabel.BIRTHPLACE))
        assertTrue(PathModel.build("me", "devika", snapshot, locations)!!.facts.any { it is FunFact.BornNearby })
    }

    @Test
    fun anUnconnectedRelativeStillGetsAScreenWithoutARelation() {
        val ui = PathModel.build("me", "stranger", snapshot, emptyList())!!
        assertNull(ui.relation)
        assertEquals(listOf("me", "stranger"), ui.people.map { it.id })
    }

    @Test
    fun nobodyOutsideTheTreeHasAPath() {
        assertNull(PathModel.build("me", "ghost", snapshot, emptyList()))
        assertNull(PathModel.build("ghost", "me", snapshot, emptyList()))
    }
}
