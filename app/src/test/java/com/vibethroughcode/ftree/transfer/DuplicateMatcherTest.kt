package com.vibethroughcode.ftree.transfer

import com.vibethroughcode.ftree.data.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether two records are the same person.
 *
 * These are the most dangerous rules in the app: a wrong merge destroys information that may exist
 * nowhere else, while a wrong split is a tidy-up. The tests are written to hold that asymmetry.
 */
class DuplicateMatcherTest {

    private fun graph(vararg edges: Pair<String, String>): MatchGraph {
        val neighbours = mutableMapOf<String, MutableSet<String>>()
        edges.forEach { (a, b) ->
            neighbours.getOrPut(a) { mutableSetOf() } += b
            neighbours.getOrPut(b) { mutableSetOf() } += a
        }
        return MatchGraph(neighbours)
    }

    private fun match(
        imported: List<PersonRecord>,
        local: List<Person>,
        importedGraph: MatchGraph = MatchGraph(emptyMap()),
        localGraph: MatchGraph = MatchGraph(emptyMap()),
        originIndex: Map<Pair<String, String>, String> = emptyMap(),
        /** For an origin held by more than one person here; [originIndex] is the usual case. */
        heldBy: Map<Pair<String, String>, List<String>> =
            originIndex.mapValues { listOf(it.value) },
        sourceTreeId: String = "their-tree",
    ) = DuplicateMatcher.match(
        imported, importedGraph, local, localGraph, heldBy, sourceTreeId,
    ).associateBy { it.importedId }

