package com.vibethroughcode.ftree.kutumb.sync.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal RFC 6455 websocket client: text frames only, which is all a Nostr relay speaks.
 * Written by hand rather than pulling in an HTTP library; this is the only code in the Kutumb
 * layer that opens a socket to the internet, so it is kept small enough to read in one sitting.
 *
 * `wss://` uses the platform trust store and hostname verification (`SSLSocketFactory.getDefault`
 * plus an explicit endpoint identification check); `ws://` is accepted only for loopback or when
 * [allowPlaintext] is set, so a typo cannot silently send traffic in the clear.
 */
class WebSocketRelay private constructor(
    override val url: String,
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val maxFrameBytes: Int,
) : RelayConnection {

    private val frames = Channel<String>(Channel.UNLIMITED)
    private val writeLock = Any()
    @Volatile private var closed = false

    override val incoming: Flow<String> = frames.receiveAsFlow()

    override suspend fun send(frame: String): Boolean = withContext(Dispatchers.IO) {
        if (closed) return@withContext false
        try {
            writeFrame(OP_TEXT, frame.toByteArray(Charsets.UTF_8))
            true
        } catch (_: IOException) {
            shutdown()
            false
        }
    }

    override fun close() {
        if (closed) return
        runCatching { writeFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
        shutdown()
    }

    private fun shutdown() {
        closed = true
        runCatching { socket.close() }
        frames.close()
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(payload.size ushr 8)
                header.write(payload.size)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write((payload.size.toLong() ushr shift).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        synchronized(writeLock) {
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    /** Reads until the socket ends, answering pings and reassembling fragmented messages. */
    private fun readLoop() {
        val message = ByteArrayOutputStream()
        var messageOpcode = -1
        try {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) break
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                val b1 = input.readUnsignedByte()
                // A server must not mask; a masked frame means this is not a websocket server.
                if (b1 and 0x80 != 0) throw IOException("Masked frame from server")
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                if (length < 0 || length > maxFrameBytes) throw IOException("Frame too large: $length")
                val payload = ByteArray(length.toInt())
                input.readFully(payload)
                when (opcode) {
                    OP_PING -> synchronized(writeLock) { runCatching { writePong(payload) } }
                    OP_PONG -> Unit
                    OP_CLOSE -> return
                    OP_TEXT, OP_CONTINUATION -> {
                        if (opcode == OP_TEXT) {
                            message.reset()
                            messageOpcode = OP_TEXT
                        } else if (messageOpcode != OP_TEXT) {
                            throw IOException("Continuation without a start")
                        }
                        message.write(payload)
                        if (message.size() > maxFrameBytes) throw IOException("Message too large")
                        if (fin) {
                            frames.trySend(String(message.toByteArray(), Charsets.UTF_8))
                            message.reset()
                            messageOpcode = -1
                        }
                    }
                    else -> throw IOException("Unsupported opcode $opcode")
                }
            }
        } catch (_: IOException) {
        } finally {
            shutdown()
        }
    }

    private fun writePong(payload: ByteArray) {
        // Control frames are at most 125 bytes; a longer ping is a protocol error we simply drop.
        if (payload.size > 125) return
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(0x80 or OP_PONG)
        output.write(0x80 or payload.size)
        output.write(mask)
        output.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
        output.flush()
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val random = SecureRandom()

        const val DEFAULT_MAX_FRAME_BYTES = 1 shl 20
        const val CONNECT_TIMEOUT_MS = 10_000

        /** A [RelayConnector] that opens real sockets. */
        fun connector(allowPlaintext: Boolean = false): RelayConnector =
            RelayConnector { url -> connect(url, allowPlaintext) }

        suspend fun connect(
            url: String,
            allowPlaintext: Boolean = false,
            maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
        ): WebSocketRelay = withContext(Dispatchers.IO) {
            val uri = try { URI(url) } catch (e: Exception) { throw IOException("Bad relay url", e) }
            val host = uri.host ?: throw IOException("Relay url has no host")
            val secure = when (uri.scheme) {
                "wss" -> true
                "ws" -> false
                else -> throw IOException("Not a websocket url")
            }
            if (!secure && !allowPlaintext && host != "localhost" && host != "127.0.0.1") {
                throw IOException("Plaintext relay refused")
            }
            val port = if (uri.port >= 0) uri.port else if (secure) 443 else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

            var socket: Socket? = null
            try {
                val plain = Socket()
                socket = plain
                plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                if (secure) {
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true)
                    socket = tls
                    val params = (tls as javax.net.ssl.SSLSocket).sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    tls.sslParameters = params
                    tls.startHandshake()
                }
                socket.soTimeout = CONNECT_TIMEOUT_MS
                val out = socket.getOutputStream()
                val inp = DataInputStream(socket.getInputStream().buffered())

                val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
                val hostHeader = if (uri.port >= 0) "$host:$port" else host
                out.write(
                    ("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                out.flush()

                val head = readHead(inp)
                val lines = head.split("\r\n")
                if (!lines[0].contains(" 101")) throw IOException("Relay refused the upgrade: ${lines[0].take(80)}")
                val accept = lines.drop(1)
                    .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
                )
                if (accept != expected) throw IOException("Bad Sec-WebSocket-Accept")

                socket.soTimeout = 0
                val relay = WebSocketRelay(url, socket, inp, out, maxFrameBytes)
                Thread({ relay.readLoop() }, "relay-reader").apply { isDaemon = true }.start()
                relay
            } catch (e: Exception) {
                runCatching { socket?.close() }
                throw if (e is IOException) e else IOException(e.message, e)
            }
        }

        private fun readHead(input: InputStream): String {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c < 0) throw EOFException("Relay closed during the handshake")
                sb.append(c.toChar())
                if (sb.length > 8192) throw IOException("Handshake response too long")
            }
            return sb.toString().trimEnd()
        }
    }
}
