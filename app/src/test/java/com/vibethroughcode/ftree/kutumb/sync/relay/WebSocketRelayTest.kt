package com.vibethroughcode.ftree.kutumb.sync.relay

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test

/** Drives [WebSocketRelay] against a throwaway RFC 6455 server on loopback. */
class WebSocketRelayTest {
    private val server = ServerSocket(0)
    private val url get() = "ws://127.0.0.1:${server.localPort}/"
    private val received = LinkedBlockingQueue<String>()
    private var script: (Socket, OutputStream) -> Unit = { _, _ -> }

    @After fun stop() { server.close() }

    private fun serve(acceptKey: ((String) -> String)? = null, status: String = "101 Switching Protocols", body: (Socket, OutputStream) -> Unit) {
        script = body
        thread(isDaemon = true) {
            val s = server.accept()
            val inp = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(inp.read().toChar())
            val key = head.lines().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
            val accept = (acceptKey ?: ::acceptFor)(key)
            val out = s.getOutputStream()
            out.write("HTTP/1.1 $status\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            out.flush()
            if (status.startsWith("101")) script(s, out)
        }
    }

    private fun acceptFor(key: String) = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
    )

    private fun OutputStream.frame(opcode: Int, payload: ByteArray, fin: Boolean = true) {
        write((if (fin) 0x80 else 0) or opcode)
        when {
            payload.size < 126 -> write(payload.size)
            payload.size <= 0xFFFF -> { write(126); write(payload.size ushr 8); write(payload.size) }
            else -> { write(127); for (s in 56 downTo 0 step 8) write((payload.size.toLong() ushr s).toInt()) }
        }
        write(payload)
        flush()
    }

    /** Reads one client frame, unmasking it, and returns (opcode, payload). */
    private fun DataInputStream.clientFrame(): Pair<Int, ByteArray> {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        assertTrue("client frames must be masked", b1 and 0x80 != 0)
        var len = b1 and 0x7F
        if (len == 126) len = readUnsignedShort() else if (len == 127) len = readLong().toInt()
        val mask = ByteArray(4).also(::readFully)
        val data = ByteArray(len).also(::readFully)
        return (b0 and 0x0F) to ByteArray(len) { (data[it].toInt() xor mask[it and 3].toInt()).toByte() }
    }

    @Test fun `text frames arrive in order, including ones sent before anyone collects`() = runBlocking {
        serve { _, out ->
            out.frame(1, "one".toByteArray())
            out.frame(1, "two".toByteArray())
            out.frame(8, ByteArray(0))
        }
        val relay = WebSocketRelay.connect(url)
        assertEquals(listOf("one", "two"), withTimeout(5000) { relay.incoming.toList() })
    }

    @Test fun `what we send is masked and arrives intact at every length class`() = runBlocking {
        serve { s, _ ->
            val inp = DataInputStream(s.getInputStream())
            repeat(3) { received.put(String(inp.clientFrame().second, Charsets.UTF_8)) }
            Thread.sleep(500)
        }
        val relay = WebSocketRelay.connect(url)
        val sizes = listOf(5, 300, 70_000)
        sizes.forEach { assertTrue(relay.send("é".repeat(it / 2 + 1).take(it))) }
        sizes.forEach { n ->
            val got = received.poll(5, TimeUnit.SECONDS)!!
            assertEquals("é".repeat(n / 2 + 1).take(n), got)
        }
        relay.close()
    }

    @Test fun `a ping is answered with a pong carrying the same payload`() = runBlocking {
        serve { s, out ->
            out.frame(9, "hi".toByteArray())
            val (op, payload) = DataInputStream(s.getInputStream()).clientFrame()
            received.put("$op:${String(payload)}")
            out.frame(8, ByteArray(0))
        }
        val relay = WebSocketRelay.connect(url)
        withTimeout(5000) { relay.incoming.toList() }
        assertEquals("10:hi", received.poll(5, TimeUnit.SECONDS))
    }

    @Test fun `a fragmented message is reassembled`() = runBlocking {
        serve { _, out ->
            out.frame(1, "hel".toByteArray(), fin = false)
            out.frame(0, "lo ".toByteArray(), fin = false)
            out.frame(0, "world".toByteArray())
            out.frame(8, ByteArray(0))
        }
        val relay = WebSocketRelay.connect(url)
        assertEquals(listOf("hello world"), withTimeout(5000) { relay.incoming.toList() })
    }

    @Test fun `a long server frame with a 64-bit length is read`() = runBlocking {
        val big = "x".repeat(70_000)
        serve { _, out -> out.frame(1, big.toByteArray()); out.frame(8, ByteArray(0)) }
        val relay = WebSocketRelay.connect(url)
        assertEquals(listOf(big), withTimeout(5000) { relay.incoming.toList() })
    }

    @Test fun `a frame over the limit ends the connection instead of allocating`() = runBlocking {
        serve { _, out -> out.frame(1, ByteArray(5000) { 'a'.code.toByte() }); Thread.sleep(500) }
        val relay = WebSocketRelay.connect(url, maxFrameBytes = 1000)
        assertEquals(emptyList<String>(), withTimeout(5000) { relay.incoming.toList() })
        assertFalse(relay.send("late"))
    }

    @Test fun `send reports false after the server closes`() = runBlocking {
        serve { _, out -> out.frame(8, ByteArray(0)) }
        val relay = WebSocketRelay.connect(url)
        withTimeout(5000) { relay.incoming.toList() }
        assertFalse(relay.send("x"))
    }

    @Test fun `a wrong accept key is refused`() = runBlocking {
        serve(acceptKey = { "AAAA" }) { _, _ -> }
        expectIo { WebSocketRelay.connect(url) }
    }

    @Test fun `a server that does not upgrade is refused`() = runBlocking {
        serve(status = "200 OK") { _, _ -> }
        expectIo { WebSocketRelay.connect(url) }
    }

    @Test fun `plaintext to a non-loopback host is refused unless allowed`() = runBlocking {
        expectIo { WebSocketRelay.connect("ws://relay.example.com/") }
        expectIo { WebSocketRelay.connect("http://relay.example.com/") }
    }

    @Test fun `an unreachable relay throws IOException`() = runBlocking {
        val port = server.localPort
        server.close()
        expectIo { WebSocketRelay.connect("ws://127.0.0.1:$port/") }
    }

    private suspend fun expectIo(block: suspend () -> Unit) {
        try { block(); fail("expected IOException") } catch (_: IOException) {}
    }
}
