/*
 * What nearby sharing says, held to the list of things that can go wrong.
 *
 * The wire has its vectors and the flow has its loopback tests. This is the third thing, the one the
 * handover on #166 said nothing was catching: *what the person is told*. A reason added to
 * `nearby/problems.js` with no sentence here fails below, from both ends, so it cannot reach a screen
 * as a number or as silence.
 */

import test from 'node:test';
import assert from 'node:assert';
import { createRequire } from 'node:module';

import {
  ALARMING, PROBLEM_NAMES, IMPORT_PROBLEM_NAMES, describeProblem, codeGroups, mergeArrivals,
  arrivalAnnouncement, describeOffer, formatBytes,
} from './nearby-words.js';

const require = createRequire(import.meta.url);
const { PROBLEM } = require('../nearby/problems.js');
const { importProblemOf } = require('../nearby/problems.js');

test('every reason the protocol has, has a sentence', () => {
  const missing = Object.keys(PROBLEM).filter((name) => !PROBLEM_NAMES.includes(name));
  assert.deepStrictEqual(missing, [], `no words for: ${missing.join(', ')}`);
  // And nothing here that the protocol has dropped, which would be a sentence nobody can reach.
  const stale = PROBLEM_NAMES.filter((name) => !(name in PROBLEM));
  assert.deepStrictEqual(stale, [], `words for reasons that no longer exist: ${stale.join(', ')}`);
});

test('every sentence exists from both ends, and names the other device when it can', () => {
  // Either end may meet any reason: one raises it, and the other receives it in an ABORT.
  for (const name of Object.keys(PROBLEM)) {
    for (const role of ['send', 'receive']) {
      const said = describeProblem(name, { role, peer: 'Quiet Heron' });
      assert.ok(said.title.length > 0, `${name}/${role} has no title`);
      assert.ok(said.body.length > 20, `${name}/${role} says almost nothing: ${said.body}`);
      assert.ok(!/\bundefined\b|\bnull\b|NaN/.test(said.body), `${name}/${role}: ${said.body}`);
      assert.ok(!/0x[0-9a-f]|\berror \d/i.test(`${said.title} ${said.body}`),
        `${name}/${role} shows a code where a sentence belongs`);
      // Without a name the sentence still reads as a sentence.
      const nameless = describeProblem(name, { role });
      assert.match(nameless.body, /^[A-Z]/, `${name}/${role} without a name: ${nameless.body}`);
    }
  }
});

test('the two reasons that mean somebody may be in the middle are alarming, and say so', () => {
  assert.deepStrictEqual([...ALARMING].sort(), ['CODES_DID_NOT_MATCH', 'KEY_NOT_AS_PROMISED']);
  for (const name of ALARMING) {
    for (const role of ['send', 'receive']) {
      const said = describeProblem(name, { role, peer: 'Quiet Heron' });
      assert.strictEqual(said.alarm, true);
      // The six digits only defend anybody if a mismatch is read as an attack rather than a glitch.
      // "Try again" is the one thing these must never say.
      assert.ok(!/try again/i.test(said.body), `${name}/${role} invites a retry: ${said.body}`);
      assert.match(said.body, /this network/, `${name}/${role} does not say where the danger is`);
    }
    assert.match(describeProblem(name, { role: 'send' }).title, /Nothing was sent/);
    assert.match(describeProblem(name, { role: 'receive' }).title, /Nothing was received/);
  }
  // KEY_NOT_AS_PROMISED carries the same weight as a mismatch, not a lesser one.
  const mismatch = describeProblem('CODES_DID_NOT_MATCH', { role: 'send' });
  const broken = describeProblem('KEY_NOT_AS_PROMISED', { role: 'send' });
  assert.strictEqual(broken.alarm, mismatch.alarm);
  assert.match(broken.body, /in the middle/);
});

test('nothing else is dressed as an attack', () => {
  for (const name of Object.keys(PROBLEM).filter((n) => !ALARMING.has(n))) {
    assert.strictEqual(describeProblem(name, { role: 'send' }).alarm, false, name);
  }
});

