package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.trust.Hex
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import java.math.BigInteger
import java.security.MessageDigest

/**
 * TEST-ONLY crypto. Core ships no secp256k1, so the tests bring a small BigInteger one (slow,
 * variable-time, never for production) to compute real ECDH shared secrets, and a stand-in
 * signature scheme. The real implementations are injected by the app.
 */
object TestSecp256k1 : Ecdh {
    private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private val GX = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    private val GY = BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)

    private class Point(val x: BigInteger, val y: BigInteger)

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        val lambda = if (a.x == b.x) {
            if ((a.y + b.y).mod(P).signum() == 0) return null
            (BigInteger.valueOf(3) * a.x * a.x) * (BigInteger.TWO * a.y).modInverse(P)
        } else {
            (b.y - a.y) * (b.x - a.x).mod(P).modInverse(P)
        }.mod(P)
        val x = (lambda * lambda - a.x - b.x).mod(P)
        return Point(x, (lambda * (a.x - x) - a.y).mod(P))
    }

    private fun multiply(point: Point, k: BigInteger): Point? {
        var result: Point? = null
        var addend: Point? = point
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    private fun scalar(hex: String): BigInteger {
        val d = BigInteger(1, requireNotNull(Hex.decode(hex)) { "not hex" })
        require(d.signum() > 0 && d < N) { "scalar out of range" }
        return d
    }

    /** x-only point with even y, or throws if x is not on the curve. */
    private fun liftX(hex: String): Point {
        val x = BigInteger(1, requireNotNull(Hex.decode(hex)) { "not hex" })
        require(x < P) { "x not below field size" }
        val ySq = (x.pow(3) + BigInteger.valueOf(7)).mod(P)
        val y = ySq.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        require(y.modPow(BigInteger.TWO, P) == ySq) { "x is not on the curve" }
        return Point(x, if (y.testBit(0)) P - y else y)
    }

    private fun bytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }

    override fun sharedX(privateKey: String, publicKey: String): ByteArray {
        val shared = multiply(liftX(publicKey), scalar(privateKey)) ?: error("point at infinity")
        return bytes32(shared.x)
    }

    fun publicKeyOf(privateKey: String): String =
        Hex.encode(bytes32(multiply(Point(GX, GY), scalar(privateKey))!!.x))

    /** A valid key pair from a counter-derived scalar, so tests are deterministic. */
    fun keyPair(seed: Int): KeyPair {
        val priv = Hex.encode(MessageDigest.getInstance("SHA-256").digest("test-key-$seed".toByteArray()))
        return KeyPair(publicKeyOf(priv), priv)
    }
}

/**
 * NOT BIP-340. A deterministic stand-in so event logic (ids, wrapping, tamper checks) can be tested
 * without a secp256k1 signing library: the "signature" is a hash of the signer's public key and the
 * message, which anyone could forge. It exercises the same sign/verify seam the real scheme fills.
 */
object TestScheme : SignatureScheme {
    private fun tag(pub: String, message: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val a = md.digest("a".toByteArray() + pub.toByteArray() + message)
        val b = md.digest("b".toByteArray() + pub.toByteArray() + message)
        return Hex.encode(a + b)
    }

    override fun generateKeyPair(): KeyPair = TestSecp256k1.keyPair(0)
    override fun sign(privateKey: String, message: ByteArray) = tag(TestSecp256k1.publicKeyOf(privateKey), message)
    override fun verify(publicKey: String, message: ByteArray, signature: String) = signature == tag(publicKey, message)
}

fun hexBytes(s: String): ByteArray = Hex.decode(s.replace(Regex("[\\s:]"), ""))!!
fun ByteArray.hex(): String = Hex.encode(this)
