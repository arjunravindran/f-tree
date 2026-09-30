/*
 * The chart's layout (#315).
 *
 * Invariants rather than coordinates. What a reader needs from this page is that everybody is on it,
 * that no card sits on top of another, that every line goes from the right card to the right card,
 * and that the page is the size of the family rather than a sheet with the family in the middle of
 * it - and each of those can be stated exactly without freezing a number that a spacing change would
 * have to update by hand. `golden.txt` is what freezes the bytes.
 *
 * The tight-bounds test below is the one that matters most: it is what a "fixed canvas, fixed
 * spacing" implementation fails. Note that `layoutArchive`'s own `width`/`height` are *not* tight -
 * they are the on-screen renderer's canvas and run up to 30% larger - so using them would fail it
 * too.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { composeBook } from './compose.js';
import { validateBook, MIN_PAGE, MAX_PAGE } from './format.js';
import { readFamily } from './family.js';
import { chartLayout, CHART_PAD } from './chart.js';
import { BOOK_FIXTURES, NOW, loadFixture } from './qa/book-fixtures.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATE = JSON.parse(readFileSync(path.join(here, 'templates/chart.json'), 'utf8'));
const EPS = 0.05;

/** The families the shapes below are named for, and every other fixture as a sweep. */
const SHAPES = {
  tiny: 'one-person',
  small: 'remarriage',
  medium: 'sample',
  deep: 'devanagari',
  wide: 'story-twelve-siblings',
  large: 'large',
  huge: 'story-large',
  unlinked: 'story-unlinked',
  none: 'empty',
};

async function chartOf(fixture) {
  const doc = await loadFixture(fixture);
  const options = { now: NOW };
  const family = readFamily(doc, options);
  const L = chartLayout(family);
  const cards = L.layout.nodes.map((n) => ({
    id: n.id, level: n.level, group: n.group,
    x: L.tx(n.x), y: L.ty(n.y), w: L.M.NODE_W * L.scale, h: L.M.NODE_H * L.scale,
  }));
  return { doc, family, L, cards, book: composeBook(doc, options, TEMPLATE) };
}

const inside = (px, py, c) => px >= c.x - EPS && px <= c.x + c.w + EPS && py >= c.y - EPS && py <= c.y + c.h + EPS;
const area = (s) => s.w * s.h;

test('every person in the family is on the chart, exactly once', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { family, cards, book } = await chartOf(fixture);
    const placed = cards.map((c) => c.id);
    assert.equal(new Set(placed).size, placed.length, `${shape}: somebody is drawn twice`);
    assert.deepEqual([...placed].sort(), family.people.map((p) => p.id).sort(),
      `${shape}: the chart and the family disagree about who is in it`);
    // Including the people no link reaches: layoutArchive packs them onto shelves of their own, so
    // the chart has no "elsewhere" list and needs none.
    for (const p of family.people.filter((q) => q.gen === null)) {
      assert.ok(placed.includes(p.id), `${shape}: ${p.id} is joined to nobody and was left off`);
    }
    const names = JSON.stringify(book);
    for (const p of family.people.filter((q) => q.name)) {
      assert.ok(names.includes(JSON.stringify(p.name).slice(1, -1)), `${shape}: ${p.name} is placed but not named`);
    }
  }
});

test('no two cards overlap', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { cards } = await chartOf(fixture);
    // Sorted by x so the sweep only compares cards whose columns can actually meet.
    const sorted = [...cards].sort((a, b) => a.x - b.x);
    for (let i = 0; i < sorted.length; i += 1) {
      for (let j = i + 1; j < sorted.length && sorted[j].x < sorted[i].x + sorted[i].w - EPS; j += 1) {
        const a = sorted[i], b = sorted[j];
        const over = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
        assert.ok(over <= EPS, `${shape}: ${a.id} and ${b.id} overlap by ${over.toFixed(2)} pt`);
      }
    }
  }
});

