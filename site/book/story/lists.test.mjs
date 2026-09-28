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
import { noLivingAge, noTextInBusyArt } from '../qa/invariants.mjs';
import { kinOf, CIRCLES } from './kin.js';
import { planStory, DENSITY } from './plan.js';
import { resolveFeatured } from './featured.js';
import { countInCircle, numberFact } from './copy.js';
import { countWords } from '../blocks/words.js';
import { FIGURES, figuresFor, generationsBehind } from './pages/lists.js';
import { NAME_SIZE, ROW, SECTION_TITLES, duplicateRegisterNames, splitColumns } from './pages/parts/register-rows.js';
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
    // What was DRAWN, not what was planned: `ctx.show` is called by the row that prints a name, so
    // a register that quietly dropped a row would still be planned right and fail here.
    const on = new Set(pagesOfKind(report, 'register').map((p) => p.page));
    const listed = new Set(Object.keys(report.shown).filter((id) => report.shown[id].some((n) => on.has(n))));
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
  // The row pitch itself, against the faces' own tables rather than an assumed line height: a
  // name's full line height (ascender to descender, as the face declares them) has to sit inside
  // one row, and so does the dates column beside it. Remetrick `book_strong` taller and this is
  // what fails, rather than 48 rows silently running off the foot of the paper.
  for (const [role, size] of [['strong', NAME_SIZE], ['text', NAME_SIZE]]) {
    const face = METRICS[STORY_TEMPLATE.fonts[role]];
    const height = (size * (face.ascender - face.descender)) / face.unitsPerEm;
    assert.ok(height < ROW, `${face.name} at ${size} pt needs ${height.toFixed(2)} pt, over the ${ROW} pt row`);
  }
  // And a name really does print no wider than the column it was fitted to.
  for (const b of linesOn(report, full.pageNo, 'name')) {
    assert.ok(b.w <= measure(b.s, METRICS[STORY_TEMPLATE.fonts[b.font]], b.size) + 0.01, `"${b.s}" prints wider than it measures`);
  }
});

test('every circle kin.js can put somebody in has a register heading', () => {
  for (const c of CIRCLES) assert.equal(typeof SECTION_TITLES[c], 'string', `the "${c}" circle has no heading`);
});

/* ------------------------------------------------------------------ in numbers */

