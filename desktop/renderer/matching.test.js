/*
 * DuplicateMatcherTest.kt, case for case.
 *
 * The rules that decide whether two records are the same person, and the most dangerous rules in
 * either app: a wrong merge destroys information that may exist nowhere else, while a wrong split
 * is a tidy-up. The tests are written to hold that asymmetry, and are kept in the Kotlin's order
 * with the Kotlin's names so the two files can be read side by side.
 */

import test from 'node:test';
import assert from 'node:assert';

import {
  MatchTier, matchPeople, mergesByDefault, needsReview, nameKey, originKey, graphOf,
} from './matching.js';

/** The Kotlin's `graph(...)` helper. */
const graph = (...edges) => graphOf(edges);

/** The Kotlin's `match(...)` helper: results keyed by imported id. */
function match({
  imported, local, importedGraph = new Map(), localGraph = new Map(),
  origins = [], sourceTreeId = 'their-tree',
}) {
  const originIndex = new Map();
  for (const [treeId, personId, localId] of origins) {
    const key = originKey(treeId, personId);
    originIndex.set(key, [...(originIndex.get(key) ?? []), localId]);
  }
  const results = matchPeople({
    imported, importedGraph, local, localGraph, originIndex, sourceTreeId,
  });
  return new Map(results.map((r) => [r.importedId, r]));
}

test('a person nobody here resembles is new', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Priya Sharma' }],
    local: [{ id: 'l1', name: 'Ankit Kumar' }],
  });
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
  assert.strictEqual(result.get('i1').localId, null);
});

test('a person we already imported from this tree is a certain match', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar' }],
    local: [{ id: 'l1', name: 'Ankit Kumar' }],
    origins: [['their-tree', 'i1', 'l1']],
  });
  const single = result.get('i1');
  assert.strictEqual(single.tier, MatchTier.CERTAIN);
  assert.strictEqual(single.localId, 'l1');
  assert.ok(mergesByDefault(single));
});

test('an inherited origin also proves identity', () => {
  // Their file came from a tree that had already merged ours, so the person carries our id.
  const result = match({
    imported: [{
      id: 'i1',
      name: 'Totally Different Spelling',
      origins: [{ treeId: 'original-tree', personId: 'old-7' }],
    }],
    local: [{ id: 'l1', name: 'Ankit Kumar' }],
    origins: [['original-tree', 'old-7', 'l1']],
  });
  assert.strictEqual(result.get('i1').tier, MatchTier.CERTAIN);
});

test('the same name with a relative in common is a strong match', () => {
  const result = match({
    imported: [{ id: 'iFather', name: 'Raj Kumar' }, { id: 'iMe', name: 'Ankit Kumar' }],
    local: [{ id: 'lFather', name: 'Raj Kumar' }, { id: 'lMe', name: 'Ankit Kumar' }],
    importedGraph: graph(['iFather', 'iMe']),
    localGraph: graph(['lFather', 'lMe']),
    origins: [['their-tree', 'iMe', 'lMe']],
  });

  // "Ankit Kumar" is provable; that makes his father's name far more than a coincidence.
  assert.strictEqual(result.get('iMe').tier, MatchTier.CERTAIN);
  const father = result.get('iFather');
  assert.strictEqual(father.tier, MatchTier.STRONG);
  assert.strictEqual(father.evidence.sharedRelatives, 1);
  assert.ok(mergesByDefault(father));
});

test('a shared relative is enough even when nobody recorded any dates', () => {
  const result = match({
    imported: [{ id: 'iFather', name: 'Raj Kumar' }, { id: 'iMe', name: 'Ankit Kumar' }],
    local: [{ id: 'lFather', name: 'Raj Kumar' }, { id: 'lMe', name: 'Ankit Kumar' }],
    importedGraph: graph(['iFather', 'iMe']),
    localGraph: graph(['lFather', 'lMe']),
    origins: [['their-tree', 'iMe', 'lMe']],
  });
  // Refusing to match for want of a birth date nobody ever wrote down would be absurd.
  assert.strictEqual(result.get('iFather').tier, MatchTier.STRONG);
});

test('the same name alone is only a weak match and stays separate by default', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar' }],
    local: [{ id: 'l1', name: 'Ankit Kumar' }],
  });
  const single = result.get('i1');
  assert.strictEqual(single.tier, MatchTier.WEAK);
  assert.strictEqual(single.localId, 'l1');
  assert.ok(!mergesByDefault(single), 'a coincidence of names must not merge on its own');
  assert.ok(needsReview(single));
});

test('birth dates that cannot both be true rule out a match entirely', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar', birthDate: '1990' }],
    local: [{ id: 'l1', name: 'Ankit Kumar', birthDate: '1962' }],
  });
  // Two people with one name born decades apart are two people.
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
  assert.strictEqual(result.get('i1').localId, null);
});

