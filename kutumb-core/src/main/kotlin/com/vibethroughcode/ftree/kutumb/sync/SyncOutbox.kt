package com.vibethroughcode.ftree.kutumb.sync

/**
 * One event waiting to reach the relays. [payloadEncrypted] is already sealed; nothing here ever
 * sees plaintext. [ackedBy] holds the relays that have confirmed it.
 */
data class OutboxEntry(
    val eventId: String,
    val payloadEncrypted: String,
    val targetPubKeys: List<String>,
    val relayUrls: List<String>,
    /** When a relay first accepted it; null until then. */
    val publishedAt: Long? = null,
    val ackedBy: Set<String> = emptySet(),
    val attempts: Int = 0,
    /** Earliest time the next attempt may run. */
    val nextAttemptAt: Long = 0,
) {
    init {
        require(eventId.isNotBlank()) { "eventId is blank" }
        require(relayUrls.isNotEmpty() && relayUrls.toSet().size == relayUrls.size) { "relayUrls must be non-empty and distinct" }
        require(relayUrls.containsAll(ackedBy)) { "acked by a relay that is not a target" }
        require(attempts >= 0) { "negative attempts" }
    }

    /** The relays still to try. */
    val unackedRelays: List<String> get() = relayUrls.filter { it !in ackedBy }
}

/** How hard to try, and when to call it done. */
data class RetryPolicy(
    val baseDelayMs: Long = 5_000,
    val maxDelayMs: Long = 15 * 60_000,
    /** Attempts after which an entry is given up on and left for the caller to surface. */
    val maxAttempts: Int = 12,
    /** Relays that must confirm before an entry is done. Capped at the entry's own relay count. */
    val requiredAcks: Int = 2,
) {
    init {
        require(baseDelayMs > 0 && maxDelayMs >= baseDelayMs) { "bad delays" }
        require(maxAttempts > 0 && requiredAcks > 0) { "bad limits" }
    }

    /** Doubles per attempt from [baseDelayMs], capped at [maxDelayMs]. [attempts] counts those already made. */
    fun delayAfter(attempts: Int): Long {
        require(attempts > 0) { "no attempt made yet" }
        val shift = (attempts - 1).coerceAtMost(40)
        val raw = baseDelayMs.toDouble() * (1L shl shift)
        return if (raw >= maxDelayMs) maxDelayMs else raw.toLong()
    }
}

enum class EntryState { PENDING, DONE, GAVE_UP }

/**
 * The local retry queue. Relays are best-effort, so an event is only done once enough of them have
 * confirmed it; until then it stays queued and is retried with backoff, only against the relays
 * that have not yet answered. Immutable: each step returns the next queue. Actual publishing is
 * the caller's job — this decides what to try and when, and records what came back.
 */
class SyncOutbox(entries: List<OutboxEntry> = emptyList(), val policy: RetryPolicy = RetryPolicy()) {
    val entries: List<OutboxEntry> = entries.toList()

    init {
        require(this.entries.map { it.eventId }.toSet().size == this.entries.size) { "duplicate eventId" }
    }

    fun stateOf(entry: OutboxEntry): EntryState = when {
        entry.ackedBy.size >= minOf(policy.requiredAcks, entry.relayUrls.size) -> EntryState.DONE
        entry.attempts >= policy.maxAttempts -> EntryState.GAVE_UP
        else -> EntryState.PENDING
    }

    /** Queues an event. Re-queueing a known eventId changes nothing, so a retried save cannot reset its progress. */
    fun enqueue(entry: OutboxEntry): SyncOutbox =
        if (entries.any { it.eventId == entry.eventId }) this else copyWith(entries + entry)

    /** Entries to try now, oldest first, each with the relays that still need it. */
    fun due(now: Long): List<Pair<OutboxEntry, List<String>>> =
        entries.filter { stateOf(it) == EntryState.PENDING && it.nextAttemptAt <= now }
            .map { it to it.unackedRelays }

    /** The earliest time anything becomes due, for scheduling the next wake-up; null when idle. */
    fun nextWakeAt(): Long? =
        entries.filter { stateOf(it) == EntryState.PENDING }.minOfOrNull { it.nextAttemptAt }

    /**
     * Records one attempt: which relays confirmed this time ([newlyAcked], merged with earlier
     * confirmations; relays outside the entry are ignored). An unknown eventId is a no-op.
     */
    fun recordAttempt(eventId: String, now: Long, newlyAcked: Set<String>): SyncOutbox {
        val entry = entries.firstOrNull { it.eventId == eventId } ?: return this
        if (stateOf(entry) != EntryState.PENDING) return this
        val acked = entry.ackedBy + newlyAcked.filter { it in entry.relayUrls }
        val attempts = entry.attempts + 1
        val updated = entry.copy(
            ackedBy = acked,
            attempts = attempts,
            publishedAt = entry.publishedAt ?: now.takeIf { acked.isNotEmpty() },
            nextAttemptAt = now + policy.delayAfter(attempts),
        )
        return copyWith(entries.map { if (it.eventId == eventId) updated else it })
    }

    /**
     * A confirmation that arrives outside an attempt (a relay answering late). Merged in without
     * counting as an attempt or moving the retry time.
     */
    fun recordAck(eventId: String, relayUrl: String, now: Long): SyncOutbox {
        val entry = entries.firstOrNull { it.eventId == eventId } ?: return this
        if (relayUrl !in entry.relayUrls || relayUrl in entry.ackedBy) return this
        val updated = entry.copy(ackedBy = entry.ackedBy + relayUrl, publishedAt = entry.publishedAt ?: now)
        return copyWith(entries.map { if (it.eventId == eventId) updated else it })
    }

    /** Drops finished entries; given-up ones stay so the app can tell the user. */
    fun withoutDone(): SyncOutbox = copyWith(entries.filter { stateOf(it) != EntryState.DONE })

    fun gaveUp(): List<OutboxEntry> = entries.filter { stateOf(it) == EntryState.GAVE_UP }

    /** Puts a given-up entry back in the queue, e.g. when the user taps retry or the network returns. */
    fun retryNow(eventId: String, now: Long): SyncOutbox =
        copyWith(entries.map { if (it.eventId == eventId && stateOf(it) == EntryState.GAVE_UP) it.copy(attempts = 0, nextAttemptAt = now) else it })

    private fun copyWith(next: List<OutboxEntry>) = SyncOutbox(next, policy)
}
