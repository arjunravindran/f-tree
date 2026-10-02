package com.vibethroughcode.ftree.kutumb.trust

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** A long-term identity key. Both halves are lower-case hex; the private half never leaves the device. */
class KeyPair(val publicKey: String, val privateKey: String) {
    // Deliberately not a data class: a generated toString would print the private key into a log.
    override fun toString() = "KeyPair(publicKey=$publicKey, privateKey=<hidden>)"
}

/**
 * The signature algorithm behind every attestation. Everything above this interface (rotation
 * rules, the trust store) is algorithm-agnostic, so the scheme can be swapped without touching it.
 *
 * Nostr's own scheme is BIP-340 Schnorr over secp256k1, which the JDK does not provide. [Ed25519Scheme]
 * is the JVM reference implementation; a BIP-340 implementation slots in here once a secp256k1
 * library is chosen for the app (see the Android Studio hand-off notes).
 */
interface SignatureScheme {
    fun generateKeyPair(): KeyPair

    /** Hex signature of [message] under [privateKey]. */
    fun sign(privateKey: String, message: ByteArray): String

    /** False, never a throw, for a malformed key or signature as well as a wrong one. */
    fun verify(publicKey: String, message: ByteArray, signature: String): Boolean
}

/** Ed25519 from the JDK (15+): 32-byte public keys, 64-byte signatures. */
object Ed25519Scheme : SignatureScheme {
    private const val ALGORITHM = "Ed25519"
    private const val PUBLIC_BYTES = 32
    private const val SIGNATURE_BYTES = 64

    // Raw key <-> JDK encoding: a fixed ASN.1 prefix in front of the 32 raw bytes.
    private val x509Prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private val pkcs8Prefix = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)

    override fun generateKeyPair(): KeyPair {
        val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        val publicRaw = pair.public.encoded.copyOfRange(x509Prefix.size, pair.public.encoded.size)
        val privateRaw = pair.private.encoded.copyOfRange(pkcs8Prefix.size, pair.private.encoded.size)
        return KeyPair(Hex.encode(publicRaw), Hex.encode(privateRaw))
    }

    override fun sign(privateKey: String, message: ByteArray): String {
        val raw = requireNotNull(Hex.decode(privateKey)) { "private key is not hex" }
        val key = KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(pkcs8Prefix + raw))
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(key)
        signer.update(message)
        return Hex.encode(signer.sign())
    }

    override fun verify(publicKey: String, message: ByteArray, signature: String): Boolean {
        if (!Hex.isHex(publicKey, PUBLIC_BYTES) || !Hex.isHex(signature, SIGNATURE_BYTES)) return false
        return try {
            val key = KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(x509Prefix + Hex.decode(publicKey)!!))
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(message)
                verify(Hex.decode(signature)!!)
            }
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }
}
