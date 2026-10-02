package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.sync.relay.FakeAck
import com.vibethroughcode.ftree.kutumb.sync.relay.FakeRelay
import com.vibethroughcode.ftree.kutumb.sync.relay.FakeRelayConnector
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Readers run in `backgroundScope`, which `advanceUntilIdle` deliberately does not wait for, so the
 * tests settle with `runCurrent`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private val alice = NostrBip340Scheme.generateKeyPair()
    private val bob = NostrBip340Scheme.generateKeyPair()
    private val carol = NostrBip340Scheme.generateKeyPair()
    private val r1 = FakeRelay("wss://one.example")
    private val r2 = FakeRelay("wss://two.example")
    private val relays = listOf(r1.url, r2.url)
    private var now = 1_800_000_000_000L
    private val env = secureWrapEnvironment { now / 1000 }
    private val fact = SyncEnvelope(SyncType.FACT, linkedMapOf("id" to "f1", "text" to "hello"))

    private class Received(val envelope: SyncEnvelope, val sender: TrustedContact)

    private inner class Node(
        val keys: KeyPair,
        scope: TestScope,
        policy: RetryPolicy = RetryPolicy(),
        ackTimeoutMs: Long = 10_000,
        seenCapacity: Int = 4096,
        failHandlerOnce: Boolean = false,
    ) {
        val repo = InMemoryKutumbRepository()
        val received = mutableListOf<Received>()
        val outcomes = mutableListOf<InboundOutcome>()
        private var failNext = failHandlerOnce
        val engine = SyncEngine(
            repository = repo,
            keyPair = keys,
            connector = FakeRelayConnector(listOf(r1, r2)),
            scope = scope.backgroundScope,
            clock = { now },
            handler = { envelope, sender, _ ->
                if (failNext) { failNext = false; error("boom") }
                received += Received(envelope, sender)
            },
            diagnostics = { _, outcome -> outcomes += outcome },
            policy = policy,
            env = env,
            ackTimeoutMs = ackTimeoutMs,
            seenCapacity = seenCapacity,
        )

        suspend fun trust(who: KeyPair, name: String) =
            repo.saveContact(TrustedContact(name, who.publicKey, pairedAt = 0, pairingMethod = PairingMethod.DIRECT))
    }

    private fun wrapFor(to: KeyPair, from: KeyPair = alice, envelope: SyncEnvelope = fact): NostrEvent =
        Nip59.wrap(envelope.toRumor(from.publicKey, now / 1000), from, to.publicKey, NostrBip340Scheme, Secp256k1Ecdh, env)

    // ---- outbound ----

    @Test
    fun publishesAWrapToEveryRelayAndClearsTheOutboxOnceAcked() = runTest {
        val a = Node(alice, this)
        val ids = a.engine.enqueue(fact, listOf(bob.publicKey, alice.publicKey, bob.publicKey), relays)
        assertEquals(1, ids.size) // own key and repeats are skipped

        val report = a.engine.flush()
        assertEquals(1, report.done)
        assertEquals(0, report.pending)
        assertNull(report.nextWakeAt)
        assertTrue(a.repo.outboxEntries().isEmpty())
        for (r in listOf(r1, r2)) {
            val sent = r.received.single()
            assertEquals(ids[0], sent.id)
            assertEquals(Nip59.KIND_GIFT_WRAP, sent.kind)
            assertEquals(bob.publicKey, sent.tagValue("p"))
            assertTrue(sent.verify(NostrBip340Scheme))
            assertTrue(!sent.content.contains("hello"))
        }
    }

    @Test
    fun partialAckIsRetriedOnlyAgainstTheRelayThatDidNotAnswer() = runTest {
        val policy = RetryPolicy(baseDelayMs = 5_000, requiredAcks = 2)
        val a = Node(alice, this, policy)
        r2.ack = FakeAck.SILENT
        val id = a.engine.enqueue(fact, listOf(bob.publicKey), relays).single()

        val first = a.engine.flush()
        assertEquals(0, first.done)
        assertEquals(1, first.pending)
        val entry = a.repo.outboxEntries().single()
        assertEquals(setOf(r1.url), entry.ackedBy)
        assertEquals(1, entry.attempts)
        assertEquals(now + 5_000, entry.nextAttemptAt)
        assertEquals(now, entry.publishedAt)
        assertEquals(now + 5_000, first.nextWakeAt)

        // Not due yet: nothing is sent.
        a.engine.flush()
        assertEquals(1, r1.received.size)
        assertEquals(1, r2.received.size)

        now += 5_000
        r2.ack = FakeAck.ACCEPT
        val second = a.engine.flush()
        assertEquals(1, second.done)
        assertEquals(1, r1.received.size) // already acked there: not resent
        assertEquals(2, r2.received.size)
        assertEquals(id, r2.received.last().id)
        assertTrue(a.repo.outboxEntries().isEmpty())
    }

    @Test
    fun backoffDoublesAndAnUnreachableRelayCountsAsNoAck() = runTest {
        val a = Node(alice, this, RetryPolicy(baseDelayMs = 1_000, requiredAcks = 2))
        r2.online = false
        a.engine.enqueue(fact, listOf(bob.publicKey), relays)
        a.engine.flush()
        now += 1_000
        a.engine.flush()
        val entry = a.repo.outboxEntries().single()
        assertEquals(2, entry.attempts)
        assertEquals(now + 2_000, entry.nextAttemptAt)
        assertEquals(setOf(r1.url), entry.ackedBy)
    }

    @Test
    fun aRejectionIsNotAnAckAndTheEntryGivesUpAfterMaxAttempts() = runTest {
        val policy = RetryPolicy(baseDelayMs = 1_000, maxDelayMs = 4_000, maxAttempts = 3, requiredAcks = 1)
        val a = Node(alice, this, policy)
        r1.ack = FakeAck.REJECT
        r2.ack = FakeAck.REJECT
        val id = a.engine.enqueue(fact, listOf(bob.publicKey), relays).single()

        repeat(3) {
            a.engine.flush()
            now += 10_000
        }
        val last = a.engine.flush()
        assertEquals(1, last.gaveUp)
        assertEquals(0, last.pending)
        assertEquals(3, a.repo.outboxEntries().single().attempts)
        assertEquals(3, r1.received.size) // the fourth flush sent nothing
        assertEquals(listOf(id), a.engine.gaveUp().map { it.eventId })

        // The user taps retry once the relays are healthy again.
        r1.ack = FakeAck.ACCEPT
        a.engine.retry(id)
        val retried = a.engine.flush()
        assertEquals(1, retried.done)
        assertTrue(a.repo.outboxEntries().isEmpty())
    }

    @Test
    fun anOkThatArrivesAfterTheTimeoutIsMergedWithoutCountingAsAnAttempt() = runTest {
        val a = Node(alice, this, RetryPolicy(requiredAcks = 2), ackTimeoutMs = 1_000)
        r2.ack = FakeAck.SILENT
        val id = a.engine.enqueue(fact, listOf(bob.publicKey), relays).single()
        a.engine.flush()
        assertEquals(setOf(r1.url), a.repo.outboxEntries().single().ackedBy)

        r2.injectRaw(RelayFrame.Ok(id, true, "").toJson())
        runCurrent()
        // Both relays now confirmed, so the entry is finished.
        assertTrue(a.repo.outboxEntries().isEmpty())
    }

    // ---- inbound ----

    @Test
    fun aWrapFromATrustedContactIsHandledOnceEvenFromTwoRelaysAndAReplay() = runTest {
        val b = Node(bob, this)
        b.trust(alice, "alice")
        val wrap = wrapFor(bob)
        r1.stored += wrap // waiting before bob connects: replayed to his REQ

        b.engine.startInbound(relays)
        runCurrent()
        assertEquals(1, b.received.size)
        assertEquals(fact, b.received[0].envelope)
        assertEquals("alice", b.received[0].sender.personId)

        r2.inject(wrap) // the same event from the second relay
        r1.inject(wrap)
        runCurrent()
        assertEquals(1, b.received.size)
        assertEquals(listOf(InboundOutcome.HANDLED, InboundOutcome.DUPLICATE, InboundOutcome.DUPLICATE), b.outcomes)

        // The subscription asked for kind 1059 tagged to bob, and nothing else.
        val filter = r1.subscriptions.single().second.filters.single()
        assertEquals(listOf(1059L), filter["kinds"])
        assertEquals(listOf(bob.publicKey), filter["#p"])
    }

    @Test
    fun theSeenSetIsBounded() = runTest {
        val b = Node(bob, this, seenCapacity = 2)
        b.trust(alice, "alice")
        val wraps = (1..3).map { wrapFor(bob, envelope = SyncEnvelope(SyncType.FACT, linkedMapOf("n" to it.toLong()))) }
        wraps.forEach { assertEquals(InboundOutcome.HANDLED, b.engine.process(it)) }
        assertEquals(InboundOutcome.DUPLICATE, b.engine.process(wraps[2]))
        assertEquals(InboundOutcome.DUPLICATE, b.engine.process(wraps[1]))
        assertEquals(InboundOutcome.HANDLED, b.engine.process(wraps[0])) // evicted, so seen as new again
    }

    @Test
    fun aSenderWhoIsNotATrustedContactIsDroppedAndNotRemembered() = runTest {
        val b = Node(bob, this)
        b.trust(alice, "alice")
        val fromCarol = wrapFor(bob, from = carol)
        b.engine.startInbound(relays)
        r1.inject(fromCarol)
        runCurrent()
        assertTrue(b.received.isEmpty())
        assertEquals(listOf(InboundOutcome.UNTRUSTED_SENDER), b.outcomes)

        // Once carol is paired, the same wrap (replayed by the relay) is accepted.
        b.trust(carol, "carol")
        assertEquals(InboundOutcome.HANDLED, b.engine.process(fromCarol))
        assertEquals("carol", b.received.single().sender.personId)
    }

    @Test
    fun aRevokedKeyIsNotTrusted() = runTest {
        val b = Node(bob, this)
        val oldKey = alice
        val newKey = NostrBip340Scheme.generateKeyPair()
        b.repo.saveContact(
            TrustedContact("alice", oldKey.publicKey, pairedAt = 0, pairingMethod = PairingMethod.DIRECT).withRotatedKey(newKey.publicKey),
        )
        assertEquals(InboundOutcome.UNTRUSTED_SENDER, b.engine.process(wrapFor(bob, from = oldKey)))
        assertEquals(InboundOutcome.HANDLED, b.engine.process(wrapFor(bob, from = newKey)))
    }

    @Test
    fun malformedAndForgedEventsAreDropped() = runTest {
        val b = Node(bob, this)
        b.trust(alice, "alice")
        b.engine.startInbound(relays)
        val good = wrapFor(bob)

        // Garbage on the wire is ignored without disturbing the reader.
        r1.injectRaw("not json")
        r1.injectRaw("[\"EVENT\",\"kutumb-inbox\",{\"id\":1}]")
        r1.injectRaw("[\"EVENT\",\"other-sub\",${good.toJson()}]")

        // Content tampered after signing: the signature no longer covers it.
        assertEquals(InboundOutcome.IGNORED, b.engine.process(good.copy(content = good.content.dropLast(4) + "AAAA")))
        // A forged signature on an otherwise genuine wrap.
        assertEquals(InboundOutcome.IGNORED, b.engine.process(good.copy(sig = "00".repeat(64))))
        // Addressed to someone else.
        assertEquals(InboundOutcome.IGNORED, b.engine.process(wrapFor(carol)))
        // Not a gift wrap at all.
        val note = UnsignedEvent(alice.publicKey, now / 1000, 1, listOf(listOf("p", bob.publicKey)), "hi").sign(alice.privateKey, NostrBip340Scheme)
        assertEquals(InboundOutcome.IGNORED, b.engine.process(note))
        // Properly signed and addressed to bob, but the inside is not a seal.
        val junk = UnsignedEvent(
            alice.publicKey, now / 1000, Nip59.KIND_GIFT_WRAP, listOf(listOf("p", bob.publicKey)), "AAAA",
        ).sign(alice.privateKey, NostrBip340Scheme)
        assertEquals(InboundOutcome.REFUSED, b.engine.process(junk))
        runCurrent()
        assertTrue(b.received.isEmpty())

        // None of that used up the real event's id.
        assertEquals(InboundOutcome.HANDLED, b.engine.process(good))
        assertEquals(1, b.received.size)
    }

    @Test
    fun anAuthenticMessageThisVersionCannotReadIsNotHanded_onAndAHandlerFailureIsRetried() = runTest {
        val b = Node(bob, this, failHandlerOnce = true)
        b.trust(alice, "alice")
        val rumor = UnsignedEvent(alice.publicKey, now / 1000, 1, emptyList(), "just text")
        val odd = Nip59.wrap(rumor, alice, bob.publicKey, NostrBip340Scheme, Secp256k1Ecdh, env)
        assertEquals(InboundOutcome.UNSUPPORTED, b.engine.process(odd))
        val newer = Nip59.wrap(UnsignedEvent(alice.publicKey, now / 1000, SyncEnvelope.RUMOR_KIND, emptyList(), "{\"v\":2,\"type\":\"fact\",\"payload\":{}}"), alice, bob.publicKey, NostrBip340Scheme, Secp256k1Ecdh, env)
        assertEquals(InboundOutcome.UNSUPPORTED, b.engine.process(newer))
        assertTrue(b.received.isEmpty())

        val wrap = wrapFor(bob)
        assertEquals(InboundOutcome.HANDLER_FAILED, b.engine.process(wrap))
        assertEquals(InboundOutcome.HANDLED, b.engine.process(wrap))
        assertEquals(InboundOutcome.DUPLICATE, b.engine.process(wrap))
    }

    // ---- both ends ----

    @Test
    fun aMessageGoesFromOneEngineThroughTheRelaysToAnother() = runTest {
        val a = Node(alice, this)
        val b = Node(bob, this)
        b.trust(alice, "alice")
        b.engine.startInbound(relays)
        runCurrent()

        a.engine.enqueue(fact, listOf(bob.publicKey), relays)
        assertEquals(1, a.engine.flush().done)
        runCurrent()

        assertEquals(fact, b.received.single().envelope)
        assertEquals(listOf(InboundOutcome.HANDLED, InboundOutcome.DUPLICATE), b.outcomes)
    }

    @Test
    fun aReconnectResubscribes() = runTest {
        val b = Node(bob, this)
        b.trust(alice, "alice")
        b.engine.startInbound(relays)
        runCurrent()
        r1.drop()
        runCurrent()
        assertTrue(r1.subscriptions.isEmpty())

        b.engine.startInbound(relays)
        runCurrent()
        assertEquals(1, r1.subscriptions.size)
        r1.inject(wrapFor(bob))
        runCurrent()
        assertEquals(1, b.received.size)
    }
}
