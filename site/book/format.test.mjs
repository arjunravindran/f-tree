/*
 * Book format 2, held to the one rule that keeps it cheap: a book declares the lowest format that
 * draws it. Heirloom uses nothing new, so it stays format 1 and its bytes never move - book.test.mjs
 * guards that with the goldens, and validateBook now refuses any book whose declared format and
 * drawn format disagree, so every composed book proves the rule as a side effect.
 *
 * golden/format2-conformance.json is the other half: a book that draws with everything format 2
 * adds, written by hand and read by both painters. #246 paints it in Kotlin against this file, so
 * what it exercises is the contract - clips on plain, transformed and dimmed groups, symbols of
 * every shape drawn many times, turned and dimmed, and a silhouette of an open string, a ring and
 * two overlapping shapes, which is what proves the silhouette rule. conformanceFeatures() below
 * checks the file still covers all of it.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { FORMAT, FORMAT_MAX, MAX_SYMBOL_DEPTH, MAX_EXPANDED_ITEMS, MIN_PAGE, MAX_PAGE, PAGE, formatOf, validateBook, group, use } from './format.js';
import { paintPage } from './svg.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (p) => readFileSync(path.join(here, p), 'utf8');

const CONFORMANCE = JSON.parse(read('golden/format2-conformance.json'));

/** A book with just enough in it to be valid, so a test can say one thing at a time. */
const book = (over = {}) => ({
  format: FORMAT,
  template: 'test', title: 'T', fileName: 'T.pdf',
  size: { w: 595, h: 842 },
  fonts: { text: 'book_text' },
  defs: {}, photos: [],
  pages: [{ label: 'One', items: [] }],
  ...over,
});

const page = (...items) => [{ label: 'One', items }];
const MARK = { mark: { items: [
  { t: 'path', d: 'M0 0 L10 0 L10 10 Z', fill: '#f2b84b', stroke: '#b9822a', sw: 0.8 },
  { t: 'circle', cx: 5, cy: 5, r: 2, fill: '#ffe7a6' },
] } };
const paint = (b, i = 0) => paintPage(b, i, { photo: () => 'data:image/jpeg;base64,', font: (k) => k });

test('a book that draws with nothing new is format 1', () => {
  const heirloom = JSON.parse(read('golden/sample-heirloom.json'));
  assert.equal(heirloom.format, FORMAT);
  assert.equal(formatOf(heirloom), FORMAT);
  assert.deepEqual(validateBook(heirloom), []);
  assert.equal(formatOf(book({ pages: page({ t: 'group', items: [{ t: 'rect', x: 0, y: 0, w: 1, h: 1 }] }) })), FORMAT);
});

test('a clip, a use or a symbol is what makes a book format 2', () => {
  assert.equal(formatOf(book({ pages: page(group([], { clip: 'M0 0 L1 0 L1 1 Z' })) })), FORMAT_MAX);
  assert.equal(formatOf(book({ symbols: MARK, pages: page(use('mark')) })), FORMAT_MAX);
  assert.equal(formatOf(book({ symbols: {} })), FORMAT_MAX);
  // Nested: a clip four groups down still counts, or a book could hide one and claim format 1.
  assert.equal(formatOf(book({ pages: page(group([group([group([use('mark')])])])) })), FORMAT_MAX);
});

test('validateBook reads both formats, and no others', () => {
  assert.deepEqual(validateBook(book()), []);
  assert.deepEqual(validateBook(book({ format: FORMAT_MAX, symbols: MARK, pages: page(use('mark')) })), []);
  assert.deepEqual(validateBook(CONFORMANCE), []);
  for (const format of [0, 3, '2', undefined]) {
    assert.ok(validateBook(book({ format })).some((p) => p.startsWith('format ')), `format ${format}`);
  }
});

