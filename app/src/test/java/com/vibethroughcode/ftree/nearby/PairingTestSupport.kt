package com.vibethroughcode.ftree.nearby

import android.content.SharedPreferences
import com.vibethroughcode.ftree.nearby.wire.Beacon
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Shared fakes for tests that drive [NearbyRepository] in pairing mode without a network. */

internal const val AWAIT_MS = 15_000L

/** Waits for the first value of a flow that satisfies [predicate]; fails the test with [what] on timeout. */
internal fun <T> Flow<T>.await(what: String, predicate: (T) -> Boolean): T = runBlocking {
    try {
        withTimeout(AWAIT_MS) { first(predicate) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for: $what")
    }
}

internal class MemorySharedPreferences : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false
        private fun put(key: String, value: Any?) = apply { pending[key] = value }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            for ((k, v) in pending) if (v == null) values.remove(k) else values[k] = v
            return true
        }
        override fun apply() { commit() }
    }
}

internal fun testPreferences(enabled: Boolean = true) = NearbyPreferences(MemorySharedPreferences()).also {
    it.setEnabled(enabled)
}

internal fun testSelf(seed: Byte, name: String) = object : NearbySelf {
    override val deviceId = DeviceId(ByteArray(16) { seed })
    override val displayName = name
}

private class PipeChannel(
    override val input: InputStream,
    override val output: OutputStream,
    override val remoteAddress: String,
) : NearbyChannel {
    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
    }
}

/**
 * A transport with no sockets. Every instance registers under its address in a shared [Lan], so one
 * repository's [connect] hands the far end of a pipe straight to another's acceptor.
 */
internal class Lan {
    val transports = ConcurrentHashMap<String, LoopTransport>()
}

internal class LoopTransport(private val lan: Lan, private val address: String) : NearbyTransport {
    @Volatile var acceptor: NearbyTransport.Acceptor? = null
    val startReceivingCalls = AtomicInteger()
    val stopReceivingCalls = AtomicInteger()
    val connectCalls = AtomicInteger()

    /** When set, [connect] waits for it, so a test can hold a pairing in "Connecting". */
    @Volatile var connectGate: CountDownLatch? = null

    /** When set, [connect] throws instead of reaching anybody. */
    @Volatile var failConnect = false

    init { lan.transports[address] = this }

    override fun startReceiving(acceptor: NearbyTransport.Acceptor): Int {
        startReceivingCalls.incrementAndGet()
        this.acceptor = acceptor
        return PORT
    }

    override fun stopReceiving() {
        stopReceivingCalls.incrementAndGet()
        acceptor = null
    }

    override fun startDiscovery(listener: NearbyTransport.BeaconListener) = Unit
    override fun stopDiscovery() = Unit
    override fun startAnnouncing(beacon: () -> Beacon) = Unit
    override fun stopAnnouncing() = Unit
    override fun query() = Unit
    override fun localAddress(): String = address
    override fun close() = Unit

    override fun connect(address: String, port: Int, timeoutMillis: Int): NearbyChannel {
        connectCalls.incrementAndGet()
        connectGate?.await()
        if (failConnect) throw java.io.IOException("unreachable")
        val target = lan.transports[address]?.acceptor ?: throw java.io.IOException("nobody listening at $address")
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut, 1 shl 16)
        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut, 1 shl 16)
        target.onConnection(PipeChannel(serverIn, serverOut, this.address))
        return PipeChannel(clientIn, clientOut, address)
    }

    companion object { const val PORT = 4242 }
}
