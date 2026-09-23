/*
 * The storybook's closing pages (#258): our lane, in numbers, the register, still to be found, and
 * the legacy page back to the featured person.
 *
 * The three checks this file exists for, in the issue's own words, are the register's completeness
 * across every fixture, the accuracy of its page references against the plan's final numbers, and
 * the promise that no living person's age reaches the numbers page. Each of them replaces a
 * guarantee the old Diwali `generations`/`find` pages made, so each is written to fail loudly:
 * every one of them was checked to fail against the page deliberately broken for it.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { composeWithPages } from '../compose.js';
import { PAGE } from '../format.js';
import { measure } from '../text.js';
import { METRICS } from '../metrics/index.js';
import { readFamily } from '../family.js';
import { validateTemplate } from '../template.js';
import { withStubs } from '../qa/stub-pages.mjs';
import { STORY_TEMPLATE } from '../qa/story-template.mjs';
import { BOOK_FIXTURES, NOW, loadFixture } from '../qa/book-fixtures.mjs';
import { noLivingAge } from '../qa/invariants.mjs';
import { kinOf, CIRCLES } from './kin.js';
import { planStory, DENSITY } from './plan.js';
import { resolveFeatured } from './featured.js';
import { countInCircle, numberFact } from './copy.js';
import { FIGURES, figuresFor, generationsBehind } from './pages/lists.js';
import { ROW, SECTION_TITLES } from './pages/parts/register-rows.js';
import { SAFE, lifeDates } from './pages/parts/furniture.js';

/** Every fixture the QA harness runs the book over, and the storybook's own among them. */
const EVERY = Object.keys(BOOK_FIXTURES);
const STORY = EVERY.filter((f) => f.startsWith('story-'));

/** One fixture, composed as a storybook: the book, its report, and what the plan decided. */
async function book(fixture, options = {}) {
  const doc = await loadFixture(fixture);
  const family = readFamily(doc, { now: NOW, ...options });
  const kin = kinOf(family, resolveFeatured(family, options), { words: options.words });
  const plan = planStory(kin, validateTemplate(STORY_TEMPLATE), family);
  return { doc, family, kin, plan, ...composeWithPages(doc, { now: NOW, ...options }, STORY_TEMPLATE, withStubs()) };
}

const pagesOfKind = (report, archetype) => report.pages.filter((p) => p.archetype === archetype);
const linesOn = (report, page, kind) => report.textBoxes.filter((b) => b.page === page && (kind === undefined || b.kind === kind));

/* ------------------------------------------------------------------ the register: completeness */

/*
 * The completeness guarantee. `everyoneShown` (qa/invariants.mjs) says everyone in scope is
 * *somewhere*; this says something stronger and more particular, because it is the promise the
 * register alone carries: everyone in scope is on a REGISTER page, so a reader who cannot find
 * somebody in the story can always find them in the list.
 */
test('the register lists everyone in scope, on every fixture', async () => {
  for (const fixture of EVERY) {
    const { family, report, plan } = await book(fixture);
    const listed = new Set(pagesOfKind(report, 'register').flatMap((p) => p.people));
    const inScope = family.people.map((p) => p.id);
    for (const id of inScope) assert.ok(listed.has(id), `${fixture}: ${id} is in scope and on no register page`);
    assert.equal(listed.size, inScope.length, `${fixture}: the register lists somebody who is not in scope`);
    // And the register is never trimmed by the story-page cap, however long it runs.
    const rows = plan.pages.filter((p) => p.archetype === 'register').reduce((n, p) => n + p.people.length + p.groups.length, 0);
    assert.ok(rows >= inScope.length, `${fixture}: the register has fewer rows than people`);
  }
});

test('a register page names and portrays every person the plan put on it', async () => {
  for (const fixture of STORY) {
    const { report } = await book(fixture);
    for (const page of pagesOfKind(report, 'register')) {
      for (const id of page.people) {
        assert.ok(report.shown[id]?.includes(page.page), `${fixture} page ${page.page}: ${id} was planned onto it and never drawn`);
      }
      assert.ok(page.people.length <= DENSITY.register, `${fixture} page ${page.page}: ${page.people.length} people`);
    }
  }
});