test('a page is whole points inside the bounds, and A4 is only the default', () => {
  // Until #313 this was an equality against 595x842. The chart template sizes its page to the
  // family, so what is checked now is the bound -- and the bound is what a painter can actually
  // emit, not a taste in paper.
  assert.deepEqual(validateBook(book()), [], 'A4 still validates');
  assert.deepEqual(validateBook(book({ size: { w: 1722, h: 7421 } })), [], 'a tall chart validates');
  assert.deepEqual(validateBook(book({ size: { w: MIN_PAGE, h: MAX_PAGE } })), [], 'the bounds themselves are in');

  // A fractional page would round differently in each painter -- Android's PdfDocument takes whole
  // points -- so it is refused rather than silently truncated.
  for (const size of [{ w: 595.5, h: 842 }, { w: 595, h: 842.01 }, { w: '595', h: 842 }, { w: NaN, h: 842 }]) {
    assert.ok(validateBook(book({ size })).some((p) => /not a whole number of points/.test(p)), JSON.stringify(size));
  }
  // Outside what a PDF can hold, or so small it could only be an arithmetic slip in the composer.
  for (const size of [{ w: 0, h: 842 }, { w: -595, h: 842 }, { w: 595, h: MAX_PAGE + 1 }, { w: MIN_PAGE - 1, h: 842 }]) {
    assert.ok(validateBook(book({ size })).some((p) => /outside/.test(p)), JSON.stringify(size));
  }
  for (const size of [undefined, null, 595, 'A4']) {
    assert.deepEqual(validateBook(book({ size })).filter((p) => p === 'no page size'), ['no page size'], JSON.stringify(size));
  }
  assert.deepEqual(PAGE, { w: 595, h: 842 }, 'A4 stays the default every other book is');
});

test('a book declares the lowest format that draws it, and neither less nor more', () => {
  const low = validateBook(book({ format: FORMAT, symbols: MARK, pages: page(use('mark')) }));
  assert.ok(low.some((p) => /draws as format 2/.test(p)), low.join('; '));
  const high = validateBook(book({ format: FORMAT_MAX }));
  assert.ok(high.some((p) => /draws as format 1/.test(p)), high.join('; '));
});

test('a clip a painter could not follow is refused, not drawn as an empty page', () => {
  const clipped = (clip) => validateBook(book({ format: FORMAT_MAX, pages: page({ t: 'group', clip, items: [] }) }));
  assert.deepEqual(clipped('M0 0 L10 0 L10 10 Z'), []);
  assert.deepEqual(clipped('M0 0 H10 V10 H0 Z'), []);
  assert.deepEqual(clipped('M0 0 L10 0 10 10Z'), [], 'a repeated set of arguments is still one command');
  assert.deepEqual(clipped('M0 0 Q5 -5 10 0 C10 5 5 10 0 10 Z'), []);
  for (const bad of ['', '   ', 'm0 0 l10 0 z', 'M0 0 A5 5 0 0 1 10 10', { d: 'M0 0' }, 42,
    'L10 10 Z', 'e', 'M0 0', 'M0 0 Z', 'M0 0 L10 0 Z', 'M0 0 L10 0 L10 Z', 'M0 0 H Z', 'M0 0 C1 1 2 2 Z',
    'M0 0 L10 0 L10 10 Z 5', 'M0 0 L1e999 0 L0 1 Z', 'M0 0 L10 0 L10 10 X', 'M0 0 L+10 0 L10 10 Z', 'M0 0 L10 0 L10 10 Z" onload="x']) {
    assert.ok(clipped(bad).some((p) => p.endsWith('clip path data')), JSON.stringify(bad));
  }
});

test('a use names a symbol the book carries', () => {
  const problems = validateBook(book({ format: FORMAT_MAX, symbols: MARK, pages: page(use('missing')) }));
  assert.deepEqual(problems, ['page 1 item 0: unknown symbol missing']);
  assert.ok(validateBook(book({ format: FORMAT_MAX, pages: page(use('mark')) })).some((p) => /unknown symbol/.test(p)));
  assert.ok(validateBook(book({ format: FORMAT_MAX, symbols: MARK, pages: page({ t: 'use', ref: 'mark', tf: [1, 0, 0, 1, 2] }) }))
    .some((p) => p.endsWith('transform')));
});

/*
 * Symbols may be built from symbols - the art compiler (#247) makes a courtyard out of lamps - so
 * nesting is allowed, and a cycle is refused here rather than left to hang a painter.
 */
test('a symbol may use another symbol, but never itself', () => {
  const symbols = {
    diya: { items: [{ t: 'path', d: 'M0 0 L4 0 L2 4 Z', fill: '#f2b84b' }] },
    pair: { items: [use('diya'), use('diya', { tf: [1, 0, 0, 1, 6, 0] })] },
    row: { items: [group([use('pair')], { tf: [1, 0, 0, 1, 0, 0] })] },
  };
  assert.deepEqual(validateBook(book({ format: FORMAT_MAX, symbols, pages: page(use('row')) })), []);

  const loop = { a: { items: [use('b')] }, b: { items: [use('a')] } };
  assert.ok(validateBook(book({ format: FORMAT_MAX, symbols: loop, pages: page(use('a')) }))
    .some((p) => /used through itself/.test(p)));
  const self = { a: { items: [group([use('a')])] } };
  assert.ok(validateBook(book({ format: FORMAT_MAX, symbols: self, pages: page(use('a')) }))
    .some((p) => /used through itself/.test(p)));

});

