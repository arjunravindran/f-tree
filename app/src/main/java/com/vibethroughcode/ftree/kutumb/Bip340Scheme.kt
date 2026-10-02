package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import fr.acinq.secp256k1.Secp256k1
import fr.acinq.secp256k1.Secp256k1Exception
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr over secp256k1 - the scheme Nostr itself signs with, so one identity key is both
 * the trust-store key and the Nostr key.
 *
 * Public keys are the 32-byte x-only form and signatures 64 bytes, as `kutumb-core` expects. BIP-340
 * signs a 32-byte value, so [sign] and [verify] sign the SHA-256 of the message; the digest, not the
 * message, is what [signDigest] and [verifyDigest] take, which is also what the published BIP-340
 * test vectors exercise.
 */
object Bip340Scheme : SignatureScheme {
    private val random = SecureRandom()
    private val secp get() = Secp256k1.get()

    override fun generateKeyPair(): KeyPair {
        val secret = ByteArray(KEY_BYTES)
        // A random 32 bytes is outside the curve order with probability about 2^-128; checked anyway.
        do random.nextBytes(secret) while (!secp.secKeyVerify(secret))
        return KeyPair(hex(publicKeyOf(secret)), hex(secret))
    }

    override fun sign(privateKey: String, message: ByteArray): String {
        val secret = requireNotNull(unhex(privateKey, KEY_BYTES)) { "private key is not 32 bytes of hex" }
        val aux = ByteArray(KEY_BYTES).also(random::nextBytes)
        return hex(signDigest(secret, sha256(message), aux))
    }

    override fun verify(publicKey: String, message: ByteArray, signature: String): Boolean {
        val key = unhex(publicKey, KEY_BYTES) ?: return false
        val sig = unhex(signature, SIGNATURE_BYTES) ?: return false
        return verifyDigest(key, sha256(message), sig)
    }

    /** The x-only public key for [secret]. */
    fun publicKeyOf(secret: ByteArray): ByteArray = secp.pubKeyCompress(secp.pubkeyCreate(secret)).copyOfRange(1, 1 + KEY_BYTES)

    fun signDigest(secret: ByteArray, digest: ByteArray, aux: ByteArray?): ByteArray = secp.signSchnorr(digest, secret, aux)

    /** False, never a throw, for a malformed key or a wrong signature. */
    fun verifyDigest(publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean =
        try {
            secp.verifySchnorr(signature, digest, publicKey)
        } catch (_: Secp256k1Exception) {
            // A key that is not a point on the curve is rejected by the library, not just unmatched.
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String, bytes: Int): ByteArray? {
        if (text.length != bytes * 2 || !text.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(bytes) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private const val KEY_BYTES = 32
    private const val SIGNATURE_BYTES = 64
}