test('every card is on the page, inside its margins', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { L, cards } = await chartOf(fixture);
    for (const c of cards) {
      assert.ok(c.x >= CHART_PAD.left - EPS, `${shape}: ${c.id} is left of the margin`);
      assert.ok(c.y >= CHART_PAD.top - EPS, `${shape}: ${c.id} is above the margin`);
      assert.ok(c.x + c.w <= L.size.w - CHART_PAD.right + EPS, `${shape}: ${c.id} runs past the right margin`);
      assert.ok(c.y + c.h <= L.size.h - CHART_PAD.bottom + EPS, `${shape}: ${c.id} runs past the foot`);
    }
  }
});

test('every connector reaches the cards it names', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { L, cards } = await chartOf(fixture);
    const byId = new Map(cards.map((c) => [c.id, c]));
    let checked = 0;
    for (const link of L.drawn) {
      const ends = [];
      for (const [ax, ay, bx, by] of link.segments) ends.push([L.tx(ax), L.ty(ay)], [L.tx(bx), L.ty(by)]);

      // A couple's or a sibling pair's line ends on both of their cards.
      for (const id of [link.a, link.b].filter(Boolean)) {
        const c = byId.get(id);
        if (!c) continue;
        assert.ok(ends.some(([px, py]) => inside(px, py, c)), `${shape}: a connector names ${id} but never reaches it`);
        checked += 1;
      }
      // A descent ends on every child it names, and leaves from its parents' own edge.
      for (const id of link.childIds ?? []) {
        const c = byId.get(id);
        if (!c) continue;
        assert.ok(ends.some(([px, py]) => inside(px, py, c)), `${shape}: a descent names child ${id} but never reaches it`);
        checked += 1;
      }
      if (link.parents?.length) {
        const edge = Math.max(...link.parents.map((id) => (byId.get(id) ? byId.get(id).x + byId.get(id).w : -Infinity)));
        assert.ok(ends.some(([px]) => Math.abs(px - edge) <= EPS),
          `${shape}: a descent from ${link.parents.join('+')} does not start at their edge`);
        checked += 1;
      }
    }
    if (cards.length > 1) assert.ok(checked > 0, `${shape}: a family of ${cards.length} drew no connector to check`);
  }
});

test('generations stay in order along the page, shelf by shelf', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { L, cards } = await chartOf(fixture);
    /*
     * Per shelf, not across the whole page. A family with unconnected branches is packed onto
     * shelves and each shelf starts its own generations over, so two people at "level 0" on
     * different shelves have no reason to share a column - and asserting that they do fails on the
     * sample family, which has two.
     */
    for (const group of new Set(cards.map((c) => c.group))) {
      /*
       * Only the shelves that are families. `layoutArchive` packs the people no link reaches into a
       * block of their own (`groups[i].kind === 'isolated'`), and they are all at one level because
       * they have no generation to be at - so a column is not a thing they could be in. They are
       * still on the page, and the test above proves it.
       */
      if (L.layout.groups?.[group]?.kind !== 'family') continue;
      const here = cards.filter((c) => c.group === group);
      const span = new Map();
      for (const c of here) {
        const s = span.get(c.level) ?? { min: Infinity, max: -Infinity };
        span.set(c.level, { min: Math.min(s.min, c.x), max: Math.max(s.max, c.x + c.w) });
      }
      const levels = [...span.entries()].sort((a, b) => a[0] - b[0]);
      for (let i = 1; i < levels.length; i += 1) {
        assert.ok(levels[i][1].min >= levels[i - 1][1].max - EPS,
          `${shape}: shelf ${group} draws generation ${levels[i][0]} over generation ${levels[i - 1][0]}`);
      }
      // Everyone in one generation of one shelf shares a column, which is what makes it a column.
      for (const [level, s] of levels) {
        const xs = here.filter((c) => c.level === level).map((c) => c.x);
        assert.ok(Math.max(...xs) - Math.min(...xs) <= EPS, `${shape}: generation ${level} of shelf ${group} is not one column (${s.min})`);
      }
    }
  }
});

