/*
 * What nearby sharing says to the person at the screen.
 *
 * Pure, and kept apart from the dialog that uses it, because the handover on #166 put its finger on
 * where the bugs have been: the vectors pin the layout of every byte, the loopback tests pin the
 * flow, and neither of them catches *what the person is told*. A DECLINE that the Kotlin read as
 * "they said no" and the desktop read as "too large" was a disagreement about a sentence. So the
 * sentences live here, one table, and `nearby-words.test.js` fails if a reason in
 * `nearby/problems.js` has none.
 *
 * Every sentence is written for one of two people, because the same number means different things
 * at the two ends. The *sender* is the one who asked; the *receiver* is the one who was asked. A
 * DECLINE is "they said no" to one and "you said no" to the other, and a screen that said the same
 * thing to both would be wrong for one of them.
 *
 * Two reasons carry an alarm, and they are not to be softened: CODES_DID_NOT_MATCH and
 * KEY_NOT_AS_PROMISED. Both mean the same thing -- a third device may have been in the middle of
 * the connection -- and the six digits are only a defence if the people holding the screens treat a
 * mismatch as that, rather than as a glitch to retry. "Try again" is the one thing these must never
 * say.
 */

/** The reasons a person is told to treat as somebody in the middle. */
export const ALARMING = new Set(['CODES_DID_NOT_MATCH', 'KEY_NOT_AS_PROMISED']);

const THE_OTHER = 'the other device';

/**
 * Every reason in `nearby/problems.js`, by name, as `{ title, send, receive }`.
 *
 * `send` and `receive` are functions of the other device's name, because "Quiet Heron declined"
 * says who and "the other device declined" does not -- and the name is the one thing on this screen
 * the person chose. Both are given for every reason, even the ones only one side can raise, because
 * the other side may receive it in an ABORT and has to say something true about it.
 */
