package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.pairing.IdentityCards
import com.vibethroughcode.ftree.nearby.wire.NearbyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityCardsTest {
    private val aliceKeys = Bip340Scheme.generateKeyPair()
    private val bobKeys = Bip340Scheme.generateKeyPair()
    private val alice = IdentityCards(Bip340Scheme, aliceKeys)
    private val bob = IdentityCards(Bip340Scheme, bobKeys)
    private val sender = NearbyProtocol.ROLE_SENDER
    private val receiver = NearbyProtocol.ROLE_RECEIVER

    @Test
    fun aCardIsAKeyAndASignatureAndTheOtherSideReadsTheKeyBack() {
        val card = alice.card("482916", sender)
        assertEquals(96, card.size)
        assertEquals(aliceKeys.publicKey, bob.peerKey(card, "482916", sender))
    }

    @Test
    fun aCardOnlyHoldsForTheSessionAndRoleItWasMadeFor() {
        val card = alice.card("482916", sender)
        assertNull(bob.peerKey(card, "482917", sender))
        assertNull(bob.peerKey(card, "482916", receiver))
    }

    @Test
    fun aTamperedOrMisSizedCardIsRefused() {
        val card = alice.card("482916", sender)
        assertNull(bob.peerKey(card.copyOf().also { it[10] = (it[10] + 1).toByte() }, "482916", sender))
        assertNull(bob.peerKey(card.copyOf().also { it[70] = (it[70] + 1).toByte() }, "482916", sender))
        assertNull(bob.peerKey(card.copyOfRange(0, 95), "482916", sender))
        assertNull(bob.peerKey(ByteArray(0), "482916", sender))
    }

    @Test
    fun aKeyPresentedWithAnotherKeysSignatureIsRefused() {
        val forged = aliceKeys.publicKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
            bob.card("482916", sender).copyOfRange(32, 96)
        assertNull(bob.peerKey(forged, "482916", sender))
    }

    @Test
    fun pairingWithYourOwnKeyIsRefused() {
        assertNull(alice.peerKey(alice.card("482916", sender), "482916", sender))
    }
}
