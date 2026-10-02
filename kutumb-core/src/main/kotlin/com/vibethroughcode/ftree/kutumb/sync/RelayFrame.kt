package com.vibethroughcode.ftree.kutumb.sync

/**
 * The NIP-01 websocket messages, as JSON arrays. Client to relay: [Event] (publish), [Req], [Close].
 * Relay to client: [EventDelivery], [Ok], [Eose], [Notice]. [parse] reads any of them (and
 * [Unknown] for a well-formed array it does not model, such as CLOSED or AUTH); it returns null,
 * never throws, for anything that is not a valid frame.
 */
sealed interface RelayFrame {
    fun toJson(): String

    /** Client publishes an event: `["EVENT", <event>]`. */
    data class Event(val event: NostrEvent) : RelayFrame {
        override fun toJson() = Json.write(listOf("EVENT", event.toJsonMap()))
    }

    /** Client subscribes: `["REQ", <sub id>, <filter>...]`. Filters are plain JSON objects, see [filter]. */
    data class Req(val subscriptionId: String, val filters: List<Map<String, Any?>>) : RelayFrame {
        init {
            require(subscriptionId.isNotEmpty() && subscriptionId.length <= 64) { "subscription id must be 1 to 64 characters" }
        }

        override fun toJson() = Json.write(listOf<Any?>("REQ", subscriptionId) + filters)
    }

    /** Client ends a subscription: `["CLOSE", <sub id>]`. */
    data class Close(val subscriptionId: String) : RelayFrame {
        override fun toJson() = Json.write(listOf("CLOSE", subscriptionId))
    }

    /** Relay sends a stored or live event: `["EVENT", <sub id>, <event>]`. */
    data class EventDelivery(val subscriptionId: String, val event: NostrEvent) : RelayFrame {
        override fun toJson() = Json.write(listOf("EVENT", subscriptionId, event.toJsonMap()))
    }

    /** Relay answers a publish: `["OK", <event id>, <accepted>, <message>]`. */
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayFrame {
        override fun toJson() = Json.write(listOf("OK", eventId, accepted, message))
    }

    /** Relay has sent everything stored for the subscription: `["EOSE", <sub id>]`. */
    data class Eose(val subscriptionId: String) : RelayFrame {
        override fun toJson() = Json.write(listOf("EOSE", subscriptionId))
    }

    /** Human-readable relay message: `["NOTICE", <message>]`. */
    data class Notice(val message: String) : RelayFrame {
        override fun toJson() = Json.write(listOf("NOTICE", message))
    }

    /** A well-formed frame of a type this client does not model. */
    data class Unknown(val type: String) : RelayFrame {
        override fun toJson() = Json.write(listOf(type))
    }

    companion object {
        /** Builds a REQ filter object; only the fields given appear. Tag filters are keyed by letter, e.g. `"p"` becomes `"#p"`. */
        fun filter(
            ids: List<String>? = null,
            authors: List<String>? = null,
            kinds: List<Int>? = null,
            since: Long? = null,
            until: Long? = null,
            limit: Int? = null,
            tags: Map<String, List<String>> = emptyMap(),
        ): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            ids?.let { out["ids"] = it }
            authors?.let { out["authors"] = it }
            kinds?.let { out["kinds"] = it.map(Int::toLong) }
            since?.let { out["since"] = it }
            until?.let { out["until"] = it }
            limit?.let { out["limit"] = it.toLong() }
            for ((letter, values) in tags) out["#$letter"] = values
            return out
        }

        fun parse(text: String): RelayFrame? {
            val array = Json.parseOrNull(text) as? List<*> ?: return null
            val type = array.firstOrNull() as? String ?: return null
            return when (type) {
                "EVENT" -> when (array.size) {
                    2 -> NostrEvent.fromMap(array[1])?.let(::Event)
                    3 -> {
                        val sub = array[1] as? String ?: return null
                        NostrEvent.fromMap(array[2])?.let { EventDelivery(sub, it) }
                    }
                    else -> null
                }
                "REQ" -> {
                    val sub = array.getOrNull(1) as? String ?: return null
                    if (sub.isEmpty() || sub.length > 64) return null
                    val filters = array.drop(2).map { f ->
                        (f as? Map<*, *>)?.entries?.associate { (k, v) -> (k as String) to v } ?: return null
                    }
                    Req(sub, filters)
                }
                "CLOSE" -> if (array.size == 2) (array[1] as? String)?.let(::Close) else null
                "OK" -> {
                    if (array.size != 4) return null
                    val id = array[1] as? String ?: return null
                    val accepted = array[2] as? Boolean ?: return null
                    val message = array[3] as? String ?: return null
                    Ok(id, accepted, message)
                }
                "EOSE" -> if (array.size == 2) (array[1] as? String)?.let(::Eose) else null
                "NOTICE" -> if (array.size == 2) (array[1] as? String)?.let(::Notice) else null
                else -> Unknown(type)
            }
        }
    }
}
