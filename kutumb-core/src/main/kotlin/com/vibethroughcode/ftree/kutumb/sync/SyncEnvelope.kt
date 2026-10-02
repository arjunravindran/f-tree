package com.vibethroughcode.ftree.kutumb.sync

/** The kinds of fact a sync message can carry. Wire names are stable; never reuse or rename one. */
enum class SyncType(val wire: String) {
    FACT("fact"),
    ANSWER("answer"),
    RESOLUTION("resolution"),
    ROTATION("rotation"),
    TREE_EDIT("tree-edit"),
    ;

    companion object {
        fun fromWire(wire: String): SyncType? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What travels inside a rumor: `{"v":1,"type":"fact","payload":{...}}`. The payload is an opaque
 * JSON object here; the game and trust layers own its fields. Versioned so a newer app's messages
 * are skipped, not misread, by an older one.
 */
data class SyncEnvelope(val type: SyncType, val payload: Map<String, Any?>) {
    fun toJson(): String = Json.write(linkedMapOf("v" to VERSION.toLong(), "type" to type.wire, "payload" to payload))

    /** The unsigned rumor that carries this envelope, authored by [senderPubkey]. */
    fun toRumor(senderPubkey: String, createdAt: Long): UnsignedEvent =
        UnsignedEvent(senderPubkey, createdAt, RUMOR_KIND, emptyList(), toJson())

    companion object {
        const val VERSION = 1

        /**
         * Kind used for sync rumors. Rumors are never published bare, so this only labels what is
         * inside the wrap; 30078 is NIP-78's "arbitrary app data" kind. It is a choice, not a spec requirement.
         */
        const val RUMOR_KIND = 30078

        /** Null for malformed JSON, a version other than [VERSION], an unknown type, or a non-object payload. */
        fun parse(json: String): SyncEnvelope? {
            val map = Json.parseOrNull(json) as? Map<*, *> ?: return null
            if (map["v"] != VERSION.toLong()) return null
            val type = (map["type"] as? String)?.let(SyncType::fromWire) ?: return null
            val payload = map["payload"] as? Map<*, *> ?: return null
            @Suppress("UNCHECKED_CAST")
            return SyncEnvelope(type, payload as Map<String, Any?>)
        }

        fun fromRumor(rumor: UnsignedEvent): SyncEnvelope? =
            if (rumor.kind == RUMOR_KIND) parse(rumor.content) else null
    }
}