/* ------------------------------------------------------------------ the register: page references */

/*
 * Every page reference points at the page that person is really on. The numbers drawn on a
 * register page are exactly its `caption` lines - nothing else on that page is one - so this
 * compares what was printed against `plan.pagesOf`, and then follows each number to the page it
 * names and checks the report says that person was drawn there.
 */
test('every register page reference is the plan\'s own number, and leads to that person', async () => {
  for (const fixture of EVERY) {
    const { report, plan } = await book(fixture);
    for (const page of pagesOfKind(report, 'register')) {
      const printed = linesOn(report, page.page, 'caption').map((b) => b.s).sort();
      const expected = page.people.map((id) => plan.pagesOf.get(id)?.[0]).filter((n) => n !== undefined).map(String).sort();
      assert.deepEqual(printed, expected, `${fixture} page ${page.page}: the references printed are not the plan's`);
      for (const id of page.people) {
        const ref = plan.pagesOf.get(id)?.[0];
        if (ref === undefined) continue;
        assert.ok(report.shown[id]?.includes(ref), `${fixture}: ${id}'s reference points at page ${ref}, which does not show them`);
        assert.notEqual(report.pages[ref - 1].archetype, 'register', `${fixture}: ${id}'s reference points at the register itself`);
      }
    }
  }
});

test('somebody the story pages had no room for is listed with no reference at all', async () => {
  // `large` is 180 people against a 28-page cap: most of them are only ever on the register.
  const { report, plan } = await book('large');
  assert.ok(plan.registerOnly.length, 'the `large` fixture was expected to overflow the story-page cap');
  const refs = pagesOfKind(report, 'register').flatMap((p) => linesOn(report, p.page, 'caption').map((b) => b.s));
  const rows = pagesOfKind(report, 'register').reduce((n, p) => n + p.people.length, 0);
  assert.equal(rows - refs.length, plan.registerOnly.length, 'a reference was printed for somebody on no story page');
});

/* ------------------------------------------------------------------ the register: 48 rows a page */

/*
 * The plan paginates the register at 48 rows a page, counting a row for each section heading, and
 * this page has to be able to hold them. Checked against the real advance tables through the
 * report's own text boxes - which are measured from `metrics/` - rather than against an assumed
 * line height: if `book_strong` were ever remetricked taller, a page would silently run off the
 * paper, and the plan would go on believing 48 fit.
 */
test('a full register page fits the paper at the real font metrics', async () => {
  const { report, plan } = await book('story-large');
  // A page is full at 48 ROWS - its people plus a row for each section heading - not 48 people.
  const full = plan.pages.find((p) => p.archetype === 'register' && p.people.length + p.groups.length === DENSITY.register);
  assert.ok(full, 'story-large was expected to fill at least one register page');
  const columns = new Map();
  for (const b of linesOn(report, full.pageNo).filter((b) => b.kind !== 'folio')) {
    const key = b.x < PAGE.w / 2 ? 'left' : 'right';
    columns.set(key, [...(columns.get(key) ?? []), b]);
  }
  for (const [side, boxes] of columns) {
    boxes.sort((a, b) => a.y - b.y);
    assert.ok(boxes[boxes.length - 1].y + boxes[boxes.length - 1].h <= SAFE.bottom,
      `the ${side} column runs past the text-safe area`);
    for (let i = 1; i < boxes.length; i++) {
      const gap = boxes[i].y - (boxes[i - 1].y + boxes[i - 1].h);
      // Lines on one row (a name and its dates) share a baseline and overlap in y, not in x.
      if (gap < 0) assert.ok(boxes[i].x >= boxes[i - 1].x + boxes[i - 1].w || boxes[i - 1].x >= boxes[i].x + boxes[i].w,
        `the ${side} column's "${boxes[i - 1].s}" and "${boxes[i].s}" collide`);
    }
  }
  // The row pitch itself: a name's ink band at its own metrics has to sit inside one row.
  const ink = measure('Shyam Lal', METRICS[STORY_TEMPLATE.fonts.strong], 9.6);
  assert.ok(ink > 0, 'the strong face has no advance table');
  const names = linesOn(report, full.pageNo, 'name');
  for (const b of names) assert.ok(b.h < ROW, `a name's ink band is ${b.h} pt, which does not fit a ${ROW} pt row`);
});

