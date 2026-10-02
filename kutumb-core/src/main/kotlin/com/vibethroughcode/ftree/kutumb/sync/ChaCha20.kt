package com.vibethroughcode.ftree.kutumb.sync

/** Raw ChaCha20 (RFC 8439 section 2.4): no Poly1305, no AEAD framing. NIP-44 authenticates with HMAC instead. */
internal object ChaCha20 {
    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    /** XORs [data] with the keystream for [key] (32 bytes) and [nonce] (12 bytes) starting at block [counter]. Encrypt and decrypt are the same call. */
    fun xor(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val out = ByteArray(data.size)
        val state = IntArray(16)
        val block = ByteArray(64)
        var blockCounter = counter
        var offset = 0
        while (offset < data.size) {
            block(key, nonce, blockCounter, state, block)
            val n = minOf(64, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor block[i].toInt()).toByte()
            offset += n
            blockCounter++
        }
        return out
    }

    /** One 64-byte keystream block. */
    fun keystreamBlock(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        require(key.size == 32 && nonce.size == 12) { "bad key or nonce size" }
        val out = ByteArray(64)
        block(key, nonce, counter, IntArray(16), out)
        return out
    }

    private fun block(key: ByteArray, nonce: ByteArray, counter: Int, state: IntArray, out: ByteArray) {
        for (i in 0 until 4) state[i] = SIGMA[i]
        for (i in 0 until 8) state[4 + i] = le32(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = le32(nonce, i * 4)
        val x = state.copyOf()
        repeat(10) {
            quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val v = x[i] + state[i]
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte()
            out[i * 4 + 3] = (v ushr 24).toByte()
        }
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
