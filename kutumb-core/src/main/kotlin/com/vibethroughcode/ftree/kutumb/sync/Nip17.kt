package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme

/** NIP-17 private direct messages: a kind-14 rumor, delivered by NIP-59 gift wrap. */
object Nip17 {
    const val KIND_CHAT_MESSAGE = 14

    /**
     * The unsigned kind-14 rumor: one `p` tag per recipient, the text as content. Its `created_at`
     * is the real send time, since the blurring happens on the seal and wrap, not here.
     */
    fun rumor(
        senderPubkey: String,
        recipientPubkeys: List<String>,
        text: String,
        createdAt: Long,
        extraTags: List<List<String>> = emptyList(),
    ): UnsignedEvent {
        require(recipientPubkeys.isNotEmpty()) { "a message needs a recipient" }
        return UnsignedEvent(
            senderPubkey, createdAt, KIND_CHAT_MESSAGE,
            recipientPubkeys.map { listOf("p", it) } + extraTags, text,
        )
    }

    /**
     * One gift wrap per participant: every recipient, plus the sender's own copy so their other
     * devices (and a restored phone) see the message too. Order follows [recipientPubkeys], self last.
     */
    fun giftWraps(
        rumor: UnsignedEvent,
        sender: KeyPair,
        recipientPubkeys: List<String>,
        scheme: SignatureScheme,
        ecdh: Ecdh,
        env: WrapEnvironment,
    ): List<NostrEvent> {
        require(rumor.kind == KIND_CHAT_MESSAGE) { "not a chat message rumor" }
        val targets = (recipientPubkeys + sender.publicKey).distinct()
        return targets.map { Nip59.wrap(rumor, sender, it, scheme, ecdh, env) }
    }
}
