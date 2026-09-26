/*
 * The hero page archetypes (#256): the cover, the opening, the waiting page, a portrait hero and
 * the closing.
 *
 * They are composed through `composeWithPages` with `qa/stub-pages.mjs` standing in for the
 * archetypes #257 and #258 own, so these run - and fail honestly - before those land, and keep
 * running unchanged once they have (`withStubs` prefers a real archetype over its stub).
 *
 * What is checked here is what the issue asks for and what no other suite can see: one lamp per
 * person on the cover and no more, the cover legible at a chat app's 150 px, the opening's
 * sentence inside the page whatever the names are, the mala only on somebody the record says has
 * died, no invented face on a person with no photograph, and both variants of every archetype.
 * The shared layout rules (sizes, collisions, density, the 10 MB budget) are `qa/invariants.mjs`'s
 * own, run here over the pages these five drew.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { composeWithPages } from '../compose.js';
import { validateBook, PAGE } from '../format.js';
import { LIBRARY } from '../art/index.js';
import { readFamily, byKey } from '../family.js';
import { validateTemplate } from '../template.js';
import { withStubs } from '../qa/stub-pages.mjs';
import { STORY_TEMPLATE } from '../qa/story-template.mjs';
import { BOOK_FIXTURES, NOW, loadFixture } from '../qa/book-fixtures.mjs';
import { INVARIANTS } from '../qa/invariants.mjs';
import { kinOf } from './kin.js';
import { planStory, VARIANTS } from './plan.js';
import { resolveFeatured } from './featured.js';
import { openingLine } from './copy.js';
import { PAGES, lampRows, MIN_LAMP } from './pages/hero.js';
import { SAFE } from './pages/parts/page.js';

const FIXTURES = Object.keys(BOOK_FIXTURES);
const MINE = Object.keys(PAGES);
const YEAR = Number(NOW.slice(0, 4));
/** Every lamp drawing a page may light a person with. */
const LAMPS = ['pc-diya', 'pc-diya-small', 'pc-diya-unknown', 'pc-lamp-unknown'];

const compose = async (name, options = {}, pages = withStubs()) => {
  const doc = await loadFixture(name);
  const family = readFamily(doc, { now: NOW, ...options });
  const { book, report } = composeWithPages(doc, { now: NOW, ...options }, STORY_TEMPLATE, pages);
  const kin = kinOf(family, resolveFeatured(family, options));
  const plan = planStory(kin, validateTemplate(STORY_TEMPLATE), family);
  return { doc, family, kin, plan, book, report };
};

/** Which pages of a composed book these five archetypes drew. */
const heroPages = (report) => report.pages.filter((p) => MINE.includes(p.archetype));
const pageOf = (report, archetype) => report.pages.find((p) => p.archetype === archetype);

/** Every `use` on a page, with every group walked: what the page actually placed. */
function uses(items, out = []) {
  for (const it of items ?? []) {
    if (it.t === 'use') out.push(it.ref);
    if (it.t === 'group') uses(it.items, out);
  }
  return out;
}

const texts = (report, page) => report.textBoxes.filter((b) => b.page === page);
const said = (report, page) => texts(report, page).map((b) => b.s).join(' | ');

/**
 * The three featured people the plan's verification asks for: whoever the book picks on its own,
 * the eldest named person, and a named leaf. The same choices `invariants.test.mjs` makes.
 */
function featuredChoices(doc) {
  const family = readFamily(doc, { now: NOW });
  const named = family.people.filter((p) => p.name);
  const byId = (a, b) => byKey(a.id, b.id);
  const eldest = [...named].sort((a, b) => ((a.by ?? Infinity) - (b.by ?? Infinity)) || byId(a, b))[0];
  const leaf = [...named.filter((p) => !family.childrenOf(p.id).length)]
    .sort((a, b) => ((b.by ?? 0) - (a.by ?? 0)) || byId(a, b))[0];
  return [undefined, eldest?.id, leaf?.id].filter((id, i) => i === 0 || id);
}

/* ------------------------------------------------------------------ every fixture, every F */

