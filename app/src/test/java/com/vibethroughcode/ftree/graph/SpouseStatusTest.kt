package com.vibethroughcode.ftree.graph

import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.SpouseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A marriage that ended, in both languages (#291, the Kotlin twin of #271).
 *
 * पति and पत्नी assert a marriage. Said about one the record says is over, they state the opposite
 * of the record — and until this, the relation panel said पत्नी for a woman entered as divorced,
 * while the desktop app, the website and the family book all said "former wife" and no Hindi word.
 *
 * Nothing already in the suite could catch it. `site/playground/kinship-hi.test.mjs` asserts parity
 * between the JS vocabulary codes and `kinship_hi.xml`, and the fix *removes* a word rather than
 * adding a code, so that check stays green either way; and the Gradle suites had no fixture for a
 * divorced spouse's Hindi term at all. This is that fixture.
 */
class SpouseStatusTest {

    private fun snapshot(subtype: SpouseKind?, wifeDied: Boolean = false): FamilySnapshot {
        val me = Person(id = "me", name = "me", gender = Gender.MALE)
        val wife = Person(id = "wife", name = "wife", gender = Gender.FEMALE, deceased = wifeDied)
        return FamilySnapshot(
            people = mapOf("me" to me, "wife" to wife),
            parentEdges = emptyList(),
            spouseEdges = listOf("me" to "wife"),
            siblingEdges = emptyList(),
            spouseKinds = subtype?.let { mapOf(("me" to "wife") to it) }.orEmpty(),
        )
    }

    private fun term(subtype: SpouseKind?, wifeDied: Boolean = false): KinshipTerm {
        val snapshot = snapshot(subtype, wifeDied)
        val found = Kinship.relate(snapshot, "me", "wife") as? Relation.Found
            ?: error("me and wife are not related in the fixture")
        return found.term ?: error("no term for a marriage")
    }

    private fun hindi(subtype: SpouseKind?, wifeDied: Boolean = false): HindiKinTerm? {
        val snapshot = snapshot(subtype, wifeDied)
        val found = Kinship.relate(snapshot, "me", "wife") as Relation.Found
        return HindiKinship.term(
            term = found.term!!,
            path = found.path,
            subject = Gender.MALE,
            target = Gender.FEMALE,
        )
    }

    /* ------------------------------------------------------------------ the status rule */

    @Test
    fun `a divorced spouse is former`() {
        assertEquals(SpouseStatus.FORMER, (term(SpouseKind.DIVORCED) as KinshipTerm.Spouse).status)
    }

    @Test
    fun `a widowed edge is late only for the partner who died`() {
        // The edge is symmetric and cannot say which of the two died, so the person being
        // described decides. A living wife on a WIDOWED edge is the widow, not the late spouse.
        assertEquals(SpouseStatus.LATE, (term(SpouseKind.WIDOWED, wifeDied = true) as KinshipTerm.Spouse).status)
        assertEquals(SpouseStatus.CURRENT, (term(SpouseKind.WIDOWED, wifeDied = false) as KinshipTerm.Spouse).status)
    }

    @Test
    fun `an ordinary marriage is current`() {
        assertEquals(SpouseStatus.CURRENT, (term(null) as KinshipTerm.Spouse).status)
        assertEquals(SpouseStatus.CURRENT, (term(SpouseKind.MARRIED) as KinshipTerm.Spouse).status)
    }

    /* ------------------------------------------------------------------ the subtype survives */

    @Test
    fun `the term carries the subtype the record stored`() {
        // The whole bug: the shortest-path step dropped it, so nothing downstream could tell a
        // marriage from one that ended.
        assertEquals(SpouseKind.DIVORCED, (term(SpouseKind.DIVORCED) as KinshipTerm.Spouse).subtype)
        assertEquals(SpouseKind.PARTNER, (term(SpouseKind.PARTNER) as KinshipTerm.Spouse).subtype)
    }

    /* ------------------------------------------------------------------ the Hindi word */

    @Test
    fun `a divorced wife gets no Hindi word`() {
        assertNull("पत्नी asserts the marriage the record says is over", hindi(SpouseKind.DIVORCED))
    }

    @Test
    fun `a partner gets no Hindi word`() {
        // PARTNER was never a marriage, so पत्नी would be inventing one.
        assertNull(hindi(SpouseKind.PARTNER))
    }

    @Test
    fun `a late wife keeps पत्नी`() {
        // That marriage WAS a marriage, and the book already says so. This is the case the fix
        // must not overshoot: removing the word here would be its own wrong answer.
        assertEquals(HindiKinTerm.PATNI, hindi(SpouseKind.WIDOWED, wifeDied = true))
    }

    @Test
    fun `an ordinary wife keeps पत्नी`() {
        assertEquals(HindiKinTerm.PATNI, hindi(null))
        assertEquals(HindiKinTerm.PATNI, hindi(SpouseKind.MARRIED))
    }
}
