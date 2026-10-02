package com.vibethroughcode.ftree.nearby

import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.pairing.IdentityCards
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import com.vibethroughcode.ftree.nearby.wire.Dh
import com.vibethroughcode.ftree.nearby.wire.Handshake
import com.vibethroughcode.ftree.nearby.wire.NearbyProblem
import com.vibethroughcode.ftree.nearby.wire.NearbyProtocol
import com.vibethroughcode.ftree.nearby.wire.PairingIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A whole pairing between the two halves, on the JVM: the same pipes, handshake and six digits as a
 * file transfer, with identity cards exchanged in place of an offer.
 */
class NearbyPairingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Pipe(
        override val input: InputStream,
        override val output: OutputStream,
        override val remoteAddress: String,
    ) : NearbyChannel {
        override fun close() {
            runCatching { output.close() }
            runCatching { input.close() }
        }
    }

    private fun pair(): Pair<NearbyChannel, NearbyChannel> {
        val senderOut = PipedOutputStream()
        val receiverIn = PipedInputStream(senderOut, 1 shl 16)
        val receiverOut = PipedOutputStream()
        val senderIn = PipedInputStream(receiverOut, 1 shl 16)
        return Pipe(senderIn, senderOut, "10.0.0.1") to Pipe(receiverIn, receiverOut, "10.0.0.2")
    }

    private fun device(seed: Byte) = object : NearbySelf {
        override val deviceId = DeviceId(ByteArray(16) { seed })
        override val displayName = "Device $seed"
    }

    private class Recorder : NearbyTransferListener {
        @Volatile var sas: String? = null
        @Volatile var requested = false
        @Volatile var peerKey: String? = null
        @Volatile var problem: NearbyProblem? = null

        override fun onCode(sas: String) { this.sas = sas }
        override fun onPairingRequest() { requested = true }
        override fun onPeerIdentified(publicKey: String) { peerKey = publicKey }
        override fun onFailed(problem: NearbyProblem, importProblem: com.vibethroughcode.ftree.transfer.ImportProblem?) { this.problem = problem }
    }

    private class Outcome(
        val sender: Recorder,
        val receiver: Recorder,
        val sendProblem: NearbyProblem?,
        val receiveProblem: NearbyProblem?,
    )

    private val alice = Bip340Scheme.generateKeyPair()
    private val bob = Bip340Scheme.generateKeyPair()

    private fun cards(key: KeyPair): PairingIdentity = IdentityCards(Bip340Scheme, key)

    /**
     * @param senderCards / receiverCards what each side presents; defaults to their own real cards
     * @param confirm what the sender's person says about the digits
     * @param accept what the receiver's person says to "pair with them?"
     */
    private fun run(
        senderCards: PairingIdentity? = cards(alice),
        receiverCards: PairingIdentity? = cards(bob),
        token: ByteArray = Handshake.NO_TOKEN,
        confirm: Boolean = true,
        accept: Boolean = true,
        senderOutgoing: com.vibethroughcode.ftree.nearby.OutgoingFile? = null,
    ): Outcome {
        val (senderChannel, receiverChannel) = pair()
        val beaconPrivate = Dh.generatePrivate()
        val beaconPublic = Dh.publicOf(beaconPrivate)
        val sendRecorder = Recorder()
        val receiveRecorder = Recorder()
        val qr = !token.contentEquals(Handshake.NO_TOKEN)

        val sendTransfer = NearbySendTransfer(
            identity = device(1),
            outgoing = senderOutgoing,
            pairedByQr = qr,
            pairingToken = token,
            expectedFingerprint = Handshake.beaconFingerprint(DeviceId(ByteArray(16) { 2 }).bytes, beaconPublic),
            listener = sendRecorder,
            pairing = senderCards,
        )
        val receiveTransfer = NearbyReceiveTransfer(
            identity = device(2),
            beaconPrivateKey = beaconPrivate,
            beaconPublicKey = beaconPublic,
            sink = ByteArrayOutputStream(),
            pairingToken = token,
            listener = receiveRecorder,
            pairing = receiverCards,
        )

        val done = CountDownLatch(2)
        var sendProblem: NearbyProblem? = null
        var receiveProblem: NearbyProblem? = null
        thread(name = "pair-receiver") { receiveProblem = receiveTransfer.run(receiverChannel); done.countDown() }
        thread(name = "pair-sender") { sendProblem = sendTransfer.run(senderChannel); done.countDown() }
        thread(name = "pair-people", isDaemon = true) {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var confirmed = qr
            var answered = false
            if (qr) sendTransfer.confirmCode(true)
            while (System.currentTimeMillis() < deadline && (!confirmed || !answered)) {
                if (!confirmed && sendRecorder.sas != null) { sendTransfer.confirmCode(confirm); confirmed = true }
                if (!answered && receiveRecorder.requested) {
                    if (accept) receiveTransfer.accept() else receiveTransfer.decline()
                    answered = true
                }
                Thread.sleep(5)
            }
        }
        assertTrue("the pairing did not finish", done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return Outcome(sendRecorder, receiveRecorder, sendProblem, receiveProblem)
    }

    @Test
    fun `two people who compare the digits end up holding each others keys`() {
        val out = run()
        assertNull("sender: ${out.sendProblem}", out.sendProblem)
        assertNull("receiver: ${out.receiveProblem}", out.receiveProblem)
        assertEquals(bob.publicKey, out.sender.peerKey)
        assertEquals(alice.publicKey, out.receiver.peerKey)
        assertEquals(out.sender.sas, out.receiver.sas)
        assertEquals(6, out.sender.sas!!.length)
    }

    @Test
    fun `scanning the code pairs without comparing digits`() {
        val token = ByteArray(NearbyProtocol.PAIRING_TOKEN_BYTES) { (it + 1).toByte() }
        val out = run(token = token)
        assertNull("sender: ${out.sendProblem}", out.sendProblem)
        assertNull("receiver: ${out.receiveProblem}", out.receiveProblem)
        assertEquals(bob.publicKey, out.sender.peerKey)
        assertEquals(alice.publicKey, out.receiver.peerKey)
    }

    @Test
    fun `digits that do not match end the pairing and nobody learns a key`() {
        val out = run(confirm = false)
        assertEquals(NearbyProblem.CODES_DID_NOT_MATCH, out.sendProblem)
        assertNull(out.sender.peerKey)
        assertNull(out.receiver.peerKey)
    }

    @Test
    fun `a receiver who says no sends nothing back`() {
        val out = run(accept = false)
        assertNotNull(out.sendProblem)
        assertNull(out.sender.peerKey)
        // The receiver proved the sender's card but never agreed, so it records nothing either.
        assertNull(out.receiver.peerKey)
    }

    @Test
    fun `a card that does not verify for this session is refused`() {
        // A card made for another session's digits: right key, wrong binding.
        val stale = object : PairingIdentity {
            private val real = cards(alice)
            override fun card(sas: String, role: Int) = real.card("000000", role)
            override fun peerKey(card: ByteArray, sas: String, role: Int) = real.peerKey(card, sas, role)
        }
        val out = run(senderCards = stale)
        assertEquals(NearbyProblem.BAD_PAIRING, out.receiveProblem)
        assertNull(out.receiver.peerKey)
        assertNull(out.sender.peerKey)
    }

    @Test
    fun `a sender cannot present a key it does not hold`() {
        // Alice's public key with a signature made by Bob's private key.
        val forged = object : PairingIdentity {
            private val signer = cards(bob)
            override fun card(sas: String, role: Int): ByteArray =
                cards(alice).card(sas, role).copyOfRange(0, 32) + signer.card(sas, role).copyOfRange(32, 96)
            override fun peerKey(card: ByteArray, sas: String, role: Int) = signer.peerKey(card, sas, role)
        }
        val out = run(senderCards = forged)
        assertEquals(NearbyProblem.BAD_PAIRING, out.receiveProblem)
        assertNull(out.receiver.peerKey)
    }

    @Test
    fun `a file sender is not taken for a pairing and the reverse`() {
        val file = File(folder.newFolder(), "family.ftree").apply { writeBytes(ByteArray(64)) }
        val asFile = run(
            senderCards = null,
            senderOutgoing = com.vibethroughcode.ftree.nearby.OutgoingFile(file, 1, 0, 0, "family.ftree"),
        )
        // A pairing receiver wants a card, not an offer.
        assertNull(asFile.receiver.peerKey)
        assertNotNull(asFile.receiveProblem)
    }
}

private const val TIMEOUT_MS = 20_000L
