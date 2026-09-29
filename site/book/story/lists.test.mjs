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
import { PAGE, pathPoints } from '../format.js';
import { createArt } from '../art/draw.js';
import { LIBRARY } from '../art/index.js';
import { measure } from '../text.js';
import { METRICS } from '../metrics/index.js';
import { readFamily } from '../family.js';
import { validateTemplate } from '../template.js';
import { withStubs } from '../qa/stub-pages.mjs';
import { STORY_TEMPLATE } from '../qa/story-template.mjs';
import { BOOK_FIXTURES, NOW, loadFixture } from '../qa/book-fixtures.mjs';
import { noLivingAge, noTextInBusyArt, noTextOverlap } from '../qa/invariants.mjs';
import { kinOf, CIRCLES } from './kin.js';
import { planStory, DENSITY } from './plan.js';
import { resolveFeatured } from './featured.js';
import { chapterVars, countInCircle, numberFact } from './copy.js';
import { countWords } from '../blocks/words.js';
import { FIGURES, figureVignette, figuresFor, generationsBehind, lostName } from './pages/lists.js';
import { NAME_SIZE, ROW, SECTION_TITLES, duplicateRegisterNames, personRow, splitColumns } from './pages/parts/register-rows.js';
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
 * Round 2, finding 25: a figure vignette used to draw exactly one motif whatever the count, so
 * "five children" and "twenty-three cousins" looked identical beside their own sentence. Where the
 * count is small enough to lay out on its own it is now drawn that many times - the same symbol
 * placed several times, never a second drawing - and past `COUNTABLE_FIGURE_MAX` it falls back to
 * one motif standing for "several", so a large family's vignette never crowds into a smear.
 */
