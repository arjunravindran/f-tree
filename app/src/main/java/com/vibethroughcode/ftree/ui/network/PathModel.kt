package com.vibethroughcode.ftree.ui.network

import com.vibethroughcode.ftree.data.PartialDate
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RecordedDate
import com.vibethroughcode.ftree.data.YearlessDate
import com.vibethroughcode.ftree.graph.FamilySnapshot
import com.vibethroughcode.ftree.graph.Kinship
import com.vibethroughcode.ftree.graph.Relation
import com.vibethroughcode.ftree.kutumb.geo.BirthFacts
import com.vibethroughcode.ftree.kutumb.geo.FunFact
import com.vibethroughcode.ftree.kutumb.geo.FunFacts
import com.vibethroughcode.ftree.kutumb.geo.Geo
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation

/** Everything the relationship screen shows about the reader and one relative. */
data class PathUi(
    val me: Person,
    val other: Person,
    /** The people the path passes through, the reader first and the relative last. */
    val people: List<Person>,
    val relation: Relation.Found?,
    /** Where they live now, apart, when both have chosen to share it. */
    val distanceKm: Double?,
    val facts: List<FunFact>,
)

object PathModel {
    fun birthFacts(person: Person): BirthFacts = when (val date = RecordedDate.parse(person.birthDate)) {
        is PartialDate -> BirthFacts(date.year, date.month, date.day)
        is YearlessDate -> BirthFacts(null, date.month, date.day)
        null -> BirthFacts()
    }

    /** Null when either person is not in the tree. */
    fun build(meId: String, otherId: String, snapshot: FamilySnapshot, locations: List<PersonLocation>): PathUi? {
        val me = snapshot.people[meId] ?: return null
        val other = snapshot.people[otherId] ?: return null
        val relation = Kinship.relate(snapshot, meId, otherId) as? Relation.Found
        val people = listOf(me) + relation?.chain.orEmpty().mapNotNull { snapshot.people[it.personId] }
        return PathUi(
            me = me,
            other = other,
            people = if (people.last().id == other.id) people else people + other,
            relation = relation,
            distanceKm = Geo.distanceKm(locations, meId, otherId, LocationLabel.CURRENT),
            facts = FunFacts.between(
                birthFacts(me), birthFacts(other),
                birthplaceKm = Geo.distanceKm(locations, meId, otherId, LocationLabel.BIRTHPLACE),
            ),
        )
    }
}
