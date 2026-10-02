package com.vibethroughcode.ftree.kutumb.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors copied from RFC 8439 (https://www.rfc-editor.org/rfc/rfc8439.txt), sections 2.3.2, 2.4.2 and appendix A.1. */
class ChaCha20Test {
    private val counting = hexBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val zeroKey = ByteArray(32)
    private val zeroNonce = ByteArray(12)

    @Test
    fun `rfc 2_3_2 block function`() {
        val block = ChaCha20.keystreamBlock(counting, hexBytes("000000090000004a00000000"), 1)
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            block.hex(),
        )
    }

    @Test
    fun `rfc 2_4_2 encryption`() {
        val plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val ciphertext = ChaCha20.xor(counting, hexBytes("000000000000004a00000000"), 1, plaintext.toByteArray())
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d",
            ciphertext.hex(),
        )
    }

    @Test
    fun `rfc 2_4_2 decryption is the same operation`() {
        val ct = hexBytes("6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b")
        val pt = ChaCha20.xor(counting, hexBytes("000000000000004a00000000"), 1, ct)
        assertEquals("Ladies and Gentlemen of the clas", String(pt))
    }

    @Test
    fun `rfc A_1 test vector 1`() = keystream(zeroKey, zeroNonce, 0,
        "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
            "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586")

    @Test
    fun `rfc A_1 test vector 2`() = keystream(zeroKey, zeroNonce, 1,
        "9f07e7be5551387a98ba977c732d080dcb0f29a048e3656912c6533e32ee7aed" +
            "29b721769ce64e43d57133b074d839d531ed1f28510afb45ace10a1f4b794d6f")

    @Test
    fun `rfc A_1 test vector 3`() = keystream(zeroKey.copyOf().also { it[31] = 1 }, zeroNonce, 1,
        "3aeb5224ecf849929b9d828db1ced4dd832025e8018b8160b82284f3c949aa5a" +
            "8eca00bbb4a73bdad192b5c42f73f2fd4e273644c8b36125a64addeb006c13a0")

    @Test
    fun `rfc A_1 test vector 4`() = keystream(zeroKey.copyOf().also { it[1] = 0xff.toByte() }, zeroNonce, 2,
        "72d54dfbf12ec44b362692df94137f328fea8da73990265ec1bbbea1ae9af0ca" +
            "13b25aa26cb4a648cb9b9d1be65b2c0924a66c54d545ec1b7374f4872e99f096")

    @Test
    fun `rfc A_1 test vector 5`() = keystream(zeroKey, zeroNonce.copyOf().also { it[11] = 2 }, 0,
        "c2c64d378cd536374ae204b9ef933fcd1a8b2288b3dfa49672ab765b54ee27c7" +
            "8a970e0e955c14f3a88e741b97c286f75f8fc299e8148362fa198a39531bed6d")

    @Test
    fun `multi block xor crosses the counter boundary`() {
        // Encrypting 128 zero bytes from counter 0 must equal blocks 0 and 1 back to back (A.1 vectors 1 and 2).
        val out = ChaCha20.xor(zeroKey, zeroNonce, 0, ByteArray(128))
        assertEquals(ChaCha20.keystreamBlock(zeroKey, zeroNonce, 0).hex() + ChaCha20.keystreamBlock(zeroKey, zeroNonce, 1).hex(), out.hex())
    }

    private fun keystream(key: ByteArray, nonce: ByteArray, counter: Int, expected: String) {
        assertEquals(expected, ChaCha20.xor(key, nonce, counter, ByteArray(64)).hex())
    }
}
