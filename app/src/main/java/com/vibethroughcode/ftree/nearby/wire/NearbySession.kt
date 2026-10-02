package com.vibethroughcode.ftree.nearby.wire

import com.vibethroughcode.ftree.transfer.ImportProblem

/**
 * Where a transfer has got to, and what should happen next.
 *
 * A pure fold: `(State, Event) -> (State, List<Action>)`. It owns no socket, no file and no clock —
 * the caller supplies the time in [NearbyEvent.Tick]. Everything that decides whether somebody's
 * family moves is therefore a function of its arguments, and the awkward parts are tested on the
 * JVM, where CI can run them. CI has no emulator, so anything that needed a device would in
 * practice be tested by hand, once, by whoever wrote it.
 *
 * The rule worth stating separately, because it is a safety property and not a flow: **the sender
 * cannot reach [SenderState.SENDING] without passing through [SenderState.CONFIRMING_CODE], unless
 * the peer was reached by scanning a QR.** A code that can be skipped by accident is a code that is
 * not doing anything.
 */

enum class SenderState {
    CONNECTING,
    AWAITING_HELLO_ACK,
    AWAITING_KEY_ACK,
    CONFIRMING_CODE,
    AWAITING_ACCEPT,

    /** Pairing only: this side card is sent, and the peer one is awaited. */
    AWAITING_PEER_IDENTITY,
    SENDING,
    AWAITING_RESULT,
    DONE,
    FAILED,
}

enum class ReceiverState {
    LISTENING,
    AWAITING_HELLO,
    AWAITING_KEY,
    AWAITING_OFFER,
    AWAITING_USER,
    RECEIVING,
    VERIFYING,
    DONE,
    FAILED,
}

sealed interface NearbyEvent {
    data object Connected : NearbyEvent
    data class FrameReceived(val type: Int, val payload: ByteArray) : NearbyEvent {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is FrameReceived && type == other.type && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()
    }

    data class UserConfirmedCode(val matched: Boolean) : NearbyEvent
    data object UserAccepted : NearbyEvent
    data object UserDeclined : NearbyEvent
    data object UserCancelled : NearbyEvent

    /** Bytes read from the file being sent. Empty means the file is finished. */
    data class FileChunkRead(val bytes: ByteArray) : NearbyEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is FileChunkRead && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object PeerClosed : NearbyEvent
    data class Tick(val atMillis: Long) : NearbyEvent
}

sealed interface NearbyAction {
    data class SendFrame(val type: Int, val payload: ByteArray) : NearbyAction {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is SendFrame && type == other.type && payload.contentEquals(other.payload))

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()

        override fun toString(): String = "SendFrame(0x%02X, ${payload.size}B)".format(type)
    }

    /** Both screens should now show these six digits. */
    data class ShowCode(val sas: String) : NearbyAction
    data class ShowOffer(val offer: Offer) : NearbyAction

    /** A pairing request has arrived and waits for the person to say yes. */
    data object ShowPairing : NearbyAction

    /** The other device has proved it holds [publicKey], over a channel the two people verified. */
    data class PeerIdentified(val publicKey: String) : NearbyAction
    data object ReadMoreOfTheFile : NearbyAction
    data class WriteChunk(val bytes: ByteArray) : NearbyAction {
        override fun equals(other: Any?): Boolean =
            this === other || (other is WriteChunk && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "WriteChunk(${bytes.size}B)"
    }

    data object VerifyAndHandOver : NearbyAction
    data class Fail(val problem: NearbyProblem, val importProblem: ImportProblem? = null) : NearbyAction
    data object Close : NearbyAction
}

/**
 * What a pairing conversation needs from the identity layer, kept as an interface so the sessions
 * stay pure and know nothing of keys or signatures.
 *
 * A card is bound to the session by [sas], the six digits both screens show: a card recorded from
 * an earlier pairing does not verify in this one, and the [role] stops one side's card being played
 * back to it as the other's.
 */
interface PairingIdentity {
    /** This device's card for [role] ([NearbyProtocol.ROLE_SENDER] or `ROLE_RECEIVER`). */
    fun card(sas: String, role: Int): ByteArray

    /** The public key (hex) the peer proved it holds, or null if the card is not a valid one. */
    fun peerKey(card: ByteArray, sas: String, role: Int): String?
}

/** One step of the fold. */
data class Step<S>(val state: S, val actions: List<NearbyAction>)

/**
 * The sending half.
 *
 * [pairedByQr] is passed in rather than discovered, because it is the one thing that legitimately
 * removes a safety step and it should be visible at the point the session is constructed rather
 * than inferred halfway through a handshake.
 */