test('every circle kin.js can put somebody in has a register heading', () => {
  for (const c of CIRCLES) assert.equal(typeof SECTION_TITLES[c], 'string', `the "${c}" circle has no heading`);
});

/* ------------------------------------------------------------------ in numbers */

test('every figure on the numbers page is a kin circle\'s own count', async () => {
  for (const fixture of STORY) {
    const { family, kin } = await book(fixture);
    for (const { line } of figuresFor(family, kin)) {
      const f = FIGURES.find((g) => numberFact(family, kin, countInCircle(kin, g.circle, g.role ?? null), g.noun) === line);
      assert.ok(f, `${fixture}: "${line}" is not any circle's count`);
    }
  }
});

test('a count of nobody is never printed as a fact', async () => {
  const { family, kin } = await book('story-leaf');
  for (const { line } of figuresFor(family, kin)) assert.ok(!/\bzero\b|\bno\b/.test(line), `"${line}" states an absence as a fact`);
});

/*
 * No living person's age, on the page most likely to reach for one. `noLivingAge` is the harness's
 * own check; this runs it over the numbers pages alone so a failure names this page rather than
 * being lost among a whole book's.
 */
test('the numbers page never gives a living person an age', async () => {
  for (const fixture of EVERY) {
    const { family, book: b, report } = await book(fixture);
    const numbers = new Set(pagesOfKind(report, 'numbers').map((p) => p.page));
    if (!numbers.size) continue;
    const only = { ...report, textBoxes: report.textBoxes.filter((x) => numbers.has(x.page)) };
    assert.deepEqual(noLivingAge({ family, book: b, report: only, year: 2026 }), [], `${fixture}: the numbers page gives somebody an age`);
  }
});

/* ------------------------------------------------------------------ our lane */

test('the lane keeps the design system\'s four houses of at most eight names', async () => {
  for (const fixture of EVERY) {
    const { report, plan } = await book(fixture);
    for (const page of plan.pages.filter((p) => p.archetype === 'lane')) {
      assert.ok(page.groups.length <= DENSITY.houses, `${fixture} page ${page.pageNo}: ${page.groups.length} houses`);
      for (const house of page.groups) assert.ok(house.people.length <= DENSITY.house, `${fixture} page ${page.pageNo}: a house of ${house.people.length}`);
      for (const id of page.people) assert.ok(report.shown[id]?.includes(page.pageNo), `${fixture}: ${id} is on lane page ${page.pageNo} and was not drawn`);
    }
  }
});

test('a lane page at the cap draws all four houses and all their names', async () => {
  const { report, plan } = await book('story-large');
  const full = plan.pages.find((p) => p.archetype === 'lane' && p.groups.length === DENSITY.houses);
  assert.ok(full, 'story-large was expected to fill a lane page');
  const names = linesOn(report, full.pageNo, 'name').length;
  // A name for everybody on the page, plus up to one nameplate a house.
  assert.ok(names >= full.people.length, `${names} name lines for ${full.people.length} people`);
  assert.ok(names <= full.people.length + DENSITY.houses, 'the lane drew more names than it has people and plates');
});

/* ------------------------------------------------------------------ still to be found */

/** Every `use` of one of `refs` drawn directly on a page (a symbol's own insides are not). */
function usesOn(page, refs) {
  let n = 0;
  const walk = (items) => {
    for (const it of items) {
      if (it.t === 'group') walk(it.items);
      else if (it.t === 'use' && refs.includes(it.ref)) n++;
    }
  };
  walk(page.items);
  return n;
}

test('still to be found keeps exactly one lamp for each name, and never one more', async () => {
  for (const fixture of EVERY) {
    const { book: b, report } = await book(fixture);
    for (const page of pagesOfKind(report, 'still-to-be-found')) {
      const lamps = usesOn(b.pages[page.page - 1], ['pc-aala', 'pc-lamp-unknown']);
      assert.equal(lamps, page.people.length, `${fixture} page ${page.page}: ${lamps} lamps for ${page.people.length} names`);
      for (const id of page.people) assert.ok(report.shown[id]?.includes(page.page), `${fixture}: ${id} has a lamp and no name`);
    }
  }
});

