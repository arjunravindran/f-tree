package com.vibethroughcode.ftree.kutumb.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Events, gift wraps, NIP-17 rumors, sync envelopes and relay frames. Crypto is the test-only [TestSecp256k1] / [TestScheme]. */
class NostrProtocolTest {
    private val alice = TestSecp256k1.keyPair(1)
    private val bob = TestSecp256k1.keyPair(2)
    private val carol = TestSecp256k1.keyPair(3)

    private var counter = 0
    private var ephemeralSeed = 100
    private fun env(now: Long = 1_700_000_000L) = WrapEnvironment(
        randomBytes = { n -> ByteArray(n) { ((++counter) * 37 + it).toByte() } },
        nowSeconds = { now },
        newEphemeralKey = { TestSecp256k1.keyPair(ephemeralSeed++) },
    )

    // ---- NIP-01 events ----

    @Test
    fun `event id is sha256 of the canonical array`() {
        val e = UnsignedEvent(alice.publicKey, 1_700_000_000L, 1, listOf(listOf("t", "x")), "hi \"there\"\n")
        assertEquals(
            "[0,\"${alice.publicKey}\",1700000000,1,[[\"t\",\"x\"]],\"hi \\\"there\\\"\\n\"]",
            e.canonicalJson(),
        )
        val expected = MessageDigest.getInstance("SHA-256").digest(e.canonicalJson().toByteArray()).hex()
        assertEquals(expected, e.id)
    }

    @Test
    fun `canonical json escapes only the NIP-01 set`() {
        val e = UnsignedEvent(alice.publicKey, 1, 1, emptyList(), "\u0001/é\u2028\b\u000c\t\r")
        assertTrue(e.canonicalJson().endsWith("\"\u0001/é\u2028\\b\\f\\t\\r\"]"))
    }

    @Test
    fun `sign then verify, and tampering is caught`() {
        val signed = UnsignedEvent(alice.publicKey, 5, 1, emptyList(), "hello").sign(alice.privateKey, TestScheme)
        assertTrue(signed.verify(TestScheme))
        assertFalse(signed.copy(content = "hullo").verify(TestScheme))
        assertFalse(signed.copy(createdAt = 6).verify(TestScheme))
        assertFalse(signed.copy(pubkey = bob.publicKey).verify(TestScheme))
        assertFalse(signed.copy(sig = "00".repeat(64)).verify(TestScheme))
        assertFalse(signed.copy(id = "00".repeat(32)).verify(TestScheme))
        assertFalse(signed.copy(sig = "zz").verify(TestScheme))
    }

    @Test
    fun `event json round trips and rejects malformed input`() {
        val signed = UnsignedEvent(alice.publicKey, 5, 1, listOf(listOf("p", bob.publicKey)), "naïve 🍕").sign(alice.privateKey, TestScheme)
        assertEquals(signed, NostrEvent.fromJson(signed.toJson()))
        assertNull(NostrEvent.fromJson("not json"))
        assertNull(NostrEvent.fromJson("[]"))
        assertNull(NostrEvent.fromJson(signed.toJson().replace("\"kind\":1", "\"kind\":\"1\"")))
        assertNull(NostrEvent.fromJson(signed.toJson().replace("\"tags\":[[", "\"tags\":[[1,")))
    }

    @Test
    fun `json parser handles escapes and rejects garbage`() {
        assertEquals("a\"b\\c/\n\u00e9", Json.parse("\"a\\\"b\\\\c\\/\\n\\u00e9\""))
        assertEquals(listOf(1L, 2.5, true, null), Json.parse(" [1, 2.5 , true,null] "))
        assertNull(Json.parseOrNull("[1,]"))
        assertNull(Json.parseOrNull("{\"a\":1} x"))
        assertNull(Json.parseOrNull("\"unterminated"))
        assertNull(Json.parseOrNull("[".repeat(200)))
    }

    // ---- NIP-59 / NIP-17 ----

    private fun rumor(text: String = "hello bob") = Nip17.rumor(alice.publicKey, listOf(bob.publicKey), text, 1_699_999_999L)

