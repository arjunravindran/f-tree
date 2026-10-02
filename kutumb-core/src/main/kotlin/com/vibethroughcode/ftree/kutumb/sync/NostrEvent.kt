package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.trust.Hex
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import java.security.MessageDigest

/**
 * An event before it has a signature: a NIP-59 "rumor" is exactly this. [id] is the NIP-01 id,
 * the SHA-256 of the canonical array `[0,pubkey,created_at,kind,tags,content]`.
 */
data class UnsignedEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    init {
        require(Hex.isHex(pubkey, 32)) { "pubkey must be 32 bytes of lower-case hex" }
    }

    val id: String get() = Hex.encode(idBytes())

    fun idBytes(): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(canonicalJson().toByteArray(Charsets.UTF_8))

    /** The exact text that is hashed to make the id. */
    fun canonicalJson(): String = Json.write(listOf(0L, pubkey, createdAt, kind.toLong(), tags, content))

    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "pubkey" to pubkey, "created_at" to createdAt, "kind" to kind.toLong(), "tags" to tags, "content" to content,
    )

    fun toJson(): String = Json.write(toJsonMap())

    /** Signs through [scheme]. The signed message is the 32 id bytes, as BIP-340 and NIP-01 require. */
    fun sign(privateKey: String, scheme: SignatureScheme): NostrEvent =
        NostrEvent(id, pubkey, createdAt, kind, tags, content, scheme.sign(privateKey, idBytes()))

    companion object {
        /** Null for anything that is not an event object, or whose claimed id disagrees with its fields. */
        fun fromJson(text: String): UnsignedEvent? = fromMap(Json.parseOrNull(text))

        fun fromMap(value: Any?): UnsignedEvent? {
            val map = value as? Map<*, *> ?: return null
            val pubkey = map["pubkey"] as? String ?: return null
            if (!Hex.isHex(pubkey, 32)) return null
            val createdAt = map["created_at"] as? Long ?: return null
            val kind = (map["kind"] as? Long)?.takeIf { it in 0..65535 }?.toInt() ?: return null
            val content = map["content"] as? String ?: return null
            val tags = parseTags(map["tags"]) ?: return null
            val event = UnsignedEvent(pubkey, createdAt, kind, tags, content)
            val claimedId = map["id"]
            if (claimedId != null && claimedId != event.id) return null
            return event
        }
    }
}

internal fun parseTags(value: Any?): List<List<String>>? {
    val raw = value as? List<*> ?: return null
    val out = ArrayList<List<String>>(raw.size)
    for (tag in raw) {
        val items = tag as? List<*> ?: return null
        val strings = ArrayList<String>(items.size)
        for (item in items) strings.add(item as? String ?: return null)
        out.add(strings)
    }
    return out
}

/** A signed Nostr event (NIP-01). Construction does not verify; call [verify]. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun unsigned(): UnsignedEvent = UnsignedEvent(pubkey, createdAt, kind, tags, content)

    /** True only if [id] is the real hash of the fields and [sig] is a valid signature of it by [pubkey]. Never throws. */
    fun verify(scheme: SignatureScheme): Boolean {
        if (!Hex.isHex(pubkey, 32) || !Hex.isHex(id, 32) || !Hex.isHex(sig, 64)) return false
        val computed = unsigned()
        if (computed.id != id) return false
        return scheme.verify(pubkey, computed.idBytes(), sig)
    }

    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "pubkey" to pubkey, "created_at" to createdAt, "kind" to kind.toLong(),
        "tags" to tags, "content" to content, "sig" to sig,
    )

    fun toJson(): String = Json.write(toJsonMap())

    /** First value of the first tag named [name], if any. */
    fun tagValue(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    companion object {
        /** Null for malformed input. Shape only: use [verify] before trusting it. */
        fun fromJson(text: String): NostrEvent? = fromMap(Json.parseOrNull(text))

        fun fromMap(value: Any?): NostrEvent? {
            val map = value as? Map<*, *> ?: return null
            val id = map["id"] as? String ?: return null
            val sig = map["sig"] as? String ?: return null
            val pubkey = map["pubkey"] as? String ?: return null
            if (!Hex.isHex(pubkey, 32)) return null
            val createdAt = map["created_at"] as? Long ?: return null
            val kind = (map["kind"] as? Long)?.takeIf { it in 0..65535 }?.toInt() ?: return null
            val content = map["content"] as? String ?: return null
            val tags = parseTags(map["tags"]) ?: return null
            return NostrEvent(id, pubkey, createdAt, kind, tags, content, sig)
        }
    }
}
