package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.sync.relay.RelayConnection
import com.vibethroughcode.ftree.kutumb.sync.relay.RelayConnector
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives a verified message from a trusted family member. Applying it (facts, answers,
 * resolutions, rotations, tree edits) is the handler's job and must be idempotent: the engine's
 * seen-set is in memory, so a restart can deliver the same message again.
 */
fun interface SyncHandler {
    suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent)
}

/** Accepts and forgets. The default until fact and tree application is wired. */
object NoOpSyncHandler : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) = Unit
}

/** Reports each message to [log] (the app passes Log.d; tests pass a list) and does nothing else. */
class LoggingSyncHandler(private val log: (String) -> Unit) : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) =
        log("sync: ${envelope.type.wire} from ${sender.personId} (rumor ${rumor.id})")
}

/** What the engine did with one inbound gift wrap. */
enum class InboundOutcome {
    HANDLED,

    /** Already processed (same wrap id, perhaps from another relay or a replay). */
    DUPLICATE,

    /** Not a kind-1059 event `p`-tagged to this identity, or its outer signature or id is wrong. */
    IGNORED,

    /** A wrap that is signed but whose inner layers failed a check (bad seal, mismatched author, undecryptable). */
    REFUSED,

    /** Opened fine, but the author is not a trusted contact's current key. Not remembered, so a later pairing can still receive it. */
    UNTRUSTED_SENDER,

    /** Authentic and trusted, but not an envelope this app version understands. */
    UNSUPPORTED,

    /** The handler threw; not remembered, so a redelivery tries again. */
    HANDLER_FAILED,
}

fun interface SyncDiagnostics {
    fun inbound(wrapId: String, outcome: InboundOutcome)
}

data class FlushReport(
    val done: Int,
    val pending: Int,
    val gaveUp: Int,
    /** When the next retry becomes due, for scheduling a wake-up; null when nothing is waiting. */
    val nextWakeAt: Long?,
)

/**
 * Moves sync messages between the local outbox and the relays, with no network of its own: the
 * [connector] is injected, as are the clock and the [scope] every reader runs in.
 *
 * Outbound: [enqueue] gift-wraps an envelope once per recipient and queues each wrap in the Room
 * outbox; [flush] publishes whatever [SyncOutbox.due] says, maps each relay's OK to an ack, and
 * records the attempt. Inbound: [startInbound] subscribes every relay for kind-1059 wraps tagged to
 * this identity, unwraps them, drops duplicates and anyone who is not a trusted contact, and hands
 * the decoded envelope to [handler].
 *
 * Time is milliseconds ([clock]); the wraps' own timestamps are seconds, derived from it.
 */
