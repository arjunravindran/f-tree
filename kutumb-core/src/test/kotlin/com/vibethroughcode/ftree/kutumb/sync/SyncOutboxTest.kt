package com.vibethroughcode.ftree.kutumb.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncOutboxTest {
    private val relays = listOf("wss://a", "wss://b", "wss://c")
    private val policy = RetryPolicy(baseDelayMs = 1_000, maxDelayMs = 8_000, maxAttempts = 4, requiredAcks = 2)

    private fun entry(id: String = "e1", at: Long = 0, urls: List<String> = relays) =
        OutboxEntry(id, "sealed", listOf("pk"), urls, nextAttemptAt = at)

    private fun outbox(vararg e: OutboxEntry) = SyncOutbox(e.toList(), policy)

    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `backoff doubles from the base and stops at the cap`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { policy.delayAfter(it) })
        assertEquals("no overflow far out", 8_000L, policy.delayAfter(500))
    }

    @Test
    fun `a new entry is due at once and targets every relay`() {
        val due = outbox(entry()).due(now = 0)
        assertEquals(1, due.size)
        assertEquals(relays, due[0].second)
    }

    @Test
    fun `re-queueing a known event keeps its progress`() {
        val progressed = outbox(entry()).recordAttempt("e1", 0, setOf("wss://a"))
        assertEquals(progressed.entries, progressed.enqueue(entry()).entries)
    }

    @Test
    fun `a partial ack schedules a retry against only the silent relays`() {
        val o = outbox(entry()).recordAttempt("e1", now = 100, newlyAcked = setOf("wss://a"))
        val e = o.entries.single()

        assertEquals(100L, e.publishedAt)
        assertEquals(1, e.attempts)
        assertEquals(1_100L, e.nextAttemptAt)
        assertTrue("not due before the backoff ends", o.due(1_099).isEmpty())
        assertEquals(listOf("wss://b", "wss://c"), o.due(1_100).single().second)
        assertEquals(1_100L, o.nextWakeAt())
    }

    @Test
    fun `no acks leaves it unpublished and backs off further each time`() {
        var o = outbox(entry())
        o = o.recordAttempt("e1", 0, emptySet())
        assertNull(o.entries.single().publishedAt)
        o = o.recordAttempt("e1", 1_000, emptySet())
        assertEquals(1_000L + 2_000L, o.entries.single().nextAttemptAt)
    }

    @Test
    fun `enough relays confirming finishes it, across attempts`() {
        var o = outbox(entry()).recordAttempt("e1", 0, setOf("wss://a")).recordAttempt("e1", 1_000, setOf("wss://c"))
        assertEquals(EntryState.DONE, o.stateOf(o.entries.single()))
        assertTrue(o.due(1_000_000).isEmpty())
        assertNull(o.nextWakeAt())
        assertTrue(o.withoutDone().entries.isEmpty())
    }

    @Test
    fun `a single-relay entry is done by one ack even though two are required`() {
        val o = outbox(entry(urls = listOf("wss://a"))).recordAttempt("e1", 0, setOf("wss://a"))
        assertEquals(EntryState.DONE, o.stateOf(o.entries.single()))
    }

    @Test
    fun `a late ack counts without costing an attempt`() {
        val o = outbox(entry()).recordAttempt("e1", 0, setOf("wss://a")).recordAck("e1", "wss://b", now = 50)
        val e = o.entries.single()
        assertEquals(1, e.attempts)
        assertEquals(1_000L, e.nextAttemptAt)
        assertEquals(setOf("wss://a", "wss://b"), e.ackedBy)
        assertEquals(EntryState.DONE, o.stateOf(e))
    }

    @Test
    fun `acks from relays that are not targets, or for unknown events, are ignored`() {
        val o = outbox(entry())
        assertEquals(o.entries, o.recordAck("e1", "wss://zzz", 0).entries)
        assertEquals(o.entries, o.recordAck("nope", "wss://a", 0).entries)
        assertEquals(o.entries, o.recordAttempt("nope", 0, setOf("wss://a")).entries)
        assertTrue(o.recordAttempt("e1", 0, setOf("wss://zzz")).entries.single().ackedBy.isEmpty())
    }

    @Test
    fun `after the attempt limit it is given up, kept, and retryable`() {
        var o = outbox(entry())
        repeat(4) { o = o.recordAttempt("e1", it * 10_000L, emptySet()) }
        val e = o.entries.single()

        assertEquals(EntryState.GAVE_UP, o.stateOf(e))
        assertTrue(o.due(Long.MAX_VALUE).isEmpty())
        assertEquals(listOf(e), o.gaveUp())
        assertEquals("not purged", 1, o.withoutDone().entries.size)
        assertEquals("no further attempts counted", o.entries, o.recordAttempt("e1", 99, emptySet()).entries)

        val again = o.retryNow("e1", now = 500_000)
        assertEquals(EntryState.PENDING, again.stateOf(again.entries.single()))
        assertEquals(1, again.due(500_000).size)
    }

    @Test
    fun `only entries whose time has come are due, oldest first`() {
        val o = outbox(entry("late", at = 500), entry("now", at = 0), entry("later", at = 900))
        assertEquals(listOf("now"), o.due(0).map { it.first.eventId })
        assertEquals(listOf("late", "now"), o.due(600).map { it.first.eventId }.sorted())
        assertEquals(0L, o.nextWakeAt())
    }

    @Test
    fun `shape is validated`() {
        rejects { entry(urls = emptyList()) }
        rejects { entry(urls = listOf("wss://a", "wss://a")) }
        rejects { entry().copy(ackedBy = setOf("wss://zzz")) }
        rejects { SyncOutbox(listOf(entry(), entry())) }
        rejects { RetryPolicy(baseDelayMs = 10, maxDelayMs = 5) }
    }
}
