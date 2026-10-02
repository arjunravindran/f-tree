package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import fr.acinq.secp256k1.Secp256k1
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The real secp256k1 in front of core's Nostr code. Core's own tests used a BigInteger stand-in
 * and a fake signature scheme; here Bip340Scheme and Secp256k1Ecdh do the work.
 */
class NostrCryptoTest {
    private val alice = NostrBip340Scheme.generateKeyPair()
    private val bob = NostrBip340Scheme.generateKeyPair()
    private val carol = NostrBip340Scheme.generateKeyPair()
    private val env = secureWrapEnvironment { 1_800_000_000L }

    private fun vectors(section: String): JsonArray {
        // Core's copy of the official nip44.vectors.json; unit tests run from the app module directory.
        val file = File("../kutumb-core/src/test/resources/nostr/nip44.vectors.json")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("v2").jsonObject.getValue(section).jsonObject.getValue("get_conversation_key").jsonArray
    }

    @Test
    fun ecdhGivesTheOfficialNip44ConversationKeys() {
        val valid = vectors("valid")
        assertEquals(35, valid.size)
        for (v in valid) {
            val o = v as JsonObject
            val key = Nip44.conversationKey(o.getValue("sec1").jsonPrimitive.content, o.getValue("pub2").jsonPrimitive.content, Secp256k1Ecdh)
            assertEquals(o.toString(), o.getValue("conversation_key").jsonPrimitive.content, SyncHex.encode(key))
        }
    }

    @Test
    fun ecdhRejectsTheOfficialInvalidVectors() {
        val invalid = vectors("invalid")
        assertEquals(8, invalid.size)
        for (v in invalid) {
            val o = v as JsonObject
            try {
                Nip44.conversationKey(o.getValue("sec1").jsonPrimitive.content, o.getValue("pub2").jsonPrimitive.content, Secp256k1Ecdh)
                fail("accepted: $o")
            } catch (_: Nip44Exception) {
            }
        }
    }

    @Test
    fun ecdhIsTheRawXAndSymmetric() {
        val ab = Secp256k1Ecdh.sharedX(alice.privateKey, bob.publicKey)
        val ba = Secp256k1Ecdh.sharedX(bob.privateKey, alice.publicKey)
        assertEquals(32, ab.size)
        assertArrayEquals(ab, ba)
        // The library's own ecdh() hashes the point; the NIP-44 shared secret is the unhashed x.
        val hashed = Secp256k1.get().ecdh(
            SyncHex.decode(alice.privateKey, 32)!!,
            byteArrayOf(2) + SyncHex.decode(bob.publicKey, 32)!!,
        )
        assertFalse(ab.contentEquals(hashed))
    }

    @Test
    fun ecdhOnFixedKeysMatchesTheVectorKey() {
        // sec1/pub2 of the first official vector; the expected shared x is the NIP-44 conversation key's input.
        val v = vectors("valid")[0].jsonObject
        val sec = v.getValue("sec1").jsonPrimitive.content
        val pub = v.getValue("pub2").jsonPrimitive.content
        val x = Secp256k1Ecdh.sharedX(sec, pub)
        // Same conversation key as HKDF-extract(salt "nip44-v2", x), computed independently here.
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("nip44-v2".toByteArray(), "HmacSHA256"))
        assertEquals(v.getValue("conversation_key").jsonPrimitive.content, SyncHex.encode(mac.doFinal(x)))
    }

    @Test
    fun eventSignaturesAreRealNostrSignaturesOverTheEventId() {
        val event = UnsignedEvent(alice.publicKey, 1_700_000_000L, 1, emptyList(), "hello").sign(alice.privateKey, NostrBip340Scheme)
        assertTrue(event.verify(NostrBip340Scheme))
        // What a relay or any other Nostr client does: BIP-340 verify of the raw 32-byte id.
        assertTrue(
            Bip340Scheme.verifyDigest(
                SyncHex.decode(alice.publicKey, 32)!!, SyncHex.decode(event.id, 32)!!, SyncHex.decode(event.sig, 64)!!,
            ),
        )
        // Bip340Scheme itself hashes first, so it is the wrong scheme for events.
        assertFalse(event.verify(Bip340Scheme))
        assertFalse(event.copy(content = "hullo").verify(NostrBip340Scheme))
    }

    @Test
    fun nip17GiftWrapRoundTripBetweenTwoIdentities() {
        val rumor = Nip17.rumor(alice.publicKey, listOf(bob.publicKey), "see you at the reunion", 1_799_999_000L)
        val wraps = Nip17.giftWraps(rumor, alice, listOf(bob.publicKey), NostrBip340Scheme, Secp256k1Ecdh, env)
        assertEquals(2, wraps.size) // bob's copy and alice's own

        val toBob = wraps.first { it.tagValue("p") == bob.publicKey }
        assertTrue(toBob.verify(NostrBip340Scheme))
        assertFalse(toBob.content.contains("reunion"))
        val opened = Nip59.unwrap(NostrEvent.fromJson(toBob.toJson())!!, bob.privateKey, NostrBip340Scheme, Secp256k1Ecdh) as UnwrapResult.Opened
        assertEquals("see you at the reunion", opened.rumor.content)
        assertEquals(alice.publicKey, opened.senderPubkey)
        assertEquals(rumor, opened.rumor)

        val toSelf = wraps.first { it.tagValue("p") == alice.publicKey }
        assertTrue(Nip59.unwrap(toSelf, alice.privateKey, NostrBip340Scheme, Secp256k1Ecdh) is UnwrapResult.Opened)

        val refused = Nip59.unwrap(toBob, carol.privateKey, NostrBip340Scheme, Secp256k1Ecdh) as UnwrapResult.Refused
        assertEquals(UnwrapFailure.WRAP_DECRYPT_FAILED, refused.reason)

        val tampered = Nip59.unwrap(toBob.copy(content = toBob.content.dropLast(4) + "AAAA"), bob.privateKey, NostrBip340Scheme, Secp256k1Ecdh) as UnwrapResult.Refused
        assertEquals(UnwrapFailure.BAD_WRAP_SIGNATURE, tampered.reason)
    }

    @Test
    fun aSyncEnvelopeSurvivesTheWholeWrap() {
        val envelope = SyncEnvelope(SyncType.FACT, linkedMapOf("id" to "f1", "text" to "Born in Kochi കൊച്ചി", "n" to 7L))
        val wrap = Nip59.wrap(envelope.toRumor(alice.publicKey, 1_799_999_000L), alice, bob.publicKey, NostrBip340Scheme, Secp256k1Ecdh, env)
        val opened = Nip59.unwrap(wrap, bob.privateKey, NostrBip340Scheme, Secp256k1Ecdh) as UnwrapResult.Opened
        assertEquals(envelope, SyncEnvelope.fromRumor(opened.rumor))
        assertNotNull(wrap.tagValue("p"))
        assertTrue(wrap.pubkey != alice.publicKey) // the relay sees a throwaway key, not the author
    }
}
