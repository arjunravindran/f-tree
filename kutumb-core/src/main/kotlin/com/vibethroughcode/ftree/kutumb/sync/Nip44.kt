package com.vibethroughcode.ftree.kutumb.sync

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Anything wrong with a NIP-44 key, message or payload. */
class Nip44Exception(message: String) : Exception(message)

/**
 * x-only ECDH on secp256k1: [privateKey] and [publicKey] are 32-byte hex, the result is the 32-byte
 * x coordinate of the shared point, unhashed. Core has no secp256k1, so the app injects this.
 * Implementations throw (any exception) for an invalid scalar or a public key not on the curve;
 * [Nip44] reports that as a [Nip44Exception].
 */
fun interface Ecdh {
    fun sharedX(privateKey: String, publicKey: String): ByteArray
}

/**
 * NIP-44 version 2: ECDH, HKDF-SHA256, padded ChaCha20, HMAC-SHA256, base64.
 * Encryption takes its 32-byte nonce as a parameter so the caller owns randomness (and tests can pin it).
 */
object Nip44 {
    private const val VERSION = 2
    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 65535
    private val SALT = "nip44-v2".toByteArray(Charsets.UTF_8)

    /** The per-pair key: HKDF-extract of the ECDH x coordinate with salt "nip44-v2". Symmetric in the two parties. */
    fun conversationKey(privateKey: String, publicKey: String, ecdh: Ecdh): ByteArray {
        val shared = try {
            ecdh.sharedX(privateKey, publicKey)
        } catch (e: Exception) {
            throw Nip44Exception("invalid key: ${e.message}")
        }
        if (shared.size != 32) throw Nip44Exception("shared secret must be 32 bytes")
        return hkdfExtract(SALT, shared)
    }

    class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        if (conversationKey.size != 32) throw Nip44Exception("conversation key must be 32 bytes")
        if (nonce.size != 32) throw Nip44Exception("nonce must be 32 bytes")
        val keys = hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    /** Length after padding: the next 32-byte multiple up to 256, then chunks of an eighth of the next power of two. */
    fun calcPaddedLen(unpaddedLen: Int): Int {
        require(unpaddedLen >= 1) { "length must be positive" }
        if (unpaddedLen <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(unpaddedLen - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray): String {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        if (bytes.size < MIN_PLAINTEXT || bytes.size > MAX_PLAINTEXT) throw Nip44Exception("plaintext must be 1 to 65535 bytes")
        val keys = messageKeys(conversationKey, nonce)
        val ciphertext = ChaCha20.xor(keys.chachaKey, keys.chachaNonce, 0, pad(bytes))
        val mac = hmac(keys.hmacKey, nonce, ciphertext)
        val payload = ByteArray(1 + 32 + ciphertext.size + 32)
        payload[0] = VERSION.toByte()
        nonce.copyInto(payload, 1)
        ciphertext.copyInto(payload, 33)
        mac.copyInto(payload, 33 + ciphertext.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        if (conversationKey.size != 32) throw Nip44Exception("conversation key must be 32 bytes")
        if (payload.isNotEmpty() && payload[0] == '#') throw Nip44Exception("unknown encryption version")
        if (payload.length < 132 || payload.length > 87472) throw Nip44Exception("invalid payload length")
        val data = try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            throw Nip44Exception("invalid base64")
        }
        if (data.size < 99 || data.size > 65603) throw Nip44Exception("invalid data length")
        if (data[0].toInt() != VERSION) throw Nip44Exception("unknown encryption version ${data[0].toInt() and 0xff}")
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val keys = messageKeys(conversationKey, nonce)
        if (!constantTimeEquals(hmac(keys.hmacKey, nonce, ciphertext), mac)) throw Nip44Exception("invalid MAC")
        return String(unpad(ChaCha20.xor(keys.chachaKey, keys.chachaNonce, 0, ciphertext)), Charsets.UTF_8)
    }

    /** 2-byte big-endian length, the text, then zeros up to [calcPaddedLen]. */
    internal fun pad(plaintext: ByteArray): ByteArray {
        val out = ByteArray(2 + calcPaddedLen(plaintext.size))
        out[0] = (plaintext.size ushr 8).toByte()
        out[1] = plaintext.size.toByte()
        plaintext.copyInto(out, 2)
        return out
    }

    internal fun unpad(padded: ByteArray): ByteArray {
        if (padded.size < 2) throw Nip44Exception("invalid padding")
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len < MIN_PLAINTEXT || padded.size != 2 + calcPaddedLen(len)) throw Nip44Exception("invalid padding")
        return padded.copyOfRange(2, 2 + len)
    }

    // HKDF-SHA256 (RFC 5869) over the JDK's HMAC.

    internal fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    internal fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    internal fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "bad HKDF length" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }

    /** Compares every byte regardless of where they first differ. */
    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        val n = minOf(a.size, b.size)
        for (i in 0 until n) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