test('every hero page is a valid format-2 page, for every fixture and every featured person', async () => {
  for (const name of FIXTURES) {
    const doc = await loadFixture(name);
    for (const featured of featuredChoices(doc)) {
      const { book, report, plan } = await compose(name, featured ? { featured } : {});
      const where = `${name} / ${featured ?? 'most connected'}`;
      assert.deepEqual(validateBook(book), [], `${where}: not a valid Book`);
      assert.equal(book.format, 2, `${where}: a storybook that places art is format 2`);
      const drawn = heroPages(report);
      assert.ok(drawn.length >= 2, `${where}: a book always has a cover and a closing`);
      for (const p of drawn) {
        const planned = plan.pages[p.page - 1];
        assert.equal(p.variant, planned.variant, `${where} page ${p.page}: drew another variant than the plan's`);
        assert.deepEqual([...p.people], [...planned.people], `${where} page ${p.page}: drew other people than the plan's`);
        assert.equal(p.density, planned.density, `${where} page ${p.page}: a density the plan did not set`);
        assert.ok(texts(report, p.page).every((b) => b.kind), `${where} page ${p.page}: a line did not say what kind it is`);
      }
    }
  }
});

test('the hero pages keep the book\'s layout invariants', async () => {
  for (const name of FIXTURES) {
    const { book, report, family } = await compose(name);
    const mine = new Set(heroPages(report).map((p) => p.page));
    const ctx = { book, report, family, scope: new Set(family.people.map((p) => p.id)), year: YEAR };
    const failures = [];
    for (const [rule, { check }] of Object.entries(INVARIANTS)) {
      for (const v of check(ctx)) {
        const at = /page (\d+)/.exec(v);
        // A violation on a stub page belongs to whichever issue replaces that stub.
        if (!at || mine.has(Number(at[1]))) failures.push(`${rule}: ${v}`);
      }
    }
    assert.deepEqual(failures, [], name);
  }
});

test('the same tree drawn twice is the same bytes', async () => {
  const a = await compose('story-eldest');
  const b = await compose('story-eldest');
  assert.equal(JSON.stringify(a.book), JSON.stringify(b.book));
});

/* ------------------------------------------------------------------ the cover */

test('the cover lights one lamp for each person in scope, and not one more', async () => {
  for (const name of FIXTURES) {
    const { book, kin } = await compose(name);
    const lit = uses(book.pages[0].items).filter((ref) => LAMPS.includes(ref));
    assert.equal(lit.length, kin.people.size, `${name}: ${lit.length} lamps for ${kin.people.size} people`);
  }
});

test('a lamp for a name nobody knows is the dashed one, and only those', async () => {
  const { book, family, kin } = await compose('story-unknown-names');
  const unnamed = [...kin.people.keys()].filter((id) => !family.byId.get(id)?.name).length;
  const lit = uses(book.pages[0].items).filter((ref) => LAMPS.includes(ref));
  assert.ok(unnamed > 0, 'the fixture has people whose names are lost');
  assert.equal(lit.filter((r) => r === 'pc-diya-unknown').length, unnamed);
  assert.equal(lit.length - unnamed, kin.people.size - unnamed, 'everyone else is lit by an ordinary lamp');
});

test('no row of lamps overlaps itself, at any size of family', () => {
  const box = { x: 240, y: 514, w: 345, h: 216 };
  for (const n of [1, 2, 3, 7, 8, 12, 23, 47, 48, 49, 96, 150, 200, 400]) {
    const rows = lampRows(n, box);
    assert.equal(rows.reduce((t, r) => t + r.count, 0), n, `${n}: the rows hold somebody else's count`);
    assert.ok(rows.every((r) => r.count > 0), `${n}: an empty row`);
    for (const r of rows) {
      const spacing = r.count > 1 ? Math.abs(r.x2 - r.x1) / (r.count - 1) : Infinity;
      assert.ok(r.w <= spacing + 1e-9, `${n}: a lamp ${r.w} pt wide on a row spaced ${spacing}`);
      assert.ok(r.w >= MIN_LAMP, `${n}: a lamp only ${r.w.toFixed(1)} pt wide no longer reads as a lamp`);
      assert.ok(r.x1 >= box.x - 1e-9 && r.x2 <= box.x + box.w + 1e-9, `${n}: a row runs outside the lamps zone`);
      assert.ok(r.y >= box.y && r.y <= box.y + box.h, `${n}: a row sits outside the lamps zone`);
    }
  }
  assert.deepEqual(lampRows(0, box), [], 'nobody to light is no rows at all');
});

