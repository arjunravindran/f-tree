package com.vibethroughcode.ftree.kutumb.sync.relay

import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * One open websocket to one relay. The real implementation arrives with the network step; the
 * sync engine only ever sees this.
 *
 * [incoming] is collected once, by the engine's reader: it emits each text frame as it arrives and
 * completes when the socket closes or fails. Frames that arrive before collection starts must be
 * buffered, not dropped.
 */
interface RelayConnection {
    val url: String

    /** Text frames from the relay, in order. Completes when the connection ends. */
    val incoming: Flow<String>

    /** False if the frame could not be handed to the socket (closed or failed); never throws for that. */
    suspend fun send(frame: String): Boolean

    fun close()
}

/** Opens connections. Throws [IOException] when the relay cannot be reached. */
fun interface RelayConnector {
    suspend fun connect(url: String): RelayConnection
}
