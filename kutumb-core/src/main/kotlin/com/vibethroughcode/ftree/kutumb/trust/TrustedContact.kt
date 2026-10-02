package com.vibethroughcode.ftree.kutumb.trust

enum class PairingMethod {
    /** Met in person and exchanged keys over the proximity handshake. */
    DIRECT,

    /** Trusted only because another relative vouched for this person's key. */
    VOUCHED,
}

/**
 * One row of the local trust store.
 *
 * [pubKeyHistory] holds every superseded key. Those keys are revoked: whatever they sign after the
 * rotation is rejected. The history is kept, not dropped, so the rejection can be recognised and
 * so there is an audit trail.
 */
data class TrustedContact(
    val personId: String,
    val pubKeyCurrent: String,
    val pubKeyHistory: List<String> = emptyList(),
    val pairedAt: Long,
    val pairingMethod: PairingMethod,
    val vouchedByPersonId: String? = null,
) {
    init {
        require(personId.isNotBlank()) { "personId is blank" }
        require(Hex.isHex(pubKeyCurrent, KEY_BYTES)) { "pubKeyCurrent is not a 32-byte hex key" }
        require(pubKeyHistory.all { Hex.isHex(it, KEY_BYTES) }) { "pubKeyHistory holds a malformed key" }
        require(pubKeyCurrent !in pubKeyHistory) { "the current key is also listed as revoked" }
        require(pubKeyHistory.toSet().size == pubKeyHistory.size) { "pubKeyHistory repeats a key" }
        when (pairingMethod) {
            PairingMethod.DIRECT -> require(vouchedByPersonId == null) { "a direct pairing has no voucher" }
            PairingMethod.VOUCHED -> require(!vouchedByPersonId.isNullOrBlank()) { "a vouched pairing names its voucher" }
        }
        require(vouchedByPersonId != personId) { "nobody vouches for themselves" }
    }

    /** True only for the active key; a superseded one is revoked. */
    fun isCurrentKey(key: String): Boolean = key == pubKeyCurrent

    fun isRevokedKey(key: String): Boolean = key in pubKeyHistory

    /**
     * Optional messaging is gated on a pair having met each other directly. A vouched relationship
     * does not unlock it, and a key rotation does not take it away (the pairing method is kept).
     */
    val messagingEnabled: Boolean get() = pairingMethod == PairingMethod.DIRECT

    /** The same person under a new key; the old one moves to history. The pairing method is kept. */
    fun withRotatedKey(newKey: String): TrustedContact =
        copy(pubKeyCurrent = newKey, pubKeyHistory = pubKeyHistory + pubKeyCurrent)

    companion object {
        const val KEY_BYTES = 32
    }
}
