package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.PairingFlow
import com.vibethroughcode.ftree.kutumb.PairingProblem
import com.vibethroughcode.ftree.kutumb.PairingState
import com.vibethroughcode.ftree.kutumb.UnavailablePairingFlow
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.nearby.wire.QrLink
import com.vibethroughcode.ftree.ui.network.NetworkListTag
import com.vibethroughcode.ftree.ui.network.NetworkPairTag
import com.vibethroughcode.ftree.ui.network.PairingCodeTag
import com.vibethroughcode.ftree.ui.network.PairingFailedTag
import com.vibethroughcode.ftree.ui.network.PairingMatchTag
import com.vibethroughcode.ftree.ui.network.PairingWaitingTag
import com.vibethroughcode.ftree.ui.network.PairingWhoTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The pairing screen, driven through a fake handshake: the real one is covered by the nearby tests. */
@RunWith(AndroidJUnit4::class)
class NetworkPairingTest {

    private class FakeFlow : PairingFlow {
        val mutable = MutableStateFlow<PairingState>(PairingState.Idle)
        override val state: StateFlow<PairingState> = mutable
        var confirmed: Boolean? = null
        override fun start() { mutable.value = PairingState.Waiting(link = null, deviceName = "Quiet Heron") }
        override fun joinByLink(link: QrLink) = Unit
        override fun confirmCode(matched: Boolean) { confirmed = matched }
        override fun cancel() { mutable.value = PairingState.Idle }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private val kutumb get() = app.container.kutumbRepository
    private val flow = FakeFlow()
    private lateinit var me: Person
    private lateinit var devika: Person

    @Before
    fun seed() {
        app.container.database.clearAllTables()
        app.container.pairingFlow = flow
        runBlocking {
            kutumb.clearAll()
            me = app.container.familyRepository.addPerson(Person(name = "Yash Rao"))
            devika = app.container.familyRepository.addPerson(Person(name = "Devika Nair"))
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
        }
        rule.onNodeWithTag(NavNetworkTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkPairTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(NetworkPairTag).performClick()
    }

    @After
    fun putTheRealOneBack() {
        app.container.pairingFlow = UnavailablePairingFlow
    }

    @Test
    fun confirmingTheSameCodeThenSayingWhoTheyAreSavesAPersonMetInPerson() {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(PairingWaitingTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Pair with a relative").assertIsDisplayed()

        flow.mutable.value = PairingState.ConfirmCode("482916", "Pixel 8")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(PairingCodeTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(PairingCodeTag).assertTextContains("482 916")
        rule.onNodeWithTag(PairingMatchTag).performClick()
        assertEquals(true, flow.confirmed)

        val key = Bip340Scheme.generateKeyPair().publicKey
        flow.mutable.value = PairingState.Verified("Pixel 8", key)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(PairingWhoTag).fetchSemanticsNodes().isNotEmpty() }
        // Not the reader themselves.
        assertEquals(0, rule.onAllNodesWithText("Yash Rao").fetchSemanticsNodes().size)
        rule.onNodeWithText("Devika Nair").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkListTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Paired in person").assertIsDisplayed()
        val saved = runBlocking { kutumb.observeContacts().first().single() }
        assertEquals(devika.id, saved.personId)
        assertEquals(key, saved.pubKeyCurrent)
        assertEquals(PairingMethod.DIRECT, saved.pairingMethod)
    }

    @Test
    fun aMismatchSavesNothingAndDoesNotOfferToTryAgain() {
        // Wait for the screen to have started the flow, which would otherwise overwrite this.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(PairingWaitingTag).fetchSemanticsNodes().isNotEmpty() }
        flow.mutable.value = PairingState.Failed(PairingProblem.CODE_MISMATCH)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(PairingFailedTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Try again").assertCountEquals(0)
        assertNull(runBlocking { kutumb.observeContacts().first().firstOrNull() })
    }
}
