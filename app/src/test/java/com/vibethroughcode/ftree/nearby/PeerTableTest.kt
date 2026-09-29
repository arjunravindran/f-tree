package com.vibethroughcode.ftree.nearby

import com.vibethroughcode.ftree.nearby.wire.Beacon
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import com.vibethroughcode.ftree.nearby.wire.NearbyPlatform
import com.vibethroughcode.ftree.nearby.wire.NearbyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is nearby, and for how long.
 *
 * Pure and clock-free, so expiry is tested by passing a number rather than by waiting seven
 * seconds. `desktop/nearby/discovery.test.js` holds the same assertions against the same rules.
 */
class PeerTableTest {

    private val a = DeviceId(ByteArray(16) { 0xA1.toByte() })
    private val b = DeviceId(ByteArray(16) { 0xB2.toByte() })

    private fun beacon(
        deviceId: DeviceId,
        name: String = "Quiet Heron",
        port: Int = 4000,
    ) = Beacon.announce(
        platform = NearbyPlatform.ANDROID,
        flags = NearbyProtocol.FLAG_ACCEPTS_TREE,
        tcpPort = port,
        deviceId = deviceId,
        keyFingerprint = ByteArray(8) { 1 },
        displayName = name,
    )

    @Test
    fun `a device appears once, however many times it announces`() {
        val table = PeerTable()
        table.seen(beacon(a), "192.168.1.5", 1000)
        table.seen(beacon(a), "192.168.1.5", 3000)
        table.seen(beacon(a), "192.168.1.5", 5000)
        assertEquals(1, table.list().size)
    }

    @Test
    fun `announcing over two transports costs one entry`() {
        // The reason announcing to multicast *and* broadcast is free: the table is keyed on
        // deviceId, so a device that answers on both is one row rather than two a person has to
        // tell apart.
        val table = PeerTable()
        table.seen(beacon(a), "192.168.1.5", 1000)
        table.seen(beacon(a), "192.168.1.5", 1001)
        assertEquals(1, table.list().size)
    }

    @Test
    fun `a device is forgotten after three missed announcements, and not before`() {
        val table = PeerTable()
        table.seen(beacon(a), "192.168.1.5", 1000)

        assertEquals(emptyList<NearbyPeer>(), table.sweep(1000 + NearbyProtocol.PEER_EXPIRY_MS - 1))
        assertEquals(1, table.list().size)

        val dropped = table.sweep(1000 + NearbyProtocol.PEER_EXPIRY_MS)
        assertEquals(1, dropped.size)
        assertEquals(0, table.list().size)
    }

    @Test
    fun `a goodbye removes a device at once rather than waiting it out`() {
        // Three goodbyes go out because one can be lost, and a peer that misses them all waits out
        // the whole expiry looking at a device that is not there.
        val table = PeerTable()
        table.seen(beacon(a), "192.168.1.5", 1000)
        table.gone(a)
        assertEquals(0, table.list().size)

        // And a goodbye for a device that was never seen is not an error.
        table.gone(b)
        assertEquals(0, table.list().size)
    }

    @Test
    fun `a renamed device is the same device`() {
        val table = PeerTable()
        table.seen(beacon(a, name = "Quiet Heron"), "192.168.1.5", 1000)
        table.seen(beacon(a, name = "Amber Otter"), "192.168.1.5", 2000)
        assertEquals(1, table.list().size)
        assertEquals("Amber Otter", table.list().first().displayName)
    }

    @Test
    fun `a device that moved to another address is the same device`() {
        val table = PeerTable()
        table.seen(beacon(a), "192.168.1.5", 1000)
        table.seen(beacon(a), "192.168.1.9", 2000)
        assertEquals(1, table.list().size)
        assertEquals("192.168.1.9", table.list().first().address)
    }

    @Test
    fun `the address comes from the datagram, never from the beacon`() {
        // A beacon carries no address at all, which is what stops a forged one pointing a sender at
        // a third machine. Checked here from the table's side.
        val table = PeerTable()
        val peer = table.seen(beacon(a), "192.168.1.77", 1000)
        assertEquals("192.168.1.77", peer.address)
    }

    @Test
    fun `two devices with the same name keep a stable order`() {
        // Two unnamed devices are both "Quiet Heron" until somebody renames one, and a list whose
        // rows swap places between announcements is a list somebody taps the wrong row in.
        val table = PeerTable()
        table.seen(beacon(a, name = "Quiet Heron"), "192.168.1.5", 1000)
        table.seen(beacon(b, name = "Quiet Heron"), "192.168.1.6", 1000)

        val first = table.list().map { it.key }
        table.seen(beacon(b, name = "Quiet Heron"), "192.168.1.6", 2000)
        table.seen(beacon(a, name = "Quiet Heron"), "192.168.1.5", 2000)
        assertEquals(first, table.list().map { it.key })
    }

    @Test
    fun `the list keeps arrival order rather than re-sorting under a finger`() {
        // Sorted by name, "Amber Otter" arriving would push "Quiet Heron" down a row just as
        // somebody reached for it. Arrival order, and a rename or a new address keeps the place.
        val table = PeerTable()
        table.seen(beacon(a, name = "Quiet Heron"), "192.168.1.5", 1000)
        table.seen(beacon(b, name = "Amber Otter"), "192.168.1.6", 1100)
        table.seen(beacon(a, name = "Zinnia"), "192.168.1.9", 2000)
        assertEquals(listOf("Zinnia", "Amber Otter"), table.list().map { it.displayName })
    }

    @Test
    fun `a peer can be found by the key the trusted list uses`() {
        val table = PeerTable()
        table.seen(beacon(a), "192.168.1.5", 1000)
        assertEquals("192.168.1.5", table[a.hex()]?.address)
        assertNull(table[b.hex()])
    }

    @Test
    fun `a device on another protocol version is listed, and says so`() {
        // #191: this used to be dropped before it reached the table, so the other device simply
        // never appeared and the only symptom was "I can't see it".
        val table = PeerTable()
        val other = beacon(a).copy(maxVersion = 9, minVersion = 9)
        table.seen(other, "192.168.1.5", 1000)
        table.seen(beacon(b), "192.168.1.6", 1000)

        val listed = table.list().associateBy { it.key }
        assertEquals(2, listed.size)
        assertFalse(listed.getValue(a.hex()).speakable)
        assertTrue(listed.getValue(a.hex()).needsThisDeviceUpdated)
        assertTrue(listed.getValue(b.hex()).speakable)
    }

    @Test
    fun `a device on an older protocol version asks for the other one to be updated`() {
        val table = PeerTable()
        // A build that only ever spoke version 0: this one is the newer of the two.
        table.seen(beacon(a).copy(maxVersion = 0, minVersion = 0), "192.168.1.5", 1000)
        val peer = table.list().single()
        assertFalse(peer.speakable)
        assertFalse(peer.needsThisDeviceUpdated)
    }
}