    @Test
    fun `a person nobody here resembles is new`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Priya Sharma")),
            local = listOf(Person(id = "l1", name = "Ankit Kumar")),
        )

        assertEquals(MatchTier.NONE, result.getValue("i1").tier)
        assertNull(result.getValue("i1").localId)
    }

    @Test
    fun `a person we already imported from this tree is a certain match`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Ankit Kumar")),
            local = listOf(Person(id = "l1", name = "Ankit Kumar")),
            originIndex = mapOf(("their-tree" to "i1") to "l1"),
        )

        val single = result.getValue("i1")
        assertEquals(MatchTier.CERTAIN, single.tier)
        assertEquals("l1", single.localId)
        assertTrue(single.mergesByDefault)
    }

    @Test
    fun `an inherited origin also proves identity`() {
        // Their file came from a tree that had already merged ours, so the person carries our id.
        val result = match(
            imported = listOf(
                PersonRecord(
                    id = "i1",
                    name = "Totally Different Spelling",
                    origins = listOf(OriginRecord("original-tree", "old-7")),
                )
            ),
            local = listOf(Person(id = "l1", name = "Ankit Kumar")),
            originIndex = mapOf(("original-tree" to "old-7") to "l1"),
        )

        assertEquals(MatchTier.CERTAIN, result.getValue("i1").tier)
    }

    @Test
    fun `the same name with a relative in common is a strong match`() {
        val result = match(
            imported = listOf(
                PersonRecord(id = "iFather", name = "Raj Kumar"),
                PersonRecord(id = "iMe", name = "Ankit Kumar"),
            ),
            local = listOf(
                Person(id = "lFather", name = "Raj Kumar"),
                Person(id = "lMe", name = "Ankit Kumar"),
            ),
            importedGraph = graph("iFather" to "iMe"),
            localGraph = graph("lFather" to "lMe"),
            originIndex = mapOf(("their-tree" to "iMe") to "lMe"),
        )

        // "Ankit Kumar" is provable; that makes his father's name far more than a coincidence.
        assertEquals(MatchTier.CERTAIN, result.getValue("iMe").tier)
        val father = result.getValue("iFather")
        assertEquals(MatchTier.STRONG, father.tier)
        assertEquals(1, father.evidence.sharedRelatives)
        assertTrue(father.mergesByDefault)
    }

    @Test
    fun `a shared relative is enough even when nobody recorded any dates`() {
        val result = match(
            imported = listOf(
                PersonRecord(id = "iFather", name = "Raj Kumar"),
                PersonRecord(id = "iMe", name = "Ankit Kumar"),
            ),
            local = listOf(
                Person(id = "lFather", name = "Raj Kumar"),
                Person(id = "lMe", name = "Ankit Kumar"),
            ),
            importedGraph = graph("iFather" to "iMe"),
            localGraph = graph("lFather" to "lMe"),
            originIndex = mapOf(("their-tree" to "iMe") to "lMe"),
        )

        // Refusing to match for want of a birth date nobody ever wrote down would be absurd.
        assertEquals(MatchTier.STRONG, result.getValue("iFather").tier)
    }

    @Test
    fun `the same name alone is only a weak match and stays separate by default`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Ankit Kumar")),
            local = listOf(Person(id = "l1", name = "Ankit Kumar")),
        )

        val single = result.getValue("i1")
        assertEquals(MatchTier.WEAK, single.tier)
        assertEquals("l1", single.localId)
        assertFalse("a coincidence of names must not merge on its own", single.mergesByDefault)
        assertTrue(single.needsReview)
    }

    @Test
    fun `birth dates that cannot both be true rule out a match entirely`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Ankit Kumar", birthDate = "1990")),
            local = listOf(Person(id = "l1", name = "Ankit Kumar", birthDate = "1962")),
        )

        // Two people with one name born decades apart are two people.
        assertEquals(MatchTier.NONE, result.getValue("i1").tier)
        assertNull(result.getValue("i1").localId)
    }

    @Test
    fun `dates of differing precision still agree`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Ankit Kumar", birthDate = "1990")),
            local = listOf(Person(id = "l1", name = "Ankit Kumar", birthDate = "1990-05-01")),
        )

        assertEquals(MatchTier.WEAK, result.getValue("i1").tier)
    }

    @Test
    fun `a birthday with no year still rules out a match when it disagrees`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Ankit Kumar", birthDate = "--04-17")),
            local = listOf(Person(id = "l1", name = "Ankit Kumar", birthDate = "1990-05-01")),
        )

        // #90: read as "no date", this veto was lost, which is the unsafe way round.
        assertEquals(MatchTier.NONE, result.getValue("i1").tier)
    }

    @Test
    fun `a birthday with no year agrees with a year that could hold it`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Ankit Kumar", birthDate = "--04-17")),
            local = listOf(Person(id = "l1", name = "Ankit Kumar", birthDate = "1990")),
        )

        assertEquals(MatchTier.WEAK, result.getValue("i1").tier)
        assertTrue(result.getValue("i1").evidence.datesAgree)
    }

    @Test
    fun `conflicting death dates also rule out a match`() {
        val result = match(
            imported = listOf(
                PersonRecord(id = "i1", name = "Raj Kumar", birthDate = "1938", deathDate = "2010")
            ),
            local = listOf(
                Person(id = "l1", name = "Raj Kumar", birthDate = "1938", deathDate = "1999")
            ),
        )

        assertEquals(MatchTier.NONE, result.getValue("i1").tier)
    }

    @Test
    fun `names are compared past accents punctuation and spacing`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "  josé   O'BRIEN ")),
            local = listOf(Person(id = "l1", name = "Jose O Brien")),
        )

        assertEquals(MatchTier.WEAK, result.getValue("i1").tier)
    }

    @Test
    fun `an abbreviated name is not treated as the same person`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "R. Kumar")),
            local = listOf(Person(id = "l1", name = "Raj Kumar")),
        )

        // Guessing here is how a merge quietly destroys someone's data.
        assertEquals(MatchTier.NONE, result.getValue("i1").tier)
    }

    @Test
    fun `an unnamed person is never matched to anybody`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1")),
            local = listOf(Person(id = "l1"), Person(id = "l2", name = "Ankit")),
        )

        // Every unknown person would otherwise collapse into one.
        assertEquals(MatchTier.NONE, result.getValue("i1").tier)
    }

    @Test
    fun `two local people with the same name and nothing to separate them are not guessed at`() {
        val result = match(
            imported = listOf(PersonRecord(id = "i1", name = "Ankit Kumar")),
            local = listOf(
                Person(id = "l1", name = "Ankit Kumar"),
                Person(id = "l2", name = "Ankit Kumar"),
            ),
        )

        assertEquals(MatchTier.NONE, result.getValue("i1").tier)
    }

    @Test
    fun `one local person is never claimed by two imported people`() {
        val result = match(
            imported = listOf(
                PersonRecord(id = "i1", name = "Ankit Kumar"),
                PersonRecord(id = "i2", name = "Ankit Kumar"),
            ),
            local = listOf(Person(id = "l1", name = "Ankit Kumar")),
            originIndex = mapOf(("their-tree" to "i1") to "l1"),
        )

        assertEquals("l1", result.getValue("i1").localId)
        assertNull("l1 is already taken", result.getValue("i2").localId)
    }

    @Test
    fun `a copy and the person it copies are each matched to themselves`() {
        // `d` is a stale copy of `a`, carrying a's origin. Both were imported before, so two
        // people here hold that origin (#193).
        val result = match(
            imported = listOf(
                PersonRecord(id = "a", name = "Ankit Kumar"),
                PersonRecord(
                    id = "d",
                    name = "Ankit Kumar",
                    origins = listOf(OriginRecord("their-tree", "a")),
                ),
            ),
            local = listOf(
                Person(id = "la", name = "Ankit Kumar"),
                Person(id = "ld", name = "Ankit Kumar"),
            ),
            heldBy = mapOf(
                ("their-tree" to "a") to listOf("la", "ld"),
                ("their-tree" to "d") to listOf("ld"),
            ),
        )

        assertEquals("la", result.getValue("a").localId)
        assertEquals(MatchTier.CERTAIN, result.getValue("a").tier)
        assertEquals("ld", result.getValue("d").localId)
        assertEquals(MatchTier.CERTAIN, result.getValue("d").tier)
    }

    @Test
    fun `of two people holding the same origin, the one holding nothing else is meant`() {
        val result = match(
            imported = listOf(PersonRecord(id = "a")),
            local = listOf(Person(id = "ld"), Person(id = "la")),
            heldBy = mapOf(
                ("their-tree" to "a") to listOf("ld", "la"),
                ("their-tree" to "d") to listOf("ld"),
            ),
        )

        assertEquals("la", result.getValue("a").localId)
    }

    @Test
    fun `matching the same file twice is stable`() {
        val imported = listOf(
            PersonRecord(id = "i1", name = "Ankit Kumar", birthDate = "1990"),
            PersonRecord(id = "i2", name = "Raj Kumar", birthDate = "1938"),
        )
        val local = listOf(
            Person(id = "l1", name = "Ankit Kumar", birthDate = "1990"),
            Person(id = "l2", name = "Raj Kumar", birthDate = "1938"),
        )
        val origins = mapOf(
            ("their-tree" to "i1") to "l1",
            ("their-tree" to "i2") to "l2",
        )

        val first = match(imported, local, originIndex = origins)
        val second = match(imported, local, originIndex = origins)

        assertEquals(first.mapValues { it.value.tier }, second.mapValues { it.value.tier })
        assertTrue(first.values.all { it.tier == MatchTier.CERTAIN })
    }

    @Test
    fun `Devanagari keys keep their vowel signs and match the JavaScript exactly`() {
        // The same table as matching.test.js, so the two shells cannot drift apart on who is the
        // same person (#113): change the key on either side and that side's table fails.
        val ram = "राम"
        val rama = "रामा"
        val kumar = "कुमार"
        val kamar = "कमार"
        val shyam = "श्याम"
        val shayam = "शयाम"
        val expected = mapOf(
            ram to ram,
            rama to rama,
            kumar to kumar,
            kamar to kamar,
            shyam to shyam,
            shayam to shayam,
            "$ram $kumar" to "$ram $kumar",
            "José" to "jose",
            "Ángel" to "angel",
            "Đặng" to "đang",
            "  josé   O'BRIEN " to "jose o brien",
        )
        expected.forEach { (name, key) -> assertEquals("key of $name", key, nameKey(name)) }
        assertFalse(nameKey(ram) == nameKey(rama))
        assertFalse(nameKey(kumar) == nameKey(kamar))
        assertFalse(nameKey(shyam) == nameKey(shayam))
    }

    @Test
    fun `a shared relative cannot merge two different Devanagari names`() {
        // Before #113 these two names keyed alike, and a shared child took them to STRONG.
        val result = match(
            imported = listOf(
                PersonRecord(id = "iA", name = "राम"),
                PersonRecord(id = "iKid", name = "Shared Child"),
            ),
            local = listOf(
                Person(id = "lA", name = "रामा"),
                Person(id = "lKid", name = "Shared Child"),
            ),
            importedGraph = graph("iA" to "iKid"),
            localGraph = graph("lA" to "lKid"),
            originIndex = mapOf(("their-tree" to "iKid") to "lKid"),
        )

        assertEquals(MatchTier.NONE, result.getValue("iA").tier)
        assertFalse(result.getValue("iA").mergesByDefault)
    }
}