test('a name is set to fit its card, and never under the floor', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { L, book } = await chartOf(fixture);
    const inner = (L.M.NODE_W - 20) * L.scale;
    const texts = book.pages[0].items.filter((i) => i.t === 'text');
    assert.ok(texts.length || shape === 'none', `${shape}: no text at all`);
    for (const item of texts) {
      assert.ok(item.size >= 8 * L.scale - EPS, `${shape}: "${item.s}" is set at ${item.size} pt`);
      // `w` is the width the composer measured the line to fit; a painter shrinks to it, never past.
      if (item.size <= 13 * L.scale + EPS) assert.ok(item.w <= inner + EPS, `${shape}: "${item.s}" is given ${item.w} pt in a ${inner} pt card`);
    }
  }
});

test('the page is the tree plus its margins, and nothing more', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { L } = await chartOf(fixture);
    const want = {
      w: Math.max(MIN_PAGE, Math.ceil(L.tree.w * L.scale + CHART_PAD.left + CHART_PAD.right)),
      h: Math.max(MIN_PAGE, Math.ceil(L.tree.h * L.scale + CHART_PAD.top + CHART_PAD.bottom)),
    };
    assert.deepEqual(L.size, want, `${shape}: the page is not the tree's own size`);
    // No more than a point of rounding, plus whatever the MIN_PAGE floor had to add for a family too
    // small to make a page on its own.
    const floored = want.w === MIN_PAGE || want.h === MIN_PAGE;
    if (!floored) {
      assert.ok(L.size.w - (L.tree.w * L.scale + CHART_PAD.left + CHART_PAD.right) < 1, `${shape}: slack across`);
      assert.ok(L.size.h - (L.tree.h * L.scale + CHART_PAD.top + CHART_PAD.bottom) < 1, `${shape}: slack down`);
    }
    assert.ok(Number.isSafeInteger(L.size.w) && Number.isSafeInteger(L.size.h), `${shape}: the page is not whole points`);
  }
});

test('the page grows with the family, and a wide generation grows the page down', async () => {
  const of = {};
  for (const [shape, fixture] of Object.entries(SHAPES)) of[shape] = (await chartOf(fixture)).L;

  // A bigger family is a bigger page. This is the test a fixed canvas could not pass.
  const ladder = ['none', 'tiny', 'small', 'medium', 'large'];
  for (let i = 1; i < ladder.length; i += 1) {
    assert.ok(area(of[ladder[i]].size) > area(of[ladder[i - 1]].size),
      `${ladder[i]} does not make a larger page than ${ladder[i - 1]}`);
  }
  // A small family does not get a page it would be lost on: one person fits in a fifth of A4.
  assert.ok(area(of.tiny.size) < 595 * 842 * 0.2, `one person took ${of.tiny.size.w}x${of.tiny.size.h}`);
  assert.ok(of.none.size.w === MIN_PAGE && of.none.size.h === MIN_PAGE, 'a family of nobody gets the smallest page there is');

  /*
   * Generations run across the page, so a family that is deep grows it across, and a generation with
   * many people in it grows it down. `wide` is twelve siblings in two generations; `deep` is five
   * generations of about the same number of people.
   */
  assert.ok(of.wide.size.h > of.wide.size.w * 2, `twelve siblings made a ${of.wide.size.w}x${of.wide.size.h} page`);
  assert.ok(of.deep.size.w > of.wide.size.w, 'five generations should run further across than two');
  assert.ok(of.huge.size.h > of.huge.size.w * 2, '200 people over 6 generations should run down, not across');
});

