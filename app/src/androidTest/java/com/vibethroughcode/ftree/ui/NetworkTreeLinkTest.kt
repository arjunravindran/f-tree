package com.vibethroughcode.ftree.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.network.PathChainTag
import com.vibethroughcode.ftree.ui.tree.CompactFamilyTag
import com.vibethroughcode.ftree.ui.tree.CompactFocusTag
import com.vibethroughcode.ftree.ui.tree.TreeInNetworkTag
import com.vibethroughcode.ftree.ui.tree.TreeOpenPersonTag
import com.vibethroughcode.ftree.ui.tree.TreeModeCompactTag
import com.vibethroughcode.ftree.ui.tree.compactPersonTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The chart's person sheet leads to the family-network screen, but only for people this phone trusts. */
@RunWith(AndroidJUnit4::class)
class NetworkTreeLinkTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private lateinit var me: Person
    private lateinit var mum: Person
    private lateinit var dad: Person

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        val kutumb = app.container.kutumbRepository
        runBlocking {
            kutumb.clearAll()
            val repo = app.container.familyRepository
            me = repo.addPerson(Person(name = "Yash Rao"))
            mum = repo.addPerson(Person(name = "Meera Rao"))
            dad = repo.addPerson(Person(name = "Hari Rao"))
            repo.addRelative(me.id, mum.id, RelativeKind.PARENT)
            repo.addRelative(me.id, dad.id, RelativeKind.PARENT)
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
            kutumb.saveContact(
                TrustedContact(mum.id, Bip340Scheme.generateKeyPair().publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
            )
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeModeCompactTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(TreeModeCompactTag).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(CompactFamilyTag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun isCentre(person: Person) = rule.onAllNodesWithTag(CompactFocusTag).fetchSemanticsNodes().any { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(person.name!!) } == true
    }

    /**
     * Opens somebody's sheet: a tap on a card re-centres on them, and a tap on the centred card opens
     * the sheet. The screen remembers where it was left, so it may already be centred on them.
     */
    private fun openSheetFor(person: Person) {
        rule.waitUntil(10_000) { isCentre(person) || rule.onAllNodesWithTag(compactPersonTag(person.id)).fetchSemanticsNodes().isNotEmpty() }
        if (!isCentre(person)) {
            rule.onNodeWithTag(compactPersonTag(person.id)).performClick()
            rule.waitUntil(10_000) { isCentre(person) }
        }
        rule.onNodeWithTag(CompactFocusTag).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(TreeOpenPersonTag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aTrustedPersonsSheetLeadsToTheirRelationshipScreen() {
        openSheetFor(mum)
        rule.onNodeWithTag(TreeInNetworkTag).assertIsDisplayed()
        rule.onNodeWithTag(TreeInNetworkTag).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(PathChainTag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anUntrustedPersonsSheetDoesNotOfferIt() {
        openSheetFor(me)
        assert(rule.onAllNodesWithTag(TreeInNetworkTag).fetchSemanticsNodes().isEmpty())
    }
}