test('every figure on the numbers page is a kin circle\'s own count', async () => {
  for (const fixture of STORY) {
    const { family, kin } = await book(fixture);
    // `numberFact` varies its construction by `index` (round 2, finding 23: six sentences that all
    // began "Sneha Sharma has...") - a figure's line has to be reproduced at the same position
    // `figuresFor` drew it in, not just from any index's construction.
    figuresFor(family, kin).forEach(({ line }, i) => {
      const f = FIGURES.find((g) => numberFact(family, kin, countInCircle(kin, g.circle, g.role ?? null), g.noun, i) === line);
      assert.ok(f, `${fixture}: "${line}" is not any circle's count`);
    });
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
    // The count is spelled out (round 2, finding 19: a numeral in a hand-set line reads as a
    // system message), never printed as a bare digit, so this checks for the word `countWords`
    // gives it - "One lamp" for one, "{Count-words} lamps" otherwise - not the numeral itself.
    const word = lit === 1 ? 'One lamp' : countWords(lit, true);
    assert.ok(new RegExp(`\\b${word}\\b`, 'i').test(said), `${fixture}: the page says "${said}" and lights ${lit} lamps`);
    assert.ok(!/\d/.test(said), `${fixture}: "${said}" prints a numeral in a hand-set line`);
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

/* ------------------------------------------------------------------ round 2: the design critic */

/*
 * `story-large` has three real "Swati Sharma"s (site/book/fixtures/story-large.json) - the fixture
 * the design critic's round 1 found two of them sharing a page reference on the register.
 */
test('two register rows sharing a name, a page reference and the same dates are told apart on the register', async () => {
  const { report } = await book('story-large');
  const pages = new Set(pagesOfKind(report, 'register').map((p) => p.page));
  // A register page is two columns, and both use the same set of `y` baselines, so a row is
  // grouped by page and column first. Within a column, a row's name, dates and page reference do
  // not share one exact `y` (the text and strong faces set their baseline a little differently at
  // the same nominal row, by well under a point) - so rows are found by clustering: a new row
  // starts whenever `y` jumps by more than a few points, far short of `ROW`'s 21 pt pitch.
  const byColumn = new Map();
  for (const b of report.textBoxes) {
    if (!pages.has(b.page) || !['name', 'caption', 'lifespan'].includes(b.kind)) continue;
    const key = `${b.page}:${b.x < PAGE.w / 2 ? 'l' : 'r'}`;
    byColumn.set(key, [...(byColumn.get(key) ?? []), b]);
  }
  const rows = [];
  for (const boxes of byColumn.values()) {
    boxes.sort((a, b) => a.y - b.y);
    let current = null;
    for (const b of boxes) {
      if (!current || b.y - current.y > 5) { current = { y: b.y }; rows.push(current); }
      if (b.kind === 'name') current.name = b.s;
      else if (b.kind === 'lifespan') current.dates = b.s;
      else if (/^\d+$/.test(b.s)) current.ref = b.s;
    }
  }
  // Grouped by what the row would say *without* the fix's own qualifier - the underlying identity
  // two same-named, same-dated, same-referenced people share - and then, within a group of more
  // than one, the *actual* printed name (qualifier included) has to be unique: the fix telling two
  // such rows apart with two different words is a pass, not a second collision.
  const groups = new Map();
  for (const { name, ref, dates } of rows) {
    if (!name) continue;
    const bare = name.replace(/\s*\([^()]*\)$/, '');
    const sig = `${bare}|${ref ?? 'none'}|${dates ?? ''}`;
    groups.set(sig, [...(groups.get(sig) ?? []), name]);
  }
  for (const [sig, names] of groups) {
    if (names.length < 2) continue;
    assert.equal(new Set(names).size, names.length, `${names.length} rows print "${sig}" and only ${new Set(names).size} distinct name(s) - a reader cannot tell them apart`);
  }
});

/*
 * `duplicateRegisterNames`'s own last resort: two people `kin.js` cannot join to the featured
 * person at all (so no kin word), in the same section (so the same fallback heading too), with the
 * same name and no dates - the one case none of the fixtures happens to reach, where a plain count
 * among themselves is the only thing left that is guaranteed to differ.
 */
test('two people with nothing else to tell them apart still get different qualifiers', () => {
  const family = { byId: new Map([
    ['a', { name: 'Sneha Sharma', by: null, dy: null, deceased: false }],
    ['b', { name: 'Sneha Sharma', by: null, dy: null, deceased: false }],
  ]) };
  const kin = {
    featured: 'f',
    people: new Map([['a', { circle: 'branches' }], ['b', { circle: 'branches' }]]),
    words: () => null, // kin.js could not join either of them to the featured person
  };
  const story = { kin, plan: { pagesOf: new Map() } };
  const q = duplicateRegisterNames({ family }, story);
  assert.equal(q.size, 2, 'both colliding rows should get a qualifier');
  assert.notEqual(q.get('a'), q.get('b'), 'two colliding rows got the same qualifier');
});

test('a living person still to be found reads "not yet placed in the tree", never a remembrance date', async () => {
  const { report, family } = await book('story-unknown-names');
  const page = pagesOfKind(report, 'still-to-be-found')[0];
  assert.ok(page, 'story-unknown-names was expected to have a still-to-be-found page');
  const livingUnnamed = family.people.filter((p) => !p.name && !p.deceased);
  assert.ok(livingUnnamed.length, 'story-unknown-names was expected to have a living, unnamed person');
  const captions = linesOn(report, page.page, 'caption').map((b) => b.s);
  assert.ok(captions.includes('not yet placed in the tree'), `the page's captions were ${JSON.stringify(captions)}`);
  assert.ok(!captions.some((s) => /^b\.\s*\d{4}/.test(s)), 'a living person was given a birth-year caption beside a remembrance lamp');
});

test('still to be found never prints reading text over the art it marked busy', async () => {
  for (const fixture of ['story-large', 'story-unknown-names']) {
    const { report } = await book(fixture);
    const pages = new Set(pagesOfKind(report, 'still-to-be-found').map((p) => p.page));
    const only = { ...report, textBoxes: report.textBoxes.filter((b) => pages.has(b.page)) };
    assert.deepEqual(noTextInBusyArt({ report: only }), [], `${fixture}: text sits over the rangoli or another busy zone`);
  }
});

/*
 * The exact shape the design critic's round 1 found on `story-large` page 19: a section of two
 * ("Brothers and sisters") lands right on the middle, so the naive `Math.ceil(rows.length / 2)`
 * break puts the heading and its first name at the foot of column one and the heading again,
 * "continued", over the second name alone in column two - two headings for two people.
 */
test('a column break never leaves a section heading with fewer than two rows on either side', () => {
  const rows = [
    { heading: 'self' }, { id: 'a' },
    { heading: 'parents' }, { id: 'b' }, { id: 'c' },
    { heading: 'siblings' }, { id: 'd' }, { id: 'e' },
    { heading: 'children' }, { id: 'f' }, { id: 'g' }, { id: 'h' },
  ];
  const [first, second] = splitColumns(rows);
  assert.equal(first.length + second.length, rows.length, 'a row was dropped or duplicated by the split');
  for (const column of [first, second]) {
    column.forEach((row, i) => {
      if (!row.heading) return;
      const after = column.length - i - 1;
      // A heading may legally be the very last row of a column (the whole section moves to the
      // next column, `registerColumns`' own "continued" heading covers it) - only a heading with
      // *some* rows after it, but fewer than two, is the orphan this rule refuses.
      assert.ok(after === 0 || after >= 2, `a "${row.heading}" heading has only ${after} row(s) after it in its column`);
    });
  }
});

test('a two-person section is never split across the column break', () => {
  // "Brothers and sisters" - one heading, two names - lands with `Math.ceil` right on the middle
  // of this 6-row page, which used to leave one name each side of the break.
  const rows = [
    { heading: 'self' }, { id: 'a' }, { id: 'b' },
    { heading: 'siblings' }, { id: 'c' }, { id: 'd' },
  ];
  const [first, second] = splitColumns(rows);
  const has = (column, id) => column.some((r) => r.id === id);
  assert.equal(has(first, 'c'), has(first, 'd'), '"siblings" was split with one name on each side of the break');
});
