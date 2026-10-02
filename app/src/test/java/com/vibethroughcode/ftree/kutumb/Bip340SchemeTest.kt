package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.recovery.RotationOutcome
import com.vibethroughcode.ftree.kutumb.recovery.RotationRules
import com.vibethroughcode.ftree.kutumb.recovery.TrustStore
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip340SchemeTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // BIP-340 test vectors 0 and 1, from the specification's test-vectors.csv.
    @Test
    fun matchesThePublishedVectors() {
        val sk0 = bytes("0000000000000000000000000000000000000000000000000000000000000003")
        assertArrayEquals(bytes("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9"), Bip340Scheme.publicKeyOf(sk0))
        val zeros = ByteArray(32)
        val sig0 = Bip340Scheme.signDigest(sk0, zeros, zeros)
        assertArrayEquals(
            bytes("E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"),
            sig0,
        )
        assertTrue(Bip340Scheme.verifyDigest(Bip340Scheme.publicKeyOf(sk0), zeros, sig0))

        val sk1 = bytes("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF")
        val msg1 = bytes("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89")
        val aux1 = bytes("0000000000000000000000000000000000000000000000000000000000000001")
        assertArrayEquals(bytes("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659"), Bip340Scheme.publicKeyOf(sk1))
        assertArrayEquals(
            bytes("6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A"),
            Bip340Scheme.signDigest(sk1, msg1, aux1),
        )
    }

    @Test
    fun aSignatureHoldsOnlyForItsKeyAndItsMessage() {
        val a = Bip340Scheme.generateKeyPair()
        val b = Bip340Scheme.generateKeyPair()
        val message = "Person P moved from X to Y".toByteArray()
        val signature = Bip340Scheme.sign(a.privateKey, message)

        assertEquals(64 * 2, signature.length)
        assertTrue(Bip340Scheme.verify(a.publicKey, message, signature))
        assertFalse(Bip340Scheme.verify(b.publicKey, message, signature))
        assertFalse(Bip340Scheme.verify(a.publicKey, message + 1, signature))
    }

    @Test
    fun malformedInputIsFalseNotAThrow() {
        val a = Bip340Scheme.generateKeyPair()
        val m = byteArrayOf(1)
        val s = Bip340Scheme.sign(a.privateKey, m)
        assertFalse(Bip340Scheme.verify("zz", m, s))
        assertFalse(Bip340Scheme.verify(a.publicKey, m, "00"))
        assertFalse(Bip340Scheme.verify(a.publicKey.uppercase(), m, s))
        assertFalse(Bip340Scheme.verify("00".repeat(32), m, s))
    }

    @Test
    fun theCoreRotationRulesAcceptASignedRotationUnderThisScheme() {
        val voucher = Bip340Scheme.generateKeyPair()
        val before = Bip340Scheme.generateKeyPair()
        val after = Bip340Scheme.generateKeyPair()
        val store = TrustStore(
            listOf(
                TrustedContact("r", voucher.publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT),
                TrustedContact("p", before.publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT),
            )
        )
        val event = IdentityRotationEvent.attest(Bip340Scheme, voucher, "p", before.publicKey, after.publicKey, timestamp = 10)

        val outcome = RotationRules.apply(store, event, Bip340Scheme) as RotationOutcome.Accepted
        assertEquals(after.publicKey, outcome.store["p"]!!.pubKeyCurrent)
        assertTrue(outcome.store.isRevoked("p", before.publicKey))
    }
}
