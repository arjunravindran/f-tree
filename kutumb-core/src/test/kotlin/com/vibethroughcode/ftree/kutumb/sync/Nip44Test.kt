package com.vibethroughcode.ftree.kutumb.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * Runs the official NIP-44 v2 vectors, test resource `nostr/nip44.vectors.json`, downloaded unmodified from
 * https://github.com/paulmillr/nip44 (nip44.vectors.json). ECDH is the test-only BigInteger implementation in
 * [TestSecp256k1], so the vectors' secret keys and public keys are used as given, with no precomputed secrets.
 */
class Nip44Test {
    @Suppress("UNCHECKED_CAST")
    private val v2: Map<String, Any?> = run {
        val text = javaClass.getResourceAsStream("/nostr/nip44.vectors.json")!!.readBytes().toString(Charsets.UTF_8)
        ((Json.parse(text) as Map<String, Any?>)["v2"] as Map<String, Any?>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(vararg path: String): Any? = path.fold(v2 as Any?) { node, key -> (node as Map<String, Any?>)[key] }

    @Suppress("UNCHECKED_CAST")
    private fun list(vararg path: String) = section(*path) as List<Map<String, Any?>>

    @Test
    fun `official get_conversation_key vectors`() {
        val vectors = list("valid", "get_conversation_key")
        assertEquals(35, vectors.size)
        for (v in vectors) {
            val key = Nip44.conversationKey(v["sec1"] as String, v["pub2"] as String, TestSecp256k1)
            assertEquals(v.toString(), v["conversation_key"], key.hex())
        }
    }

    @Test
    fun `official invalid get_conversation_key vectors are rejected`() {
        val vectors = list("invalid", "get_conversation_key")
        assertEquals(8, vectors.size)
        for (v in vectors) {
            try {
                Nip44.conversationKey(v["sec1"] as String, v["pub2"] as String, TestSecp256k1)
                fail("accepted: $v")
            } catch (_: Nip44Exception) {
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `official get_message_keys vectors`() {
        val block = section("valid", "get_message_keys") as Map<String, Any?>
        val conversationKey = hexBytes(block["conversation_key"] as String)
        val keys = block["keys"] as List<Map<String, Any?>>
        assertEquals(32, keys.size)
        for (k in keys) {
            val m = Nip44.messageKeys(conversationKey, hexBytes(k["nonce"] as String))
            assertEquals(k["chacha_key"], m.chachaKey.hex())
            assertEquals(k["chacha_nonce"], m.chachaNonce.hex())
            assertEquals(k["hmac_key"], m.hmacKey.hex())
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `official calc_padded_len vectors`() {
        val pairs = section("valid", "calc_padded_len") as List<List<Long>>
        assertEquals(24, pairs.size)
        for ((input, expected) in pairs) assertEquals("len $input", expected.toInt(), Nip44.calcPaddedLen(input.toInt()))
    }

    @Test
    fun `official encrypt_decrypt vectors`() {
        val vectors = list("valid", "encrypt_decrypt")
        assertEquals(10, vectors.size)
        for (v in vectors) {
            val sec1 = v["sec1"] as String
            val sec2 = v["sec2"] as String
            val conversationKey = Nip44.conversationKey(sec1, TestSecp256k1.publicKeyOf(sec2), TestSecp256k1)
            assertEquals(v["conversation_key"], conversationKey.hex())
            // The key is symmetric: the other party derives the same one.
            assertArrayEquals(conversationKey, Nip44.conversationKey(sec2, TestSecp256k1.publicKeyOf(sec1), TestSecp256k1))

            val payload = Nip44.encrypt(v["plaintext"] as String, conversationKey, hexBytes(v["nonce"] as String))
            assertEquals(v["payload"], payload)
            assertEquals(v["plaintext"], Nip44.decrypt(v["payload"] as String, conversationKey))
        }
    }

    @Test
    fun `official encrypt_decrypt_long_msg vectors`() {
        val vectors = list("valid", "encrypt_decrypt_long_msg")
        assertEquals(3, vectors.size)
        for (v in vectors) {
            val plaintext = (v["pattern"] as String).repeat((v["repeat"] as Long).toInt())
            assertEquals(v["plaintext_sha256"], sha256(plaintext.toByteArray()))
            val key = hexBytes(v["conversation_key"] as String)
            val payload = Nip44.encrypt(plaintext, key, hexBytes(v["nonce"] as String))
            assertEquals(v["payload_sha256"], sha256(payload.toByteArray()))
            assertEquals(plaintext, Nip44.decrypt(payload, key))
        }
    }

    @Test
    fun `official invalid decrypt vectors are rejected`() {
        val vectors = list("invalid", "decrypt")
        assertEquals(12, vectors.size)
        for (v in vectors) {
            try {
                Nip44.decrypt(v["payload"] as String, hexBytes(v["conversation_key"] as String))
                fail("accepted: ${v["note"]}")
            } catch (_: Nip44Exception) {
            }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `official invalid plaintext lengths are rejected`() {
        val key = ByteArray(32) { 1 }
        val lengths = section("invalid", "encrypt_msg_lengths") as List<Long>
        assertEquals(4, lengths.size)
        for (len in lengths) {
            // The 10 MB case is built directly; the others are small.
            try {
                Nip44.encrypt("a".repeat(len.toInt()), key, ByteArray(32))
                fail("accepted length $len")
            } catch (_: Nip44Exception) {
            }
        }
    }

    @Test
    fun `padding round trip hides length differences`() {
        for (len in listOf(1, 31, 32, 33, 255, 256, 257, 1000, 65535)) {
            val padded = Nip44.pad(ByteArray(len) { 7 })
            assertEquals(2 + Nip44.calcPaddedLen(len), padded.size)
            assertEquals(len, Nip44.unpad(padded).size)
        }
    }

    @Test
    fun `a flipped ciphertext byte fails the MAC`() {
        val key = ByteArray(32) { 9 }
        val payload = java.util.Base64.getDecoder().decode(Nip44.encrypt("hello", key, ByteArray(32) { 3 }))
        payload[40] = (payload[40].toInt() xor 1).toByte()
        try {
            Nip44.decrypt(java.util.Base64.getEncoder().encodeToString(payload), key)
            fail("accepted tampered payload")
        } catch (e: Nip44Exception) {
            assertEquals("invalid MAC", e.message)
        }
    }

    @Test
    fun `constant time compare`() {
        assertTrue(Nip44.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(Nip44.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(Nip44.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 0)))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
}
