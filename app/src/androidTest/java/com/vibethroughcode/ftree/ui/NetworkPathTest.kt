package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.kutumb.geo.LocationSource
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.network.PathBackTag
import com.vibethroughcode.ftree.ui.network.PathChainTag
import com.vibethroughcode.ftree.ui.network.PathDistanceTag
import com.vibethroughcode.ftree.ui.network.PathFactsTag
import com.vibethroughcode.ftree.ui.network.PathMessageTag
import com.vibethroughcode.ftree.ui.network.networkRowTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One relative's relationship screen, reached by tapping them in the family network. */
@RunWith(AndroidJUnit4::class)
class NetworkPathTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private lateinit var devika: Person

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        val kutumb = app.container.kutumbRepository
        runBlocking {
            kutumb.clearAll()
            val repo = app.container.familyRepository
            val me = repo.addPerson(Person(name = "Yash Rao", birthDate = "1990-03-22"))
            devika = repo.addPerson(Person(name = "Devika Nair", birthDate = "1976-03-30"))
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
            kutumb.saveContact(
                TrustedContact(devika.id, Bip340Scheme.generateKeyPair().publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
            )
            kutumb.saveLocation(PersonLocation(me.id, LocationLabel.CURRENT, 51.5074, -0.1278, LocationSource.MANUAL, 1))
            kutumb.saveLocation(PersonLocation(devika.id, LocationLabel.CURRENT, 48.8566, 2.3522, LocationSource.MANUAL, 1))
        }
        rule.onNodeWithTag(NavNetworkTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(networkRowTag(devika.id)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(networkRowTag(devika.id)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(PathChainTag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun showsThePathTheDistanceAndTheFactsTheyShare() {
        rule.onNodeWithTag(PathChainTag).assertIsDisplayed()
        rule.onNodeWithTag(PathDistanceTag).assertIsDisplayed()
        rule.onNodeWithTag(PathFactsTag).assertIsDisplayed()
    }

    @Test
    fun messagingIsOfferedForADirectPairingButNotYetWorking() {
        rule.onNodeWithTag(PathMessageTag).assertIsNotEnabled()
    }

    @Test
    fun backReturnsToTheNetwork() {
        rule.onNodeWithTag(PathBackTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(networkRowTag(devika.id)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, rule.onAllNodesWithTag(networkRowTag(devika.id)).fetchSemanticsNodes().size)
    }
}