test('a cover with nobody to light does not claim a lamp for each of us', async () => {
  const { report, book } = await compose('story-empty');
  assert.equal(uses(book.pages[0].items).filter((r) => LAMPS.includes(r)).length, 0);
  assert.ok(!said(report, 1).includes('lamp'), said(report, 1));
  assert.ok(said(report, 1).includes(STORY_TEMPLATE.cover.greeting), 'the greeting is still there');
});

test('the cover is the family\'s: it names nobody, and reports nobody as shown', async () => {
  for (const name of ['story-large', 'story-eldest', 'story-tiny']) {
    const { report, family } = await compose(name);
    assert.equal(pageOf(report, 'cover').page, 1);
    for (const [id, pages] of Object.entries(report.shown)) assert.ok(!pages.includes(1), `${name}: the cover claims to show ${id}`);
    const words = said(report, 1);
    for (const p of family.people) if (p.name) assert.ok(!words.includes(p.name), `${name}: the cover prints ${p.name}`);
  }
});

test('the cover prints the family\'s own line, once', async () => {
  const { report, family } = await compose('story-eldest');
  const words = said(report, 1);
  assert.ok(family.title.startsWith('The ') && family.title.endsWith(' Family'), `the fixture's title is ${family.title}`);
  // "from the {family} family" over a title that is already "The Iyer Family" prints it twice.
  assert.ok(!/\bthe The\b/i.test(words), `the cover says the family's name twice: ${words}`);
  assert.ok(words.includes('from the Iyer family'), words);
});

test('the cover\'s greeting and family line stay large enough to read at 150 px', async () => {
  // A chat app shows the cover about 150 px wide, a quarter of A4's 595 pt (#240's approval gate).
  const SCALE = 150 / PAGE.w;
  for (const name of FIXTURES) {
    const { report, book } = await compose(name);
    const titles = texts(report, 1).filter((b) => b.kind === 'title');
    assert.equal(titles.length, 2, `${name}: the cover is a greeting and the family's line`);
    for (const b of titles) {
      assert.ok(b.size * SCALE >= 6.5, `${name}: ${b.s} prints at ${(b.size * SCALE).toFixed(1)} px on a phone`);
      assert.ok(b.x >= SAFE.x && b.x + b.w <= PAGE.w - SAFE.x, `${name}: ${b.s} runs outside the safe area`);
    }
    assert.equal(book.pages[0].label, STORY_TEMPLATE.cover.greeting);
  }
});

/* ------------------------------------------------------------------ the opening */

test('the opening says who the featured person is, in copy.js\'s own words', async () => {
  for (const name of ['story-eldest', 'story-large', 'story-devanagari', 'story-twelve-siblings']) {
    const { report, family, kin } = await compose(name);
    const page = pageOf(report, 'opening-hero');
    const sentence = openingLine(family, kin);
    assert.ok(sentence, `${name}: the fixture has an opening sentence`);
    // The sentence is broken into lines by the page, so it is read back the way it was written.
    const body = texts(report, page.page).filter((b) => b.kind === 'body').map((b) => b.s).join(' ');
    assert.equal(body, sentence, name);
    assert.deepEqual([...page.people], [kin.featured]);
  }
});

test('the opening\'s words stay inside the page, whatever the names are', async () => {
  for (const name of FIXTURES) {
    const doc = await loadFixture(name);
    for (const featured of featuredChoices(doc)) {
      const { report } = await compose(name, featured ? { featured } : {});
      const page = pageOf(report, 'opening-hero');
      if (!page) continue;
      for (const b of texts(report, page.page)) {
        if (b.kind === 'folio') continue;   // page furniture sits in the margin on purpose
        assert.ok(b.x >= SAFE.x - 0.5 && b.x + b.w <= PAGE.w - SAFE.x + 0.5, `${name}: "${b.s}" runs off the side`);
        assert.ok(b.y >= SAFE.y && b.y + b.h <= SAFE.y + SAFE.h, `${name}: "${b.s}" runs off the top or foot`);
      }
    }
  }
});

