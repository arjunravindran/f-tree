package com.vibethroughcode.ftree.kutumb.trust

/**
 * "Person P moved from key X to key Y, attested by relative R at time T." Signed by R's key.
 *
 * Constructing one checks its shape only. Whether the signature holds, and whether this device
 * should believe R, are separate questions: [hasValidSignature] and the recovery layer's rules.
 */
data class IdentityRotationEvent(
    val personId: String,
    val oldPubKey: String,
    val newPubKey: String,
    val vouchedByPubKey: String,
    val signature: String,
    val timestamp: Long,
) {
    init {
        require(personId.isNotBlank()) { "personId is blank" }
        require(Hex.isHex(oldPubKey, TrustedContact.KEY_BYTES)) { "oldPubKey is not a 32-byte hex key" }
        require(Hex.isHex(newPubKey, TrustedContact.KEY_BYTES)) { "newPubKey is not a 32-byte hex key" }
        require(Hex.isHex(vouchedByPubKey, TrustedContact.KEY_BYTES)) { "vouchedByPubKey is not a 32-byte hex key" }
        require(oldPubKey != newPubKey) { "a rotation must change the key" }
        require(timestamp >= 0) { "timestamp is negative" }
    }

    fun hasValidSignature(scheme: SignatureScheme): Boolean =
        scheme.verify(vouchedByPubKey, signingPayload(personId, oldPubKey, newPubKey, vouchedByPubKey, timestamp), signature)

    companion object {
        private const val DOMAIN = "ftree-kutumb/identity-rotation/v1"

        /**
         * The exact bytes that are signed. Domain-separated and newline-delimited (hex and ids
         * cannot contain a newline), so a signature over a rotation can never be replayed as
         * something else, and two different events can never share a payload.
         */
        fun signingPayload(personId: String, oldPubKey: String, newPubKey: String, vouchedByPubKey: String, timestamp: Long): ByteArray {
            require('\n' !in personId) { "personId contains a newline" }
            return listOf(DOMAIN, personId, oldPubKey, newPubKey, vouchedByPubKey, timestamp.toString())
                .joinToString("\n").toByteArray(Charsets.UTF_8)
        }

        /** The voucher's side of the in-person re-pairing: attest and sign with their own private key. */
        fun attest(
            scheme: SignatureScheme,
            voucher: KeyPair,
            personId: String,
            oldPubKey: String,
            newPubKey: String,
            timestamp: Long,
        ): IdentityRotationEvent = IdentityRotationEvent(
            personId = personId,
            oldPubKey = oldPubKey,
            newPubKey = newPubKey,
            vouchedByPubKey = voucher.publicKey,
            signature = scheme.sign(voucher.privateKey, signingPayload(personId, oldPubKey, newPubKey, voucher.publicKey, timestamp)),
            timestamp = timestamp,
        )
    }
}
