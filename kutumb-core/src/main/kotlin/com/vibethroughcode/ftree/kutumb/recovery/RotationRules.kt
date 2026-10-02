package com.vibethroughcode.ftree.kutumb.recovery

import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme

enum class RotationRejection {
    /** The signature does not verify under the stated voucher key. */
    BAD_SIGNATURE,

    /** This device does not trust the voucher's current key, so their word counts for nothing here. */
    VOUCHER_NOT_TRUSTED,

    /** The voucher is the person being recovered. A lost key cannot vouch for itself. */
    SELF_VOUCH,

    /** This device has no record of the person, so there is no record to update. */
    UNKNOWN_PERSON,

    /** The event's old key is not the person's active key here (already moved on, or never theirs). */
    STALE_OLD_KEY,

    /** The new key is already somebody's key (or was one). */
    NEW_KEY_IN_USE,
}

sealed interface RotationOutcome {
    /** Applied: [store] is the updated trust store. */
    data class Accepted(val store: TrustStore) : RotationOutcome

    /** This exact rotation is already in the store; nothing changed. Makes redelivery harmless. */
    data object AlreadyApplied : RotationOutcome

    data class Rejected(val reason: RotationRejection) : RotationOutcome
}

/**
 * Applying a signed rotation to the local trust store.
 *
 * Single-vouch, as decided: one valid attestation from one trusted relative is enough. There is no
 * second confirmation from the person being recovered or from anyone else, and nothing here
 * should grow one without that decision being revisited.
 */
object RotationRules {

    fun apply(store: TrustStore, event: IdentityRotationEvent, scheme: SignatureScheme): RotationOutcome {
        val person = store[event.personId] ?: return reject(RotationRejection.UNKNOWN_PERSON)

        // Redelivery: the rotation is already in effect. Checked first so a replay is a no-op even
        // after the voucher has themselves rotated away.
        if (person.pubKeyCurrent == event.newPubKey && event.oldPubKey in person.pubKeyHistory) {
            return RotationOutcome.AlreadyApplied
        }

        if (!event.hasValidSignature(scheme)) return reject(RotationRejection.BAD_SIGNATURE)

        val voucher = store.byCurrentKey(event.vouchedByPubKey) ?: return reject(RotationRejection.VOUCHER_NOT_TRUSTED)
        if (voucher.personId == event.personId) return reject(RotationRejection.SELF_VOUCH)

        if (person.pubKeyCurrent != event.oldPubKey) return reject(RotationRejection.STALE_OLD_KEY)
        if (store.contacts.any { event.newPubKey == it.pubKeyCurrent || event.newPubKey in it.pubKeyHistory }) {
            return reject(RotationRejection.NEW_KEY_IN_USE)
        }

        return RotationOutcome.Accepted(store.with(person.withRotatedKey(event.newPubKey)))
    }

    private fun reject(reason: RotationRejection) = RotationOutcome.Rejected(reason)
}