test('an opening that folds the chapters above it says the book begins here', async () => {
  const { report, plan } = await compose('one-person');
  const page = pageOf(report, 'opening-hero');
  assert.deepEqual([...plan.pages[page.page - 1].folds], ['roots', 'courtyards'], 'the fixture folds its roots in');
  assert.match(said(report, page.page), /is the first name this family remembers/);
});

/* ------------------------------------------------------------------ the waiting page */

test('a book with nobody to feature waits for its family, and invents no one', async () => {
  const { report, book } = await compose('story-empty');
  const page = pageOf(report, 'waiting');
  assert.equal(page.page, 2);
  assert.deepEqual([...page.people], []);
  assert.deepEqual(report.shown, {});
  const words = said(report, 2);
  assert.match(words, /waiting for its family/);
  assert.match(words, /Add the people you remember/);
  // The template's opening copy is written round a person this book has not got: filling it would
  // print "This is." (copy.js drops the placeholder, not the sentence round it).
  assert.ok(!words.includes('This is'), words);
  assert.ok(uses(book.pages[1].items).includes('pc-arch-jharokha'), 'the window is still there');
});

/* ------------------------------------------------------------------ a portrait hero */

/** Draws one archetype where another was planned, so any fixture can exercise it. */
const insteadOf = (planned, archetype, patch = {}) => withStubs({
  ...PAGES,
  [planned]: (ctx, page, story) => PAGES[archetype](ctx, { ...page, archetype, ...patch }, story),
});

test('the mala hangs on a departed person\'s frame, and on nobody else\'s', async () => {
  const dead = await compose('story-twelve-siblings');
  const page = pageOf(dead.report, 'portrait-hero');
  const people = page.people.map((id) => dead.family.byId.get(id));
  assert.ok(people.every((p) => p.deceased), 'the fixture\'s portrait hero is two departed people');
  const hung = uses(dead.book.pages[page.page - 1].items).filter((r) => r === 'pc-mala-departed');
  assert.equal(hung.length, people.length);

  const living = await compose('story-tiny');
  const lp = pageOf(living.report, 'portrait-hero');
  assert.ok(lp.people.every((id) => !living.family.byId.get(id).deceased), 'the fixture\'s portrait hero is living');
  assert.ok(!uses(living.book.pages[lp.page - 1].items).includes('pc-mala-departed'), 'a garland by a living person');
});

/** Where a `use` of `id` lands on the page, from its transform and the drawing's own viewBox. */
function whereUsed(items, id) {
  const found = [];
  const walk = (list) => {
    for (const it of list ?? []) {
      if (it.t === 'group') walk(it.items);
      if (it.t !== 'use' || it.ref !== id) continue;
      const [a, b, c, d, e, f] = it.tf ?? [1, 0, 0, 1, 0, 0];
      const [vx, vy, vw, vh] = LIBRARY.symbols[id.replace(/^pc-/, '')].vb;
      const xs = [], ys = [];
      for (const [x, y] of [[vx, vy], [vx + vw, vy], [vx, vy + vh], [vx + vw, vy + vh]]) {
        xs.push(a * x + c * y + e);
        ys.push(b * x + d * y + f);
      }
      found.push({ x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) });
    }
  };
  walk(items);
  return found;
}

test('a departed person\'s mala hangs on the frame, never across the name under it', async () => {
  const { report, book, family } = await compose('story-eldest');
  const page = pageOf(report, 'portrait-hero');
  assert.equal(page.people.filter((id) => family.byId.get(id).deceased).length, 1, 'the fixture has one departed parent');
  const [mala] = whereUsed(book.pages[page.page - 1].items, 'pc-mala-departed');
  assert.ok(mala, 'no mala on a departed person\'s frame');
  for (const b of texts(report, page.page)) {
    const over = Math.min(b.x + b.w, mala.x + mala.w) - Math.max(b.x, mala.x) > 0.5
      && Math.min(b.y + b.h, mala.y + mala.h) - Math.max(b.y, mala.y) > 0.5;
    assert.ok(!over, `the garland hangs across "${b.s}"`);
  }
});

