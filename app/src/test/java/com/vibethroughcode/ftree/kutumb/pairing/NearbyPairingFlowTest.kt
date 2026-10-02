package com.vibethroughcode.ftree.kutumb.pairing

import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.PairingProblem
import com.vibethroughcode.ftree.kutumb.PairingState
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.nearby.Lan
import com.vibethroughcode.ftree.nearby.LoopTransport
import com.vibethroughcode.ftree.nearby.NearbyRepository
import com.vibethroughcode.ftree.nearby.await
import com.vibethroughcode.ftree.nearby.testPreferences
import com.vibethroughcode.ftree.nearby.testSelf
import com.vibethroughcode.ftree.nearby.wire.NearbyProblem
import com.vibethroughcode.ftree.nearby.wire.QrLink
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [NearbyPairingFlow]: what the pairing screen is told as the nearby layer goes through its states. */
class NearbyPairingFlowTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lan = Lan()
    private val alice = Bip340Scheme.generateKeyPair()
    private val bob = Bip340Scheme.generateKeyPair()

    private class Phone(val transport: LoopTransport, val nearby: NearbyRepository, val flow: NearbyPairingFlow)

    private fun phone(seed: Byte, name: String, key: KeyPair?, enabled: Boolean = true): Phone {
        val transport = LoopTransport(lan, "10.0.0.$seed")
        val nearby = NearbyRepository(testPreferences(enabled), testSelf(seed, name), transport, folder.newFolder(), scope)
        val kutumb = InMemoryKutumbRepository()
        if (key != null) runBlocking { kutumb.setIdentity(LocalIdentity("person-$seed", key)) }
        return Phone(transport, nearby, NearbyPairingFlow(nearby, kutumb, Bip340Scheme, { name }, scope))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun Phone.await(what: String, p: (PairingState) -> Boolean) = flow.state.await(what, p)

    /** Starts [this] and returns the link it shows. */
    private fun Phone.startAndGetLink(): QrLink {
        flow.start()
        return (await("$what waiting with a link") { it is PairingState.Waiting && it.link != null } as PairingState.Waiting).link!!
    }

    private val Phone.what get() = "phone"

    @Test
    fun `starts Idle`() {
        assertEquals(PairingState.Idle, phone(1, "Alice", alice).flow.state.value)
    }

    @Test
    fun `without an identity it fails NO_IDENTITY and never touches the network`() {
        val p = phone(1, "Alice", key = null)
        p.flow.start()
        assertEquals(
            PairingState.Failed(PairingProblem.NO_IDENTITY),
            p.await("no identity") { it !is PairingState.Idle },
        )
        assertEquals(0, p.transport.startReceivingCalls.get())
    }

    @Test
    fun `with nearby switched off it fails NEARBY_OFF and opens nothing`() {
        val p = phone(1, "Alice", alice, enabled = false)
        p.flow.start()
        assertEquals(PairingState.Failed(PairingProblem.NEARBY_OFF), p.await("off") { it !is PairingState.Idle })
        assertEquals(0, p.transport.startReceivingCalls.get())
    }

    @Test
    fun `waiting carries the link to scan and this phone's name`() {
        val p = phone(1, "Alice", alice)
        val link = p.startAndGetLink()
        val waiting = p.flow.state.value as PairingState.Waiting
        assertEquals("Alice", waiting.deviceName)
        assertEquals(p.nearby.pairing.value, link)
    }

    @Test
    fun `cancel returns to Idle and stops listening`() {
        val p = phone(1, "Alice", alice)
        p.startAndGetLink()
        p.flow.cancel()
        assertEquals(PairingState.Idle, p.flow.state.value)
        assertEquals(1, p.transport.stopReceivingCalls.get())
        assertNull(p.nearby.pairing.value)
    }

    @Test
    fun `joining by link shows Connecting under the other phone's name`() {
        val a = phone(1, "Alice", alice)
        val b = phone(2, "Bob", bob)
        val link = b.startAndGetLink()
        a.startAndGetLink()
        val gate = CountDownLatch(1)
        a.transport.connectGate = gate
        a.flow.joinByLink(link)
        assertEquals(PairingState.Connecting("Bob"), a.await("connecting") { it is PairingState.Connecting })
        a.transport.failConnect = true
        gate.countDown()
    }

    @Test
    fun `a digit pairing shows the same code on both phones and ends Verified with the other's key`() {
        val a = phone(1, "Alice", alice)
        val b = phone(2, "Bob", bob)
        val link = b.startAndGetLink()
        a.startAndGetLink()
        a.flow.joinByLink(link.copy(token = null))
        val sender = a.await("alice code") { it is PairingState.ConfirmCode } as PairingState.ConfirmCode
        // Bob is only asked once Alice has said the digits match and sent her card.
        a.flow.confirmCode(true)
        val receiver = b.await("bob code") { it is PairingState.ConfirmCode } as PairingState.ConfirmCode
        assertEquals("Bob", sender.peerName)
        assertEquals("Alice", receiver.peerName)
        assertNotNull(sender.code)
        assertEquals(sender.code, receiver.code)
        b.flow.confirmCode(true)
        assertEquals(
            PairingState.Verified("Bob", bob.publicKey),
            a.await("alice verified") { it is PairingState.Verified || it is PairingState.Failed },
        )
        assertEquals(
            PairingState.Verified("Alice", alice.publicKey),
            b.await("bob verified") { it is PairingState.Verified || it is PairingState.Failed },
        )
    }

    @Test
    fun `a scanned phone is asked to confirm with no code`() {
        val a = phone(1, "Alice", alice)
        val b = phone(2, "Bob", bob)
        val link = b.startAndGetLink()
        a.startAndGetLink()
        a.flow.joinByLink(link)
        assertEquals(PairingState.ConfirmCode(null, "Alice"), b.await("bob asked") { it is PairingState.ConfirmCode })
    }

    @Test
    fun `a receiver answering different reaches the sender as DECLINED`() {
        val a = phone(1, "Alice", alice)
        val b = phone(2, "Bob", bob)
        val link = b.startAndGetLink()
        a.startAndGetLink()
        a.flow.joinByLink(link.copy(token = null))
        a.await("alice code") { it is PairingState.ConfirmCode }
        a.flow.confirmCode(true)
        b.await("bob code") { it is PairingState.ConfirmCode }
        b.flow.confirmCode(false)
        assertEquals(
            PairingState.Failed(PairingProblem.DECLINED),
            a.await("alice fails") { it is PairingState.Failed || it is PairingState.Verified },
        )
    }

    @Test
    fun `a sender answering different fails as CODE_MISMATCH`() {
        val a = phone(1, "Alice", alice)
        val b = phone(2, "Bob", bob)
        val link = b.startAndGetLink()
        a.startAndGetLink()
        a.flow.joinByLink(link.copy(token = null))
        a.await("alice code") { it is PairingState.ConfirmCode }
        a.flow.confirmCode(false)
        assertEquals(PairingState.Failed(PairingProblem.CODE_MISMATCH), a.await("alice fails") { it is PairingState.Failed })
    }

    @Test
    fun `an unreachable phone ends as CONNECTION_LOST`() {
        val a = phone(1, "Alice", alice)
        val own = a.startAndGetLink()
        a.transport.failConnect = true
        a.flow.joinByLink(own.copy(address = "10.0.0.77"))
        assertEquals(PairingState.Failed(PairingProblem.CONNECTION_LOST), a.await("lost") { it is PairingState.Failed })
    }

    @Test
    fun `starting again replaces the earlier attempt`() {
        val p = phone(1, "Alice", alice)
        p.startAndGetLink()
        p.flow.start()
        p.await("waiting again") { it is PairingState.Waiting }
        assertTrue(p.flow.state.value is PairingState.Waiting)
    }

    // --- problemOf -----------------------------------------------------------------------------

    @Test
    fun `problemOf maps every nearby problem to a pairing problem`() {
        val mismatch = setOf(NearbyProblem.CODES_DID_NOT_MATCH, NearbyProblem.KEY_NOT_AS_PROMISED, NearbyProblem.BAD_PAIRING)
        val declined = setOf(NearbyProblem.DECLINED, NearbyProblem.CANCELLED)
        for (problem in NearbyProblem.entries) {
            val expected = when (problem) {
                in mismatch -> PairingProblem.CODE_MISMATCH
                in declined -> PairingProblem.DECLINED
                else -> PairingProblem.CONNECTION_LOST
            }
            assertEquals(problem.name, expected, NearbyPairingFlow.problemOf(problem))
        }
    }
}