test('dates of differing precision still agree', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar', birthDate: '1990' }],
    local: [{ id: 'l1', name: 'Ankit Kumar', birthDate: '1990-05-01' }],
  });
  assert.strictEqual(result.get('i1').tier, MatchTier.WEAK);
});

test('a birthday with no year still rules out a match when it disagrees (#90)', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar', birthDate: '--04-17' }],
    local: [{ id: 'l1', name: 'Ankit Kumar', birthDate: '1990-05-01' }],
  });
  // Read as "no date", this veto was lost; it is the unsafe way round.
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
});

test('a birthday with no year agrees with a year that could hold it (#90)', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar', birthDate: '--04-17' }],
    local: [{ id: 'l1', name: 'Ankit Kumar', birthDate: '1990' }],
  });
  assert.strictEqual(result.get('i1').tier, MatchTier.WEAK);
  assert.strictEqual(result.get('i1').evidence.datesAgree, true);
});

test('conflicting death dates also rule out a match', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Raj Kumar', birthDate: '1938', deathDate: '2010' }],
    local: [{ id: 'l1', name: 'Raj Kumar', birthDate: '1938', deathDate: '1999' }],
  });
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
});

test('names are compared past accents punctuation and spacing', () => {
  const result = match({
    imported: [{ id: 'i1', name: "  josé   O'BRIEN " }],
    local: [{ id: 'l1', name: 'Jose O Brien' }],
  });
  assert.strictEqual(result.get('i1').tier, MatchTier.WEAK);
});

test('an abbreviated name is not treated as the same person', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'R. Kumar' }],
    local: [{ id: 'l1', name: 'Raj Kumar' }],
  });
  // Guessing here is how a merge quietly destroys someone's data.
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
});

test('an unnamed person is never matched to anybody', () => {
  const result = match({
    imported: [{ id: 'i1' }],
    local: [{ id: 'l1' }, { id: 'l2', name: 'Ankit' }],
  });
  // Every unknown person would otherwise collapse into one.
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
});

test('two local people with the same name and nothing to separate them are not guessed at', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar' }],
    local: [{ id: 'l1', name: 'Ankit Kumar' }, { id: 'l2', name: 'Ankit Kumar' }],
  });
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
});

test('one local person is never claimed by two imported people', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'Ankit Kumar' }, { id: 'i2', name: 'Ankit Kumar' }],
    local: [{ id: 'l1', name: 'Ankit Kumar' }],
    origins: [['their-tree', 'i1', 'l1']],
  });
  assert.strictEqual(result.get('i1').localId, 'l1');
  assert.strictEqual(result.get('i2').localId, null, 'l1 is already taken');
});

test('a copy and the person it copies are each matched to themselves', () => {
  // `d` is a stale copy of `a`, carrying a's origin. Both were imported before, so two people here
  // hold that origin -- and the one indexed last is the copy (#193).
  const result = match({
    imported: [
      { id: 'a', name: 'Ankit Kumar' },
      { id: 'd', name: 'Ankit Kumar', origins: [{ treeId: 'their-tree', personId: 'a' }] },
    ],
    local: [{ id: 'la', name: 'Ankit Kumar' }, { id: 'ld', name: 'Ankit Kumar' }],
    origins: [['their-tree', 'a', 'la'], ['their-tree', 'd', 'ld'], ['their-tree', 'a', 'ld']],
  });
  const settled = (id) => [result.get(id).localId, result.get(id).tier];
  assert.deepStrictEqual(settled('a'), ['la', MatchTier.CERTAIN]);
  assert.deepStrictEqual(settled('d'), ['ld', MatchTier.CERTAIN]);
});

test('of two people holding the same origin, the one holding nothing else is meant', () => {
  const result = match({
    imported: [{ id: 'a', name: null }],
    local: [{ id: 'ld', name: null }, { id: 'la', name: null }],
    origins: [['their-tree', 'a', 'la'], ['their-tree', 'a', 'ld'], ['their-tree', 'd', 'ld']],
  });
  assert.strictEqual(result.get('a').localId, 'la');
});

test('matching the same file twice is stable', () => {
  const args = {
    imported: [
      { id: 'i1', name: 'Ankit Kumar', birthDate: '1990' },
      { id: 'i2', name: 'Raj Kumar', birthDate: '1938' },
    ],
    local: [
      { id: 'l1', name: 'Ankit Kumar', birthDate: '1990' },
      { id: 'l2', name: 'Raj Kumar', birthDate: '1938' },
    ],
    origins: [['their-tree', 'i1', 'l1'], ['their-tree', 'i2', 'l2']],
  };
  const first = match(args);
  const second = match(args);

  assert.deepStrictEqual(
    [...first].map(([k, v]) => [k, v.tier]),
    [...second].map(([k, v]) => [k, v.tier]));
  assert.ok([...first.values()].every((m) => m.tier === MatchTier.CERTAIN));
});

/* ---------------------------------------------------------------- beyond the Kotlin's table */

/*
 * Added here. The Kotlin keys its origin index on a `Pair`, which cannot be confused; JavaScript's
 * Map needs the key flattened into a string, and that flattening is a place to introduce a bug the
 * Kotlin cannot have.
 */