const PROBLEMS = {
  PROTOCOL_TOO_NEW: {
    title: 'These two versions of f-tree can’t talk to each other',
    send: (peer) => `${peer} has an older f-tree that doesn’t understand this one. Update f-tree `
      + `on ${peer}, then try again.`,
    receive: (peer) => `${peer} has a newer f-tree than this computer. Update f-tree here, then `
      + 'try again.',
  },
  PROTOCOL_TOO_OLD: {
    title: 'These two versions of f-tree can’t talk to each other',
    send: (peer) => `This f-tree is too old for ${peer}. Update f-tree on this computer, then try `
      + 'again.',
    receive: (peer) => `${peer} has an older f-tree that this one no longer understands. Update `
      + `f-tree on ${peer}, then try again.`,
  },
  NOT_A_NEARBY_PEER: {
    title: 'That isn’t f-tree',
    send: () => 'Something answered at that address, but it wasn’t f-tree. Check the address on '
      + 'the other device’s Receive screen.',
    receive: () => 'Something connected that wasn’t f-tree, and was turned away.',
  },
  MALFORMED_FRAME: {
    title: 'The connection sent something unreadable',
    send: (peer) => `${peer} sent something this f-tree couldn’t read, so it stopped. Nothing was `
      + 'sent.',
    receive: (peer) => `${peer} sent something this f-tree couldn’t read, so it stopped. Nothing `
      + 'was added.',
  },
  FRAME_TOO_LARGE: {
    title: 'The connection sent something unreadable',
    send: (peer) => `${peer} sent more at once than f-tree ever sends, so it stopped.`,
    receive: (peer) => `${peer} sent more at once than f-tree ever sends, so it stopped. Nothing `
      + 'was added.',
  },
  UNEXPECTED_MESSAGE: {
    title: 'The two devices got out of step',
    send: (peer) => `${peer} said something at the wrong moment, so the transfer stopped. Nothing `
      + 'was sent. Open Receive on it again and start over.',
    receive: (peer) => `${peer} said something at the wrong moment, so the transfer stopped. `
      + 'Nothing was added.',
  },
  BAD_PUBLIC_KEY: {
    title: 'The connection couldn’t be secured',
    send: (peer) => `${peer} offered a key that can’t be used safely, so nothing was sent.`,
    receive: (peer) => `${peer} offered a key that can’t be used safely, so the connection was `
      + 'refused.',
  },
  WRONG_DEVICE: {
    title: 'A different device answered',
    send: (peer) => `The device that answered isn’t the ${peer} in the list, so nothing was sent. `
      + 'If it keeps happening, something on this network may be pretending to be it.',
    receive: () => 'The sending device found that it had reached a different device from the one '
      + 'it picked, and stopped.',
  },
  BAD_PAIRING: {
    title: 'That code has already been used',
    send: () => 'The code scanned has been used or has run out. Scan the one showing on the other '
      + 'device now.',
    receive: () => 'A device tried to connect with a code that had been used or had run out. A '
      + 'fresh code is showing now.',
  },
  DECRYPT_FAILED: {
    title: 'The connection was interfered with',
    send: (peer) => `Something that crossed the connection to ${peer} had been changed on the way, `
      + 'so the transfer stopped at once.',
    receive: (peer) => `Something that crossed the connection from ${peer} had been changed on `
      + 'the way, so the transfer stopped at once. Nothing was added.',
  },
  CODES_DID_NOT_MATCH: {
    title: {
      send: 'The codes were different. Nothing was sent.',
      receive: 'The codes were different. Nothing was received.',
    },
    send: (peer) => `Different codes mean another device may be in the middle, between this `
      + `computer and ${peer}, able to read or change what crosses. Don’t send your family over `
      + 'this network until you know why. Moving to a network you trust, like your own home Wi-Fi, '
      + 'is the fix.',
    receive: (peer) => `${peer} saw a different code from this one. That means another device may `
      + 'be in the middle, able to read or change what crosses. Nothing was received. Don’t '
      + 'accept a family over this network until you know why.',
  },
  DECLINED: {
    title: 'Not accepted',
    send: (peer) => `${peer} didn’t accept the tree. Nothing was sent.`,
    receive: () => 'You declined, so nothing was received.',
  },
  TIMED_OUT: {
    title: 'Nobody answered in time',
    send: (peer) => `${peer} didn’t answer for too long, so the transfer stopped. Nothing was `
      + 'sent.',
    receive: (peer) => `${peer} went quiet for too long, so the transfer stopped. Nothing was `
      + 'added.',
  },
  CANCELLED: {
    title: 'Stopped',
    send: (peer) => `${peer} stopped the transfer. Nothing was sent.`,
    receive: (peer) => `${peer} stopped the transfer. Nothing was received.`,
  },
  CONNECTION_LOST: {
    title: 'The connection dropped',
    send: (peer) => `The connection to ${peer} ended before the transfer finished. Check both `
      + 'devices are still on the same Wi-Fi, then try again.',
    receive: (peer) => `The connection from ${peer} ended before the transfer finished. Nothing was `
      + 'added.',
  },
  TOO_LARGE: {
    title: 'This tree is too large to send',
    send: (peer) => `${peer} won’t take a file this size. Nearby sharing carries up to 512 MB.`,
    receive: (peer) => `${peer} offered a file larger than 512 MB, so it was turned down.`,
  },
  NO_SPACE: {
    title: 'Not enough room',
    send: (peer) => `${peer} doesn’t have room for this tree. Free some space there, then try `
      + 'again.',
    receive: () => 'This computer doesn’t have room for that tree. Free some space, then try '
      + 'again.',
  },
  TRANSFER_INCOMPLETE: {
    title: 'Part of the tree didn’t arrive',
    send: (peer) => `${peer} didn’t receive the whole tree, so it kept none of it. Try again.`,
    receive: () => 'Part of the tree didn’t arrive, so none of it was kept. Ask them to send it '
      + 'again.',
  },
  CONTENT_MISMATCH: {
    title: 'What arrived isn’t what was sent',
    send: (peer) => `The tree that reached ${peer} didn’t match the one sent, so it was thrown `
      + 'away. Try again.',
    receive: () => 'The tree that arrived didn’t match what the sender described, so it was thrown '
      + 'away before anything read it. Ask them to send it again.',
  },
  TREE_FORMAT_TOO_NEW: {
    title: 'One of these copies of f-tree is too old',
    send: (peer) => `${peer} has an f-tree too old to read trees from this one. Update f-tree `
      + `on ${peer}, then try again.`,
    receive: (peer) => `${peer} writes trees this f-tree is too old to read. Update f-tree here, `
      + 'then try again.',
  },
  IMPORT_REFUSED: {
    title: 'The tree arrived but couldn’t be read',
    send: (peer) => `${peer} received the whole file but couldn’t open it as a family tree.`,
    receive: () => 'The whole file arrived, but it couldn’t be opened as a family tree.',
  },
  NETWORK: {
    title: 'This computer can’t reach the network',
    send: () => 'f-tree couldn’t connect. Check this computer is on the same Wi-Fi as the other '
      + 'device, and that the address is the one on its Receive screen.',
    receive: () => 'f-tree couldn’t start listening on this network. Check this computer is on '
      + 'Wi-Fi, and that a firewall isn’t blocking f-tree.',
  },
  PERMISSION: {
    title: 'f-tree isn’t allowed on the network',
    send: () => 'This computer isn’t letting f-tree use the local network. Allow it in the '
      + 'system’s privacy or firewall settings, then try again.',
    receive: () => 'This computer isn’t letting f-tree use the local network. Allow it in the '
      + 'system’s privacy or firewall settings, then try again.',
  },
  BUSY: {
    title: 'That device is busy',
    send: (peer) => `${peer} is already in the middle of another transfer. Wait for it to finish, `
      + 'then try again.',
    receive: () => 'Another device tried to connect during this transfer, and was told to wait.',
  },
  KEY_NOT_AS_PROMISED: {
    title: {
      send: 'The connection was tampered with. Nothing was sent.',
      receive: 'The connection was tampered with. Nothing was received.',
    },
    send: (peer) => `The device answering as ${peer} changed its key after promising it, which is `
      + 'what a device in the middle of a connection does to fake a matching code. Don’t send '
      + 'your family over this network until you know why. Moving to a network you trust, like '
      + 'your own home Wi-Fi, is the fix.',
    receive: () => 'The sending device found that this connection had been tampered with, and '
      + 'stopped before anything crossed. Don’t accept a family over this network until you know '
      + 'why.',
  },
  UNKNOWN: {
    title: 'The other device stopped',
    send: (peer) => `${peer} stopped the transfer. Nothing was sent.`,
    receive: (peer) => `${peer} stopped the transfer. Nothing was added.`,
  },
};

