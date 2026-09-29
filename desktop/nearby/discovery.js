/*
 * Finding the other device, and being found.
 *
 * A small UDP beacon rather than mDNS, which is the obvious alternative and was not chosen. mDNS
 * means either a dependency or a partial reimplementation of a protocol with real subtleties, and
 * on a desktop it contends with whatever Avahi or Bonjour already owns port 5353. This is about
 * eighty lines a side, symmetric in both languages, and every byte of it is pinned by the same
 * golden table as the handshake.
 *
 * Two things in here are deliberate and look redundant:
 *
 *   Announcing to multicast **and** to broadcast. IGMP snooping on consumer access points routinely
 *   drops groups the switch has not learned, while broadcast survives; on other networks the
 *   reverse. The peer table is keyed on deviceId, so a device that answers on both costs one
 *   comparison and nothing else.
 *
 *   Filtering our own beacons on deviceId rather than on source address. Two copies of the app on
 *   one machine must still see each other, and multicast loopback behaviour is not portable enough
 *   to depend on either way.
 */

const dgram = require('node:dgram');
const os = require('node:os');
const { EventEmitter } = require('node:events');

const protocol = require('./protocol');
const beaconWire = require('./beacon');
const { lanInterfaces, beaconTargets } = require('./interfaces');

/**
 * The peer table.
 *
 * Keyed on deviceId, which is what makes announcing twice free, and expired on a clock the caller
 * supplies so the sweeping is testable without waiting seven seconds.
 */
class PeerTable extends EventEmitter {
  constructor(expiryMs = protocol.PEER_EXPIRY_MS) {
    super();
    this.expiryMs = expiryMs;
    this.peers = new Map();
  }

  /**
   * Records a beacon, from the address the datagram actually came from.
   *
   * The address is a parameter rather than a field of the beacon, and that is the whole point: a
   * forged beacon cannot name a third machine, and an advertisement cannot go stale by carrying an
   * interface the device has since left.
   */
  seen(beacon, address, atMillis) {
    const key = beaconWire.deviceIdHex(beacon.deviceId);
    const existing = this.peers.get(key);
    const peer = {
      deviceId: beacon.deviceId,
      key,
      address,
      port: beacon.tcpPort,
      displayName: beacon.displayName,
      platform: beacon.platform,
      flags: beacon.flags,
      keyFingerprint: beacon.keyFingerprint,
      maxVersion: beacon.maxVersion,
      minVersion: beacon.minVersion,
      // Whether this build and that one can hold a conversation at all. Kept on the peer rather
      // than filtered out, so a device on another version of f-tree can be shown and said to be
      // on another version, instead of simply never appearing (#191).
      speakable: beaconWire.speakable(beacon),
      lastSeen: atMillis,
    };
    this.peers.set(key, peer);

    if (!existing) this.emit('appeared', peer);
    else if (existing.displayName !== peer.displayName || existing.address !== peer.address
      || existing.speakable !== peer.speakable) {
      this.emit('changed', peer);
    }
    return peer;
  }

  /** A device that said goodbye. Removed at once rather than waited out. */
  gone(deviceId) {
    const key = beaconWire.deviceIdHex(deviceId);
    const peer = this.peers.get(key);
    if (!peer) return;
    this.peers.delete(key);
    this.emit('vanished', peer);
  }

  /** Three missed announcements and some slack. */
  sweep(atMillis) {
    for (const [key, peer] of [...this.peers]) {
      if (atMillis - peer.lastSeen >= this.expiryMs) {
        this.peers.delete(key);
        this.emit('vanished', peer);
      }
    }
  }

  list() {
    return [...this.peers.values()].sort((a, b) => a.displayName.localeCompare(b.displayName));
  }
}

/**
 * The socket half.
 *
 * A receiver advertises; a sender browses; a device doing both does both. Nothing is announced
 * until `advertise` is called, which happens when visibility is switched on and not before -- there
 * is no background service, no notification to justify, and no device quietly announcing itself
 * while nobody is looking at the screen that says it is.
 */