/** A chain of `n` symbols, s1 using s2 ... using sn, and a page that uses s1. */
const chain = (n) => book({
  format: FORMAT_MAX,
  symbols: Object.fromEntries(Array.from({ length: n }, (_, i) =>
    [`s${i + 1}`, { items: i + 1 < n ? [use(`s${i + 2}`)] : [{ t: 'path', d: 'M0 0 L1 1 L0 1 Z', fill: '#f2b84b' }] }])),
  pages: page(use('s1')),
});

test('validateBook and the painter agree on how deep symbols may nest: four draws, five does not', () => {
  assert.equal(MAX_SYMBOL_DEPTH, 4);
  const four = chain(4);
  assert.deepEqual(validateBook(four), []);
  assert.ok(paint(four).includes('M0 0 L1 1 L0 1 Z'));
  const five = chain(5);
  assert.ok(validateBook(five).some((p) => /nested 5 deep, more than 4/.test(p)), validateBook(five).join('; '));
  assert.throws(() => paint(five), /more than 4 deep/);
});

test('a symbol shared by many is walked once, not once per use', () => {
  // Five levels of fifty uses each: re-walking a shared symbol per use is 50^4 walks.
  const reads = new Map();
  const counted = (id, items) => ({ get items() { reads.set(id, (reads.get(id) ?? 0) + 1); return items; } });
  const symbols = { leaf: counted('leaf', [{ t: 'circle', cx: 0, cy: 0, r: 1, fill: '#f2b84b' }]) };
  for (const [id, next] of [['l3', 'leaf'], ['l2', 'l3'], ['l1', 'l2']]) {
    symbols[id] = counted(id, Array.from({ length: 50 }, () => use(next)));
  }
  validateBook(book({ format: FORMAT_MAX, symbols, pages: page(use('l1')) }));
  for (const [id, n] of reads) assert.ok(n <= 4, `symbol ${id} was walked ${n} times`);
});

test('a page may not multiply past the expansion cap', () => {
  const blob = { items: Array.from({ length: 199 }, () => ({ t: 'circle', cx: 0, cy: 0, r: 1, fill: '#f2b84b' })) };
  // Each use of blob draws 1 + 199 = 200 items, so 100 uses is exactly the cap and 101 is over.
  const at = (n) => validateBook(book({ format: FORMAT_MAX, symbols: { blob }, pages: page(...Array.from({ length: n }, () => use('blob'))) }));
  assert.equal(MAX_EXPANDED_ITEMS, 20000);
  assert.deepEqual(at(100), []);
  assert.ok(at(101).some((p) => /page 1: draws 20200 items once expanded/.test(p)), at(101).join('; '));
  // And the multiplication that makes the cap necessary: four levels of forty is 2.6 million.
  const symbols = { leaf: { items: [{ t: 'circle', cx: 0, cy: 0, r: 1, fill: '#f2b84b' }] } };
  for (const [id, next] of [['l3', 'leaf'], ['l2', 'l3'], ['l1', 'l2']]) symbols[id] = { items: Array.from({ length: 40 }, () => use(next)) };
  assert.ok(validateBook(book({ format: FORMAT_MAX, symbols, pages: page(use('l1')) })).some((p) => /once expanded/.test(p)));
});

test('symbols is a map of at least one symbol, or absent', () => {
  for (const symbols of [null, [], [MARK.mark], 'mark', {}]) {
    const problems = validateBook(book({ format: FORMAT_MAX, symbols, pages: page() }));
    assert.ok(problems.some((p) => /^symbols is (not a map|empty)$/.test(p)), `${JSON.stringify(symbols)}: ${problems.join('; ')}`);
  }
});

test('a ref names the book\'s own symbol or gradient, never one the prototype lends it', () => {
  for (const ref of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) {
    const viaUse = validateBook(book({ format: FORMAT_MAX, symbols: MARK, pages: page({ t: 'use', ref }) }));
    assert.ok(viaUse.includes(`page 1 item 0: unknown symbol ${ref}`), `${ref}: ${viaUse.join('; ')}`);
    const viaFill = validateBook(book({ pages: page({ t: 'rect', x: 0, y: 0, w: 1, h: 1, fill: { ref } }) }));
    assert.ok(viaFill.includes(`page 1 item 0: unknown gradient ${ref}`), `${ref}: ${viaFill.join('; ')}`);
    assert.throws(() => paint(book({ format: FORMAT_MAX, symbols: MARK, pages: page({ t: 'use', ref }) })), /unknown symbol/);
    assert.throws(() => paint(book({ pages: page({ t: 'rect', x: 0, y: 0, w: 1, h: 1, fill: { ref } }) })), /unknown gradient/);
  }
});