test('a person with no photograph is never given a face, and one with no name is a lamp', async () => {
  const { book, family, report } = await compose('story-unknown-names', {}, insteadOf('gathering', 'portrait-hero', {
    variant: 'niche', density: 'hero',
  }));
  const page = report.pages.find((p) => p.archetype === 'portrait-hero');
  const placed = uses(book.pages[page.page - 1].items);
  const unnamed = page.people.filter((id) => !family.byId.get(id)?.name);
  assert.ok(unnamed.length, 'the fixture puts a person with no name on this page');
  assert.equal(placed.filter((r) => r === 'pc-lamp-unknown').length, unnamed.length, 'a name nobody knows is a lit lamp');
  // Nobody in these fixtures has a photograph, so every face on the page would be an invented one.
  assert.ok(family.people.every((p) => !p.photo));
  assert.ok(!placed.some((r) => r.startsWith('pc-avatar-') && unnamed.length === page.people.length), 'a face on a person with no record');
});

test('a note appears beside a portrait only when the reader asked for one', async () => {
  const noted = readFamily(await loadFixture('story-notes'), { now: NOW, notes: true }).people.find((p) => p.note);
  assert.ok(noted, 'the fixture has a note');
  const pages = insteadOf('gathering', 'portrait-hero', { variant: 'arch', density: 'hero', people: [noted.id] });
  const at = (r) => r.pages.find((p) => p.archetype === 'portrait-hero').page;
  const first = noted.note.split('\n')[0];
  const on = await compose('story-notes', { notes: true }, pages);
  assert.ok(said(on.report, at(on.report)).includes(first), 'the note is not on the page');
  const off = await compose('story-notes', {}, pages);
  assert.ok(!said(off.report, at(off.report)).includes(first), 'a note nobody asked for');
});

/* ------------------------------------------------------------------ the closing */

test('the closing carries the greeting, the call to action, the QR code and the credit', async () => {
  const { report, book } = await compose('story-eldest');
  const page = pageOf(report, 'closing');
  assert.equal(page.page, book.pages.length, 'the closing is the last page');
  const words = said(report, page.page);
  assert.ok(words.includes(STORY_TEMPLATE.cover.greeting), 'no greeting');
  assert.match(words, /Is someone missing\?/);
  assert.match(words, /Scan to get f-tree/);
  assert.match(words, /Made with f-tree/);
  // The code itself: one long path of dark modules, drawn where the scene keeps room for it.
  const qr = book.pages[page.page - 1].items.filter((it) => it.t === 'path' && it.d.length > 2000);
  assert.equal(qr.length, 1, 'the QR code is not on the page');
});

test('nothing on the closing page is drawn over the QR code', async () => {
  const { report, book } = await compose('story-eldest');
  const page = pageOf(report, 'closing');
  const items = book.pages[page.page - 1].items;
  const plate = items.find((it) => it.t === 'rect' && it.r !== undefined);
  assert.ok(plate, 'the QR sits on a plate');
  const over = (b) => Math.min(b.x + b.w, plate.x + plate.w) - Math.max(b.x, plate.x) > 0.5
    && Math.min(b.y + b.h, plate.y + plate.h) - Math.max(b.y, plate.y) > 0.5;
  for (const b of texts(report, page.page)) assert.ok(!over(b), `"${b.s}" prints across the QR code`);
  // The folio's own lamp is art, not text, and it sits in the same corner of the page.
  for (const b of whereUsed(items, 'pc-diya-small')) assert.ok(!over(b), 'the folio lamp sits on the QR code');
});

/* ------------------------------------------------------------------ the variants */

test('every hero archetype draws both of the variants plan.js lists for it', async () => {
  for (const archetype of MINE) {
    const fixture = archetype === 'waiting' ? 'story-empty' : 'story-eldest';
    const planned = archetype === 'portrait-hero' ? 'portrait-hero' : archetype;
    const seen = new Set();
    for (const variant of VARIANTS[archetype]) {
      const { book, report } = await compose(fixture, {}, insteadOf(planned, archetype, { variant }));
      const page = report.pages.find((p) => p.archetype === archetype && p.variant === variant);
      assert.ok(page, `${archetype}/${variant} did not draw`);
      assert.deepEqual(validateBook(book), [], `${archetype}/${variant}`);
      const drawn = JSON.stringify(book.pages[page.page - 1]);
      assert.ok(!seen.has(drawn), `${archetype}: ${variant} draws exactly what the last variant drew`);
      seen.add(drawn);
      assert.ok(texts(report, page.page).every((b) => b.kind), `${archetype}/${variant}: a line with no kind`);
    }
  }
});
