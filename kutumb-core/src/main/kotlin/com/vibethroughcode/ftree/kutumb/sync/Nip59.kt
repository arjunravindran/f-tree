package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme

/**
 * Everything non-deterministic a gift wrap needs, injected so the logic stays pure and testable.
 * [randomBytes] must be cryptographically secure in production; [newEphemeralKey] must return a
 * fresh secp256k1 key pair on every call (each wrap uses its own throwaway key).
 */
class WrapEnvironment(
    val randomBytes: (Int) -> ByteArray,
    val nowSeconds: () -> Long,
    val newEphemeralKey: () -> KeyPair,
)

/** Why a gift wrap was refused. */
enum class UnwrapFailure {
    NOT_A_GIFT_WRAP,
    BAD_WRAP_SIGNATURE,
    WRAP_DECRYPT_FAILED,
    MALFORMED_SEAL,
    NOT_A_SEAL,
    BAD_SEAL_SIGNATURE,
    SEAL_HAS_TAGS,
    SEAL_DECRYPT_FAILED,
    MALFORMED_RUMOR,

    /** The seal's author is not the rumor's claimed author: someone is passing off another person's words. */
    SENDER_MISMATCH,
}

sealed interface UnwrapResult {
    /** [senderPubkey] is the verified author: the key that signed the seal and wrote the rumor. */
    data class Opened(val rumor: UnsignedEvent, val senderPubkey: String, val seal: NostrEvent) : UnwrapResult
    data class Refused(val reason: UnwrapFailure) : UnwrapResult
}

/**
 * NIP-59: rumor (unsigned) inside a seal (kind 13, signed by the real author, encrypted to the
 * recipient) inside a gift wrap (kind 1059, signed by a throwaway key, `p`-tagged to the recipient).
 * Relays see only the wrap: a random key, a recipient, and a blurred timestamp.
 */
object Nip59 {
    const val KIND_SEAL = 13
    const val KIND_GIFT_WRAP = 1059

    /** Timestamps on seals and wraps are pushed back by up to this long so arrival time is not leaked. */
    const val MAX_TIMESTAMP_SKEW_SECONDS = 2L * 24 * 60 * 60

    /** Seal: the rumor's JSON, NIP-44 encrypted from [sender] to [recipientPubkey], signed by [sender]. Tags are always empty. */
    fun seal(
        rumor: UnsignedEvent,
        sender: KeyPair,
        recipientPubkey: String,
        scheme: SignatureScheme,
        ecdh: Ecdh,
        env: WrapEnvironment,
    ): NostrEvent {
        require(rumor.pubkey == sender.publicKey) { "rumor author must be the sealing key" }
        val key = Nip44.conversationKey(sender.privateKey, recipientPubkey, ecdh)
        val content = Nip44.encrypt(rumor.toJson(), key, env.randomBytes(32))
        return UnsignedEvent(sender.publicKey, randomisedTime(env), KIND_SEAL, emptyList(), content)
            .sign(sender.privateKey, scheme)
    }

    /** Gift wrap: the seal's JSON, encrypted from a fresh ephemeral key to [recipientPubkey]. */
    fun giftWrap(
        seal: NostrEvent,
        recipientPubkey: String,
        scheme: SignatureScheme,
        ecdh: Ecdh,
        env: WrapEnvironment,
    ): NostrEvent {
        val ephemeral = env.newEphemeralKey()
        val key = Nip44.conversationKey(ephemeral.privateKey, recipientPubkey, ecdh)
        val content = Nip44.encrypt(seal.toJson(), key, env.randomBytes(32))
        return UnsignedEvent(
            ephemeral.publicKey, randomisedTime(env), KIND_GIFT_WRAP, listOf(listOf("p", recipientPubkey)), content,
        ).sign(ephemeral.privateKey, scheme)
    }

    /** Rumor -> seal -> wrap, for one recipient. */
    fun wrap(
        rumor: UnsignedEvent,
        sender: KeyPair,
        recipientPubkey: String,
        scheme: SignatureScheme,
        ecdh: Ecdh,
        env: WrapEnvironment,
    ): NostrEvent = giftWrap(seal(rumor, sender, recipientPubkey, scheme, ecdh, env), recipientPubkey, scheme, ecdh, env)

    /**
     * Opens [wrap] with [recipientPrivateKey], checking every layer: both signatures and ids, the
     * seal being tag-free, and the seal's pubkey equalling the rumor's. Never throws.
     */
    fun unwrap(wrap: NostrEvent, recipientPrivateKey: String, scheme: SignatureScheme, ecdh: Ecdh): UnwrapResult {
        fun refused(reason: UnwrapFailure) = UnwrapResult.Refused(reason)
        if (wrap.kind != KIND_GIFT_WRAP) return refused(UnwrapFailure.NOT_A_GIFT_WRAP)
        if (!wrap.verify(scheme)) return refused(UnwrapFailure.BAD_WRAP_SIGNATURE)

        val sealJson = try {
            Nip44.decrypt(wrap.content, Nip44.conversationKey(recipientPrivateKey, wrap.pubkey, ecdh))
        } catch (_: Nip44Exception) {
            return refused(UnwrapFailure.WRAP_DECRYPT_FAILED)
        }
        val seal = NostrEvent.fromJson(sealJson) ?: return refused(UnwrapFailure.MALFORMED_SEAL)
        if (seal.kind != KIND_SEAL) return refused(UnwrapFailure.NOT_A_SEAL)
        if (!seal.verify(scheme)) return refused(UnwrapFailure.BAD_SEAL_SIGNATURE)
        if (seal.tags.isNotEmpty()) return refused(UnwrapFailure.SEAL_HAS_TAGS)

        val rumorJson = try {
            Nip44.decrypt(seal.content, Nip44.conversationKey(recipientPrivateKey, seal.pubkey, ecdh))
        } catch (_: Nip44Exception) {
            return refused(UnwrapFailure.SEAL_DECRYPT_FAILED)
        }
        val rumor = UnsignedEvent.fromJson(rumorJson) ?: return refused(UnwrapFailure.MALFORMED_RUMOR)
        if (rumor.pubkey != seal.pubkey) return refused(UnwrapFailure.SENDER_MISMATCH)
        return UnwrapResult.Opened(rumor, seal.pubkey, seal)
    }

    /** now minus a uniform-ish random amount in [0, 2 days). */
    internal fun randomisedTime(env: WrapEnvironment): Long {
        val b = env.randomBytes(4)
        require(b.size == 4) { "randomBytes returned the wrong length" }
        val n = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
            ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
        return env.nowSeconds() - n % MAX_TIMESTAMP_SKEW_SECONDS
    }
}
