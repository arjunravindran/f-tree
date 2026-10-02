package com.vibethroughcode.ftree.kutumb.pairing

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.PairingFlow
import com.vibethroughcode.ftree.kutumb.PairingProblem
import com.vibethroughcode.ftree.kutumb.PairingState
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import com.vibethroughcode.ftree.nearby.NearbyPairState
import com.vibethroughcode.ftree.nearby.NearbyRepository
import com.vibethroughcode.ftree.nearby.wire.NearbyProblem
import com.vibethroughcode.ftree.nearby.wire.QrLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * In-person pairing over the nearby protocol: the same encrypted conversation, the same six digits
 * or scanned token, with an identity card exchanged where a file transfer would send an offer.
 *
 * Nothing is trusted here. This only reports when the other phone has proved it holds a key, over a
 * channel two people verified face to face; saving that key as a contact is a separate act, taken
 * by a person on the pairing screen.
 */
class NearbyPairingFlow(
    private val nearby: NearbyRepository,
    private val kutumb: KutumbRepository,
    private val scheme: SignatureScheme,
    private val deviceName: () -> String,
    private val scope: CoroutineScope,
) : PairingFlow {

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    override val state: StateFlow<PairingState> = _state.asStateFlow()

    private var job: Job? = null

    override fun start() {
        job?.cancel()
        job = scope.launch {
            val identity = kutumb.observeIdentity().first()
            if (identity == null) {
                _state.value = PairingState.Failed(PairingProblem.NO_IDENTITY)
                return@launch
            }
            nearby.beginPairing(IdentityCards(scheme, identity.keyPair))
            combine(nearby.pair, nearby.pairing) { pair, link -> map(pair, link) }
                .collect { _state.value = it }
        }
    }

    override fun joinByLink(link: QrLink) = nearby.pairByLink(link)

    override fun confirmCode(matched: Boolean) = nearby.answerPairing(matched)

    override fun cancel() {
        job?.cancel()
        job = null
        nearby.endPairing()
        _state.value = PairingState.Idle
    }

    private fun map(pair: NearbyPairState, link: QrLink?): PairingState = when (pair) {
        NearbyPairState.Idle -> PairingState.Waiting(link, deviceName())
        NearbyPairState.Off -> PairingState.Failed(PairingProblem.NEARBY_OFF)
        is NearbyPairState.Connecting -> PairingState.Connecting(pair.name)
        is NearbyPairState.ConfirmCode -> PairingState.ConfirmCode(pair.code, pair.peerName)
        is NearbyPairState.Requested -> PairingState.ConfirmCode(pair.code, pair.peerName)
        is NearbyPairState.Paired -> PairingState.Verified(pair.peerName, pair.peerPublicKey)
        is NearbyPairState.Failed -> PairingState.Failed(problemOf(pair.problem))
    }

    companion object {
        /** A mismatch is its own answer, because it is the one that may mean somebody is in the middle. */
        fun problemOf(problem: NearbyProblem): PairingProblem = when (problem) {
            NearbyProblem.CODES_DID_NOT_MATCH, NearbyProblem.KEY_NOT_AS_PROMISED, NearbyProblem.BAD_PAIRING ->
                PairingProblem.CODE_MISMATCH
            NearbyProblem.DECLINED, NearbyProblem.CANCELLED -> PairingProblem.DECLINED
            else -> PairingProblem.CONNECTION_LOST
        }
    }
}