test('a symbol holds art, not words and not somebody\'s photograph', () => {
  const words = { s: { items: [{ t: 'text', x: 0, y: 0, s: 'Aangan', font: 'text', size: 10, fill: '#2a1a33' }] } };
  assert.ok(validateBook(book({ format: FORMAT_MAX, symbols: words, pages: page(use('s')) }))
    .some((p) => p === 'symbol s item 0: type text'));
  const photo = { s: { items: [{ t: 'image', id: 'p1', x: 0, y: 0, w: 10, h: 10, clip: 'rect' }] } };
  assert.ok(validateBook(book({ format: FORMAT_MAX, symbols: photo, pages: page(use('s')) }))
    .some((p) => p === 'symbol s item 0: type image'));
  assert.ok(validateBook(book({ format: FORMAT_MAX, symbols: { s: {} }, pages: page(use('s')) }))
    .some((p) => p === 'symbol s: no items'));
});

/*
 * The fixture is the contract #246 is held to, so what it covers is checked here, feature by
 * feature, by walking the file. Dropping a case from it fails this test by name.
 */
function conformanceFeatures(b) {
  const seen = new Set();
  const note = (on, name) => { if (on) seen.add(name); };
  const bounds = (it) => (it.t === 'circle' ? [it.cx - it.r, it.cy - it.r, it.cx + it.r, it.cy + it.r]
    : it.t === 'rect' ? [it.x, it.y, it.x + it.w, it.y + it.h] : null);
  const overlap = (p, q) => p && q && p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3];
  const flatten = (items) => items.flatMap((it) => (it.t === 'group' ? flatten(it.items)
    : it.t === 'use' ? flatten(b.symbols[it.ref].items) : [it]));
  const walk = (items, { inSymbol, inClip }) => items.forEach((it) => {
    if (it.t === 'group') {
      const clip = it.clip;
      note(clip !== undefined && it.items.some((c) => c.t === 'image'), 'a clipped photograph');
      note(clip !== undefined && it.tf, 'a clip on a group with a tf');
      note(clip !== undefined && it.op !== undefined, 'a clip and an op on one group');
      note(/H/.test(clip ?? ''), 'a clip using H');
      note(/V/.test(clip ?? ''), 'a clip using V');
      note(/C/.test(clip ?? ''), 'a clip using C');
      note(inSymbol && it.tf, 'a symbol holding a group with a tf');
      walk(it.items, { inSymbol, inClip: inClip || clip !== undefined });
      return;
    }
    if (inSymbol) {
      note(it.t === 'circle', 'a symbol holding a circle');
      note(it.t === 'rect' && it.r, 'a symbol holding a rounded rect');
      note(it.t === 'path' && it.rule === 'evenodd', 'a symbol holding an evenodd path');
      note(it.dash, 'a symbol holding a dashed stroke');
      note(it.fill?.ref, 'a symbol holding a gradient fill');
      note(it.t === 'use', 'a symbol using a symbol');
    }
    if (it.t !== 'use') return;
    note(inClip, 'a use inside a clipped group');
    note(it.tf, 'a use turned by a tf');
    note(it.fill === undefined && it.op !== undefined, 'a use dimmed by an op');
    if (it.fill !== undefined && it.op !== undefined) {
      const drawn = flatten(b.symbols[it.ref].items);
      note(drawn.some((d) => d.t === 'path' && d.stroke && d.fill === undefined && !/Z\s*$/.test(d.d) && /C|Q/.test(d.d)),
        'a dimmed silhouette of an open curved string');
      note(drawn.some((d) => ['circle', 'rect'].includes(d.t) && d.stroke && d.fill === undefined), 'a dimmed silhouette of a ring');
      const filled = drawn.filter((d) => d.fill !== undefined);
      note(filled.some((d, i) => filled.some((e, j) => i < j && overlap(bounds(d), bounds(e)))), 'a dimmed silhouette of overlapping shapes');
      note(b.symbols[it.ref].items.some((c) => c.t === 'use'), 'a silhouette through a nested use');
    }
  });
  b.pages.forEach((p) => walk(p.items, { inSymbol: false, inClip: false }));
  Object.values(b.symbols).forEach((sym) => walk(sym.items, { inSymbol: true, inClip: false }));
  const uses = new Map();
  const count = (items) => items.forEach((it) => {
    if (it.t === 'use') uses.set(it.ref, (uses.get(it.ref) ?? 0) + 1);
    if (it.t === 'group') count(it.items);
  });
  b.pages.forEach((p) => count(p.items));
  note([...uses.values()].some((n) => n >= 3), 'a symbol drawn many times');
  return seen;
}

