package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.network.NetworkListTag
import com.vibethroughcode.ftree.ui.network.NetworkWhoTag
import com.vibethroughcode.ftree.ui.network.networkMessageTag
import com.vibethroughcode.ftree.ui.network.networkRowTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The family network list: choosing who you are, then who is trusted and how they came to be. */
@RunWith(AndroidJUnit4::class)
class NetworkContactsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private val repo get() = app.container.familyRepository
    private val kutumb get() = app.container.kutumbRepository

    private lateinit var me: Person
    private lateinit var devika: Person
    private lateinit var asha: Person

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        runBlocking {
            kutumb.clearAll()
            me = repo.addPerson(Person(name = "Yash Rao"))
            devika = repo.addPerson(Person(name = "Devika Nair"))
            asha = repo.addPerson(Person(name = "Asha Menon"))
        }
        rule.onNodeWithTag(NavNetworkTag).performClick()
    }

    private fun contact(person: Person, method: PairingMethod, voucher: Person? = null) = TrustedContact(
        personId = person.id,
        pubKeyCurrent = Bip340Scheme.generateKeyPair().publicKey,
        pairedAt = 1,
        pairingMethod = method,
        vouchedByPersonId = voucher?.id,
    )

    @Test
    fun theReaderSaysWhoTheyAreOnceAndTheKeyIsMintedOnThePhone() {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkWhoTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Yash Rao").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkWhoTag).fetchSemanticsNodes().isEmpty() }
        val identity = runBlocking { kutumb.observeIdentity().first { it != null } }
        assertEquals(me.id, identity!!.personId)
        assertNotNull(identity.keyPair.publicKey)
        rule.onNodeWithText("No one paired yet").assertIsDisplayed()
    }

    @Test
    fun metContactsAreMarkedAndVouchedOnesAreLockedOutOfMessaging() {
        runBlocking {
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
            kutumb.saveContact(contact(devika, PairingMethod.DIRECT))
            kutumb.saveContact(contact(asha, PairingMethod.VOUCHED, voucher = devika))
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkListTag).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(networkRowTag(devika.id)).assertIsDisplayed()
        rule.onNodeWithText("Paired in person").assertIsDisplayed()
        rule.onNodeWithText("Vouched by Devika Nair").assertIsDisplayed()

        // Messaging is not built yet, so even the unlocked control is drawn unavailable.
        rule.onNodeWithTag(networkMessageTag(devika.id)).assertIsNotEnabled()
        // A plain icon merges into its row for a screen reader; look for it unmerged.
        rule.onNodeWithTag(networkMessageTag(asha.id), useUnmergedTree = true).assertIsDisplayed()
    }
}
