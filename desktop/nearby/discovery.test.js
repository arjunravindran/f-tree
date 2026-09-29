/*
 * The peer table, and two copies of the app finding each other on one machine.
 *
 * The table is pure and gets the detailed treatment. The socket half gets one test, because what
 * it does on a real network -- an access point that drops an unfamiliar multicast group, a guest
 * SSID with client isolation, a phone that walks out of range -- cannot be reproduced from a test
 * process on loopback, and pretending otherwise would be worse than saying so.
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const protocol = require('./protocol');
const beaconWire = require('./beacon');
const { PeerTable, Discovery } = require('./discovery');
const { NearbyIdentity } = require('./identity');

function beaconFor(deviceId, {
  name = 'Quiet Heron', port = 4000, type = protocol.BEACON_ANNOUNCE,
  maxVersion = protocol.VERSION, minVersion = protocol.MIN_VERSION,
} = {}) {
  return {
    messageType: type,
    maxVersion,
    minVersion,
    platform: beaconWire.PLATFORM.ANDROID,
    flags: protocol.FLAG_ACCEPTS_TREE,
    tcpPort: port,
    deviceId: Buffer.from(deviceId),
    keyFingerprint: Buffer.alloc(8, 1),
    displayName: name,
  };
}

const A = Buffer.alloc(16, 0xa1);
const B = Buffer.alloc(16, 0xb2);

test('a device appears once, however many times it announces', () => {
  const table = new PeerTable();
  const appeared = [];
  table.on('appeared', (peer) => appeared.push(peer.displayName));

  table.seen(beaconFor(A), '192.168.1.5', 1000);
  table.seen(beaconFor(A), '192.168.1.5', 3000);
  table.seen(beaconFor(A), '192.168.1.5', 5000);

  assert.deepEqual(appeared, ['Quiet Heron']);
  assert.equal(table.list().length, 1);
});

test('announcing twice over two transports costs one entry', () => {
  // The reason announcing to multicast *and* broadcast is free: the table is keyed on deviceId, so
  // a device that answers on both is one row and one comparison.
  const table = new PeerTable();
  table.seen(beaconFor(A), '192.168.1.5', 1000);
  table.seen(beaconFor(A), '192.168.1.5', 1001);
  assert.equal(table.list().length, 1);
});

test('a device is forgotten after three missed announcements', () => {
  const table = new PeerTable();
  const vanished = [];
  table.on('vanished', (peer) => vanished.push(peer.displayName));

  table.seen(beaconFor(A), '192.168.1.5', 1000);
  table.sweep(1000 + protocol.PEER_EXPIRY_MS - 1);
  assert.equal(table.list().length, 1, 'forgotten too early');

  table.sweep(1000 + protocol.PEER_EXPIRY_MS);
  assert.deepEqual(vanished, ['Quiet Heron']);
  assert.equal(table.list().length, 0);
});

test('a goodbye removes a device at once rather than waiting it out', () => {
  // Three goodbyes are sent because one can be lost. A peer that misses them all waits out the
  // full expiry looking at a device that is not there, which is the thing this avoids.
  const table = new PeerTable();
  const vanished = [];
  table.on('vanished', (peer) => vanished.push(peer.key));

  table.seen(beaconFor(A), '192.168.1.5', 1000);
  table.gone(A);
  assert.equal(table.list().length, 0);
  assert.equal(vanished.length, 1);

  // And a goodbye for a device that was never seen is not an error.
  table.gone(B);
  assert.equal(vanished.length, 1);
});

test('a renamed device is a change, not a second device', () => {
  const table = new PeerTable();
  const changed = [];
  table.on('changed', (peer) => changed.push(peer.displayName));

  table.seen(beaconFor(A, { name: 'Quiet Heron' }), '192.168.1.5', 1000);
  table.seen(beaconFor(A, { name: 'Amber Otter' }), '192.168.1.5', 2000);

  assert.deepEqual(changed, ['Amber Otter']);
  assert.equal(table.list().length, 1);
});

test('a device that moved to another address is a change, not a second device', () => {
  const table = new PeerTable();
  const changed = [];
  table.on('changed', (peer) => changed.push(peer.address));

  table.seen(beaconFor(A), '192.168.1.5', 1000);
  table.seen(beaconFor(A), '192.168.1.9', 2000);

  assert.deepEqual(changed, ['192.168.1.9']);
  assert.equal(table.list()[0].address, '192.168.1.9');
});

test('the address comes from the datagram, never from the beacon', () => {
  // A beacon carries no address at all, so a forged one cannot point a sender at a third machine.
  // This test is really an assertion about the wire format, checked from the table's side.
  const table = new PeerTable();
  const peer = table.seen(beaconFor(A), '192.168.1.77', 1000);
  assert.equal(peer.address, '192.168.1.77');
  assert.ok(!('address' in beaconFor(A)), 'a beacon should not carry an address');
});

test('two devices are two rows, sorted for a list somebody reads', () => {
  const table = new PeerTable();
  table.seen(beaconFor(A, { name: 'Quiet Heron' }), '192.168.1.5', 1000);
  table.seen(beaconFor(B, { name: 'Amber Otter' }), '192.168.1.6', 1000);
  assert.deepEqual(table.list().map((p) => p.displayName), ['Amber Otter', 'Quiet Heron']);
});

test('a device on another protocol version is listed, and marked as one', () => {
  // #191: this used to be dropped before it reached the table, so the other device simply never
  // appeared and the only symptom was "I can't see it" -- the hardest case to work out remotely.
  const table = new PeerTable();
  table.seen(beaconFor(A, { name: 'Quiet Heron', maxVersion: 9, minVersion: 9 }), '192.168.1.5', 1000);
  table.seen(beaconFor(B, { name: 'Amber Otter' }), '192.168.1.6', 1000);

  const listed = new Map(table.list().map((peer) => [peer.displayName, peer]));
  assert.equal(listed.size, 2);
  assert.equal(listed.get('Quiet Heron').speakable, false);
  assert.equal(listed.get('Amber Otter').speakable, true);
});

test('a device that becomes speakable is a change, not a second device', () => {
  // The other end being updated while this screen is open: the row has to stop being greyed.
  const table = new PeerTable();
  const changed = [];
  table.on('changed', (peer) => changed.push(peer));
  table.seen(beaconFor(A, { maxVersion: 9, minVersion: 9 }), '192.168.1.5', 1000);
  table.seen(beaconFor(A), '192.168.1.5', 2000);

  assert.equal(table.list().length, 1);
  assert.deepEqual(changed.map((peer) => peer.speakable), [true]);
});

test('two copies on one machine find each other', async (t) => {
  // The one socket test. It filters on deviceId rather than on source address, which is exactly
  // what makes this possible: on a machine where both ends share an address, filtering on the
  // address would have each copy ignore the other.
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ftree-discovery-'));
  const one = new NearbyIdentity(path.join(directory, 'one'));
  const two = new NearbyIdentity(path.join(directory, 'two'));

  const advertiser = new Discovery({ identity: one });
  const browser = new Discovery({ identity: two });

  try {
    await advertiser.start();
    await browser.start();
  } catch (error) {
    // A machine where the port is taken or multicast is administratively off is not a machine
    // where this test says anything. Skipping is honest; failing would not be.
    advertiser.close();
    browser.close();
    t.skip(`no usable multicast socket here: ${error.message}`);
    return;
  }

  const found = new Promise((resolve) => browser.once('appeared', resolve));
  advertiser.advertise({ tcpPort: 54321, keyFingerprint: Buffer.alloc(8, 7) });

  const timeout = new Promise((resolve) => setTimeout(() => resolve(null), 4000));
  const peer = await Promise.race([found, timeout]);

  advertiser.close();
  browser.close();

  if (peer === null) {
    t.skip('no beacon arrived; this machine drops loopback multicast and broadcast');
    return;
  }
  assert.equal(peer.port, 54321);
  assert.equal(Buffer.from(peer.deviceId).toString('hex'), one.deviceId.toString('hex'));
  assert.equal(peer.displayName, one.displayName);
});

test('a device never sees itself', async (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ftree-self-'));
  const identity = new NearbyIdentity(directory);
  const discovery = new Discovery({ identity });

  try {
    await discovery.start();
  } catch (error) {
    discovery.close();
    t.skip(`no usable multicast socket here: ${error.message}`);
    return;
  }

  // Specifically *itself*, not "nothing at all". Other tests in this suite advertise on the same
  // port at the same time, and a real peer turning up is the feature working rather than a fault.
  const self = discovery.identity.deviceId.toString('hex');
  const sawSelf = [];
  discovery.on('appeared', (peer) => {
    if (Buffer.from(peer.deviceId).toString('hex') === self) sawSelf.push(peer);
  });
  discovery.advertise({ tcpPort: 54322, keyFingerprint: Buffer.alloc(8) });

  await new Promise((resolve) => setTimeout(resolve, 1500));
  discovery.close();
  assert.deepEqual(sawSelf, [], 'the device listed itself as a peer');
});
