/*
 * The template validator (#243): format 1 unchanged, format 2 (the storybook's paper-cut schema)
 * new. `book.test.mjs` already holds format 1's own "anything unexpected is refused" table against
 * the shipped Heirloom template; this file is format 2's, built from a fixture, because no
 * format-2 template ships yet - the real one is assembled across #250-#259.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  validateTemplate, PALETTE_KEYS, PAPERCUT_PALETTE_KEYS, FONT_KEYS, ROLE_FONT_KEYS, HAND_FONT_KEY,
  REQUIRED_CHAPTERS,
} from './template.js';

// A palette with one #rrggbb colour per token, so the fixture is complete without hand-typing 29
// hexes whose exact value never matters to the validator.
const papercutPalette = Object.fromEntries(PAPERCUT_PALETTE_KEYS.map((k, i) => [k, `#${(i * 7 % 256).toString(16).padStart(2, '0')}1a2b`]));

function papercutTemplate(overrides = {}) {
  return {
    format: 2,
    id: 'diwali',
    name: 'Diwali',
    fileSuffix: 'Book',
    art: 'papercut',
    fonts: { display: 'book_display', text: 'book_text', strong: 'book_strong', hand: HAND_FONT_KEY },
    palette: { ...papercutPalette },
    cover: { greeting: 'शुभ दीपावली', subtitle: 'from the {family} family', line: 'One lamp for each of us' },
    story: { chapters: ['cover', 'opening', 'roots', 'register', 'closing'] },
    copy: {
      opening: { title: 'Opening', line: '{featured-first} was born in {year}.' },
      roots: { title: 'Roots', line: { one: '{n} generation before {featured}', other: '{n} generations before {featured}' } },
    },
    ...overrides,
  };
}

test('a well-formed format-2 template validates, and freezes what it returns', () => {
  const t = validateTemplate(papercutTemplate());
  assert.equal(t.format, 2);
  assert.equal(t.id, 'diwali');
  assert.equal(t.art, 'papercut');
  assert.deepEqual(Object.keys(t.fonts).sort(), ['display', 'hand', 'strong', 'text']);
  assert.equal(t.fonts.hand, HAND_FONT_KEY);
  assert.equal(Object.keys(t.palette).length, 29);
  assert.deepEqual(t.story.chapters, ['cover', 'opening', 'roots', 'register', 'closing']);
  assert.equal(t.copy.roots.line.other, '{n} generations before {featured}');
  assert.throws(() => { t.art = 'watercolour'; }, /Cannot assign to read only property/);
});

test('the paper-cut palette is exactly the 29 tokens the approved frames were rendered from', () => {
  assert.equal(PAPERCUT_PALETTE_KEYS.length, 29);
  assert.equal(new Set(PAPERCUT_PALETTE_KEYS).size, 29);
  assert.deepEqual(PAPERCUT_PALETTE_KEYS, [
    'paper', 'paperDeep', 'card', 'ink', 'inkSoft', 'night', 'deep', 'glow', 'dusk', 'gold', 'flame',
    'brass', 'marigold', 'saffron', 'sindoor', 'rani', 'peacock', 'indigo', 'leaf', 'leafDeep',
    'stone', 'clay', 'skin', 'silver', 'sky', 'wash', 'haze', 'dayHaze', 'dayMid',
  ]);
});

test('format 1 keeps its own 22 palette tokens, untouched by format 2', () => {
  assert.equal(PALETTE_KEYS.length, 22);
  assert.deepEqual(ROLE_FONT_KEYS, ['book_display', 'book_text', 'book_strong']);
  assert.ok(FONT_KEYS.includes(HAND_FONT_KEY), 'the release embeds the hand font');
  assert.ok(!ROLE_FONT_KEYS.includes(HAND_FONT_KEY), 'the hand font is not a format-1 role');
});

test('a missing palette token is refused', () => {
  const bad = papercutTemplate();
  delete bad.palette.dayMid;
  assert.throws(() => validateTemplate(bad), /template:.*palette\.dayMid/);
});

test('a palette token that is not #rrggbb is refused', () => {
  const bad = papercutTemplate({ palette: { ...papercutPalette, gold: 'gold' } });
  assert.throws(() => validateTemplate(bad), /template:.*palette\.gold/);
});

test('an unknown palette token is refused', () => {
  const bad = papercutTemplate({ palette: { ...papercutPalette, extra: '#000000' } });
  assert.throws(() => validateTemplate(bad), /unknown palette key "extra"/);
});

for (const chapter of REQUIRED_CHAPTERS) {
  test(`a story missing "${chapter}" is refused`, () => {
    const bad = papercutTemplate();
    bad.story = { chapters: bad.story.chapters.filter((c) => c !== chapter) };
    // dropping one of these four may also drop it from `copy`'s allowed chapters, which is fine -
    // the failure we want is the missing-chapter one, so keep copy limited to what remains.
    bad.copy = Object.fromEntries(Object.entries(bad.copy).filter(([k]) => bad.story.chapters.includes(k)));
    assert.throws(() => validateTemplate(bad), new RegExp(`story.chapters must include "${chapter}"`));
  });
}

test('a chapter listed twice is refused', () => {
  const bad = papercutTemplate();
  bad.story = { chapters: [...bad.story.chapters, 'opening'] };
  assert.throws(() => validateTemplate(bad), /a chapter is listed twice/);
});

test('an unknown placeholder is refused, in copy and in the cover alike', () => {
  assert.throws(() => validateTemplate(papercutTemplate({ cover: { ...papercutTemplate().cover, subtitle: 'from the {count} family' } })), /unknown placeholder "\{count\}"/);
  const bad = papercutTemplate();
  bad.copy.opening.line = '{featured-first} has {count} cousins.';
  assert.throws(() => validateTemplate(bad), /unknown placeholder "\{count\}"/);
});

test('every known placeholder is accepted', () => {
  const t = papercutTemplate();
  t.copy.opening.line = '{featured} {featured-first} {family} {from-family} {n} {year}';
  assert.doesNotThrow(() => validateTemplate(t));
});

test('copy naming a chapter not in story.chapters is refused', () => {
  const bad = papercutTemplate();
  bad.copy.legacy = { title: 'Legacy', line: 'x' };
  assert.throws(() => validateTemplate(bad), /copy names a chapter "legacy" not in story.chapters/);
});

test('an unknown top-level key is refused', () => {
  const bad = papercutTemplate({ script: 'alert(1)' });
  assert.throws(() => validateTemplate(bad), /unknown key "script"/);
});

test('markup in copy is refused, same as format 1', () => {
  const bad = papercutTemplate();
  bad.copy.opening.title = '<b>Opening</b>';
  assert.throws(() => validateTemplate(bad), /template:/);
});

test('an id that is not lower-case letters, digits and hyphens is refused', () => {
  assert.throws(() => validateTemplate(papercutTemplate({ id: 'Diwali Story' })), /id must be lower-case/);
});

test('an art kind other than papercut is refused - it is the only one format 2 knows today', () => {
  assert.throws(() => validateTemplate(papercutTemplate({ art: 'watercolour' })), /art must be one of papercut/);
});

test('a font role missing, or not one of the release\'s files, is refused', () => {
  const noHand = papercutTemplate();
  delete noHand.fonts.hand;
  assert.throws(() => validateTemplate(noHand), /font for "hand"/);
  assert.throws(() => validateTemplate(papercutTemplate({ fonts: { ...papercutTemplate().fonts, hand: 'book_display' } })), /font for "hand" must be one of book_hand/);
});

test('a format this app does not know, newer than 1 or 2, is refused before any key is looked at', () => {
  assert.throws(() => validateTemplate({ format: 3, id: 'x' }), /format 3 - this app reads format 1 or 2/);
});

test('a format-1 document draws a format-2 storybook, rather than being refused by name', async () => {
  // Until #258 built the last archetypes this refused by name, on purpose, so a half-built
  // composer could not print a book with holes in it. The document's own format is not the
  // template's: a plain `.ftree` is what every storybook is drawn from.
  const { composeBook } = await import('./compose.js');
  const doc = { format: 1, people: [{ id: 'a', name: 'Asha' }] };
  const book = composeBook(doc, { now: '2026-09-21' }, papercutTemplate());
  assert.equal(book.format, 2);
  assert.ok(book.pages.length > 0, 'a storybook with somebody in it draws pages');
});

test('a broken placeholder is refused, not printed with its braces', () => {
  for (const line of ['from the {family', 'from the family}', '{{n}} lamps', '{n}}']) {
    const t = papercutTemplate();
    t.cover.subtitle = line;
    assert.throws(() => validateTemplate(t), /unmatched/, line);
  }
});

test('Kalam is only ever the hand role, in either format', async () => {
  const heirloom = JSON.parse(await import('node:fs').then((fs) => fs.readFileSync(new URL('./templates/heirloom.json', import.meta.url), 'utf8')));
  assert.throws(() => validateTemplate({ ...heirloom, fonts: { ...heirloom.fonts, display: 'book_hand' } }), /font for "display"/);
  const t = papercutTemplate();
  t.fonts.text = 'book_hand';
  assert.throws(() => validateTemplate(t), /font for "text"/);
});
