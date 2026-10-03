package com.vibethroughcode.ftree.graph

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.Relationship
import com.vibethroughcode.ftree.data.RelationshipType

/** One line between two people in a Circle, seen from one of them. */
data class Connection(val edgeId: String, val other: Person, val label: String?)

/** Connections that share a label, shown under one heading. A null label is "connected, unspecified". */
data class ConnectionGroup(val label: String?, val connections: List<Connection>)

/**
 * The pure side of a Circle: friends, colleagues, a team. People are joined by plain
 * [RelationshipType.CONNECTED] edges with an optional free-text label, and nothing is
 * ancestral, so there is no chart direction and no kinship. Everything here is a function of the
 * people and edges it is handed, so it is tested on the JVM like the family layout.
 */
object Circle {
    const val MAX_LABEL = 30

    /** Trimmed, inner whitespace collapsed, capped; blank means no label. */
    fun cleanLabel(raw: String?): String? =
        raw?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_LABEL)?.trim()?.ifEmpty { null }

    private fun key(label: String?) = label?.lowercase()

    private fun nameKey(p: Person) = p.name?.trim()?.lowercase().orEmpty().ifEmpty { "￿" } // unnamed last

    /** Everyone [personId] is directly connected to, labelled, in a stable order. */
    fun connectionsOf(personId: String, people: Collection<Person>, edges: Collection<Relationship>): List<Connection> {
        val byId = people.associateBy { it.id }
        return edges.asSequence()
            .filter { it.type == RelationshipType.CONNECTED }
            .mapNotNull { e ->
                val other = byId[e.other(personId) ?: return@mapNotNull null] ?: return@mapNotNull null
                Connection(e.id, other, cleanLabel(e.subtype))
            }
            .sortedWith(compareBy({ it.label == null }, { key(it.label) }, { nameKey(it.other) }, { it.other.id }))
            .toList()
    }

    /**
     * The same connections under one heading per label. Labels that differ only in case are one
     * group, headed by whichever spelling sorts first, so "Friend" and "friend" do not split.
     * Unlabelled connections come last.
     */
    fun grouped(connections: List<Connection>): List<ConnectionGroup> =
        connections.groupBy { key(it.label) }
            .map { (_, list) -> ConnectionGroup(list.mapNotNull { it.label }.minOrNull(), list) }
            .sortedWith(compareBy({ it.label == null }, { key(it.label) }))

    /**
     * People who are not directly connected to [personId] but are connected to one of their
     * connections, with the connection(s) that lead there ("through"). This is the circle's
     * answer to "who might I ask to be introduced to?".
     */
    fun throughOthers(personId: String, people: Collection<Person>, edges: Collection<Relationship>): List<Pair<Person, List<Person>>> {
        val direct = connectionsOf(personId, people, edges).map { it.other }
        val directIds = direct.mapTo(HashSet()) { it.id }
        val via = LinkedHashMap<String, MutableList<Person>>()
        val byId = people.associateBy { it.id }
        for (d in direct) {
            for (c in connectionsOf(d.id, people, edges)) {
                val id = c.other.id
                if (id == personId || id in directIds) continue
                via.getOrPut(id) { mutableListOf() }.add(d)
            }
        }
        return via.entries
            .sortedWith(compareBy({ -it.value.size }, { nameKey(byId.getValue(it.key)) }, { it.key }))
            .map { (id, through) -> byId.getValue(id) to through }
    }

    /** Where to start looking: the best connected person; ties go to the first by name. Null for an empty circle. */
    fun startingPoint(people: Collection<Person>, edges: Collection<Relationship>): String? {
        if (people.isEmpty()) return null
        val degree = HashMap<String, Int>()
        edges.filter { it.type == RelationshipType.CONNECTED }.forEach { e ->
            degree[e.fromPersonId] = (degree[e.fromPersonId] ?: 0) + 1
            degree[e.toPersonId] = (degree[e.toPersonId] ?: 0) + 1
        }
        return people.minWithOrNull(compareBy({ -(degree[it.id] ?: 0) }, { nameKey(it) }, { it.id }))?.id
    }

    /** Labels already used in this circle, most used first, for suggesting them again. */
    fun labelsInUse(edges: Collection<Relationship>): List<String> =
        edges.filter { it.type == RelationshipType.CONNECTED }
            .mapNotNull { cleanLabel(it.subtype) }
            .groupBy { key(it) }
            .values
            .sortedWith(compareBy({ -it.size }, { key(it.first()) }))
            .map { it.minOf { s -> s } }
}