class SenderSession(
    private val pairedByQr: Boolean,
    private val hello: () -> ByteArray,
    private val key: () -> ByteArray,
    private val onHelloAck: (HelloAck) -> Unit,
    private val onKeyAck: (KeyMessage) -> String,
    private val offer: () -> Offer,
    /** Non-null makes this a pairing: an identity card is exchanged instead of an offer. */
    private val identity: PairingIdentity? = null,
) {
    private var sas = ""

    var state: SenderState = SenderState.CONNECTING
        private set

    fun step(event: NearbyEvent): Step<SenderState> {
        if (state == SenderState.DONE || state == SenderState.FAILED) {
            return Step(state, emptyList())
        }
        if (event is NearbyEvent.UserCancelled) return fail(NearbyProblem.CANCELLED)
        if (event is NearbyEvent.PeerClosed) return fail(NearbyProblem.CONNECTION_LOST)

        return try {
            when (state) {
                SenderState.CONNECTING -> onConnecting(event)
                SenderState.AWAITING_HELLO_ACK -> onAwaitingHelloAck(event)
                SenderState.AWAITING_KEY_ACK -> onAwaitingKeyAck(event)
                SenderState.CONFIRMING_CODE -> onConfirmingCode(event)
                SenderState.AWAITING_ACCEPT -> onAwaitingAccept(event)
                SenderState.AWAITING_PEER_IDENTITY -> onAwaitingPeerIdentity(event)
                SenderState.SENDING -> onSending(event)
                SenderState.AWAITING_RESULT -> onAwaitingResult(event)
                else -> Step(state, emptyList())
            }
        } catch (failure: NearbyFailure) {
            fail(failure.problem, failure.importProblem)
        }
    }

    private fun onConnecting(event: NearbyEvent): Step<SenderState> = when (event) {
        is NearbyEvent.Connected -> {
            state = SenderState.AWAITING_HELLO_ACK
            Step(state, listOf(NearbyAction.SendFrame(NearbyProtocol.TYPE_HELLO, hello())))
        }
        else -> ignore(event)
    }

    private fun onAwaitingHelloAck(event: NearbyEvent): Step<SenderState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_HELLO_ACK -> {
            onHelloAck(HelloAck.decode(event.payload))
            state = SenderState.AWAITING_KEY_ACK
            Step(state, listOf(NearbyAction.SendFrame(NearbyProtocol.TYPE_KEY, key())))
        }
        else -> ignore(event)
    }

    private fun onAwaitingKeyAck(event: NearbyEvent): Step<SenderState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_KEY_ACK -> {
            sas = onKeyAck(KeyMessage.decode(event.payload))
            if (pairedByQr) {
                // The token in the code already proved both directions. There is nothing left for
                // a person to check, and asking them to check nothing teaches them to tap through.
                proceed()
            } else {
                state = SenderState.CONFIRMING_CODE
                Step(state, listOf(NearbyAction.ShowCode(sas)))
            }
        }
        else -> ignore(event)
    }

    private fun onConfirmingCode(event: NearbyEvent): Step<SenderState> = when {
        event is NearbyEvent.UserConfirmedCode && event.matched -> proceed()
        event is NearbyEvent.UserConfirmedCode -> fail(NearbyProblem.CODES_DID_NOT_MATCH)
        else -> ignore(event)
    }

    /** Past the point where the other device is known to be the one in front of this one. */
    private fun proceed(): Step<SenderState> =
        if (identity != null) {
            state = SenderState.AWAITING_PEER_IDENTITY
            Step(state, listOf(NearbyAction.SendFrame(NearbyProtocol.TYPE_IDENTITY, identity.card(sas, NearbyProtocol.ROLE_SENDER))))
        } else {
            state = SenderState.AWAITING_ACCEPT
            Step(state, listOf(sendOffer()))
        }

    private fun onAwaitingPeerIdentity(event: NearbyEvent): Step<SenderState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_IDENTITY -> {
            val key = identity?.peerKey(event.payload, sas, NearbyProtocol.ROLE_RECEIVER)
            if (key == null) {
                fail(NearbyProblem.BAD_PAIRING)
            } else {
                state = SenderState.DONE
                Step(state, listOf(NearbyAction.PeerIdentified(key), NearbyAction.Close))
            }
        }
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_DECLINE -> fail(NearbyProblem.DECLINED)
        else -> ignore(event)
    }

    private fun onAwaitingAccept(event: NearbyEvent): Step<SenderState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_ACCEPT -> {
            state = SenderState.SENDING
            Step(state, listOf(NearbyAction.ReadMoreOfTheFile))
        }
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_DECLINE ->
            fail(NearbyProblem.DECLINED)
        else -> ignore(event)
    }

    private fun onSending(event: NearbyEvent): Step<SenderState> = when {
        event is NearbyEvent.FileChunkRead && event.bytes.isNotEmpty() -> Step(
            state,
            listOf(
                NearbyAction.SendFrame(NearbyProtocol.TYPE_DATA, event.bytes),
                NearbyAction.ReadMoreOfTheFile,
            ),
        )
        event is NearbyEvent.FileChunkRead -> {
            state = SenderState.AWAITING_RESULT
            val summary = offer()
            Step(
                state,
                listOf(
                    NearbyAction.SendFrame(
                        NearbyProtocol.TYPE_END,
                        End(summary.totalBytes, summary.sha256).encode(),
                    ),
                ),
            )
        }
        else -> ignore(event)
    }

    private fun onAwaitingResult(event: NearbyEvent): Step<SenderState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_RESULT -> {
            val result = Result.decode(event.payload)
            if (result.accepted) {
                state = SenderState.DONE
                Step(state, listOf(NearbyAction.Close))
            } else {
                fail(NearbyProblem.IMPORT_REFUSED, result.importProblem)
            }
        }
        else -> ignore(event)
    }

    private fun sendOffer() =
        NearbyAction.SendFrame(NearbyProtocol.TYPE_OFFER, offer().encode())

    /**
     * Anything that is not expected here ends the connection.
     *
     * Never skipped, and that is the whole of it: ignoring an unrecognised frame in an encrypted
     * stream means consuming a sequence number whose meaning is unknown, which is exactly how a
     * downgrade is smuggled past a version check. A [NearbyEvent.Tick] is the one thing that may
     * pass through untouched, because the caller sends it constantly and it says nothing.
     */
    private fun ignore(event: NearbyEvent): Step<SenderState> =
        if (event is NearbyEvent.Tick) Step(state, emptyList())
        else fail(NearbyProblem.UNEXPECTED_MESSAGE)

    private fun fail(
        problem: NearbyProblem,
        importProblem: ImportProblem? = null,
    ): Step<SenderState> {
        val wasLive = state != SenderState.CONNECTING
        state = SenderState.FAILED
        return Step(
            state,
            buildList {
                if (wasLive) {
                    add(NearbyAction.SendFrame(NearbyProtocol.TYPE_ABORT, Abort(problem).encode()))
                }
                add(NearbyAction.Fail(problem, importProblem))
                add(NearbyAction.Close)
            },
        )
    }
}