/** What the importer said about a file it could not read, as the sender is told it. */
const IMPORT_PROBLEMS = {
  notAnArchive: 'it isn’t a .ftree file',
  notATreeFile: 'there’s no family tree inside it',
  fromANewerVersion: 'it was written by a newer f-tree than theirs',
  empty: 'there’s nobody in it',
  unreadable: 'part of it couldn’t be read',
};

/** Every name the table covers. The test holds this to `nearby/problems.js`. */
export const PROBLEM_NAMES = Object.freeze(Object.keys(PROBLEMS));
export const IMPORT_PROBLEM_NAMES = Object.freeze(Object.keys(IMPORT_PROBLEMS));

/**
 * The sentence for a reason, from one side.
 *
 * A name this build does not know reads as UNKNOWN -- "the other device stopped" -- and is never
 * shown as a number. A later release may stop for a reason that did not exist when this was
 * written, and "error 27" is neither true nor useful where "the other device stopped" is both.
 *
 * @param {string} name     a key of PROBLEM, as `problemName()` gives it
 * @param {object} context
 * @param {'send'|'receive'} context.role  which end this screen is
 * @param {string} [context.peer]          the other device's name, if it is known
 * @param {string} [context.importProblem] for IMPORT_REFUSED, the importer's own reason
 */
export function describeProblem(name, { role, peer = null, importProblem = null } = {}) {
  const known = Object.hasOwn(PROBLEMS, name) ? name : 'UNKNOWN';
  const entry = PROBLEMS[known];
  const who = peer || THE_OTHER;
  let body = (role === 'receive' ? entry.receive : entry.send)(who);
  // A sentence that starts with the other device's name reads "the other device ..." when there is
  // no name, which needs a capital.
  body = body.charAt(0).toUpperCase() + body.slice(1);
  if (known === 'IMPORT_REFUSED' && importProblem && Object.hasOwn(IMPORT_PROBLEMS, importProblem)) {
    body = body.replace(/\.$/, `: ${IMPORT_PROBLEMS[importProblem]}.`);
  }
  // A heading that says what did not happen has to say it from this side: nothing was *sent* is
  // the sender's sentence, and the receiver was never going to send anything.
  const title = typeof entry.title === 'string' ? entry.title : entry.title[role === 'receive' ? 'receive' : 'send'];
  return { name: known, title, body, alarm: ALARMING.has(known) };
}