    @Test
    fun `gift wrap round trip`() {
        val wrap = Nip59.wrap(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, env())
        assertEquals(Nip59.KIND_GIFT_WRAP, wrap.kind)
        assertEquals(listOf(listOf("p", bob.publicKey)), wrap.tags)
        assertNotEquals(alice.publicKey, wrap.pubkey) // signed by a throwaway key
        assertTrue(wrap.verify(TestScheme))
        assertFalse(wrap.content.contains("hello bob"))

        val opened = Nip59.unwrap(wrap, bob.privateKey, TestScheme, TestSecp256k1) as UnwrapResult.Opened
        assertEquals(alice.publicKey, opened.senderPubkey)
        assertEquals(rumor(), opened.rumor)
        assertEquals(Nip59.KIND_SEAL, opened.seal.kind)
        assertEquals(emptyList<List<String>>(), opened.seal.tags)
    }

    @Test
    fun `seal and wrap timestamps are randomised into the past`() {
        val now = 1_700_000_000L
        val times = (1..20).map { Nip59.wrap(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, env(now)).createdAt }
        assertTrue(times.all { it <= now && it > now - Nip59.MAX_TIMESTAMP_SKEW_SECONDS })
        assertTrue(times.toSet().size > 1)
        val opened = Nip59.unwrap(
            Nip59.wrap(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, env(now)), bob.privateKey, TestScheme, TestSecp256k1,
        ) as UnwrapResult.Opened
        assertEquals(1_699_999_999L, opened.rumor.createdAt) // the rumor keeps the real time
    }

    @Test
    fun `each wrap uses a fresh ephemeral key`() {
        val e = env()
        val a = Nip59.wrap(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, e)
        val b = Nip59.wrap(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, e)
        assertNotEquals(a.pubkey, b.pubkey)
    }

    @Test
    fun `the wrong recipient cannot open a wrap`() {
        val wrap = Nip59.wrap(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, env())
        val result = Nip59.unwrap(wrap, carol.privateKey, TestScheme, TestSecp256k1)
        assertEquals(UnwrapResult.Refused(UnwrapFailure.WRAP_DECRYPT_FAILED), result)
    }

    @Test
    fun `seal pubkey must equal rumor pubkey`() {
        val e = env()
        // Mallory seals a rumor that claims to be from Alice.
        val forged = UnsignedEvent(alice.publicKey, 1, Nip17.KIND_CHAT_MESSAGE, listOf(listOf("p", bob.publicKey)), "send me money")
        val key = Nip44.conversationKey(carol.privateKey, bob.publicKey, TestSecp256k1)
        val seal = UnsignedEvent(carol.publicKey, 1, Nip59.KIND_SEAL, emptyList(), Nip44.encrypt(forged.toJson(), key, e.randomBytes(32)))
            .sign(carol.privateKey, TestScheme)
        val wrap = Nip59.giftWrap(seal, bob.publicKey, TestScheme, TestSecp256k1, e)
        assertEquals(
            UnwrapResult.Refused(UnwrapFailure.SENDER_MISMATCH),
            Nip59.unwrap(wrap, bob.privateKey, TestScheme, TestSecp256k1),
        )
    }

