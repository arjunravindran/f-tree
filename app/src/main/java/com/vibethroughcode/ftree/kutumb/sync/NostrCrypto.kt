package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

internal object SyncHex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Null unless [text] is exactly [bytes] bytes of lower-case hex. */
    fun decode(text: String, bytes: Int): ByteArray? {
        if (text.length != bytes * 2 || !text.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(bytes) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * The raw x coordinate of the ECDH point, which is what NIP-44 takes as its shared secret.
 * Deliberately not the library's own `ecdh()`, which returns SHA-256 of the compressed point and
 * would make every conversation key differ from every other Nostr client's.
 *
 * Throws [IllegalArgumentException] for malformed hex, and the library's exception for a scalar
 * outside the curve order or an x that is not on the curve; [Nip44.conversationKey] reports any of
 * those as a [Nip44Exception].
 */
object Secp256k1Ecdh : Ecdh {
    override fun sharedX(privateKey: String, publicKey: String): ByteArray {
        val secret = requireNotNull(SyncHex.decode(privateKey, 32)) { "private key is not 32 bytes of hex" }
        val x = requireNotNull(SyncHex.decode(publicKey, 32)) { "public key is not 32 bytes of hex" }
        // An x-only key means the point with even y, which is the 0x02 compressed prefix.
        val point = Secp256k1.get().pubKeyTweakMul(byteArrayOf(0x02) + x, secret)
        return point.copyOfRange(1, 33)
    }
}

/**
 * BIP-340 as Nostr uses it: the signed message is the 32-byte event id itself.
 *
 * [Bip340Scheme.sign] hashes its message first (right for the trust store's attestations, which
 * sign arbitrary bytes), so using it directly for events would produce signatures no relay accepts.
 * This adapter signs and verifies the digest as given.
 */
object NostrBip340Scheme : SignatureScheme {
    private val random = SecureRandom()

    override fun generateKeyPair(): KeyPair = Bip340Scheme.generateKeyPair()

    override fun sign(privateKey: String, message: ByteArray): String {
        require(message.size == 32) { "a Nostr signature is over the 32-byte event id" }
        val secret = requireNotNull(SyncHex.decode(privateKey, 32)) { "private key is not 32 bytes of hex" }
        val aux = ByteArray(32).also(random::nextBytes)
        return SyncHex.encode(Bip340Scheme.signDigest(secret, message, aux))
    }

    override fun verify(publicKey: String, message: ByteArray, signature: String): Boolean {
        if (message.size != 32) return false
        val key = SyncHex.decode(publicKey, 32) ?: return false
        val sig = SyncHex.decode(signature, 64) ?: return false
        return Bip340Scheme.verifyDigest(key, message, sig)
    }
}

/** Production randomness and ephemeral keys for gift wraps; [nowSeconds] is injected for determinism. */
fun secureWrapEnvironment(nowSeconds: () -> Long): WrapEnvironment {
    val random = SecureRandom()
    return WrapEnvironment(
        randomBytes = { n -> ByteArray(n).also(random::nextBytes) },
        nowSeconds = nowSeconds,
        newEphemeralKey = { Bip340Scheme.generateKeyPair() },
    )
}