test('the lamps lit are the number the page says are lit', async () => {
  for (const fixture of ['story-unknown-names', 'story-large']) {
    const { report, plan } = await book(fixture);
    const pages = pagesOfKind(report, 'still-to-be-found');
    if (!pages.length) continue;
    const lit = plan.pages.reduce((n, p) => n + (p.archetype === 'still-to-be-found' ? p.people.length : 0), 0);
    const said = linesOn(report, pages[0].page, 'body').map((b) => b.s).join(' ');
    assert.ok(new RegExp(`\\b${lit}\\b|\\bOne lamp\\b`).test(said), `${fixture}: the page says "${said}" and lights ${lit} lamps`);
  }
});

test('a name the record lost is placed by its relation to the featured person', async () => {
  const { report } = await book('story-unknown-names');
  const page = pagesOfKind(report, 'still-to-be-found')[0];
  assert.ok(page, 'story-unknown-names was expected to have a still-to-be-found page');
  const said = linesOn(report, page.page).map((b) => b.s);
  assert.ok(said.some((s) => /’s/.test(s)), 'nobody on the page is named through a relative');
  assert.ok(!said.some((s) => /unknown/i.test(s)), 'the page printed the word "unknown"');
});

/* ------------------------------------------------------------------ legacy */

test('legacy lights one lamp for every generation behind the featured person, and one for them', async () => {
  for (const fixture of STORY) {
    const { book: b, report, kin } = await book(fixture);
    for (const page of pagesOfKind(report, 'legacy')) {
      const lamps = usesOn(b.pages[page.page - 1], ['pc-diya']);
      assert.equal(lamps, generationsBehind(kin) + 1, `${fixture}: ${lamps} lamps for ${generationsBehind(kin)} generations and one featured person`);
    }
  }
});

test('generationsBehind counts the line kin.js recorded, not a fixed depth', async () => {
  const alone = await book('story-unlinked');
  assert.equal(alone.kin.circles.parents.length, 0, 'story-unlinked was expected to join F to nobody above them');
  assert.equal(generationsBehind(alone.kin), 0, 'a featured person with nobody above them has no generations behind');

  const leaf = await book('story-leaf');
  assert.equal(generationsBehind(leaf.kin), 1, 'parents alone are one generation');

  // story-large knows both sets of grandparents as well, which is one generation further back.
  const large = await book('story-large');
  assert.ok(large.kin.circles.grandparents.length, 'story-large was expected to know its grandparents');
  assert.equal(generationsBehind(large.kin), 2);
  const deepest = Math.min(...[...large.kin.people.values()].map((e) => e.gen ?? 0));
  assert.equal(generationsBehind(large.kin), -deepest, 'every generation on the line up from F is counted');
});

test('the legacy page carries one handwritten note, and a reader\'s own note where there is one', async () => {
  const plain = await book('story-notes');
  const noted = await book('story-notes', { notes: true });
  const page = pagesOfKind(plain.report, 'legacy')[0];
  assert.ok(page, 'story-notes was expected to have a legacy page');
  const words = (r) => linesOn(r, page.page, 'body').map((b) => b.s).join(' ');
  assert.ok(words(plain.report).length, 'the legacy page printed no note at all');
  const own = noted.family.byId.get(noted.kin.featured)?.note;
  if (own) assert.notEqual(words(noted.report), words(plain.report), 'the reader asked for notes and got the template line');
});

/* ------------------------------------------------------------------ small things, precisely */

test('the dates a list column prints are years, and never a living person\'s full birth date', () => {
  assert.equal(lifeDates({ deceased: true, by: 1935, dy: 1999 }), '1935 – 1999');
  assert.equal(lifeDates({ deceased: true, by: null, dy: 1999 }), 'd. 1999');
  assert.equal(lifeDates({ deceased: true, by: 1935, dy: null }), 'b. 1935');
  assert.equal(lifeDates({ deceased: true, by: null, dy: null }), 'Late');
  assert.equal(lifeDates({ deceased: false, by: 1990 }), 'b. 1990');
  assert.equal(lifeDates({ deceased: false, by: null }), '');
  assert.equal(lifeDates(null), '');
});
