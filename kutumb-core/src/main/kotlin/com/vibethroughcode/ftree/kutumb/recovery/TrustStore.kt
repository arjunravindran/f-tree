package com.vibethroughcode.ftree.kutumb.recovery

import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

/** The local trust store as an immutable value: every change returns a new one. */
class TrustStore private constructor(private val byPerson: Map<String, TrustedContact>) {

    constructor(contacts: Collection<TrustedContact> = emptyList()) : this(index(contacts))

    val contacts: Collection<TrustedContact> get() = byPerson.values

    operator fun get(personId: String): TrustedContact? = byPerson[personId]

    /** The contact whose *active* key this is. A revoked key finds nobody. */
    fun byCurrentKey(key: String): TrustedContact? = byPerson.values.firstOrNull { it.pubKeyCurrent == key }

    fun with(contact: TrustedContact): TrustStore = TrustStore(index(byPerson.values.filter { it.personId != contact.personId } + contact))

    /** Whether [key] was ever a key of [personId] and has since been superseded. */
    fun isRevoked(personId: String, key: String): Boolean = byPerson[personId]?.isRevokedKey(key) == true

    /**
     * Network-wide revocation, applied to anything received: a message is accepted only if it is
     * signed by the person's *current* key. Whatever a superseded key signs afterwards fails here.
     */
    fun acceptsSignature(personId: String, message: ByteArray, signature: String, scheme: SignatureScheme): Boolean {
        val contact = byPerson[personId] ?: return false
        return scheme.verify(contact.pubKeyCurrent, message, signature)
    }

    private companion object {
        fun index(contacts: Collection<TrustedContact>): Map<String, TrustedContact> {
            val map = LinkedHashMap<String, TrustedContact>()
            for (c in contacts) {
                require(map.put(c.personId, c) == null) { "two contacts for ${c.personId}" }
            }
            val owners = HashMap<String, String>()
            for (c in map.values) for (key in c.pubKeyHistory + c.pubKeyCurrent) {
                val other = owners.put(key, c.personId)
                require(other == null || other == c.personId) { "one key belongs to both $other and ${c.personId}" }
            }
            return map
        }
    }
}
