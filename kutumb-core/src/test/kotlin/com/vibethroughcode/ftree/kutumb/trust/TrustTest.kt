package com.vibethroughcode.ftree.kutumb.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrustTest {
    private val scheme = Ed25519Scheme
    private val msg = "hello".toByteArray()

    private fun contact(
        key: String = scheme.generateKeyPair().publicKey,
        method: PairingMethod = PairingMethod.DIRECT,
        voucher: String? = null,
        history: List<String> = emptyList(),
    ) = TrustedContact("p1", key, history, pairedAt = 1L, pairingMethod = method, vouchedByPersonId = voucher)

    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
    }

    /* ------------------------------------------------------------------ keypairs */

    @Test
    fun `a generated keypair is 32-byte hex and unique each time`() {
        val a = scheme.generateKeyPair()
        val b = scheme.generateKeyPair()
        assertTrue(Hex.isHex(a.publicKey, 32))
        assertTrue(Hex.isHex(a.privateKey, 32))
        assertNotEquals(a.publicKey, b.publicKey)
    }

    @Test
    fun `a keypair never prints its private key`() {
        val key = scheme.generateKeyPair()
        assertFalse(key.toString().contains(key.privateKey))
    }

    @Test
    fun `a signature verifies under its own key and nothing else`() {
        val a = scheme.generateKeyPair()
        val b = scheme.generateKeyPair()
        val sig = scheme.sign(a.privateKey, msg)
        assertTrue(scheme.verify(a.publicKey, msg, sig))
        assertFalse(scheme.verify(b.publicKey, msg, sig))
        assertFalse(scheme.verify(a.publicKey, "hellp".toByteArray(), sig))
    }

    @Test
    fun `malformed keys and signatures fail verification rather than throw`() {
        val a = scheme.generateKeyPair()
        val sig = scheme.sign(a.privateKey, msg)
        assertFalse(scheme.verify("zz", msg, sig))
        assertFalse(scheme.verify(a.publicKey, msg, "00"))
        assertFalse(scheme.verify(a.publicKey, msg, ""))
        assertFalse(scheme.verify(a.publicKey.uppercase(), msg, sig))
        assertFalse(scheme.verify(a.publicKey, msg, sig.dropLast(2) + "00"))
    }

    @Test
    fun `hex round trips and refuses garbage`() {
        assertEquals("00ff10", Hex.encode(Hex.decode("00ff10")!!))
        assertEquals(null, Hex.decode("abc"))
        assertEquals(null, Hex.decode("xy"))
        assertEquals(null, Hex.decode(""))
    }

    /* ------------------------------------------------------------------ TrustedContact */

    @Test
    fun `a contact is valid with a direct pairing and no voucher`() {
        val c = contact()
        assertTrue(c.messagingEnabled)
    }

    @Test
    fun `a vouched contact must name its voucher and a direct one must not`() {
        rejects { contact(method = PairingMethod.VOUCHED) }
        rejects { contact(method = PairingMethod.DIRECT, voucher = "r") }
        assertFalse(contact(method = PairingMethod.VOUCHED, voucher = "r").messagingEnabled)
    }

    @Test
    fun `nobody vouches for themselves`() {
        rejects { contact(method = PairingMethod.VOUCHED, voucher = "p1") }
    }

    @Test
    fun `keys must be well formed and the current key cannot be revoked`() {
        rejects { contact(key = "nothex") }
        val k = scheme.generateKeyPair().publicKey
        rejects { contact(key = k, history = listOf(k)) }
        rejects { contact(history = listOf("bad")) }
        val old = scheme.generateKeyPair().publicKey
        rejects { contact(history = listOf(old, old)) }
    }

    @Test
    fun `rotating a key moves the old one to history and keeps the pairing method`() {
        val old = scheme.generateKeyPair().publicKey
        val new = scheme.generateKeyPair().publicKey
        val rotated = contact(key = old).withRotatedKey(new)
        assertEquals(new, rotated.pubKeyCurrent)
        assertEquals(listOf(old), rotated.pubKeyHistory)
        assertTrue(rotated.isCurrentKey(new))
        assertTrue(rotated.isRevokedKey(old))
        assertFalse(rotated.isCurrentKey(old))
        assertTrue("messaging survives a rotation", rotated.messagingEnabled)
    }

    /* ------------------------------------------------------------------ IdentityRotationEvent */

    private fun event(voucher: KeyPair = scheme.generateKeyPair(), ts: Long = 100L): Triple<IdentityRotationEvent, String, String> {
        val old = scheme.generateKeyPair().publicKey
        val new = scheme.generateKeyPair().publicKey
        return Triple(IdentityRotationEvent.attest(scheme, voucher, "p1", old, new, ts), old, new)
    }

    @Test
    fun `an attested rotation verifies`() {
        val (e, _, _) = event()
        assertTrue(e.hasValidSignature(scheme))
    }

    @Test
    fun `changing any signed field breaks the signature`() {
        val (e, old, new) = event()
        val other = scheme.generateKeyPair().publicKey
        assertFalse(e.copy(personId = "p2").hasValidSignature(scheme))
        assertFalse(e.copy(oldPubKey = other).hasValidSignature(scheme))
        assertFalse(e.copy(newPubKey = other).hasValidSignature(scheme))
        assertFalse(e.copy(timestamp = e.timestamp + 1).hasValidSignature(scheme))
        assertFalse(e.copy(vouchedByPubKey = other).hasValidSignature(scheme))
        assertFalse("swapping old and new", e.copy(oldPubKey = new, newPubKey = old).hasValidSignature(scheme))
    }

    @Test
    fun `a signature by someone other than the stated voucher is invalid`() {
        val voucher = scheme.generateKeyPair()
        val impostor = scheme.generateKeyPair()
        val (e, _, _) = event(voucher)
        val forged = e.copy(signature = scheme.sign(impostor.privateKey, "x".toByteArray()))
        assertFalse(forged.hasValidSignature(scheme))
    }

    @Test
    fun `event shape is validated on construction`() {
        val (e, old, new) = event()
        rejects { e.copy(oldPubKey = e.newPubKey) }
        rejects { e.copy(personId = " ") }
        rejects { e.copy(newPubKey = "short") }
        rejects { e.copy(timestamp = -1) }
        rejects { IdentityRotationEvent.signingPayload("p\n1", old, new, e.vouchedByPubKey, 1) }
    }

    @Test
    fun `payloads differ whenever any field differs`() {
        val (e, old, new) = event()
        val base = IdentityRotationEvent.signingPayload("p1", old, new, e.vouchedByPubKey, 1)
        assertFalse(base.contentEquals(IdentityRotationEvent.signingPayload("p1", old, new, e.vouchedByPubKey, 2)))
        assertFalse(base.contentEquals(IdentityRotationEvent.signingPayload("p11", old, new, e.vouchedByPubKey, 1)))
    }
}
