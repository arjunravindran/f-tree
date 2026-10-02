package com.vibethroughcode.ftree.kutumb.pairing

import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import com.vibethroughcode.ftree.nearby.wire.PairingIdentity

/**
 * The identity cards two phones swap when they pair: a public key and a signature that proves its
 * holder is the one talking.
 *
 * Layout: 32 bytes of x-only public key, then a 64-byte signature. The signature covers a domain
 * label, the six digits of *this* session, the sender/receiver role and the key itself, so:
 *
 *  - a card recorded from an earlier pairing does not verify in a new one (different digits);
 *  - a card cannot be played back at its own author as the other side's (different role);
 *  - nobody can present a key they do not hold, which would otherwise let a person in the room
 *    register somebody else's public key under their own name.
 *
 * The channel the card travels on is already authenticated by the two people comparing digits (or
 * by the scanned token); the signature adds proof of possession, not secrecy.
 */
class IdentityCards(
    private val scheme: SignatureScheme,
    private val own: KeyPair,
) : PairingIdentity {

    override fun card(sas: String, role: Int): ByteArray {
        val key = unhex(own.publicKey, KEY_BYTES) ?: error("own public key is not 32 bytes of hex")
        val signature = unhex(scheme.sign(own.privateKey, payload(sas, role, own.publicKey)), SIGNATURE_BYTES)
            ?: error("signature is not 64 bytes of hex")
        return key + signature
    }

    override fun peerKey(card: ByteArray, sas: String, role: Int): String? {
        if (card.size != KEY_BYTES + SIGNATURE_BYTES) return null
        val key = hex(card.copyOfRange(0, KEY_BYTES))
        // Pairing with oneself, or with a clone of one's own key, is never what anybody meant.
        if (key == own.publicKey) return null
        val signature = hex(card.copyOfRange(KEY_BYTES, card.size))
        return key.takeIf { scheme.verify(it, payload(sas, role, it), signature) }
    }

    private fun payload(sas: String, role: Int, publicKey: String): ByteArray =
        listOf(LABEL, sas, role.toString(), publicKey).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String, bytes: Int): ByteArray? {
        if (text.length != bytes * 2 || !text.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(bytes) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val LABEL = "ftree-kutumb/pair/1"
        const val KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64
    }
}
