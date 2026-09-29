package com.vibethroughcode.ftree.nearby

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.vibethroughcode.ftree.nearby.wire.Beacon
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import com.vibethroughcode.ftree.nearby.wire.NearbyProtocol
import com.vibethroughcode.ftree.nearby.wire.QrLink
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The real sockets.
 *
 * Everything in this file is I/O and threads; everything that decides what bytes *mean* is in
 * `nearby/wire/`, which has no `java.net` import at all. That split is why the protocol is tested
 * on the JVM where CI can reach it, and why this class can be replaced wholesale by a fake in an
 * instrumented test.
 *
 * One thing here has no equivalent on the desktop and is easy to leave out: **the multicast lock**.
 * Android's Wi-Fi stack drops multicast and broadcast packets before they reach an app unless one
 * is held, to save power. Without it discovery does not fail loudly — the socket binds, the joins
 * succeed, announcements go out, and nothing ever arrives. It is released the moment discovery
 * stops, because a lock left held is a battery complaint nobody will trace back here.
 */
class LanTransport(
    context: Context,
    private val ownDeviceId: DeviceId,
) : NearbyTransport {

    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var serverSocket: ServerSocket? = null
    private var beaconSocket: MulticastSocket? = null
    @Volatile private var lanForBeacons: Lan? = null
    private var announcement: (() -> Beacon)? = null

    private val accepting = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val announcing = AtomicBoolean(false)
    private var lastAnsweredQuery = 0L

    override fun startReceiving(acceptor: NearbyTransport.Acceptor): Int {
        stopReceiving()
        // Port 0: the system picks one, and it goes in the beacon. A fixed port is one more thing
        // to collide with and one more thing somebody has to firewall by number.
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        serverSocket = socket
        accepting.set(true)

        thread(name = "nearby-accept", isDaemon = true) {
            while (accepting.get()) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    // The socket was closed under us, which is how stopReceiving works.
                    break
                } catch (_: Exception) {
                    break
                }
                // Nagle off: this protocol writes small frames and then waits for an answer, which
                // is exactly the shape Nagle delays by up to 40 ms a turn for no benefit.
                runCatching { client.tcpNoDelay = true }
                acceptor.onConnection(SocketChannel(client))
            }
        }
        return socket.localPort
    }

    override fun stopReceiving() {
        accepting.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    override fun startDiscovery(listener: NearbyTransport.BeaconListener) {
        if (listening.get()) return

        // Acquired before the socket, because a datagram that arrives in between is one this device
        // will never see and a peer that will take another two seconds to appear.
        multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        val socket = MulticastSocket(NearbyProtocol.BEACON_PORT)
        socket.reuseAddress = true
        val lan = lan()
        lanForBeacons = lan
        runCatching {
            // Joined, and sent from, on the Wi-Fi itself. Left to the system, both happen on the
            // default interface, which with a VPN on is the VPN and hears nobody in the room.
            lan?.network?.bindSocket(socket)
            val group = InetAddress.getByName(NearbyProtocol.MULTICAST_GROUP)
            val onInterface = lan?.networkInterface
            if (onInterface != null) {
                socket.networkInterface = onInterface
                socket.joinGroup(InetSocketAddress(group, 0), onInterface)
            } else {
                socket.joinGroup(group)
            }
            // TTL 1 is what makes "the same Wi-Fi" literally true: the datagram does not survive a
            // router, so a device two hops away cannot see this one however the network is wired.
            socket.timeToLive = NearbyProtocol.MULTICAST_TTL
        }
        socket.soTimeout = SOCKET_POLL_MILLIS
        beaconSocket = socket
        listening.set(true)

        thread(name = "nearby-discover", isDaemon = true) {
            val buffer = ByteArray(NearbyProtocol.BEACON_MAX_SIZE)
            while (listening.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    // A timeout every second, or a closed socket. Both mean "look again".
                    continue
                }
                val beacon = Beacon.decode(packet.data, packet.length) ?: continue

                // Filtered on deviceId and never on source address: two copies on one machine must
                // still see each other, and multicast loopback behaviour is not portable enough to
                // depend on either way.
                if (beacon.deviceId == ownDeviceId) continue

                if (beacon.messageType == NearbyProtocol.BEACON_QUERY) {
                    answerQuery()
                    continue
                }
                listener.onBeacon(beacon, packet.address.hostAddress ?: continue)
            }
        }
    }

    override fun stopDiscovery() {
        listening.set(false)
        runCatching {
            val group = InetAddress.getByName(NearbyProtocol.MULTICAST_GROUP)
            val onInterface = lanForBeacons?.networkInterface
            if (onInterface != null) {
                beaconSocket?.leaveGroup(InetSocketAddress(group, 0), onInterface)
            } else {
                beaconSocket?.leaveGroup(group)
            }
        }
        lanForBeacons = null
        runCatching { beaconSocket?.close() }
        beaconSocket = null
        // Released here rather than in close(), because a lock held while nothing is listening is a
        // battery complaint nobody will ever trace back to this feature.
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    override fun startAnnouncing(beacon: () -> Beacon) {
        announcement = beacon
        if (announcing.getAndSet(true)) return

        thread(name = "nearby-announce", isDaemon = true) {
            // A burst rather than a first beat, so a device whose screen has just opened appears at
            // once and one lost datagram does not cost two seconds of an empty list.
            for (delay in NearbyProtocol.ANNOUNCE_BURST_MS) {
                if (!announcing.get()) return@thread
                Thread.sleep(delay.toLong())
                announce()
            }
            while (announcing.get()) {
                Thread.sleep(NearbyProtocol.ANNOUNCE_INTERVAL_MS.toLong())
                announce()
            }
        }
    }

    override fun stopAnnouncing() {
        if (!announcing.getAndSet(false)) return
        // Three goodbyes, because one can be lost and a peer that misses it waits out the whole
        // expiry staring at a device that is no longer there.
        announcement?.let { source ->
            val farewell = source().copy(messageType = NearbyProtocol.BEACON_GOODBYE).encode()
            thread(name = "nearby-goodbye", isDaemon = true) {
                repeat(NearbyProtocol.GOODBYE_COUNT) {
                    send(farewell)
                    Thread.sleep(NearbyProtocol.GOODBYE_GAP_MS.toLong())
                }
            }
        }
        announcement = null
    }

    override fun query() {
        send(Beacon.query().encode())
    }

    override fun connect(address: String, port: Int, timeoutMillis: Int): NearbyChannel {
        val socket = Socket()
        // Over the Wi-Fi, never through a VPN that would carry a LAN address somewhere else.
        runCatching { lan()?.network?.bindSocket(socket) }
        socket.connect(InetSocketAddress(address, port), timeoutMillis)
        runCatching { socket.tcpNoDelay = true }
        return SocketChannel(socket)
    }

    /** The local network this feature speaks on: its handle, interface, address and prefix. */
    private class Lan(
        val network: Network,
        val networkInterface: NetworkInterface?,
        val address: Inet4Address,
        val prefixLength: Int,
    )

    /**
     * The Wi-Fi (or wired) network, found by what it is rather than by being the active one.
     *
     * With a VPN switched on, the active network *is* the VPN: its address is not one anybody in the
     * room can reach, a limited broadcast leaves through it and reaches nobody, and a connection to
     * a LAN address may be carried off to the VPN's far end. So the network is chosen by transport,
     * and every socket this class opens is bound to it with [Network.bindSocket].
     *
     * Read from link properties — ACCESS_NETWORK_STATE, already declared — rather than from
     * `WifiManager.connectionInfo`, which is deprecated and on recent releases answers 0.0.0.0
     * without a location permission this feature deliberately never asks for.
     */
    @Suppress("DEPRECATION") // allNetworks: the callback API would need a registration held for one lookup.
    private fun lan(): Lan? = runCatching {
        val candidates = connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        for (network in candidates) {
            val properties = connectivity.getLinkProperties(network) ?: continue
            val link = properties.linkAddresses.firstOrNull { link ->
                val address = link.address
                address is Inet4Address && address.hostAddress?.let(QrLink::isPrivateAddress) == true
            } ?: continue
            return@runCatching Lan(
                network = network,
                networkInterface = properties.interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() },
                address = link.address as Inet4Address,
                prefixLength = link.prefixLength,
            )
        }
        null
    }.getOrNull()

    override fun localAddress(): String? = lan()?.address?.hostAddress

    override fun close() {
        stopAnnouncing()
        stopDiscovery()
        stopReceiving()
    }

    private fun announce() {
        announcement?.let { send(it().encode()) }
    }

    /**
     * One extra announcement, jittered, at most one every 500 ms.
     *
     * The jitter is so twenty devices in a room do not all answer in the same millisecond and lose
     * most of the answers to collisions. The rate limit is so a flood of queries cannot turn this
     * device into an amplifier.
     */
    private fun answerQuery() {
        val source = announcement ?: return
        val now = System.currentTimeMillis()
        if (now - lastAnsweredQuery < NearbyProtocol.QUERY_ANSWER_MIN_GAP_MS) return
        lastAnsweredQuery = now
        thread(name = "nearby-answer", isDaemon = true) {
            Thread.sleep((Math.random() * NearbyProtocol.QUERY_ANSWER_JITTER_MS).toLong())
            send(source().encode())
        }
    }

    /**
     * To the multicast group **and** to broadcast.
     *
     * Not redundant. IGMP snooping on consumer access points routinely drops groups the switch has
     * not learned, while broadcast survives; on other networks the reverse. The peer table is keyed
     * on deviceId, so a device that answers on both costs one comparison and nothing else.
     */
    private fun send(datagram: ByteArray) {
        val socket = beaconSocket ?: return
        // The subnet's own broadcast rather than 255.255.255.255 when the subnet is known: see
        // LanAddress.directedBroadcast for why the limited one can leave by the wrong door.
        val broadcast = lanForBeacons
            ?.let { InetAddress.getByAddress(LanAddress.directedBroadcast(it.address.address, it.prefixLength)) }
            ?: InetAddress.getByName(NearbyProtocol.BROADCAST_ADDRESS)
        for (target in listOf(InetAddress.getByName(NearbyProtocol.MULTICAST_GROUP), broadcast)) {
            runCatching {
                socket.send(DatagramPacket(datagram, datagram.size, target, NearbyProtocol.BEACON_PORT))
            }
            // A send that fails is a network that is not there. There is nowhere to report it and
            // nothing to do about it: the next announcement is two seconds away.
        }
    }

    private class SocketChannel(private val socket: Socket) : NearbyChannel {
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override val remoteAddress: String get() = socket.inetAddress?.hostAddress.orEmpty()
        override fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val MULTICAST_LOCK_TAG = "f-tree-nearby"

        /** How often the receive loop checks whether it has been asked to stop. */
        const val SOCKET_POLL_MILLIS = 1000
    }
}
