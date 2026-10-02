package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.nearby.wire.QrLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an in-person pairing has got to, as the pairing screen needs to show it. */
sealed interface PairingState {
    /** Nothing started. */
    data object Idle : PairingState

    /** Showing this phone to be found: [link] is the QR to scan, when there is an address to put in it. */
    data class Waiting(val link: QrLink?, val deviceName: String) : PairingState

    data class Connecting(val name: String) : PairingState

    /**
     * Both phones should show [code]; the two people have to say whether it is the same one. A null
     * [code] is a phone that was scanned: nothing was compared, and it is only asked whether to pair.
     */
    data class ConfirmCode(val code: String?, val peerName: String) : PairingState

    /**
     * The other phone is proven to be the one in front of this one, and holds [peerPublicKey]. Who
     * that person is in the tree is still for the reader to say - the other phone cannot know which
     * entry in *this* tree is theirs.
     */
    data class Verified(val peerName: String, val peerPublicKey: String) : PairingState

    data class Failed(val problem: PairingProblem) : PairingState
}

enum class PairingProblem {
    /** This build cannot pair. */
    UNAVAILABLE,

    /** Nearby sharing is switched off in Settings, and pairing does not switch it on. */
    NEARBY_OFF,

    /** The reader has not yet said who they are, so there is no key to pair with. */
    NO_IDENTITY,

    /** The two screens did not show the same code. Somebody may be in the middle; not a retry-and-see. */
    CODE_MISMATCH,

    CONNECTION_LOST,

    /** The other person said the codes did not match, or backed out. */
    DECLINED,
}

/**
 * The in-person handshake as the screen sees it.
 *
 * An interface so the screen and the trust store can be built and tested without a network: the
 * implementation that rides the nearby protocol is one of two, the other being a fake.
 */
interface PairingFlow {
    val state: StateFlow<PairingState>

    /** Start showing this phone to be found, and listening for the other one. */
    fun start()

    /** The other phone's QR was scanned: connect to it. */
    fun joinByLink(link: QrLink)

    /** The reader's answer to "does their screen show the same code?". */
    fun confirmCode(matched: Boolean)

    /** Stop, and forget everything about the attempt. */
    fun cancel()
}

/** What a build without pairing says, rather than a screen that looks as though it works. */
object UnavailablePairingFlow : PairingFlow {
    private val _state = MutableStateFlow<PairingState>(PairingState.Failed(PairingProblem.UNAVAILABLE))
    override val state: StateFlow<PairingState> = _state.asStateFlow()
    override fun start() = Unit
    override fun joinByLink(link: QrLink) = Unit
    override fun confirmCode(matched: Boolean) = Unit
    override fun cancel() = Unit
}
