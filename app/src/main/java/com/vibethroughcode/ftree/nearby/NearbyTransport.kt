package com.vibethroughcode.ftree.nearby

import com.vibethroughcode.ftree.nearby.wire.Beacon
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import com.vibethroughcode.ftree.nearby.wire.NearbyPlatform
import java.io.InputStream
import java.io.OutputStream

/**
 * A device somebody could send to, as the list on screen knows it.
 *
 * The [address] is taken from the datagram a beacon arrived in and is never read out of the beacon
 * itself — that is what stops a forged announcement pointing a sender at a third machine, and it is
 * also why an advertisement cannot go stale by naming an interface the device has since left.
 */
data class NearbyPeer(
    val deviceId: DeviceId,
    val address: String,
    val port: Int,
    val displayName: String,
    val platform: NearbyPlatform,
    val flags: Int,
    val keyFingerprint: ByteArray,
    val lastSeenAt: Long,
) {
    /** Stable across a rename or a change of address, which is what the trusted list is keyed on. */
    val key: String get() = deviceId.hex()

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is NearbyPeer &&
                deviceId == other.deviceId &&
                address == other.address &&
                port == other.port &&
                displayName == other.displayName &&
                platform == other.platform &&
                flags == other.flags &&
                keyFingerprint.contentEquals(other.keyFingerprint) &&
                lastSeenAt == other.lastSeenAt
            )

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + port
        result = 31 * result + displayName.hashCode()
        result = 31 * result + platform.hashCode()
        result = 31 * result + flags
        result = 31 * result + keyFingerprint.contentHashCode()
        return 31 * result + lastSeenAt.hashCode()
    }
}

/** A connection, with no opinion about who opened it or what will travel over it. */
interface NearbyChannel : AutoCloseable {
    val input: InputStream
    val output: OutputStream

    /** The address this connection is actually to, which a caller checks against what it expected. */
    val remoteAddress: String
}

/**
 * Everything nearby sharing needs from a network, behind one interface.
 *
 * This is the seam. [LanTransport] is the real thing and holds every socket; an instrumented test
 * substitutes a fake and drives the whole receive flow — offer, code, accept, review — on an
 * emulator that cannot do multicast at all. Without the seam, the only way to exercise a screen
 * would be two physical devices and a router, which means in practice it would be exercised once,
 * by hand, by whoever wrote it.
 *
 * Nothing here starts on construction. A transport that bound a port when it was built would make
 * the privacy claim depend on nobody ever constructing one.
 */
interface NearbyTransport {

    /** Where incoming connections arrive once [startReceiving] has been called. */
    fun interface Acceptor {
        fun onConnection(channel: NearbyChannel)
    }

    /** Beacons as they arrive, already decoded and already filtered of this device's own. */
    fun interface BeaconListener {
        fun onBeacon(beacon: Beacon, fromAddress: String)
    }

    /**
     * Binds a TCP port and starts answering.
     *
     * @return the port actually bound, which is ephemeral and goes in the beacon. A fixed port
     * would be one more thing to collide with and one more thing to firewall by number.
     */
    fun startReceiving(acceptor: Acceptor): Int

    fun stopReceiving()

    /** Joins the multicast group and starts listening. Announcing is separate and deliberate. */
    fun startDiscovery(listener: BeaconListener)

    fun stopDiscovery()

    /**
     * Begins announcing. Only a device that has said it wants to be found calls this.
     *
     * The beacon is supplied per tick rather than passed once, so that renaming this device reaches
     * the room within a beat. A payload frozen when announcing started meant the new name did not
     * leave the phone until the screen was closed and opened again (#192).
     */
    fun startAnnouncing(beacon: () -> Beacon)

    /** Stops announcing and says goodbye, rather than simply going quiet. */
    fun stopAnnouncing()

    /** Asks who is there, saying nothing about who is asking. */
    fun query()

    /** Opens a connection to a peer. Blocking; callers are already off the main thread. */
    fun connect(address: String, port: Int, timeoutMillis: Int): NearbyChannel

    /**
     * This device's private IPv4 address on the local network, or null when it has none.
     *
     * Shown on the receive screen so it can be typed on the other device — the fallback for a
     * network that drops discovery, and the only way in for an emulator, which cannot hear a
     * beacon at all. Only a private address is ever returned, the same rule a typed or scanned
     * address is held to on the way in.
     */
    fun localAddress(): String? = null

    fun close()
}
