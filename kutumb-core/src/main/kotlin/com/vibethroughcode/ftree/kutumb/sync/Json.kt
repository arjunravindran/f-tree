package com.vibethroughcode.ftree.kutumb.sync

/**
 * A minimal JSON reader/writer, because core takes no dependencies and Nostr needs byte-exact output.
 *
 * Values are plain Kotlin: `null`, [Boolean], [Long] (integers), [Double] (anything with a fraction
 * or exponent), [String], [List] and [Map] (insertion-ordered). [write] emits no whitespace and
 * escapes strings the way NIP-01 prescribes for the event id: only `\n \" \\ \r \t \b \f` are
 * escaped, every other character (control characters included) is written as-is.
 */
internal object Json {
    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWs()
        val value = reader.value(0)
        reader.skipWs()
        if (!reader.atEnd()) throw ParseException("trailing characters at ${reader.pos}")
        return value
    }

    /** Null instead of a throw for malformed input. */
    fun parseOrNull(text: String): Any? = try { parse(text) } catch (_: ParseException) { null }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            is Int -> out.append(value.toString())
            is Long -> out.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "JSON cannot hold $value" }
                out.append(value.toString())
            }
            is String -> writeString(value, out)
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, item -> if (i > 0) out.append(','); write(item, out) }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k as? String ?: throw IllegalArgumentException("JSON object key is not a string"), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            else -> throw IllegalArgumentException("cannot serialise ${value::class.simpleName}")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '\n' -> out.append("\\n")
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private const val MAX_DEPTH = 64

    private class Reader(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("nested too deeply")
            if (atEnd()) throw ParseException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw ParseException("unexpected '$c' at $pos")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("bad literal at $pos")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("expected key at $pos")
                val key = str()
                skipWs()
                expect(':')
                skipWs()
                out[key] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw ParseException("expected , or } at $pos")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(value(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseException("expected , or ] at $pos")
                }
            }
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw ParseException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("short \\u escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: throw ParseException("bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var fractional = false
            if (pos < s.length && s[pos] == '.') {
                fractional = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                fractional = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            return (if (fractional) text.toDoubleOrNull() else text.toLongOrNull())
                ?: throw ParseException("bad number '$text'")
        }

        fun peek(): Char = if (atEnd()) throw ParseException("unexpected end") else s[pos]

        fun expect(c: Char) {
            if (peek() != c) throw ParseException("expected '$c' at $pos")
            pos++
        }
    }
}