/** The receiving half. Consent lives here, which is why this side is the one that advertises. */
class ReceiverSession(
    private val busy: Boolean = false,
    private val helloAck: (Hello) -> ByteArray,
    private val keyAck: (KeyMessage) -> Pair<ByteArray, String>,
    private val acceptable: (Offer) -> NearbyProblem?,
    /** Non-null makes this a pairing: it expects an identity card where a transfer expects an offer. */
    private val identity: PairingIdentity? = null,
) {
    private var sas = ""
    private var peerKey: String? = null

    var state: ReceiverState = ReceiverState.LISTENING
        private set

    private var offer: Offer? = null

    fun step(event: NearbyEvent): Step<ReceiverState> {
        if (state == ReceiverState.DONE || state == ReceiverState.FAILED) {
            return Step(state, emptyList())
        }
        if (event is NearbyEvent.UserCancelled) return fail(NearbyProblem.CANCELLED)
        if (event is NearbyEvent.PeerClosed && state != ReceiverState.LISTENING) {
            return fail(NearbyProblem.CONNECTION_LOST)
        }

        return try {
            when (state) {
                ReceiverState.LISTENING -> onListening(event)
                ReceiverState.AWAITING_HELLO -> onAwaitingHello(event)
                ReceiverState.AWAITING_KEY -> onAwaitingKey(event)
                ReceiverState.AWAITING_OFFER -> onAwaitingOffer(event)
                ReceiverState.AWAITING_USER -> onAwaitingUser(event)
                ReceiverState.RECEIVING -> onReceiving(event)
                else -> Step(state, emptyList())
            }
        } catch (failure: NearbyFailure) {
            fail(failure.problem, failure.importProblem)
        }
    }

    private fun onListening(event: NearbyEvent): Step<ReceiverState> = when (event) {
        is NearbyEvent.Connected ->
            // Accepted only far enough to say so. A refusal somebody can read beats a hang.
            if (busy) {
                fail(NearbyProblem.BUSY)
            } else {
                state = ReceiverState.AWAITING_HELLO
                Step(state, emptyList())
            }
        else -> ignore(event)
    }

    private fun onAwaitingHello(event: NearbyEvent): Step<ReceiverState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_HELLO -> {
            val reply = helloAck(Hello.decode(event.payload))
            state = ReceiverState.AWAITING_KEY
            Step(state, listOf(NearbyAction.SendFrame(NearbyProtocol.TYPE_HELLO_ACK, reply)))
        }
        else -> ignore(event)
    }

    private fun onAwaitingKey(event: NearbyEvent): Step<ReceiverState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_KEY -> {
            val (reply, shown) = keyAck(KeyMessage.decode(event.payload))
            sas = shown
            state = ReceiverState.AWAITING_OFFER
            Step(
                state,
                listOf(
                    NearbyAction.SendFrame(NearbyProtocol.TYPE_KEY_ACK, reply),
                    NearbyAction.ShowCode(shown),
                ),
            )
        }
        else -> ignore(event)
    }

    private fun onAwaitingOffer(event: NearbyEvent): Step<ReceiverState> = when {
        identity != null && event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_IDENTITY -> {
            val key = identity.peerKey(event.payload, sas, NearbyProtocol.ROLE_SENDER)
            if (key == null) {
                fail(NearbyProblem.BAD_PAIRING)
            } else {
                // Proven, but not yet agreed to: nothing is saved and nothing is sent back until a
                // person says yes.
                peerKey = key
                state = ReceiverState.AWAITING_USER
                Step(state, listOf(NearbyAction.ShowPairing))
            }
        }
        identity == null && event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_OFFER -> {
            val incoming = Offer.decode(event.payload)
            val refusal = acceptable(incoming)
            if (refusal != null) {
                // Refused on size or space before a byte moves, which is the reason the offer
                // carries a length at all.
                declineWith(refusal)
            } else {
                offer = incoming
                state = ReceiverState.AWAITING_USER
                Step(state, listOf(NearbyAction.ShowOffer(incoming)))
            }
        }
        else -> ignore(event)
    }

    private fun onAwaitingUser(event: NearbyEvent): Step<ReceiverState> = when (event) {
        is NearbyEvent.UserAccepted -> if (identity != null && peerKey != null) {
            state = ReceiverState.DONE
            Step(
                state,
                listOf(
                    NearbyAction.SendFrame(NearbyProtocol.TYPE_IDENTITY, identity.card(sas, NearbyProtocol.ROLE_RECEIVER)),
                    NearbyAction.PeerIdentified(peerKey!!),
                    NearbyAction.Close,
                ),
            )
        } else {
            state = ReceiverState.RECEIVING
            Step(state, listOf(NearbyAction.SendFrame(NearbyProtocol.TYPE_ACCEPT, ByteArray(0))))
        }
        is NearbyEvent.UserDeclined -> declineWith(NearbyProblem.DECLINED)
        is NearbyEvent.UserConfirmedCode ->
            if (event.matched) Step(state, emptyList()) else fail(NearbyProblem.CODES_DID_NOT_MATCH)
        else -> ignore(event)
    }

    private fun onReceiving(event: NearbyEvent): Step<ReceiverState> = when {
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_DATA ->
            Step(state, listOf(NearbyAction.WriteChunk(event.payload)))
        event is NearbyEvent.FrameReceived && event.type == NearbyProtocol.TYPE_END -> {
            state = ReceiverState.VERIFYING
            Step(state, listOf(NearbyAction.VerifyAndHandOver))
        }
        else -> ignore(event)
    }

    /**
     * The bytes are whole and the importer has looked at them. Called by the caller, not by an
     * event, because verifying touches a file and this class touches nothing.
     */
    fun verified(problem: ImportProblem?): Step<ReceiverState> {
        if (state != ReceiverState.VERIFYING) return Step(state, emptyList())
        state = ReceiverState.DONE
        return Step(
            state,
            listOf(
                NearbyAction.SendFrame(
                    NearbyProtocol.TYPE_RESULT,
                    Result(problem == null, problem).encode(),
                ),
                NearbyAction.Close,
            ),
        )
    }

    /** What arrived is not what was sent. Nothing is handed to the importer. */
    fun contentMismatch(): Step<ReceiverState> = fail(NearbyProblem.CONTENT_MISMATCH)

    private fun declineWith(problem: NearbyProblem): Step<ReceiverState> {
        state = ReceiverState.FAILED
        return Step(
            state,
            listOf(
                NearbyAction.SendFrame(
                    NearbyProtocol.TYPE_DECLINE,
                    byteArrayOf(problem.code.toByte()),
                ),
                NearbyAction.Fail(problem),
                NearbyAction.Close,
            ),
        )
    }

    private fun ignore(event: NearbyEvent): Step<ReceiverState> =
        if (event is NearbyEvent.Tick) Step(state, emptyList())
        else fail(NearbyProblem.UNEXPECTED_MESSAGE)

    private fun fail(
        problem: NearbyProblem,
        importProblem: ImportProblem? = null,
    ): Step<ReceiverState> {
        state = ReceiverState.FAILED
        return Step(
            state,
            listOf(
                NearbyAction.SendFrame(NearbyProtocol.TYPE_ABORT, Abort(problem).encode()),
                NearbyAction.Fail(problem, importProblem),
                NearbyAction.Close,
            ),
        )
    }
}