class Discovery extends EventEmitter {
  /**
   * `interfaces` returns the LAN adapters to use, and is asked again before every send -- so a
   * laptop that joins the Wi-Fi after the dialog opened starts announcing there within one beat,
   * rather than after somebody closes and reopens it. Injectable so a test can hand it a table.
   */
  constructor({
    identity,
    clock = () => Date.now(),
    interfaces = () => lanInterfaces(os.networkInterfaces()),
  } = {}) {
    super();
    this.identity = identity;
    this.clock = clock;
    this.interfaces = interfaces;
    this.socket = null;
    this.peers = new PeerTable();
    this.announcement = null;
    this.timers = [];
    this.lastAnswered = 0;
    /** Adapter addresses the group has been joined on. */
    this.joined = new Set();
    this.joinedDefault = false;
    /** Sends go one at a time; see `#send`. */
    this.sending = Promise.resolve();

    this.peers.on('appeared', (peer) => this.emit('appeared', peer));
    this.peers.on('changed', (peer) => this.emit('changed', peer));
    this.peers.on('vanished', (peer) => this.emit('vanished', peer));
  }

  /**
   * Binds and joins the group.
   *
   * `reuseAddr` so two copies on one machine can both listen, which is how this gets tested at all.
   * A failure to join the multicast group is not fatal: broadcast alone still works on most
   * networks, and a device that can be found one way out of two is better than a feature that
   * refuses to start.
   */
  start() {
    return new Promise((resolve, reject) => {
      this.socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      this.socket.once('error', reject);
      this.socket.on('message', (datagram, remote) => this.#onDatagram(datagram, remote));

      this.socket.bind(protocol.BEACON_PORT, () => {
        try {
          this.socket.setMulticastTTL(protocol.MULTICAST_TTL);
          this.socket.setMulticastLoopback(true);
        } catch (error) {
          this.emit('multicast-unavailable', error);
        }
        this.#join(this.interfaces());
        try {
          this.socket.setBroadcast(true);
        } catch (error) {
          this.emit('broadcast-unavailable', error);
        }

        this.timers.push(setInterval(() => this.peers.sweep(this.clock()), protocol.PEER_SWEEP_INTERVAL_MS));
        resolve(this.socket.address().port);
      });
    });
  }

  /**
   * Starts announcing on a given TCP port.
   *
   * TTL 1 is what makes "the same Wi-Fi" literally true: the datagram does not survive a router,
   * so a device two hops away cannot see this one however the network is arranged.
   */
  advertise({ tcpPort, keyFingerprint, flags = protocol.SUPPORTED_FLAGS }) {
    this.announcement = beaconWire.announce({
      platform: beaconWire.thisPlatform(),
      flags,
      tcpPort,
      deviceId: this.identity.deviceId,
      keyFingerprint,
      displayName: this.identity.displayName,
    });

    // A burst rather than a first beat, so a device whose screen has just opened appears at once
    // and one lost datagram does not cost two seconds of an empty list.
    for (const delay of protocol.ANNOUNCE_BURST_MS) {
      this.timers.push(setTimeout(() => this.#announce(), delay));
    }
    this.timers.push(setInterval(() => this.#announce(), protocol.ANNOUNCE_INTERVAL_MS));
  }

  /** Asks who is there, without saying anything about who is asking. */
  query() {
    this.#send(beaconWire.encodeBeacon(beaconWire.query()));
  }

  /**
   * Stops announcing, and says so rather than simply going quiet.
   *
   * Three goodbyes because a single one can be lost, and a peer that misses it waits out the full
   * expiry staring at a device that is no longer there.
   */
  stop() {
    if (this.announcement && this.socket) {
      const farewell = beaconWire.encodeBeacon(beaconWire.goodbye(this.announcement));
      for (let i = 0; i < protocol.GOODBYE_COUNT; i += 1) {
        setTimeout(() => this.#send(farewell), i * protocol.GOODBYE_GAP_MS);
      }
    }
    this.announcement = null;
    for (const timer of this.timers) {
      clearTimeout(timer);
      clearInterval(timer);
    }
    this.timers = [];
  }

  close() {
    this.stop();
    if (this.socket) {
      try {
        this.socket.close();
      } catch {
        // Already closed. Nothing to report.
      }
      this.socket = null;
    }
  }

  #announce() {
    if (!this.announcement) return;
    this.#send(beaconWire.encodeBeacon(this.announcement));
  }

  /**
   * The group joined on every LAN adapter, not only the one the OS calls default.
   *
   * With no interface named, `addMembership` joins on the default adapter alone, and on a machine
   * with a VM switch or a VPN that is often not the one the room is on -- beacons from the phone
   * arrive on the Wi-Fi and are never delivered. Each adapter is joined once and forgotten when it
   * goes, so one that comes back is joined again. A failure on one adapter is that adapter's news.
   */
  #join(lans) {
    if (!this.socket) return;
    const present = new Set(lans.map((lan) => lan.address));
    for (const address of [...this.joined]) if (!present.has(address)) this.joined.delete(address);
    for (const lan of lans) {
      if (this.joined.has(lan.address)) continue;
      try {
        this.socket.addMembership(protocol.MULTICAST_GROUP, lan.address);
        this.joined.add(lan.address);
      } catch (error) {
        this.emit('multicast-unavailable', error);
      }
    }
    // No adapter qualified: join the way this always did, on whatever the OS picks.
    if (!lans.length && !this.joinedDefault) {
      try {
        this.socket.addMembership(protocol.MULTICAST_GROUP);
        this.joinedDefault = true;
      } catch (error) {
        this.emit('multicast-unavailable', error);
      }
    }
  }

  /**
   * One datagram to every target, in turn.
   *
   * Per adapter: to the group with the multicast interface set to that adapter, and to that
   * adapter's own subnet-directed broadcast, which routing delivers out of the adapter that owns the
   * subnet. See `interfaces.js` for why neither the default multicast interface nor 255.255.255.255
   * can be trusted on a machine with more than one network.
   *
   * In turn, and each send finished before the next begins, because `setMulticastInterface` is a
   * socket option read when the datagram actually leaves. Two sends queued back to back would both
   * go out through whichever adapter was set last. The adapters are read again on every call, and
   * newly appeared ones joined.
   */
  #send(datagram) {
    this.sending = this.sending.then(() => this.#sendNow(datagram)).catch(() => {});
    return this.sending;
  }

  async #sendNow(datagram) {
    if (!this.socket) return;
    const lans = this.interfaces();
    this.#join(lans);
    const targets = beaconTargets(lans, {
      group: protocol.MULTICAST_GROUP,
      limitedBroadcast: protocol.BROADCAST_ADDRESS,
    });
    for (const target of targets) {
      if (!this.socket) return;
      if (target.via) {
        try {
          this.socket.setMulticastInterface(target.via);
        } catch {
          continue; // the adapter went between listing it and using it
        }
      }
      await new Promise((resolve) => {
        // A send that fails is a network that is not there. There is nowhere to report it and
        // nothing to do about it: the next announcement is two seconds away.
        try {
          this.socket.send(datagram, protocol.BEACON_PORT, target.address, () => resolve());
        } catch {
          resolve();
        }
      });
    }
  }

  #onDatagram(datagram, remote) {
    const beacon = beaconWire.decodeBeacon(datagram);
    if (beacon === null) return;

    // Our own, on deviceId rather than on address, so two copies on one machine still see each
    // other and multicast loopback behaviour never becomes load-bearing.
    if (Buffer.from(beacon.deviceId).equals(this.identity.deviceId)) return;

    if (beacon.messageType === protocol.BEACON_QUERY) {
      this.#answerQuery();
      return;
    }
    if (beacon.messageType === protocol.BEACON_GOODBYE) {
      this.peers.gone(beacon.deviceId);
      return;
    }
    // A beacon this build cannot speak to is still recorded. Dropping it meant the other device
    // never appeared, and "I can't see it" is the hardest thing to diagnose over the phone (#191).
    this.peers.seen(beacon, remote.address, this.clock());
  }

  /**
   * One extra announcement, jittered, at most one every 500 ms.
   *
   * The jitter is so that twenty devices in a room do not all answer in the same millisecond and
   * lose most of the answers to collisions. The rate limit is so that a query flood cannot turn
   * this device into an amplifier.
   */
  #answerQuery() {
    if (!this.announcement) return;
    const now = this.clock();
    if (now - this.lastAnswered < protocol.QUERY_ANSWER_MIN_GAP_MS) return;
    this.lastAnswered = now;
    setTimeout(() => this.#announce(), Math.random() * protocol.QUERY_ANSWER_JITTER_MS);
  }
}

module.exports = { Discovery, PeerTable };