test('a small count draws that many motifs, and a large one draws a single motif standing for "several"', () => {
  const drawn = [];
  const ctx = {
    P: { ink: '#000', paperDeep: '#fff', gold: '#f2b84b' },
    zone() {},
    art: {
      box: () => ({ w: 10, h: 10 }),
      place: (id, placement) => ({ t: 'use', ref: `pc-${id}`, ...placement }),
    },
  };
  const countUses = (count) => figureVignette(ctx, 'lotus', 100, 100, 68, count).filter((it) => it.ref === 'pc-lotus').length;
  assert.equal(countUses(1), 1, 'a count of one should draw one motif');
  assert.equal(countUses(3), 3, 'a count of three should draw three motifs, one per instance');
  assert.equal(countUses(5), 5, 'a count at the countable maximum should still draw one motif each');
  assert.equal(countUses(23), 1, 'a count past the countable maximum should fall back to a single motif');
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

/*
 * Round 2, finding 12: `story-large` runs eight consecutive lane pages (p10-p17), and before this
 * fix every one of them washed the identical amber sky over the identical four house colours, with
 * only the mirror telling one page from the next. The wash is the first `rect` laid directly over
 * the scene at `op: 0.2` (sky) or `op: 0.18` (a house); a reverted fix leaves neither behind.
 */
test('lane pages do not all wash their sky the same colour', async () => {
  const { book: b, plan } = await book('story-large');
  const lanePages = plan.pages.filter((p) => p.archetype === 'lane');
  assert.ok(lanePages.length >= 4, 'story-large was expected to have several lane pages');
  const skyFills = lanePages.map((p) => b.pages[p.pageNo - 1].items.find((it) => it.t === 'rect' && it.op === 0.2)?.fill);
  assert.ok(skyFills.every(Boolean), `every lane page was expected to wash its own sky: ${JSON.stringify(skyFills)}`);
  assert.ok(new Set(skyFills).size > 1, `every lane page washed its sky the same colour (${skyFills[0]})`);
});

test('a full lane page tints its own four houses with more than one colour', async () => {
  const { book: b, plan } = await book('story-large');
  const full = plan.pages.find((p) => p.archetype === 'lane' && p.groups.length === DENSITY.houses);
  assert.ok(full, 'story-large was expected to fill a lane page');
  const houseFills = b.pages[full.pageNo - 1].items.filter((it) => it.t === 'rect' && it.op === 0.18).map((it) => it.fill);
  assert.equal(houseFills.length, DENSITY.houses, `expected a tint for each of ${DENSITY.houses} houses, found ${houseFills.length}`);
  assert.ok(new Set(houseFills).size > 1, 'every house on the page was tinted the same colour');
});

/**
 * The `haveli-lane` scene's own named zones, fetched straight from the art library rather than
 * through `lists.js`'s own fix - a ground truth its geometry can be checked against. `zones()`
 * never reads the palette, so a bare `{ P: {} }` context is enough to ask for them.
 */
function laneZones(mirrored) {
  const art = createArt({ P: {} }, LIBRARY);
  const placement = { x: 0, y: 0, w: PAGE.w, anchor: 'top-left', ...(mirrored ? { flip: 'x' } : {}) };
  return new Map(art.zones('haveli-lane', placement).map((z) => [z.name, z]));
}

/**
 * The lane's own four houses' wall heights below the scene's own ground line - `tools/
 * book_scenes.mjs`'s `haveliLane`, its own `houses` array's own `h` - read here independently of
 * `pages/lists.js`'s `houseHole`, as the ground truth the tests below check it against.
 */
const LANE_HOUSE_WALL_HEIGHT = { 1: 236, 2: 262, 3: 244, 4: 272 };

/*
 * Round 3: the per-house wash (`houseHole`, `pages/lists.js`) used one shared top for all four
 * houses - the scene's own shared "roofs" zone, which belongs to the town's skyline behind the
 * houses, not to any one of them - so it stood well above the three shorter houses' own roofs, a
 * hard-edged panel over the house against the sky, and the same height on every page regardless of
 * which house actually stood there, cutting a flat line across what should be an uneven skyline.
 * This checks the wash actually drawn against the scene's own authored geometry above, not against
 * `houseHole`'s own numbers.
 */
test('a lane house\'s own wash follows that house\'s own roof', async () => {
  const { book: b, plan } = await book('story-large');
  const lanePages = plan.pages.filter((p) => p.archetype === 'lane' && p.groups.length === DENSITY.houses);
  assert.ok(lanePages.length, 'story-large was expected to have a full lane page');
  for (const page of lanePages) {
    const zones = laneZones(page.variant === 'lane-mirrored');
    const washes = b.pages[page.pageNo - 1].items.filter((it) => it.t === 'rect' && it.op === 0.18);
    assert.equal(washes.length, DENSITY.houses, `page ${page.pageNo}: expected ${DENSITY.houses} house washes, found ${washes.length}`);
    for (let n = 1; n <= DENSITY.houses; n++) {
      const doorstep = zones.get(`doorstep-${n}`);
      const centre = doorstep.x + doorstep.w / 2;
      const wash = washes.find((r) => r.x <= centre && centre <= r.x + r.w);
      assert.ok(wash, `page ${page.pageNo}: no wash rect stands over house ${n}'s own doorstep`);
      const expectedTop = doorstep.y - LANE_HOUSE_WALL_HEIGHT[n] - 8;
      assert.ok(Math.abs(wash.y - expectedTop) <= 1, `page ${page.pageNo}, house ${n}: the wash starts at y ${wash.y}, expected close to its own roof at ${expectedTop}`);
    }
  }
});

/*
 * Round 4 (#245's invariant suite, the day a format-2 template shipped for it to run over): round
 * 2's finding-9 fix - a house holding two of the same name keeps its dates even when crowded -
 * forced the dates on and then divided the house's height by however many people stood in it. A
 * house of eight got a slot of about 12 pt to hold a name at 9 and a date line at 18.5, so every
 * date line printed over the next person's name: 74 `noTextOverlap` violations across the fixture
 * set, none of them visible to the tests this page had of its own.
 *
 * The room decides now (`datedInHouse`): everyone's dates if they fit, otherwise only the people
 * who need them to be told apart from a namesake, otherwise none. This checks the text actually
 * drawn on every lane page of every storybook fixture, through the same invariant the suite runs,
 * so it fails the same way the suite did rather than restating `datedInHouse`'s own arithmetic.
 */
test('no lane page prints a date line over the next person\'s name', async () => {
  for (const fixture of STORY) {
    const { book: b, report, plan } = await book(fixture);
    const lanePages = new Set(plan.pages.filter((p) => p.archetype === 'lane').map((p) => p.pageNo));
    if (!lanePages.size) continue;
    const onLane = report.textBoxes.filter((t) => lanePages.has(t.page));
    assert.ok(onLane.length, `${fixture}: lane pages drew no text at all`);
    assert.deepEqual(noTextOverlap({ book: b, report: { ...report, textBoxes: onLane } }), [],
      `${fixture}: a lane page overlaps its own text`);
  }
});

/*
 * The other half of the same fix: it must not buy the overlap back by simply dropping every date.
 * A house that holds two of the same name is exactly the case finding 9 was raised for - the
 * register's page reference alone cannot tell those two apart - so those people keep their dates
 * even where a crowded house cannot give them to everybody.
 */
test('a house holding two of the same name still dates them', async () => {
  const { family, report, plan } = await book('story-large');
  let checked = 0;
  for (const page of plan.pages.filter((p) => p.archetype === 'lane')) {
    for (const house of page.groups) {
      const names = house.people.map((id) => family.byId.get(id)?.name).filter(Boolean);
      if (new Set(names).size === names.length) continue;   // no namesakes in this house
      checked += 1;
      const dated = linesOn(report, page.pageNo, 'lifespan').length;
      assert.ok(dated >= 2, `page ${page.pageNo}: a house holds two of the same name, and the page printed ${dated} date lines`);
    }
  }
  assert.ok(checked > 0, 'story-large was expected to put two of the same name in one house');
});

/*
 * Round 3, part 2, and its resolution.
 *
 * Round 2's finding 7 removed the marigold string a blank nameboard used to carry - that garland
 * over a house nobody has died in was the real breach and it stays gone. Its remedy, punching the
 * unused house out of the paper, cited #257's `courtyards` as the technique; but #257 had tried a
 * hole in that scene and rejected it, because a scene is one drawing rather than layers a page can
 * pick apart, so a hole takes the house's own sky and skyline with it and leaves a rectangle of the
 * wrong shade behind. Round 3 found exactly that here. Stopping the cut at the doorstep saved the
 * street and the otla, and the panel still stood against the sky.
 *
 * So the lane does what the courtyards do: the scene is drawn whole, and the empty slot is simply
 * left undressed. This holds it to that - nothing on a lane page is clipped, however few branches
 * the page has - because the render is the only thing that ever caught this, twice.
 */
test('a lane page with an empty house draws its scene whole, cutting nothing out of it', async () => {
  const { book: b, plan } = await book('story-large');
  const short = plan.pages.filter((p) => p.archetype === 'lane' && p.groups.length < DENSITY.houses);
  assert.ok(short.length, 'story-large was expected to have a lane page with an empty house');
  for (const page of short) {
    const items = b.pages[page.pageNo - 1].items;
    assert.equal(items.filter((it) => it.t === 'group' && it.clip).length, 0,
      `page ${page.pageNo}: the lane clipped its scene, which takes that house's own sky and skyline with it`);
  }
});

/*
 * The other half of finding 7: an undressed house must stay undressed. Nothing may name it, and
 * above all nothing may hang a garland over it - the notation this book keeps for the departed.
 */
test('an empty house carries no name and no garland', async () => {
  const { book: b, report, plan } = await book('story-large');
  for (const page of plan.pages.filter((p) => p.archetype === 'lane' && p.groups.length < DENSITY.houses)) {
    const drawn = page.groups.length;
    const plates = linesOn(report, page.pageNo, 'name').filter((t) => t.y < 500);
    assert.ok(plates.length <= drawn, `page ${page.pageNo}: ${plates.length} door plates for ${drawn} houses`);
    assert.equal(usesOn(b.pages[page.pageNo - 1], ['mala', 'mala-departed']), 0,
      `page ${page.pageNo}: a garland hangs on the lane, which is the book's own mourning notation`);
  }
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

/*
 * Ankit's decision 9: the chapter's own count and the page's own lamps must be the same number.
 *
 * They were two definitions. `plan.js` put people with no *recorded* name on the page; `copy.js`
 * counted people with no *printable* name - and `nameOf` gives an unnamed person a kin description
 * to print ("Raj Kumar's wife"), so almost nobody counted. `story-large` read "0 lamps are lit"
 * over three lit lamps, and `story-unnamed` counted five for a page that does not exist. #258 fixed
 * the page by handing it the plan's count and left `copy.js` wrong for every other caller; there is
 * one definition now (`plan.js`'s `isStillToBeFound`).
 *
 * This holds them together on every fixture, in both directions - a chapter that over-counts is as
 * wrong as one that under-counts, and the old code did both.
 */
test('the chapter\'s count of names still to be found is the number of lamps actually lit', async () => {
  for (const fixture of STORY) {
    const { family, kin, plan } = await book(fixture);
    const lit = plan.pages.reduce((n, p) => n + (p.archetype === 'still-to-be-found' ? p.people.length : 0), 0);
    assert.equal(chapterVars('still-to-be-found', family, kin).n, lit,
      `${fixture}: the copy counts a different number of names than the book lights lamps for`);
  }
});

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

/*
 * `duplicateRegisterNames` computing a qualifier is only half the fix - `personRow` has to print
 * it. This drives `personRow` itself (a minimal stand-in for `ctx`, recording what `ctx.line`
 * was asked to draw) so a regression in *that* half - the map computed right, never read - fails
 * here even on a family with no real collision to reach through the full compose pipeline.
 */
test('personRow prints the qualifier a colliding name was given', () => {
  const family = { byId: new Map([
    ['a', { name: 'Sneha Sharma', by: null, dy: null, deceased: false }],
    ['b', { name: 'Sneha Sharma', by: null, dy: null, deceased: false }],
  ]) };
  const kin = {
    featured: 'f',
    people: new Map([['a', { circle: 'branches' }], ['b', { circle: 'branches' }]]),
    words: () => null,
  };
  const story = { kin, plan: { pagesOf: new Map() } };
  const disambiguate = duplicateRegisterNames({ family }, story);
  const drawn = [];
  const ctx = {
    P: { ink: '#000', brass: '#000', clay: '#000', inkSoft: '#000' },
    family,
    show() {},
    fit: (s, role, size) => size,
    measure: (s) => s.length * 5,
    line(x, y, s, role, size, fill, opts) { const item = { s, kind: opts?.kind }; drawn.push(item); return item; },
  };
  personRow(ctx, story, 'a', 0, 0, 200, { portraits: false, disambiguate });
  const name = drawn.find((d) => d.kind === 'name');
  assert.ok(name, 'personRow drew no name line');
  assert.match(name.s, /^Sneha Sharma \(.+\)$/, `"${name.s}" carries no qualifier`);
});

test('a name already said to be "late" does not repeat it as a bare "Late" caption', () => {
  const ctx = {
    family: { byId: new Map([
      ['w', { name: null, deceased: true, by: null, dy: null }],
      ['husband', { name: 'Raj Kumar', deceased: true, by: 1930, dy: 1990 }],
    ]) },
  };
  const story = {
    kin: {
      featured: 'husband',
      people: new Map([
        ['w', { namedBy: { id: 'husband', word: 'late wife' } }],
        ['husband', { namedBy: null }],
      ]),
      words: () => null,
    },
  };
  const { name, under } = lostName(ctx, story, 'w', 'Raj Kumar');
  assert.equal(name, 'Raj Kumar’s late wife');
  assert.equal(under, '', `"${under}" repeats "late" as a bare date caption`);
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
 * The other half of that promise, and the one #245 was actually missing.
 *
 * `noTextInBusyArt` compares text against the zones the art *declares*, so it is only as honest as
 * those zones are. The violation #245 cites - the folio credit printing over the rangoli's petals
 * on `story-large` p24 - was invisible to it for exactly that reason: the check was written and
 * passing, and the zone it checked against did not cover what the page drew. A test that only asks
 * "does any text sit in a declared zone" can never catch that; it has to be asked the other way
 * round, against the geometry the painter is actually handed.
 *
 * So this measures the rangoli as drawn - every path point and circle, through the group's own
 * transform - and holds the declared zone to containing it. Shrink the zone and this fails, where
 * the invariant above would still pass.
 */
test('the rangoli declares a busy zone that covers what it actually draws', async () => {
  for (const fixture of ['story-large', 'story-unknown-names']) {
    const { book: b, report } = await book(fixture);
    const page = pagesOfKind(report, 'still-to-be-found')[0];
    assert.ok(page, `${fixture} was expected to have a still-to-be-found page`);
    const items = b.pages[page.page - 1].items;
    const g = items.find((it) => it.t === 'group' && it.tf);
    assert.ok(g, `${fixture}: expected the rangoli to be drawn as a transformed group`);

    const [a, bb, c, d, e, f] = g.tf;
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    const at = (x, y) => {
      const px = a * x + c * y + e, py = bb * x + d * y + f;
      minX = Math.min(minX, px); maxX = Math.max(maxX, px);
      minY = Math.min(minY, py); maxY = Math.max(maxY, py);
    };
    const walk = (list) => {
      for (const it of list) {
        if (it.items) { walk(it.items); continue; }
        if (it.t === 'circle') { at(it.cx - it.r, it.cy - it.r); at(it.cx + it.r, it.cy + it.r); }
        if (it.t === 'path' && it.d) for (const [x, y] of pathPoints(it.d) ?? []) at(x, y);
      }
    };
    walk(g.items);
    assert.ok(Number.isFinite(minX), `${fixture}: the rangoli group drew nothing measurable`);

    const covers = report.artZones.some((z) => z.page === page.page && z.kind === 'busy'
      && z.x <= minX && z.y <= minY && z.x + z.w >= maxX && z.y + z.h >= maxY);
    assert.ok(covers, `${fixture}: the rangoli draws x ${minX.toFixed(1)}-${maxX.toFixed(1)}, `
      + `y ${minY.toFixed(1)}-${maxY.toFixed(1)}, and no busy zone on page ${page.page} contains it - `
      + 'text could sit on it with noTextInBusyArt none the wiser');
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
  // 9 rows, with "siblings" (one heading, two names, "c" and "d") placed so the naive
  // `Math.ceil(9 / 2) = 5` lands right inside it: `rows[4]` is "c", not a heading, so the old
  // "back up off a bare heading" rule never fires, and the break used to fall between "c" and
  // "d" - one name at the foot of column one, the other under a lone "continued" heading in
  // column two. This is `story-large` page 19's own shape, reproduced exactly.
  const rows = [
    { heading: 'self' }, { id: 'a' }, { id: 'b' },
    { heading: 'siblings' }, { id: 'c' }, { id: 'd' },
    { heading: 'children' }, { id: 'f' }, { id: 'g' },
  ];
  assert.equal(Math.ceil(rows.length / 2), 5, 'the reproduction drifted off the naive break point');
  const [first, second] = splitColumns(rows);
  assert.equal(first.length + second.length, rows.length, 'a row was dropped or duplicated by the split');
  const has = (column, id) => column.some((r) => r.id === id);
  assert.equal(has(first, 'c'), has(first, 'd'), '"siblings" was split with one name on each side of the break');
});
