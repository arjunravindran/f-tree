package com.vibethroughcode.ftree.kutumb.recovery

import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme

/** What a batch of received rotations did to the store. */
data class PropagationResult(
    val store: TrustStore,
    val applied: List<IdentityRotationEvent>,
    /** Redeliveries that changed nothing. */
    val duplicates: List<IdentityRotationEvent>,
    /** Events that never became valid, with why (the reason from the last attempt). */
    val rejected: List<Pair<IdentityRotationEvent, RotationRejection>>,
)

object RotationPropagation {

    /**
     * Applies rotations received from the relays, in whatever order they arrived.
     *
     * Relays deliver out of order, and rotations chain: P's key moves X→Y, then Y→Z, and R's own
     * rotation may be what makes a later vouch trustworthy. So events are tried oldest first and
     * the leftovers retried for as long as a pass makes progress; an event that depends on another
     * still in flight is not lost just because it came first. Whatever is still failing when a
     * pass changes nothing is reported as rejected.
     */
    fun apply(store: TrustStore, events: Collection<IdentityRotationEvent>, scheme: SignatureScheme): PropagationResult {
        var current = store
        val applied = mutableListOf<IdentityRotationEvent>()
        val duplicates = mutableListOf<IdentityRotationEvent>()
        var pending = events.distinct().sortedWith(compareBy({ it.timestamp }, { it.signature }))
        val lastReason = HashMap<IdentityRotationEvent, RotationRejection>()

        do {
            var progressed = false
            val stillPending = mutableListOf<IdentityRotationEvent>()
            for (event in pending) {
                when (val outcome = RotationRules.apply(current, event, scheme)) {
                    is RotationOutcome.Accepted -> { current = outcome.store; applied += event; progressed = true }
                    RotationOutcome.AlreadyApplied -> { duplicates += event; progressed = true }
                    is RotationOutcome.Rejected -> { lastReason[event] = outcome.reason; stillPending += event }
                }
            }
            pending = stillPending
        } while (progressed && pending.isNotEmpty())

        return PropagationResult(current, applied, duplicates, pending.map { it to lastReason.getValue(it) })
    }
}
