package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class SyncStatus {
    /** The switch is off. No connection exists. */
    OFF,

    /** On, but nothing to do it with: no relays named, or this phone has no identity yet. */
    NEEDS_SETUP,

    /** Running: subscribed, and flushing the outbox. */
    ON,
}

/**
 * Keeps a [SyncEngine] running while, and only while, sync is switched on, relays are named and
 * this phone has an identity. Turning any of the three off stops the engine and closes every
 * socket; changing the relays or the identity restarts it.
 *
 * While running it re-subscribes (which also reconnects relays that dropped) and flushes the
 * outbox on a timer, sooner when the retry policy says an entry is due or [poke] is called.
 */
class SyncService(
    private val enabled: Flow<Boolean>,
    private val relays: Flow<List<String>>,
    private val repository: KutumbRepository,
    private val newEngine: (KeyPair) -> SyncEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val maxIdleMs: Long = 60_000,
) {
    private val _status = MutableStateFlow(SyncStatus.OFF)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var engine: SyncEngine? = null
    private val relayList = MutableStateFlow<List<String>>(emptyList())

    /** Asks for a flush now (something was just queued). Does nothing while not running. */
    fun poke() { wake.trySend(Unit) }

    /**
     * Queues [envelope] for [recipients] on the current relays and pokes the loop. Returns false,
     * queuing nothing, when sync is not running: the caller's local change stands either way.
     */
    suspend fun publish(envelope: SyncEnvelope, recipients: List<String>): Boolean {
        val running = engine ?: return false
        val urls = relayList.value
        if (urls.isEmpty() || recipients.isEmpty()) return false
        running.enqueue(envelope, recipients, urls)
        poke()
        return true
    }

    fun start() {
        scope.launch {
            combine(enabled, relays, repository.observeIdentity()) { on, urls, identity ->
                Triple(on, urls, identity?.keyPair)
            }.distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second && a.third?.publicKey == b.third?.publicKey }
                .collectLatest { (on, urls, keys) ->
                    if (!on) { _status.value = SyncStatus.OFF; return@collectLatest }
                    if (urls.isEmpty() || keys == null) { _status.value = SyncStatus.NEEDS_SETUP; return@collectLatest }
                    run(keys, urls)
                }
        }
    }

    private suspend fun run(keys: KeyPair, urls: List<String>) {
        val running = newEngine(keys)
        engine = running
        relayList.value = urls
        _status.value = SyncStatus.ON
        try {
            while (true) {
                running.startInbound(urls)
                val report = running.flush()
                val untilDue = report.nextWakeAt?.let { (it - clock()).coerceAtLeast(1_000) } ?: maxIdleMs
                withTimeoutOrNull(minOf(untilDue, maxIdleMs)) { wake.receive() }
            }
        } finally {
            withContext(NonCancellable) {
                engine = null
                relayList.value = emptyList()
                running.stop()
            }
        }
    }
}
