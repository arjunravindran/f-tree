package com.vibethroughcode.ftree.kutumb.recovery

import com.vibethroughcode.ftree.kutumb.trust.Ed25519Scheme
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private val scheme = Ed25519Scheme

    /** A device that trusts Ravi (R) and Priya (P), plus their private keys. */
    private class World {
        val r = Ed25519Scheme.generateKeyPair()
        val p = Ed25519Scheme.generateKeyPair()
        val store = TrustStore(listOf(contact("ravi", r), contact("priya", p)))
    }

    private companion object {
        fun contact(id: String, key: KeyPair, method: PairingMethod = PairingMethod.DIRECT, voucher: String? = null) =
            TrustedContact(id, key.publicKey, emptyList(), 1L, method, voucher)
    }

    private fun attest(voucher: KeyPair, person: String, old: KeyPair, new: KeyPair, ts: Long = 10L) =
        IdentityRotationEvent.attest(scheme, voucher, person, old.publicKey, new.publicKey, ts)

    private fun rejected(outcome: RotationOutcome) = (outcome as RotationOutcome.Rejected).reason

    /* ------------------------------------------------------------------ one rotation */

    @Test
    fun `one trusted relative's vouch is enough, with no further confirmation`() {
        val w = World()
        val newP = scheme.generateKeyPair()
        val outcome = RotationRules.apply(w.store, attest(w.r, "priya", w.p, newP), scheme)

        val updated = (outcome as RotationOutcome.Accepted).store
        assertEquals(newP.publicKey, updated["priya"]!!.pubKeyCurrent)
        assertEquals(listOf(w.p.publicKey), updated["priya"]!!.pubKeyHistory)
        assertEquals("others untouched", w.r.publicKey, updated["ravi"]!!.pubKeyCurrent)
    }

    @Test
    fun `the old key is revoked network-wide once the rotation lands`() {
        val w = World()
        val newP = scheme.generateKeyPair()
        val updated = (RotationRules.apply(w.store, attest(w.r, "priya", w.p, newP), scheme) as RotationOutcome.Accepted).store
        val msg = "a fact answer".toByteArray()

        assertTrue(w.store.acceptsSignature("priya", msg, scheme.sign(w.p.privateKey, msg), scheme))
        assertFalse(updated.acceptsSignature("priya", msg, scheme.sign(w.p.privateKey, msg), scheme))
        assertTrue(updated.acceptsSignature("priya", msg, scheme.sign(newP.privateKey, msg), scheme))
        assertTrue(updated.isRevoked("priya", w.p.publicKey))
        assertNull("a revoked key finds nobody", updated.byCurrentKey(w.p.publicKey))
    }

    @Test
    fun `a direct pairing stays direct across a rotation, so messaging survives`() {
        val w = World()
        val updated = (RotationRules.apply(w.store, attest(w.r, "priya", w.p, scheme.generateKeyPair()), scheme) as RotationOutcome.Accepted).store
        assertTrue(updated["priya"]!!.messagingEnabled)
    }

    @Test
    fun `a tampered or unsigned attestation is rejected`() {
        val w = World()
        val e = attest(w.r, "priya", w.p, scheme.generateKeyPair())
        val forged = e.copy(newPubKey = scheme.generateKeyPair().publicKey)
        assertEquals(RotationRejection.BAD_SIGNATURE, rejected(RotationRules.apply(w.store, forged, scheme)))
    }

    @Test
    fun `a voucher this device does not trust counts for nothing`() {
        val w = World()
        val stranger = scheme.generateKeyPair()
        val e = attest(stranger, "priya", w.p, scheme.generateKeyPair())
        assertEquals(RotationRejection.VOUCHER_NOT_TRUSTED, rejected(RotationRules.apply(w.store, e, scheme)))
    }

    @Test
    fun `a revoked key cannot vouch`() {
        val w = World()
        val newR = scheme.generateKeyPair()
        val afterR = (RotationRules.apply(w.store, attest(w.p, "ravi", w.r, newR), scheme) as RotationOutcome.Accepted).store
        val e = attest(w.r, "priya", w.p, scheme.generateKeyPair()) // signed with Ravi's old, revoked key
        assertEquals(RotationRejection.VOUCHER_NOT_TRUSTED, rejected(RotationRules.apply(afterR, e, scheme)))
    }

    @Test
    fun `nobody can vouch for their own rotation`() {
        val w = World()
        val e = attest(w.p, "priya", w.p, scheme.generateKeyPair())
        assertEquals(RotationRejection.SELF_VOUCH, rejected(RotationRules.apply(w.store, e, scheme)))
    }

    @Test
    fun `a person this device has never trusted is not invented from a rotation`() {
        val w = World()
        val e = attest(w.r, "meera", scheme.generateKeyPair(), scheme.generateKeyPair())
        assertEquals(RotationRejection.UNKNOWN_PERSON, rejected(RotationRules.apply(w.store, e, scheme)))
    }

    @Test
    fun `an attestation naming the wrong old key is stale`() {
        val w = World()
        val e = attest(w.r, "priya", scheme.generateKeyPair(), scheme.generateKeyPair())
        assertEquals(RotationRejection.STALE_OLD_KEY, rejected(RotationRules.apply(w.store, e, scheme)))
    }

    @Test
    fun `a new key that already belongs to someone is refused`() {
        val w = World()
        assertEquals(RotationRejection.NEW_KEY_IN_USE, rejected(RotationRules.apply(w.store, attest(w.r, "priya", w.p, w.r), scheme)))
    }

    @Test
    fun `redelivery of an applied rotation changes nothing`() {
        val w = World()
        val e = attest(w.r, "priya", w.p, scheme.generateKeyPair())
        val once = (RotationRules.apply(w.store, e, scheme) as RotationOutcome.Accepted).store
        assertEquals(RotationOutcome.AlreadyApplied, RotationRules.apply(once, e, scheme))
    }

    @Test
    fun `a second rotation from the same old key loses to the first`() {
        val w = World()
        val first = attest(w.r, "priya", w.p, scheme.generateKeyPair(), ts = 10)
        val second = attest(w.r, "priya", w.p, scheme.generateKeyPair(), ts = 11)
        val once = (RotationRules.apply(w.store, first, scheme) as RotationOutcome.Accepted).store
        assertEquals(RotationRejection.STALE_OLD_KEY, rejected(RotationRules.apply(once, second, scheme)))
    }

    /* ------------------------------------------------------------------ propagation */

    @Test
    fun `a chain of rotations resolves even when the relays deliver it backwards`() {
        val w = World()
        val p2 = scheme.generateKeyPair()
        val p3 = scheme.generateKeyPair()
        val first = attest(w.r, "priya", w.p, p2, ts = 10)
        val second = attest(w.r, "priya", p2, p3, ts = 20)

        val result = RotationPropagation.apply(w.store, listOf(second, first), scheme)

        assertEquals(p3.publicKey, result.store["priya"]!!.pubKeyCurrent)
        assertEquals(listOf(w.p.publicKey, p2.publicKey), result.store["priya"]!!.pubKeyHistory)
        assertEquals(listOf(first, second), result.applied)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `a vouch from a relative who rotates later still lands if it is older`() {
        val w = World()
        val newP = scheme.generateKeyPair()
        val newR = scheme.generateKeyPair()
        val priya = attest(w.r, "priya", w.p, newP, ts = 10)       // Ravi vouches, then...
        val ravi = attest(newP, "ravi", w.r, newR, ts = 20)        // ...Priya (new key) vouches for Ravi's phone change

        // Delivered newest first: Ravi's change cannot apply until Priya's new key is trusted.
        val result = RotationPropagation.apply(w.store, listOf(ravi, priya), scheme)

        assertEquals(newP.publicKey, result.store["priya"]!!.pubKeyCurrent)
        assertEquals(newR.publicKey, result.store["ravi"]!!.pubKeyCurrent)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `a batch reports duplicates and rejections separately and applies the rest`() {
        val w = World()
        val good = attest(w.r, "priya", w.p, scheme.generateKeyPair(), ts = 10)
        val bad = attest(scheme.generateKeyPair(), "ravi", w.r, scheme.generateKeyPair(), ts = 11)

        val result = RotationPropagation.apply(w.store, listOf(good, good, bad), scheme)

        assertEquals(listOf(good), result.applied)
        assertEquals(listOf(bad to RotationRejection.VOUCHER_NOT_TRUSTED), result.rejected)
        assertEquals(w.r.publicKey, result.store["ravi"]!!.pubKeyCurrent)

        val replay = RotationPropagation.apply(result.store, listOf(good), scheme)
        assertEquals(listOf(good), replay.duplicates)
        assertTrue(replay.applied.isEmpty())
    }

    /* ------------------------------------------------------------------ store */

    @Test
    fun `the store refuses two contacts that share a key or a person`() {
        val k = scheme.generateKeyPair()
        try { TrustStore(listOf(contact("a", k), contact("b", k))); throw AssertionError("shared key accepted") } catch (_: IllegalArgumentException) {}
        try { TrustStore(listOf(contact("a", k), contact("a", scheme.generateKeyPair()))); throw AssertionError("dup person accepted") } catch (_: IllegalArgumentException) {}
    }
}