test('a page stays inside what a PDF can hold, and is scaled only when it must be', async () => {
  for (const [shape, fixture] of Object.entries(SHAPES)) {
    const { L, book } = await chartOf(fixture);
    assert.deepEqual(validateBook(book), [], `${shape}: the book does not validate`);
    assert.ok(L.size.w <= MAX_PAGE && L.size.h <= MAX_PAGE, `${shape}: ${L.size.w}x${L.size.h} is past the limit`);
    // Every family we have fits at 1:1 in this orientation, 200 people included. The scale is a
    // guard, not a habit: if this ever starts failing, a real family got past 14400 pt and the
    // guard is what kept it printable.
    assert.equal(L.scale, 1, `${shape}: was scaled to ${L.scale}`);
  }
});

test('a tree far past the page limit is scaled down uniformly rather than refused', async () => {
  // Built rather than fixtured: nothing in `fixtures/` is large enough to reach 14400 pt, and the
  // guard should not be the one path with no test on it. A chain of generations runs across.
  const people = [];
  const relationships = [];
  for (let i = 0; i < 90; i += 1) {
    people.push({ id: `p${i}`, name: `Person Number ${i}`, birthDate: String(1900 + i) });
    if (i) relationships.push({ id: `r${i}`, from: `p${i - 1}`, to: `p${i}`, type: 'PARENT', subtype: null });
  }
  const doc = { format: 'f-tree', version: 1, people, relationships };
  const family = readFamily(doc, { now: NOW });
  const L = chartLayout(family);
  assert.ok(L.scale < 1, `90 generations should have needed scaling, got ${L.scale}`);
  assert.ok(L.size.w <= MAX_PAGE && L.size.h <= MAX_PAGE, `${L.size.w}x${L.size.h} is still past the limit`);
  /*
   * Uniform, stated on the tree itself rather than on the page: this family is 90 generations of one
   * person, so its height is floored up to MIN_PAGE and the page's own ratio says nothing about
   * whether the tree was squashed. A card keeping its shape does.
   */
  const cards = L.layout.nodes.map((n) => ({ x: L.tx(n.x), y: L.ty(n.y) }));
  const cardRatio = (L.M.NODE_W * L.scale) / (L.M.NODE_H * L.scale);
  assert.ok(Math.abs(cardRatio - L.M.NODE_W / L.M.NODE_H) < 1e-9, 'a card changed shape');
  const across = Math.max(...cards.map((c) => c.x)) - Math.min(...cards.map((c) => c.x));
  assert.ok(Math.abs(across - (L.tree.w - L.M.NODE_W) * L.scale) < 1, 'the tree was not scaled by the scale it reports');
  assert.ok(across <= MAX_PAGE - CHART_PAD.left - CHART_PAD.right + 1, 'the scaled tree still does not fit');
  const book = composeBook(doc, { now: NOW }, TEMPLATE);
  assert.deepEqual(validateBook(book), [], 'a scaled chart must still be a valid book');
  assert.deepEqual(book.size, L.size, 'the book took a different page from the layout');
});

test('the same family gives the same chart, every time', async () => {
  for (const fixture of ['sample', 'large', 'story-large']) {
    const doc = await loadFixture(fixture);
    const once = JSON.stringify(composeBook(doc, { now: NOW }, TEMPLATE));
    assert.equal(JSON.stringify(composeBook(doc, { now: NOW }, TEMPLATE)), once, `${fixture}: composing twice differed`);
    assert.equal(JSON.stringify(composeBook(structuredClone(doc), { now: NOW }, TEMPLATE)), once, `${fixture}: a copy of the document differed`);
    // Key order in the document must not reach the page.
    const shuffled = { ...doc, people: [...doc.people].reverse() };
    const again = composeBook(shuffled, { now: NOW }, TEMPLATE);
    assert.equal(again.pages[0].items.length, JSON.parse(once).pages[0].items.length,
      `${fixture}: reversing the document changed how much is drawn`);
  }
});

test('every fixture we have draws a chart that validates', async () => {
  for (const name of Object.keys(BOOK_FIXTURES)) {
    const book = composeBook(await loadFixture(name), { now: NOW }, TEMPLATE);
    assert.deepEqual(validateBook(book), [], name);
    assert.equal(book.pages.length, 1, `${name}: a chart is one page`);
  }
});
