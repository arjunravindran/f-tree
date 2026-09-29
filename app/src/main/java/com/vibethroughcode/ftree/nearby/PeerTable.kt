package com.vibethroughcode.ftree.nearby

import com.vibethroughcode.ftree.nearby.wire.Beacon
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import com.vibethroughcode.ftree.nearby.wire.NearbyProtocol

/**
 * Who is nearby, as the list on screen sees it.
 *
 * Pure and clock-free — the caller passes the time in — which is what makes expiry testable without
 * anybody waiting seven seconds, and it is why this class has no `android.*` import despite living
 * outside `wire/`. It sits here rather than in `wire/` because nothing on the network ever encodes
 * a peer table; it is this side's bookkeeping about messages, not a message.
 *
 * Keyed on [DeviceId], which is what makes announcing to multicast *and* to broadcast free: a
 * device that answers on both is one row and one comparison, not two rows a person has to tell
 * apart.
 */
class PeerTable(private val expiryMillis: Long = NearbyProtocol.PEER_EXPIRY_MS.toLong()) {

    private val peers = LinkedHashMap<String, NearbyPeer>()

    /**
     * Records an announcement.
     *
     * [fromAddress] is a parameter rather than a field of [beacon], and that is the whole point: a
     * beacon carries no address at all, so a forged one cannot point a sender at a third machine,
     * and an advertisement cannot go stale by naming an interface the device has since left.
     */
    fun seen(beacon: Beacon, fromAddress: String, atMillis: Long): NearbyPeer {
        val peer = NearbyPeer(
            deviceId = beacon.deviceId,
            address = fromAddress,
            port = beacon.tcpPort,
            displayName = beacon.displayName,
            platform = beacon.platform,
            flags = beacon.flags,
            keyFingerprint = beacon.keyFingerprint,
            lastSeenAt = atMillis,
            maxVersion = beacon.maxVersion,
            minVersion = beacon.minVersion,
        )
        peers[peer.key] = peer
        return peer
    }

    /** A device that said goodbye. Removed at once rather than waited out. */
    fun gone(deviceId: DeviceId) {
        peers.remove(deviceId.hex())
    }

    /** Three missed announcements and some slack. Returns what was dropped. */
    fun sweep(atMillis: Long): List<NearbyPeer> {
        val expired = peers.values.filter { atMillis - it.lastSeenAt >= expiryMillis }
        expired.forEach { peers.remove(it.key) }
        return expired
    }

    fun clear() {
        peers.clear()
    }

    operator fun get(key: String): NearbyPeer? = peers[key]

    /**
     * In the order the devices first appeared, and never re-sorted.
     *
     * Sorting by name looks tidier and is worse in the hand: a device that arrives and sorts above
     * the row somebody is reaching for moves that row out from under their finger, and out from
     * under a screen reader's cursor mid-sentence. A [LinkedHashMap] keeps insertion order when a
     * key is written again, so a device that announces twice a second, is renamed, or changes
     * address keeps its place; only one that expires or says goodbye and comes back goes to the end.
     */
    fun list(): List<NearbyPeer> = peers.values.toList()
}