test('the conformance book draws with everything format 2 adds', () => {
  assert.equal(CONFORMANCE.format, FORMAT_MAX);
  assert.equal(formatOf(CONFORMANCE), FORMAT_MAX, 'the fixture must declare the lowest format that draws it');
  assert.deepEqual(validateBook(CONFORMANCE), []);
  const seen = conformanceFeatures(CONFORMANCE);
  const wanted = [
    'a clipped photograph', 'a clip on a group with a tf', 'a clip and an op on one group',
    'a clip using H', 'a clip using V', 'a clip using C', 'a use inside a clipped group',
    'a symbol holding a circle', 'a symbol holding a rounded rect', 'a symbol holding a group with a tf',
    'a symbol holding an evenodd path', 'a symbol holding a dashed stroke', 'a symbol holding a gradient fill',
    'a symbol using a symbol', 'a symbol drawn many times', 'a use turned by a tf', 'a use dimmed by an op',
    'a dimmed silhouette of an open curved string', 'a dimmed silhouette of a ring',
    'a dimmed silhouette of overlapping shapes', 'a silhouette through a nested use',
  ];
  assert.deepEqual(wanted.filter((f) => !seen.has(f)), [], 'the conformance book no longer covers these');
  // The checklist itself must be able to fail: a book with nothing in it covers nothing.
  assert.equal(conformanceFeatures({ ...CONFORMANCE, pages: [{ label: 'x', items: [] }], symbols: {} }).size, 0);
});

