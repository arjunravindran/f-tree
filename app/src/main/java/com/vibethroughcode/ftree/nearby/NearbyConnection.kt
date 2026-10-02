package com.vibethroughcode.ftree.nearby

import com.vibethroughcode.ftree.nearby.wire.Frame
import com.vibethroughcode.ftree.nearby.wire.FrameCrypto
import com.vibethroughcode.ftree.nearby.wire.FrameReader
import com.vibethroughcode.ftree.nearby.wire.NearbyFailure
import com.vibethroughcode.ftree.nearby.wire.NearbyProblem
import com.vibethroughcode.ftree.nearby.wire.NearbyProtocol
import com.vibethroughcode.ftree.nearby.wire.TranscriptHash
import com.vibethroughcode.ftree.nearby.wire.encodeFrame
import java.io.InputStream
import java.io.OutputStream

/** A frame, once it has been opened. */
data class OpenFrame(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is OpenFrame && type == other.type && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

/**
 * One connection, and the moment it stops being readable by anyone watching.
 *
 * The first four frames are plaintext — they are the key agreement, so they have to be — and every
 * frame after them is sealed. This class owns that switch, the two counters the sealing depends on,
 * and nothing else. It does not decide what to send, when to send it, or whether the device at the
 * other end should be trusted; those live in `NearbySession`, which owns no socket.
 *
 * The split exists so the part that can be *wrong* is not tangled with the part that can be *slow*.
 * `desktop/nearby/connection.js` is the same class in the other language.
 */
class NearbyConnection(
    private val input: InputStream,
    private val output: OutputStream,
    /**
     * This end's direction constant, and the peer's.
     *
     * They differ on the two sides of one connection, which is what keeps the two counters in
     * separate nonce spaces. Without that, both ends would encrypt their first frame under the same
     * key and the same nonce — and a repeated GCM nonce does not leak one message, it leaks the
     * authentication key and forges every frame after it.
     */
    private val sendDirection: Int,
    private val receiveDirection: Int,
) : AutoCloseable {

    private val reader = FrameReader()
    private var sendKey: ByteArray? = null
    private var receiveKey: ByteArray? = null
    private var sendSequence = 0L
    private var receiveSequence = 0L
    private var openedOne = false
    private val buffer = ByteArray(READ_BUFFER_BYTES)
    private val pending = ArrayDeque<Frame>()

    val secured: Boolean get() = sendKey != null

    /** Called once the key agreement is done. Everything after this is sealed. */
    fun secure(sendKey: ByteArray, receiveKey: ByteArray) {
        this.sendKey = sendKey
        this.receiveKey = receiveKey
    }

    /**
     * A frame out.
     *
     * Before [secure], plaintext and recorded in the transcript. After it, sealed under the next
     * sequence number — which is never transmitted. Both sides count, and a side that lost count
     * produces a tag the other cannot verify, which is the detection rather than the bug.
     */
    fun send(type: Int, payload: ByteArray = ByteArray(0), transcript: TranscriptHash? = null) {
        val key = sendKey
        if (key == null) {
            val frame = encodeFrame(type, payload)
            transcript?.add(frame)
            output.write(frame)
        } else {
            val sealed = FrameCrypto.seal(key, sendDirection, sendSequence, type, payload)
            sendSequence++
            output.write(encodeFrame(type, sealed))
        }
        output.flush()
    }

    /**
     * Blocks for the next frame, or returns `null` when the peer closed.
     *
     * A frame that will not open is fatal and throws: either the key is wrong — a stale code, or
     * somebody in between — or the bytes were altered, and there is nothing a retry could fix in
     * either case. The *first* sealed frame failing is reported separately, because a wrong pairing
     * and a connection that went bad halfway through need different sentences on screen.
     */
    fun read(transcript: TranscriptHash? = null): OpenFrame? {
        while (true) {
            pending.removeFirstOrNull()?.let { return open(it, transcript) }
            val count = try {
                input.read(buffer)
            } catch (_: Exception) {
                return null
            }
            if (count <= 0) return null
            pending.addAll(reader.feed(buffer, count))
        }
    }

    private fun open(frame: Frame, transcript: TranscriptHash?): OpenFrame {
        checkType(frame.type)
        val key = receiveKey
            ?: run {
                transcript?.add(encodeFrame(frame.type, frame.payload))
                return OpenFrame(frame.type, frame.payload)
            }
        val firstFrame = !openedOne
        val payload = FrameCrypto.open(
            key = key,
            direction = receiveDirection,
            sequence = receiveSequence,
            type = frame.type,
            sealed = frame.payload,
            firstFrame = firstFrame,
        )
        openedOne = true
        receiveSequence++
        return OpenFrame(frame.type, payload)
    }

    override fun close() {
        runCatching { output.flush() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 16 * 1024

        /**
         * Refuses a frame type this build does not know, before a session ever sees it.
         *
         * Fatal, never skipped, and the reserved range is refused by name rather than by falling
         * through. Skipping an unrecognised frame in an encrypted stream consumes a sequence number
         * whose meaning is unknown, which is exactly how a downgrade gets smuggled past a version
         * check. The reserved range belongs to a merge-sync conversation that does not exist yet; a
         * version 1 build must refuse it rather than treat it as noise.
         */
        val KNOWN_TYPES = setOf(
            NearbyProtocol.TYPE_HELLO,
            NearbyProtocol.TYPE_HELLO_ACK,
            NearbyProtocol.TYPE_KEY,
            NearbyProtocol.TYPE_KEY_ACK,
            NearbyProtocol.TYPE_OFFER,
            NearbyProtocol.TYPE_ACCEPT,
            NearbyProtocol.TYPE_DECLINE,
            NearbyProtocol.TYPE_DATA,
            NearbyProtocol.TYPE_END,
            NearbyProtocol.TYPE_RESULT,
            NearbyProtocol.TYPE_IDENTITY,
            NearbyProtocol.TYPE_ABORT,
        )

        fun checkType(type: Int) {
            if (type !in KNOWN_TYPES) throw NearbyFailure(NearbyProblem.UNEXPECTED_MESSAGE)
        }
    }
}
