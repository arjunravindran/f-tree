package com.vibethroughcode.ftree.graph

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.Relationship
import com.vibethroughcode.ftree.data.RelationshipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleTest {
    private fun p(id: String, name: String?) = Person(id = id, name = name)
    private fun link(a: String, b: String, label: String? = null) =
        Relationship.of(a, b, RelationshipType.CONNECTED, label, id = "$a-$b")

    private val asha = p("asha", "Asha")
    private val ben = p("ben", "Ben")
    private val chen = p("chen", "Chen")
    private val dara = p("dara", "Dara")
    private val unnamed = p("u", null)
    private val people = listOf(asha, ben, chen, dara, unnamed)

    @Test fun `connected is symmetric and stored in one canonical order`() {
        assertTrue(RelationshipType.CONNECTED.isSymmetric)
        val e = Relationship.of("ben", "asha", RelationshipType.CONNECTED, null)
        assertEquals("asha" to "ben", e.fromPersonId to e.toPersonId)
    }

    @Test fun `labels are cleaned`() {
        assertEquals("Work friend", Circle.cleanLabel("  Work   friend "))
        assertNull(Circle.cleanLabel("   "))
        assertNull(Circle.cleanLabel(null))
        assertEquals(Circle.MAX_LABEL, Circle.cleanLabel("x".repeat(100))!!.length)
    }

    @Test fun `connections are seen from either end, ignore family edges, and sort by label then name`() {
        val edges = listOf(
            link("asha", "ben", "Friend"),
            link("chen", "asha", "colleague"),
            link("asha", "dara"),
            Relationship.of("asha", "u", RelationshipType.PARENT),
        )
        val ofAsha = Circle.connectionsOf("asha", people, edges)
        assertEquals(listOf("chen", "ben", "dara"), ofAsha.map { it.other.id })
        assertEquals(listOf("colleague", "Friend", null), ofAsha.map { it.label })
        assertEquals(listOf("asha"), Circle.connectionsOf("ben", people, edges).map { it.other.id })
        assertTrue(Circle.connectionsOf("u", people, edges).isEmpty())
    }

    @Test fun `labels that differ only in case share a group, and unlabelled come last`() {
        val edges = listOf(link("asha", "ben", "friend"), link("asha", "chen", "Friend"), link("asha", "dara"))
        val groups = Circle.grouped(Circle.connectionsOf("asha", people, edges))
        assertEquals(listOf("Friend", null), groups.map { it.label })
        assertEquals(listOf("ben", "chen"), groups[0].connections.map { it.other.id })
    }

    @Test fun `people reachable through a connection are listed with who leads there`() {
        val edges = listOf(link("asha", "ben"), link("ben", "chen"), link("asha", "dara"), link("dara", "chen"), link("ben", "dara"))
        val through = Circle.throughOthers("asha", people, edges)
        assertEquals(listOf("chen"), through.map { it.first.id }) // dara and ben are already direct
        assertEquals(setOf("ben", "dara"), through.single().second.map { it.id }.toSet())
    }

    @Test fun `the best connected person is where to start, ties by name`() {
        val edges = listOf(link("asha", "ben"), link("ben", "chen"), link("ben", "dara"))
        assertEquals("ben", Circle.startingPoint(people, edges))
        assertEquals("asha", Circle.startingPoint(people, emptyList())) // all tied: first by name
        assertNull(Circle.startingPoint(emptyList(), edges))
    }

    @Test fun `labels in use come most used first`() {
        val edges = listOf(link("asha", "ben", "Friend"), link("asha", "chen", "friend"), link("asha", "dara", "Boss"))
        assertEquals(listOf("Friend", "Boss"), Circle.labelsInUse(edges))
    }
}