test('the SVG painter expands every use, so pages joined into one file cannot collide', () => {
  const svg = CONFORMANCE.pages.map((_, i) => paint(CONFORMANCE, i)).join('');
  assert.ok(!/<use\b/.test(svg), 'a use survived into the print file');
  assert.ok(!/NaN|undefined|Infinity/.test(svg), 'the print file holds a non-number');
  assert.ok(/<clipPath id="[^"]+"><path d="M197.5 420/.test(svg), 'the arch is not a clipPath');
  const ids = [...svg.matchAll(/ id="([^"]+)"/g)].map((m) => m[1]);
  assert.equal(new Set(ids).size, ids.length, 'the joined pages repeat an id');
  for (const [, ref] of svg.matchAll(/url\(#([^)]+)\)/g)) assert.ok(ids.includes(ref), `#${ref} resolves to nothing`);
  // The lamp is authored once and stands in nine places across the two pages: two on the first
  // (the lamp and its shadow), and seven on the second, four of them through lamp-pair.
  const bowl = CONFORMANCE.symbols.diya.items[0].d;
  assert.equal(svg.split(`d="${bowl}"`).length - 1, 9);
});

/*
 * The silhouette rule, Ankit's decision of 2026-09-21: a paper shadow is the same shape, offset
 * (docs/book-design-system.md). Every fill and every stroke takes the use's colour; everything
 * else about the shape - stroke width, dash, cap, join, which items are filled at all, their own
 * opacities - is kept, and the use's op dims the silhouette once, as a whole.
 */
const SHADOWED = {
  art: { items: [
    { t: 'path', d: 'M0 0 C10 -8 20 8 30 0', stroke: '#b5562a', sw: 1.5, dash: [2, 1], cap: 'round', join: 'bevel' },
    { t: 'circle', cx: 40, cy: 0, r: 6, stroke: '#b9822a', sw: 2, op: 0.5 },
    { t: 'rect', x: 50, y: -5, w: 10, h: 10, r: 2, fill: { ref: 'band' } },
    { t: 'group', op: 0.7, items: [use('dot')] },
  ] },
  dot: { items: [{ t: 'circle', cx: 70, cy: 0, r: 3, fill: '#ffe7a6', stroke: '#f2b84b', sw: 0.5 }] },
};
const shadowBook = (u) => book({
  format: FORMAT_MAX,
  defs: { band: { type: 'linear', x1: 0, y1: 0, x2: 1, y2: 0, stops: [[0, '#17122e', 1], [1, '#3a2352', 1]] } },
  symbols: SHADOWED,
  pages: page(u),
});

test('a silhouette recolours every fill and every stroke, and keeps the rest of the shape', () => {
  const b = shadowBook(use('art', { fill: '#2a1a33', op: 0.4 }));
  assert.deepEqual(validateBook(b), []);
  const svg = paint(b);
  const colours = [...svg.matchAll(/(?:fill|stroke|stop-color)="(#[0-9a-f]{6})"/g)].map((m) => m[1]);
  assert.deepEqual([...new Set(colours)], ['#2a1a33'], svg);
  assert.ok(!/url\(#/.test(svg), 'a gradient survived into the silhouette');
  // The open string is still a dashed, round-capped, bevelled line of the same width, not a fill.
  assert.ok(svg.includes('<path d="M0 0 C10 -8 20 8 30 0" fill="none" stroke="#2a1a33" stroke-width="1.5" stroke-dasharray="2 1" stroke-linecap="round" stroke-linejoin="bevel"/>'), svg);
  // The ring stays a ring, and keeps its own opacity; the nested group keeps its opacity too.
  assert.ok(svg.includes('<circle cx="40" cy="0" r="6" fill="none" stroke="#2a1a33" stroke-width="2" opacity="0.5"/>'), svg);
  assert.ok(svg.includes('<g opacity="0.7">'), svg);
  // A fill-only item gains no stroke, and a nested use casts in the same colour.
  assert.ok(svg.includes('<rect x="50" y="-5" width="10" height="10" rx="2" fill="#2a1a33"/>'), svg);
  assert.ok(svg.includes('<circle cx="70" cy="0" r="3" fill="#2a1a33" stroke="#2a1a33" stroke-width="0.5"/>'), svg);
});

test('a silhouette is a solid colour, never a gradient', () => {
  const problems = validateBook(shadowBook(use('art', { fill: { ref: 'band' } })));
  assert.ok(problems.includes('page 1 item 0: a silhouette fill must be a #rrggbb colour'), problems.join('; '));
  assert.ok(validateBook(shadowBook(use('art', { fill: '#FFF' }))).length);
});

test('a use\'s op dims it once, as one layer, and never item by item', () => {
  for (const fill of ['#2a1a33', undefined]) {
    const svg = paint(shadowBook(use('art', { fill, op: 0.4, tf: [1, 0, 0, 1, 2, 3] })));
    assert.ok(svg.includes('<g transform="matrix(1 0 0 1 2 3)" opacity="0.4">'), `${fill}: ${svg}`);
    assert.equal(svg.split('opacity="0.4"').length - 1, 1, `${fill}: the use's op was pushed down to its items`);
  }
});

test('the conformance shadows are cast by the rule', () => {
  const shadows = CONFORMANCE.pages[0].items.filter((it) => it.t === 'use' && it.fill !== undefined);
  const svg = paint({ ...CONFORMANCE, pages: [{ label: 'Shadow', items: shadows }] });
  const colours = new Set([...svg.matchAll(/(?:fill|stroke)="(#[0-9a-f]{6})"/g)].map((m) => m[1]));
  assert.deepEqual([...colours], [...new Set(shadows.map((u) => u.fill))], svg);
  assert.ok(/stroke="#[0-9a-f]{6}" stroke-width/.test(svg), 'the symbol\'s strokes were dropped from its shadow');

  // Down through a nested use as well: a compound motif casts one colour, not a stack of drawings.
  const nested = book({
    format: FORMAT_MAX,
    symbols: { ...MARK, pair: { items: [use('mark'), use('mark', { tf: [1, 0, 0, 1, 12, 0] })] } },
    pages: page(use('pair', { fill: '#17122e', op: 0.35 })),
  });
  assert.deepEqual(validateBook(nested), []);
  const shadow = paint(nested);
  assert.equal(shadow.split('fill="#17122e"').length - 1, 4);
  assert.equal(shadow.split('stroke="#17122e" stroke-width="0.8"').length - 1, 2);
  assert.ok(!/#f2b84b|#ffe7a6|#b9822a/.test(shadow), 'the symbol\'s own colours leaked into its shadow');
});

test('the painter refuses a symbol it cannot find, rather than drawing a gap', () => {
  assert.throws(() => paint(book({ format: FORMAT_MAX, symbols: MARK, pages: page(use('missing')) })), /unknown symbol missing/);
});