test('two origins cannot collide through the separator used to key them', () => {
  // Ids chosen so a naive `${treeId}-${personId}` or `${treeId}:${personId}` would produce the
  // same string for both, and merge two people who share nothing but punctuation.
  assert.notStrictEqual(originKey('tree-a', 'b-1'), originKey('tree', 'a-b-1'));
  assert.notStrictEqual(originKey('a:b', 'c'), originKey('a', 'b:c'));
  assert.notStrictEqual(originKey('a b', 'c'), originKey('a', 'b c'));
});

test('a name is reduced to form, never to content', () => {
  assert.strictEqual(nameKey("  jos\u00e9   O'BRIEN "), nameKey('Jose O Brien'));
  assert.strictEqual(nameKey('Raj  Kumar'), 'raj kumar');
  assert.notStrictEqual(nameKey('R. Kumar'), nameKey('Raj Kumar'));
  assert.strictEqual(nameKey(''), null);
  assert.strictEqual(nameKey('   '), null);
  assert.strictEqual(nameKey(null), null);
});

/*
 * Devanagari, and every script whose vowels are marks rather than letters.
 *
 * A Devanagari vowel sign is a mark, not a letter, and the key used to throw marks away, so
 * "राम" reduced to "र म" and distinct names collided: see #113. The key now strips
 * only the Latin combining accents and keeps every other mark as part of the word. The issue
 * suggested keeping category Mc as well, but that does not work: U+0941 is Mn, so
 * "कुमार" and "कमार" would still have collided.
 *
 * The Kotlin does exactly the same thing, and `DuplicateMatcherTest` carries the same table, checked
 * by running both `java.text.Normalizer` and Node over the same inputs and comparing output
 * character for character. If either side changes, that table fails on that side and forces the
 * other to change with it, rather than the two silently drifting apart on who is the same person.
 */
test('Devanagari keys keep their vowel signs, and match the Kotlin exactly', () => {
  const asKotlin = {
    'राम': 'राम',
    'रामा': 'रामा',
    'कुमार': 'कुमार',
    'कमार': 'कमार',
    'श्याम': 'श्याम',
    'शयाम': 'शयाम',
    'राम कुमार': 'राम कुमार',
  };
  for (const [name, expected] of Object.entries(asKotlin)) {
    assert.strictEqual(nameKey(name), expected, `${name} keyed differently from the Kotlin`);
  }
  // Latin accents are still stripped; only the range NFD leaves for them is.
  assert.strictEqual(nameKey('José'), 'jose');
  assert.strictEqual(nameKey('Ángel'), 'angel');
  assert.strictEqual(nameKey('Đặng'), 'đang');
});

test('names that differ only in a Devanagari vowel sign are no longer the same name', () => {
  const result = match({
    imported: [{ id: 'i1', name: 'राम' }],       // Ram
    local: [{ id: 'l1', name: 'रामा' }],   // Rama, a different name
  });
  assert.notStrictEqual(nameKey('राम'), nameKey('रामा'));
  assert.strictEqual(result.get('i1').tier, MatchTier.NONE);
  assert.ok(!mergesByDefault(result.get('i1')));
});

test('a shared relative can no longer merge two different Devanagari names', () => {
  // The case that made #113 worth fixing: the rules were fed two names that are not the same name,
  // and a shared child then took them to STRONG, which merges without asking.
  const result = match({
    imported: [{ id: 'iA', name: 'राम' }, { id: 'iKid', name: 'Shared Child' }],
    local: [{ id: 'lA', name: 'रामा' }, { id: 'lKid', name: 'Shared Child' }],
    importedGraph: graph(['iA', 'iKid']),
    localGraph: graph(['lA', 'lKid']),
    origins: [['their-tree', 'iKid', 'lKid']],
  });
  assert.notStrictEqual(result.get('iA').tier, MatchTier.STRONG);
  assert.ok(!mergesByDefault(result.get('iA')),
    'two different Hindi names must not merge without being asked about');
});

test('a strange file cannot make the matcher run long', () => {
  // Every person shares a name and a relative with every other, which is the shape that would
  // make an unbounded pass loop keep finding "new" evidence.
  const n = 300;
  const imported = [];
  const local = [];
  const edges = [];
  for (let i = 0; i < n; i += 1) {
    imported.push({ id: `i${i}`, name: 'Same Name' });
    local.push({ id: `l${i}`, name: 'Same Name' });
    if (i > 0) edges.push([`i${i - 1}`, `i${i}`]);
  }
  const started = Date.now();
  const result = match({
    imported,
    local,
    importedGraph: graphOf(edges),
    localGraph: graphOf(edges.map(([a, b]) => [a.replace('i', 'l'), b.replace('i', 'l')])),
  });
  assert.strictEqual(result.size, n);
  assert.ok(Date.now() - started < 5000, `took ${Date.now() - started}ms`);
});
