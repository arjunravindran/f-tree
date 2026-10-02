package com.vibethroughcode.ftree.nearby

import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.pairing.IdentityCards
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import com.vibethroughcode.ftree.nearby.wire.NearbyProblem
import com.vibethroughcode.ftree.nearby.wire.PairingIdentity
import com.vibethroughcode.ftree.nearby.wire.QrLink
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [NearbyRepository]'s pairing mode, with two repositories talking over an in-memory LAN: the real
 * protocol and cards, no sockets. Only the transport and the SharedPreferences are fakes.
 */
class NearbyRepositoryPairingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lan = Lan()
    private val alice = Bip340Scheme.generateKeyPair()
    private val bob = Bip340Scheme.generateKeyPair()

    private fun cards(key: KeyPair): PairingIdentity = IdentityCards(Bip340Scheme, key)

    private class Side(val prefs: NearbyPreferences, val transport: LoopTransport, val repo: NearbyRepository)

    private fun side(seed: Byte, name: String, enabled: Boolean = true): Side {
        val prefs = testPreferences(enabled)
        val transport = LoopTransport(lan, "10.0.0.$seed")
        val repo = NearbyRepository(prefs, testSelf(seed, name), transport, folder.newFolder(), scope)
        return Side(prefs, transport, repo)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun fakeLink(address: String = "10.0.0.9", token: ByteArray? = null) = QrLink(
        address = address,
        port = LoopTransport.PORT,
        deviceId = DeviceId(ByteArray(16) { 9 }),
        keyFingerprint = ByteArray(32),
        token = token,
        displayName = "Somebody",
    )

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    // --- beginPairing / endPairing -------------------------------------------------------------

    @Test
    fun `beginPairing with nearby switched off reports Off and opens nothing`() {
        val s = side(1, "Alice", enabled = false)
        s.repo.beginPairing(cards(alice))
        assertEquals(NearbyPairState.Off, s.repo.pair.value)
        assertEquals(0, s.transport.startReceivingCalls.get())
        assertNull(s.repo.listening.value)
        assertNull(s.repo.pairing.value)
    }

    @Test
    fun `beginPairing makes the device listen and publishes a code to scan`() {
        val s = side(1, "Alice")
        s.repo.beginPairing(cards(alice))
        assertEquals(NearbyPairState.Idle, s.repo.pair.value)
        assertEquals(1, s.transport.startReceivingCalls.get())
        val link = s.repo.pairing.value
        assertNotNull(link)
        assertEquals("10.0.0.1", link!!.address)
        assertEquals(LoopTransport.PORT, link.port)
        assertEquals("Alice", link.displayName)
        assertNotNull(link.token)
        // A pairing is not shown as a file: the file-sharing state is left alone.
        assertTrue(s.repo.state.value is NearbyState.Browsing)
    }

    @Test
    fun `endPairing stops listening and takes the code off the screen`() {
        val s = side(1, "Alice")
        s.repo.beginPairing(cards(alice))
        s.repo.endPairing()
        assertEquals(NearbyPairState.Idle, s.repo.pair.value)
        assertEquals(1, s.transport.stopReceivingCalls.get())
        assertNull(s.repo.pairing.value)
        assertNull(s.repo.listening.value)
        assertNull(s.transport.acceptor)
    }

    @Test
    fun `endPairing with no pairing started does nothing`() {
        val s = side(1, "Alice")
        s.repo.endPairing()
        assertEquals(NearbyPairState.Idle, s.repo.pair.value)
        assertEquals(0, s.transport.stopReceivingCalls.get())
    }

    @Test
    fun `endPairing after a failure clears it back to Idle`() {
        val s = side(1, "Alice")
        s.repo.beginPairing(cards(alice))
        s.transport.failConnect = true
        s.repo.pairByLink(fakeLink())
        s.repo.pair.await("failure") { it is NearbyPairState.Failed }
        s.repo.endPairing()
        assertEquals(NearbyPairState.Idle, s.repo.pair.value)
    }

    // --- pairByLink guards ---------------------------------------------------------------------

    @Test
    fun `pairByLink before beginPairing is ignored`() {
        val s = side(1, "Alice")
        s.repo.pairByLink(fakeLink())
        assertEquals(NearbyPairState.Idle, s.repo.pair.value)
        assertEquals(0, s.transport.connectCalls.get())
    }

    @Test
    fun `pairByLink after nearby is switched off says Off and does not connect`() {
        val s = side(1, "Alice")
        s.repo.beginPairing(cards(alice))
        s.prefs.setEnabled(false)
        s.repo.pairByLink(fakeLink())
        assertEquals(NearbyPairState.Off, s.repo.pair.value)
        assertEquals(0, s.transport.connectCalls.get())
    }

    @Test
    fun `pairByLink to somebody unreachable fails as a network problem and can be retried`() {
        val s = side(1, "Alice")
        s.repo.beginPairing(cards(alice))
        s.transport.failConnect = true
        s.repo.pairByLink(fakeLink())
        assertEquals(
            NearbyPairState.Failed(NearbyProblem.NETWORK),
            s.repo.pair.await("network failure") { it is NearbyPairState.Failed },
        )
        // The slot was released: a second try reaches connect again.
        s.repo.pairByLink(fakeLink())
        awaitTrue("second connect") { s.transport.connectCalls.get() == 2 }
    }

    @Test
    fun `pairByLink names the device from the link and ignores a second link while one is in flight`() {
        val s = side(1, "Alice")
        s.repo.beginPairing(cards(alice))
        val gate = CountDownLatch(1)
        s.transport.connectGate = gate
        s.repo.pairByLink(fakeLink())
        assertEquals(NearbyPairState.Connecting("Somebody"), s.repo.pair.value)
        s.repo.pairByLink(fakeLink(address = "10.0.0.8"))
        awaitTrue("first connect") { s.transport.connectCalls.get() >= 1 }
        assertEquals(1, s.transport.connectCalls.get())
        assertEquals(NearbyPairState.Connecting("Somebody"), s.repo.pair.value)
        s.transport.failConnect = true
        gate.countDown()
        s.repo.pair.await("failure after gate") { it is NearbyPairState.Failed }
    }

    @Test
    fun `a link without a display name is named by its address`() {
        val s = side(1, "Alice")
        s.repo.beginPairing(cards(alice))
        val gate = CountDownLatch(1)
        s.transport.connectGate = gate
        s.repo.pairByLink(fakeLink().copy(displayName = null))
        assertEquals(NearbyPairState.Connecting("10.0.0.9"), s.repo.pair.value)
        s.transport.failConnect = true
        gate.countDown()
    }

    // --- whole pairings between two repositories -----------------------------------------------

    private class Phones(val sender: Side, val receiver: Side, val link: QrLink)

    /** Alice (sender) and Bob (receiver) both in pairing mode, with Bob's code in hand. */
    private fun twoPhones(scanned: Boolean): Phones {
        val a = side(1, "Alice")
        val b = side(2, "Bob")
        b.repo.beginPairing(cards(bob))
        a.repo.beginPairing(cards(alice))
        val link = b.repo.pairing.value!!
        return Phones(a, b, if (scanned) link else link.copy(token = null))
    }

    private fun NearbyRepository.settled(what: String) =
        pair.await(what) { it is NearbyPairState.Paired || it is NearbyPairState.Failed }

    @Test
    fun `scanning the code pairs two phones and each learns the other's key`() {
        val p = twoPhones(scanned = true)
        p.sender.repo.pairByLink(p.link)
        // The scanning phone compares nothing; the scanned one is only asked whether to pair.
        val requested = p.receiver.repo.pair.await("receiver asked") { it is NearbyPairState.Requested }
            as NearbyPairState.Requested
        assertEquals("Alice", requested.peerName)
        assertNull(requested.code)
        p.receiver.repo.answerPairing(true)
        assertEquals(NearbyPairState.Paired(alice.publicKey, "Alice"), p.receiver.repo.settled("receiver paired"))
        assertEquals(NearbyPairState.Paired(bob.publicKey, "Bob"), p.sender.repo.settled("sender paired"))
        // Still not a file transfer.
        assertTrue(p.sender.repo.state.value !is NearbyState.Failed)
    }

    @Test
    fun `comparing digits shows the same six on both phones and pairs on a yes from each`() {
        val p = twoPhones(scanned = false)
        p.sender.repo.pairByLink(p.link)
        val code = p.sender.repo.pair.await("sender code") { it is NearbyPairState.ConfirmCode }
            as NearbyPairState.ConfirmCode
        assertEquals("Bob", code.peerName)
        assertEquals(6, code.code.length)
        // The receiver is only asked once the sender has said the digits match and sent its card.
        p.sender.repo.answerPairing(true)
        val requested = p.receiver.repo.pair.await("receiver asked") { it is NearbyPairState.Requested }
            as NearbyPairState.Requested
        assertEquals(code.code, requested.code)
        p.receiver.repo.answerPairing(true)
        assertEquals(NearbyPairState.Paired(bob.publicKey, "Bob"), p.sender.repo.settled("sender paired"))
        assertEquals(NearbyPairState.Paired(alice.publicKey, "Alice"), p.receiver.repo.settled("receiver paired"))
    }

    @Test
    fun `a sender who says the digits differ fails with CODES_DID_NOT_MATCH and nobody is paired`() {
        val p = twoPhones(scanned = false)
        p.sender.repo.pairByLink(p.link)
        p.sender.repo.pair.await("sender code") { it is NearbyPairState.ConfirmCode }
        p.sender.repo.answerPairing(false)
        assertEquals(NearbyPairState.Failed(NearbyProblem.CODES_DID_NOT_MATCH), p.sender.repo.settled("sender fails"))
        assertTrue(p.receiver.repo.settled("receiver ends") is NearbyPairState.Failed)
    }

    @Test
    fun `a receiver who says no reaches the sender as DECLINED`() {
        val p = twoPhones(scanned = false)
        p.sender.repo.pairByLink(p.link)
        p.sender.repo.pair.await("sender code") { it is NearbyPairState.ConfirmCode }
        p.sender.repo.answerPairing(true)
        p.receiver.repo.pair.await("receiver asked") { it is NearbyPairState.Requested }
        p.receiver.repo.answerPairing(false)
        assertEquals(NearbyPairState.Failed(NearbyProblem.DECLINED), p.sender.repo.settled("sender fails"))
    }

    @Test
    fun `a card made for another session is refused by the receiver as BAD_PAIRING`() {
        val a = side(1, "Alice")
        val b = side(2, "Bob")
        val real = cards(alice)
        a.repo.beginPairing(object : PairingIdentity {
            override fun card(sas: String, role: Int) = real.card("000000", role)
            override fun peerKey(card: ByteArray, sas: String, role: Int) = real.peerKey(card, sas, role)
        })
        b.repo.beginPairing(cards(bob))
        a.repo.pairByLink(b.repo.pairing.value!!)
        // Whatever each person is asked on the way, they say yes; the card is what must stop it.
        val answers = Thread {
            repeat(2) {
                a.repo.pair.value.let { if (it is NearbyPairState.ConfirmCode) a.repo.answerPairing(true) }
                b.repo.pair.value.let { if (it is NearbyPairState.Requested) b.repo.answerPairing(true) }
                Thread.sleep(50)
            }
        }
        answers.start()
        assertEquals(NearbyPairState.Failed(NearbyProblem.BAD_PAIRING), b.repo.settled("receiver refuses"))
        answers.join()
    }

    @Test
    fun `a connection to a phone that is not in pairing mode is not answered as a pairing`() {
        // Bob is merely visible for files, so receivePairing must not run and no pair state appears.
        val a = side(1, "Alice")
        val b = side(2, "Bob")
        b.repo.setVisible(true)
        a.repo.beginPairing(cards(alice))
        a.repo.pairByLink(fakeLink(address = "10.0.0.2"))
        assertTrue(a.repo.settled("sender ends") is NearbyPairState.Failed)
        assertEquals(NearbyPairState.Idle, b.repo.pair.value)
    }

    @Test
    fun `ending a pairing while the other phone waits releases it`() {
        val p = twoPhones(scanned = false)
        p.sender.repo.pairByLink(p.link)
        p.sender.repo.pair.await("sender code") { it is NearbyPairState.ConfirmCode }
        p.sender.repo.answerPairing(true)
        p.receiver.repo.pair.await("receiver asked") { it is NearbyPairState.Requested }
        p.receiver.repo.endPairing()
        assertEquals(NearbyPairState.Idle, p.receiver.repo.pair.value)
        assertTrue(p.sender.repo.settled("sender ends") is NearbyPairState.Failed)
    }
}