/* ------------------------------------------------------------------ the six digits */

/**
 * Six digits as two groups of three, and a label a screen reader speaks one digit at a time.
 *
 * Grouped because six in a row is a number to be read, and two threes are a code to be compared --
 * "483 920" is checked a group at a time against the other screen. The label spells the digits out,
 * so a screen reader says "4 8 3, 9 2 0" rather than "four hundred and eighty-three thousand, nine
 * hundred and twenty", which nobody can hold up against another screen.
 */
export function codeGroups(sas) {
  const digits = String(sas ?? '').replace(/\D/g, '').padStart(6, '0').slice(-6);
  const groups = [digits.slice(0, 3), digits.slice(3)];
  return {
    groups,
    label: `Code: ${[...groups[0]].join(' ')}, ${[...groups[1]].join(' ')}`,
    text: groups.join(' '),
  };
}

/* ------------------------------------------------------------------ the list of devices */

/**
 * The list as it should now be drawn: old devices where they were, new ones at the end.
 *
 * Discovery sorts by name, which is right for a table and wrong for a list somebody is about to
 * click. A device appearing mid-way down moves every row beneath it, and the row under the pointer
 * becomes a different device a moment before the click lands. So the order is arrival, and a
 * device that drops out takes its row with it and moves nothing above it.
 *
 * @returns {{ list: object[], arrived: object[] }} `arrived` for the one polite announcement
 */
export function mergeArrivals(previous, current) {
  const byKey = new Map(current.map((peer) => [peer.key, peer]));
  const kept = previous.filter((peer) => byKey.has(peer.key)).map((peer) => byKey.get(peer.key));
  const known = new Set(previous.map((peer) => peer.key));
  const arrived = current.filter((peer) => !known.has(peer.key));
  return { list: [...kept, ...arrived], arrived };
}

/**
 * One sentence for the status line, however many arrived at once.
 *
 * One polite line rather than one per device: a room with six devices in it would otherwise read
 * out six interruptions while somebody is trying to listen for the one they want.
 */
export function arrivalAnnouncement(arrived, total) {
  if (!arrived.length) return null;
  // A device on another version of f-tree is announced as being there, and as not being usable in
  // the same breath (#191). Saying only that it appeared would send somebody to a row that cannot
  // be pressed, with nothing said about why.
  const named = arrived.length === 1
    ? `${arrived[0].name} appeared${arrived[0].speakable === false ? ', on a different version of f-tree' : ''}`
    : `${arrived.length} devices appeared`;
  return `${named}. ${total === 1 ? '1 device' : `${total} devices`} nearby.`;
}

const PLATFORMS = { 1: 'Android', 2: 'Linux', 3: 'Windows', 4: 'macOS' };

/** The platform byte from a beacon, as a word. Unknown is left unsaid rather than guessed. */
export function platformName(code) {
  return PLATFORMS[code] ?? '';
}

/* ------------------------------------------------------------------ the offer */

/** A size somebody can picture. Kilobytes are rounded up so a small tree is never "0 KB". */
export function formatBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n < 1024) return `${n} bytes`;
  if (n < 1024 * 1024) return `${Math.ceil(n / 1024)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/**
 * What the sender says it is sending, as a claim.
 *
 * The counts are the sender's word and nothing more: they are typed into the offer by the device
 * sending it, and nothing checks them until the file is open. The review that follows counts for
 * itself. So they are introduced as what the other device *says*, never as what the file holds.
 */
export function describeOffer(offer, peer) {
  const who = peer || 'The other device';
  const parts = [
    plural(offer.peopleCount, 'person', 'people'),
    plural(offer.relationshipCount, 'relationship', 'relationships'),
    plural(offer.photoCount, 'photograph', 'photographs'),
  ];
  return {
    claim: `${who} says it holds ${parts[0]}, ${parts[1]} and ${parts[2]}.`,
    size: formatBytes(offer.totalBytes),
    name: offer.suggestedFileName || 'family.ftree',
  };
}