class SyncEngine(
    private val repository: KutumbRepository,
    private val keyPair: KeyPair,
    private val connector: RelayConnector,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val handler: SyncHandler = NoOpSyncHandler,
    private val diagnostics: SyncDiagnostics = SyncDiagnostics { _, _ -> },
    private val policy: RetryPolicy = RetryPolicy(),
    private val scheme: SignatureScheme = NostrBip340Scheme,
    private val ecdh: Ecdh = Secp256k1Ecdh,
    private val env: WrapEnvironment = secureWrapEnvironment { clock() / 1000 },
    private val ackTimeoutMs: Long = 10_000,
    private val seenCapacity: Int = 4096,
) {
    private class Link(val connection: RelayConnection) {
        val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
        var reader: Job? = null
    }

    private val linksLock = Mutex()
    private val links = HashMap<String, Link>()
    private val flushLock = Mutex()
    private val inboundUrls = LinkedHashSet<String>()
    private val inbox = Channel<NostrEvent>(Channel.UNLIMITED)
    private var inboxWorker: Job? = null
    private val seen = LinkedHashSet<String>()

    // ---- outbound ----

    /**
     * Wraps [envelope] for each of [recipientPubkeys] (own key and repeats ignored) and queues one
     * outbox entry per wrap against [relayUrls]. Returns the wrap ids. Nothing is sent until [flush].
     */
    suspend fun enqueue(envelope: SyncEnvelope, recipientPubkeys: List<String>, relayUrls: List<String>): List<String> {
        val relays = relayUrls.distinct()
        require(relays.isNotEmpty()) { "no relays to publish to" }
        val rumor = envelope.toRumor(keyPair.publicKey, clock() / 1000)
        return recipientPubkeys.distinct().filter { it != keyPair.publicKey }.map { recipient ->
            val wrap = Nip59.wrap(rumor, keyPair, recipient, scheme, ecdh, env)
            repository.saveOutboxEntry(OutboxEntry(wrap.id, wrap.toJson(), listOf(recipient), relays))
            wrap.id
        }
    }

    /** One pass over everything due now. Safe to call repeatedly or concurrently; passes do not overlap. */
    suspend fun flush(): FlushReport = flushLock.withLock {
        val due = SyncOutbox(repository.outboxEntries(), policy).due(clock())
        var done = 0
        for ((entry, relays) in due) {
            val acked = coroutineScope {
                relays.map { url -> async { url to publish(entry, url) } }.awaitAll()
            }.filter { it.second }.map { it.first }.toSet()
            val box = SyncOutbox(listOf(entry), policy).recordAttempt(entry.eventId, clock(), acked)
            done += persist(box)
        }
        val box = SyncOutbox(repository.outboxEntries(), policy)
        FlushReport(
            done = done,
            pending = box.entries.count { box.stateOf(it) == EntryState.PENDING },
            gaveUp = box.gaveUp().size,
            nextWakeAt = box.nextWakeAt(),
        )
    }

    /** Entries the retry policy has stopped on, for the app to surface; see [SyncOutbox.retryNow]. */
    suspend fun gaveUp(): List<OutboxEntry> = SyncOutbox(repository.outboxEntries(), policy).gaveUp()

    /** Puts a given-up entry back in the queue, due immediately. */
    suspend fun retry(eventId: String) {
        val box = SyncOutbox(repository.outboxEntries(), policy).retryNow(eventId, clock())
        box.entries.firstOrNull { it.eventId == eventId }?.let { repository.saveOutboxEntry(it) }
    }

    /** Saves what is still waiting, deletes what is finished; returns how many finished. */
    private suspend fun persist(box: SyncOutbox): Int {
        var done = 0
        for (entry in box.entries) {
            if (box.stateOf(entry) == EntryState.DONE) {
                repository.deleteOutboxEntry(entry.eventId)
                done++
            } else {
                repository.saveOutboxEntry(entry)
            }
        }
        return done
    }

    private suspend fun publish(entry: OutboxEntry, url: String): Boolean {
        val event = NostrEvent.fromJson(entry.payloadEncrypted) ?: return false
        val link = ensureLink(url) ?: return false
        val answer = CompletableDeferred<Boolean>()
        link.pending[entry.eventId] = answer
        try {
            if (!link.connection.send(RelayFrame.Event(event).toJson())) return false
            return withTimeoutOrNull(ackTimeoutMs) { answer.await() } ?: false
        } finally {
            link.pending.remove(entry.eventId, answer)
        }
    }

    /** An OK that arrives after its attempt gave up waiting. Merged without counting as an attempt. */
    private suspend fun lateAck(eventId: String, url: String) = flushLock.withLock {
        val entry = repository.outboxEntries().firstOrNull { it.eventId == eventId } ?: return@withLock
        persist(SyncOutbox(listOf(entry), policy).recordAck(eventId, url, clock()))
        Unit
    }

    // ---- inbound ----

    /**
     * Subscribes [relayUrls] for wraps addressed to this identity. Idempotent; call again to bring
     * back a relay whose connection dropped (a reconnect re-sends the subscription on its own).
     * There is no `since`: wraps are back-dated by up to two days, and the seen-set absorbs replays.
     */
    suspend fun startInbound(relayUrls: List<String>) {
        if (inboxWorker == null) inboxWorker = scope.launch { for (wrap in inbox) process(wrap) }
        linksLock.withLock { inboundUrls.addAll(relayUrls) }
        relayUrls.forEach { ensureLink(it) }
    }

    /** Closes every connection and stops reading. Outbox entries stay queued. */
    suspend fun stop() {
        inboxWorker?.cancel()
        inboxWorker = null
        linksLock.withLock {
            inboundUrls.clear()
            links.values.forEach { it.connection.close(); it.reader?.cancel() }
            links.clear()
        }
    }

    /** Decides what to do with one inbound wrap; exposed for the reader and for tests. */
    suspend fun process(wrap: NostrEvent): InboundOutcome = decide(wrap).also { diagnostics.inbound(wrap.id, it) }

    private suspend fun decide(wrap: NostrEvent): InboundOutcome {
        if (wrap.kind != Nip59.KIND_GIFT_WRAP || keyPair.publicKey !in wrap.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }) {
            return InboundOutcome.IGNORED
        }
        // Verified before the id is remembered, so a forged event cannot use up a real one's id.
        if (!wrap.verify(scheme)) return InboundOutcome.IGNORED
        if (!remember(wrap.id)) return InboundOutcome.DUPLICATE

        val opened = Nip59.unwrap(wrap, keyPair.privateKey, scheme, ecdh) as? UnwrapResult.Opened
            ?: return InboundOutcome.REFUSED
        val sender = repository.observeContacts().first().firstOrNull { it.isCurrentKey(opened.senderPubkey) }
        if (sender == null) {
            forget(wrap.id)
            return InboundOutcome.UNTRUSTED_SENDER
        }
        val envelope = SyncEnvelope.fromRumor(opened.rumor) ?: return InboundOutcome.UNSUPPORTED
        return try {
            handler.onEnvelope(envelope, sender, opened.rumor)
            InboundOutcome.HANDLED
        } catch (e: CancellationException) {
            forget(wrap.id)
            throw e
        } catch (_: Exception) {
            forget(wrap.id)
            InboundOutcome.HANDLER_FAILED
        }
    }

    /** True if [id] was new. Oldest ids fall out past [seenCapacity]. */
    private fun remember(id: String): Boolean = synchronized(seen) {
        if (!seen.add(id)) return false
        while (seen.size > seenCapacity) seen.remove(seen.first())
        true
    }

    private fun forget(id: String) = synchronized(seen) { seen.remove(id) }

    // ---- connections ----

    private suspend fun ensureLink(url: String): Link? = linksLock.withLock {
        links[url]?.let { return it }
        val connection = try {
            connector.connect(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val link = Link(connection)
        links[url] = link
        link.reader = scope.launch {
            try {
                connection.incoming.collect { onFrame(link, it) }
            } finally {
                linksLock.withLock { if (links[url] === link) links.remove(url) }
            }
        }
        if (url in inboundUrls) {
            connection.send(RelayFrame.Req(INBOX_SUBSCRIPTION, listOf(inboxFilter())).toJson())
        }
        link
    }

    private fun inboxFilter() = RelayFrame.filter(kinds = listOf(Nip59.KIND_GIFT_WRAP), tags = mapOf("p" to listOf(keyPair.publicKey)))

    private fun onFrame(link: Link, text: String) {
        when (val frame = RelayFrame.parse(text) ?: return) {
            is RelayFrame.Ok -> {
                val waiting = link.pending.remove(frame.eventId)
                if (waiting != null) waiting.complete(frame.accepted)
                else if (frame.accepted) scope.launch { lateAck(frame.eventId, link.connection.url) }
            }
            is RelayFrame.EventDelivery -> if (frame.subscriptionId == INBOX_SUBSCRIPTION) inbox.trySend(frame.event)
            else -> Unit
        }
    }

    private companion object {
        const val INBOX_SUBSCRIPTION = "kutumb-inbox"
    }
}