test('a reason this build has never heard of reads as the other device stopping', () => {
  for (const unknown of ['SOMETHING_FROM_A_LATER_RELEASE', undefined, '27']) {
    const said = describeProblem(unknown, { role: 'send', peer: 'Amber Swift' });
    assert.strictEqual(said.name, 'UNKNOWN');
    assert.strictEqual(said.title, 'The other device stopped');
    assert.match(said.body, /^Amber Swift stopped/);
  }
});

test('a refused import says which of the importer\'s reasons it was', () => {
  // The importer's own refusals keep a sentence each, as the protocol asks.
  for (const code of [1, 2, 3, 4, 5]) {
    const reason = importProblemOf(code);
    assert.ok(IMPORT_PROBLEM_NAMES.includes(reason), `no words for import problem ${reason}`);
    const said = describeProblem('IMPORT_REFUSED', { role: 'send', peer: 'Quiet Heron', importProblem: reason });
    assert.match(said.body, /: .+\.$/, said.body);
  }
});

test('the six digits are two groups of three, and are spoken one digit at a time', () => {
  const code = codeGroups('483920');
  assert.deepStrictEqual(code.groups, ['483', '920']);
  assert.strictEqual(code.text, '483 920');
  // Not "four hundred and eighty-three thousand...": nobody can compare that against a screen.
  assert.strictEqual(code.label, 'Code: 4 8 3, 9 2 0');
  // Leading zeros are part of the code, not padding to be dropped.
  assert.deepStrictEqual(codeGroups('007001').groups, ['007', '001']);
  assert.deepStrictEqual(codeGroups(7001).groups, ['007', '001']);
});

test('devices keep their place in the list as others arrive and leave', () => {
  const heron = { key: 'a', name: 'Quiet Heron' };
  const swift = { key: 'b', name: 'Amber Swift' };
  const otter = { key: 'c', name: 'Brisk Otter' };

  let seen = mergeArrivals([], [heron]);
  assert.deepStrictEqual(seen.list.map((p) => p.key), ['a']);

  // Discovery hands them over sorted by name. "Amber" sorts first; it must still go to the end,
  // or the row under the pointer changes a moment before the click lands.
  seen = mergeArrivals(seen.list, [swift, heron]);
  assert.deepStrictEqual(seen.list.map((p) => p.key), ['a', 'b']);
  assert.deepStrictEqual(seen.arrived.map((p) => p.key), ['b']);

  // One leaving moves nothing above it, and a later arrival joins the end.
  seen = mergeArrivals(seen.list, [swift, otter]);
  assert.deepStrictEqual(seen.list.map((p) => p.key), ['b', 'c']);

  // A device whose name changed is updated where it stands, not re-announced.
  seen = mergeArrivals(seen.list, [{ ...swift, name: 'Study desk' }, otter]);
  assert.deepStrictEqual(seen.list.map((p) => p.name), ['Study desk', 'Brisk Otter']);
  assert.deepStrictEqual(seen.arrived, []);
});

test('arrivals are announced in one sentence, not one per device', () => {
  assert.strictEqual(arrivalAnnouncement([], 3), null);
  assert.strictEqual(arrivalAnnouncement([{ name: 'Quiet Heron' }], 1),
    'Quiet Heron appeared. 1 device nearby.');
  assert.strictEqual(arrivalAnnouncement([{ name: 'A' }, { name: 'B' }], 4),
    '2 devices appeared. 4 devices nearby.');
  // A device that cannot be sent to is announced as being there and as not being usable, in one
  // breath: otherwise a screen reader sends somebody to a row that will not respond (#191).
  assert.strictEqual(arrivalAnnouncement([{ name: 'Quiet Heron', speakable: false }], 1),
    'Quiet Heron appeared, on a different version of f-tree. 1 device nearby.');
});

test('the offer is described as the sender\'s claim, not as fact', () => {
  const said = describeOffer({
    peopleCount: 23, relationshipCount: 27, photoCount: 1, totalBytes: 12063, suggestedFileName: 'sample-family.ftree',
  }, 'Quiet Heron');
  assert.strictEqual(said.claim, 'Quiet Heron says it holds 23 people, 27 relationships and 1 photograph.');
  assert.strictEqual(said.size, '12 KB');
  assert.strictEqual(said.name, 'sample-family.ftree');
  assert.strictEqual(formatBytes(900), '900 bytes');
  assert.strictEqual(formatBytes(5 * 1024 * 1024), '5.0 MB');
});
