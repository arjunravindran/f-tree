package com.vibethroughcode.ftree.kutumb.trust

/** Lower-case hex, the text form of every key and signature in the trust store. */
internal object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Null for anything that is not an even-length string of hex digits. */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun isHex(text: String, bytes: Int): Boolean =
        text.length == bytes * 2 && text.all { it in '0'..'9' || it in 'a'..'f' }
}