    @Test
    fun `building a seal for someone else's rumor is refused`() {
        val notMine = Nip17.rumor(bob.publicKey, listOf(carol.publicKey), "x", 1)
        try {
            Nip59.seal(notMine, alice, carol.publicKey, TestScheme, TestSecp256k1, env())
            org.junit.Assert.fail("sealed a rumor by another author")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `bad signatures and wrong kinds are refused`() {
        val e = env()
        val wrap = Nip59.wrap(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, e)
        assertEquals(
            UnwrapResult.Refused(UnwrapFailure.BAD_WRAP_SIGNATURE),
            Nip59.unwrap(wrap.copy(sig = "00".repeat(64)), bob.privateKey, TestScheme, TestSecp256k1),
        )
        val plain = UnsignedEvent(alice.publicKey, 1, 1, emptyList(), "x").sign(alice.privateKey, TestScheme)
        assertEquals(
            UnwrapResult.Refused(UnwrapFailure.NOT_A_GIFT_WRAP),
            Nip59.unwrap(plain, bob.privateKey, TestScheme, TestSecp256k1),
        )

        // A wrap whose inner event is not a seal.
        val ephemeral = TestSecp256k1.keyPair(500)
        val key = Nip44.conversationKey(ephemeral.privateKey, bob.publicKey, TestSecp256k1)
        fun wrapOf(inner: NostrEvent) = UnsignedEvent(
            ephemeral.publicKey, 1, Nip59.KIND_GIFT_WRAP, listOf(listOf("p", bob.publicKey)), Nip44.encrypt(inner.toJson(), key, e.randomBytes(32)),
        ).sign(ephemeral.privateKey, TestScheme)
        assertEquals(
            UnwrapResult.Refused(UnwrapFailure.NOT_A_SEAL),
            Nip59.unwrap(wrapOf(plain), bob.privateKey, TestScheme, TestSecp256k1),
        )
        val badSeal = Nip59.seal(rumor(), alice, bob.publicKey, TestScheme, TestSecp256k1, e).copy(sig = "11".repeat(64))
        assertEquals(
            UnwrapResult.Refused(UnwrapFailure.BAD_SEAL_SIGNATURE),
            Nip59.unwrap(wrapOf(badSeal), bob.privateKey, TestScheme, TestSecp256k1),
        )
        val taggedSeal = UnsignedEvent(alice.publicKey, 1, Nip59.KIND_SEAL, listOf(listOf("x", "y")), "irrelevant").sign(alice.privateKey, TestScheme)
        assertEquals(
            UnwrapResult.Refused(UnwrapFailure.SEAL_HAS_TAGS),
            Nip59.unwrap(wrapOf(taggedSeal), bob.privateKey, TestScheme, TestSecp256k1),
        )
        assertEquals(
            UnwrapResult.Refused(UnwrapFailure.MALFORMED_SEAL),
            Nip59.unwrap(
                UnsignedEvent(ephemeral.publicKey, 1, Nip59.KIND_GIFT_WRAP, emptyList(), Nip44.encrypt("{}", key, e.randomBytes(32)))
                    .sign(ephemeral.privateKey, TestScheme),
                bob.privateKey, TestScheme, TestSecp256k1,
            ),
        )
    }

    @Test
    fun `nip17 rumor shape and fan-out to every participant`() {
        val r = Nip17.rumor(alice.publicKey, listOf(bob.publicKey, carol.publicKey), "lunch?", 42L)
        assertEquals(Nip17.KIND_CHAT_MESSAGE, r.kind)
        assertEquals(listOf(listOf("p", bob.publicKey), listOf("p", carol.publicKey)), r.tags)
        assertEquals("lunch?", r.content)

        val wraps = Nip17.giftWraps(r, alice, listOf(bob.publicKey, carol.publicKey), TestScheme, TestSecp256k1, env())
        assertEquals(listOf(bob.publicKey, carol.publicKey, alice.publicKey), wraps.map { it.tagValue("p") })
        for ((wrap, key) in wraps.zip(listOf(bob, carol, alice))) {
            val opened = Nip59.unwrap(wrap, key.privateKey, TestScheme, TestSecp256k1) as UnwrapResult.Opened
            assertEquals(r, opened.rumor)
        }
    }

    @Test
    fun `nip17 needs a recipient`() {
        try {
            Nip17.rumor(alice.publicKey, emptyList(), "x", 1)
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- SyncEnvelope ----

    @Test
    fun `envelope round trips every type through a wrap`() {
        for (type in SyncType.entries) {
            val envelope = SyncEnvelope(type, linkedMapOf("id" to "f1", "n" to 3L, "ok" to true, "list" to listOf("a", "b")))
            val rumor = envelope.toRumor(alice.publicKey, 10L)
            val wrap = Nip59.wrap(rumor, alice, bob.publicKey, TestScheme, TestSecp256k1, env())
            val opened = Nip59.unwrap(wrap, bob.privateKey, TestScheme, TestSecp256k1) as UnwrapResult.Opened
            assertEquals(envelope, SyncEnvelope.fromRumor(opened.rumor))
        }
    }

    @Test
    fun `envelope wire format is versioned and stable`() {
        assertEquals(
            "{\"v\":1,\"type\":\"tree-edit\",\"payload\":{\"a\":1}}",
            SyncEnvelope(SyncType.TREE_EDIT, mapOf("a" to 1L)).toJson(),
        )
    }

    @Test
    fun `envelope parse rejects newer versions, unknown types and bad shapes`() {
        assertNull(SyncEnvelope.parse("{\"v\":2,\"type\":\"fact\",\"payload\":{}}"))
        assertNull(SyncEnvelope.parse("{\"v\":1,\"type\":\"gossip\",\"payload\":{}}"))
        assertNull(SyncEnvelope.parse("{\"v\":1,\"type\":\"fact\",\"payload\":[]}"))
        assertNull(SyncEnvelope.parse("{\"type\":\"fact\",\"payload\":{}}"))
        assertNull(SyncEnvelope.parse("nope"))
        assertNull(SyncEnvelope.fromRumor(rumor())) // a chat message is not a sync envelope
    }

    // ---- RelayFrame ----

    private val sample = UnsignedEvent(alice.publicKey, 5, 1, emptyList(), "hi").sign(alice.privateKey, TestScheme)

    @Test
    fun `client frames serialise to NIP-01 text`() {
        assertEquals("[\"EVENT\",${sample.toJson()}]", RelayFrame.Event(sample).toJson())
        assertEquals("[\"CLOSE\",\"sub1\"]", RelayFrame.Close("sub1").toJson())
        val filter = RelayFrame.filter(kinds = listOf(1059), since = 100L, limit = 50, tags = mapOf("p" to listOf(bob.publicKey)))
        assertEquals(
            "[\"REQ\",\"sub1\",{\"kinds\":[1059],\"since\":100,\"limit\":50,\"#p\":[\"${bob.publicKey}\"]}]",
            RelayFrame.Req("sub1", listOf(filter)).toJson(),
        )
    }

    @Test
    fun `every frame parses back from its own text`() {
        val frames = listOf(
            RelayFrame.Event(sample),
            RelayFrame.Req("s", listOf(RelayFrame.filter(kinds = listOf(1059)), RelayFrame.filter(authors = listOf(alice.publicKey)))),
            RelayFrame.Close("s"),
            RelayFrame.EventDelivery("s", sample),
            RelayFrame.Ok(sample.id, false, "blocked: spam"),
            RelayFrame.Eose("s"),
            RelayFrame.Notice("slow down"),
        )
        for (f in frames) assertEquals(f, RelayFrame.parse(f.toJson()))
    }

    @Test
    fun `relay frames in the wild`() {
        assertEquals(RelayFrame.Ok("abc", true, ""), RelayFrame.parse("[\"OK\",\"abc\",true,\"\"]"))
        assertEquals(RelayFrame.Eose("x"), RelayFrame.parse("[\"EOSE\", \"x\"]"))
        assertEquals(RelayFrame.Unknown("AUTH"), RelayFrame.parse("[\"AUTH\",\"challenge\"]"))
        assertEquals(RelayFrame.Unknown("CLOSED"), RelayFrame.parse("[\"CLOSED\",\"s\",\"error: shutting down\"]"))
    }

    @Test
    fun `malformed frames are null`() {
        for (bad in listOf(
            "", "{}", "[]", "[1]", "[\"OK\",\"id\",\"yes\",\"\"]", "[\"OK\",\"id\",true]", "[\"EOSE\"]", "[\"NOTICE\",5]",
            "[\"EVENT\"]", "[\"EVENT\",\"s\",{}]", "[\"EVENT\",{\"id\":1}]", "[\"REQ\"]", "[\"REQ\",\"\"]", "[\"REQ\",\"s\",5]", "[\"CLOSE\"]",
        )) {
            assertNull(bad, RelayFrame.parse(bad))
        }
    }
}
