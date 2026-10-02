package com.vibethroughcode.ftree.kutumb.sync.relay

import com.vibethroughcode.ftree.kutumb.sync.NostrEvent
import com.vibethroughcode.ftree.kutumb.sync.RelayFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException

/** How a [FakeRelay] answers a published event. */
enum class FakeAck { ACCEPT, REJECT, SILENT }

/**
 * An in-memory relay for tests: it stores accepted events, answers a publish with OK true/false or
 * says nothing at all, and replays what it has stored to a REQ (matching kinds and `#p`), then EOSE.
 * [inject] delivers an event to live subscriptions as a real relay would; [drop] ends every
 * connection from the relay's side.
 */
class FakeRelay(val url: String) {
    var ack: FakeAck = FakeAck.ACCEPT
    var rejectMessage: String = "blocked: not welcome"

    /** While false, [FakeRelayConnector.connect] throws, as for an unreachable relay. */
    var online: Boolean = true

    /** Every EVENT frame received, accepted or not, in order. */
    val received = mutableListOf<NostrEvent>()
    val stored = mutableListOf<NostrEvent>()
    val subscriptions = mutableListOf<Pair<FakeConnection, RelayFrame.Req>>()
    private val connections = mutableListOf<FakeConnection>()

    internal fun open(): FakeConnection = FakeConnection(url, this).also { connections += it }

    fun drop() {
        connections.toList().forEach { it.closeFromRelay() }
        connections.clear()
        subscriptions.clear()
    }

    /** Stores [event] and delivers it to every matching live subscription. */
    fun inject(event: NostrEvent) {
        stored += event
        broadcast(event)
    }

    private fun broadcast(event: NostrEvent) {
        for ((connection, req) in subscriptions.toList()) {
            if (req.filters.any { matches(it, event) }) {
                connection.push(RelayFrame.EventDelivery(req.subscriptionId, event).toJson())
            }
        }
    }

    /** Sends [text] to every open connection as-is, for malformed frames and late OKs. */
    fun injectRaw(text: String) = connections.toList().forEach { it.push(text) }

    internal fun handle(from: FakeConnection, text: String) {
        when (val frame = RelayFrame.parse(text)) {
            is RelayFrame.Event -> {
                received += frame.event
                when (ack) {
                    FakeAck.ACCEPT -> {
                        if (stored.none { it.id == frame.event.id }) {
                            stored += frame.event
                            broadcast(frame.event)
                        }
                        from.push(RelayFrame.Ok(frame.event.id, true, "").toJson())
                    }
                    FakeAck.REJECT -> from.push(RelayFrame.Ok(frame.event.id, false, rejectMessage).toJson())
                    FakeAck.SILENT -> Unit
                }
            }
            is RelayFrame.Req -> {
                subscriptions += from to frame
                stored.filter { e -> frame.filters.any { matches(it, e) } }
                    .forEach { from.push(RelayFrame.EventDelivery(frame.subscriptionId, it).toJson()) }
                from.push(RelayFrame.Eose(frame.subscriptionId).toJson())
            }
            is RelayFrame.Close -> subscriptions.removeAll { it.first === from && it.second.subscriptionId == frame.subscriptionId }
            else -> Unit
        }
    }

    private fun matches(filter: Map<String, Any?>, event: NostrEvent): Boolean {
        (filter["kinds"] as? List<*>)?.let { kinds -> if (kinds.none { (it as? Number)?.toInt() == event.kind }) return false }
        (filter["authors"] as? List<*>)?.let { if (event.pubkey !in it) return false }
        (filter["ids"] as? List<*>)?.let { if (event.id !in it) return false }
        (filter["since"] as? Number)?.let { if (event.createdAt < it.toLong()) return false }
        (filter["#p"] as? List<*>)?.let { wanted ->
            val tagged = event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
            if (tagged.none { it in wanted }) return false
        }
        return true
    }
}

class FakeConnection internal constructor(override val url: String, private val relay: FakeRelay) : RelayConnection {
    private val frames = Channel<String>(Channel.UNLIMITED)
    private var open = true

    override val incoming: Flow<String> = frames.receiveAsFlow()

    override suspend fun send(frame: String): Boolean {
        if (!open) return false
        relay.handle(this, frame)
        return true
    }

    override fun close() {
        open = false
        frames.close()
    }

    internal fun push(frame: String) {
        if (open) frames.trySend(frame)
    }

    internal fun closeFromRelay() = close()
}

/** A [RelayConnector] over a set of [FakeRelay]s. An unknown or offline URL fails like an unreachable relay. */
class FakeRelayConnector(relays: List<FakeRelay>) : RelayConnector {
    private val byUrl = relays.associateBy { it.url }
    val connectAttempts = mutableListOf<String>()

    override suspend fun connect(url: String): RelayConnection {
        connectAttempts += url
        val relay = byUrl[url]?.takeIf { it.online } ?: throw IOException("cannot reach $url")
        return relay.open()
    }
}
