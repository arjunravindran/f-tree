/*
 * The family page archetypes (#257): the banyan of ancestors, the two courtyards, and the
 * gathering that draws parents, siblings, spouses and children.
 *
 * What is checked here is what the pages promise beyond "it drew something": that the art says
 * what the record says and nothing more (a mala only on somebody who has died, a lamp rather than
 * an invented face for a name nobody wrote down), that kinship is drawn as composition (a marigold
 * string only between two people the record actually married, children below their parents), that
 * the plan's own decisions are drawn rather than second-guessed, and that a page at the design
 * system's density caps is still readable - no text over art, no text over text, nothing under the
 * size floors.
 *
 * The pages are composed through `qa/stub-pages.mjs`, so these run before #256 and #258 land and
 * keep running after they do.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { composeWithPages } from '../compose.js';
import { PAGE, validateBook, pathPoints } from '../format.js';
import { withStubs } from '../qa/stub-pages.mjs';
import { STORY_TEMPLATE } from '../qa/story-template.mjs';
import { loadFixture, NOW, STORYBOOK_MANIFEST } from '../qa/book-fixtures.mjs';
import { noTextInBusyArt, noTextOverlap, sizes } from '../qa/invariants.mjs';
import { readFamily } from '../family.js';
import { kinOf } from './kin.js';
import { resolveFeatured } from './featured.js';
import { PAGES } from './pages/family.js';
import { SAFE, TYPE } from './pages/parts/paper.js';

const MINE = Object.keys(PAGES);

/** The fixture's own featured person, so a test names the same F the manifest does. */
const featuredOf = (fixture) => STORYBOOK_MANIFEST[fixture]?.ids?.featured;

/**
 * Composes a fixture's whole book with #257's pages (and stubs for the rest), optionally rewriting
 * the `PagePlan` handed to one archetype - which is how a page at a density cap, or a variant the
 * planner would not have chosen for this family, gets drawn without inventing a whole context.
 */
async function compose(fixture, { rewrite, archetype, ...options } = {}) {
  const doc = await loadFixture(fixture);
  const opts = { now: NOW, featured: featuredOf(fixture), ...options };
  const plans = [];
  const table = { ...withStubs() };
  for (const a of MINE) {
    table[a] = (ctx, page, story) => {
      const p = rewrite && (!archetype || archetype === a) ? { ...page, ...rewrite(page, story) } : page;
      plans.push(p);
      return PAGES[p.archetype](ctx, p, story);
    };
  }
  const { book, report } = composeWithPages(doc, opts, STORY_TEMPLATE, table);
  const family = readFamily(doc, opts);
  const kin = kinOf(family, resolveFeatured(family, opts), { words: opts.words });
  return { book, report, plans, family, kin, opts };
}

/** The page numbers an archetype drew. */
const pagesOf = (report, archetype) => report.pages.filter((p) => p.archetype === archetype).map((p) => p.page);

/** Every `use` item on a page, however deep inside a group it is nested. */
function* allUses(items) {
  for (const it of items) {
    if (it.t === 'use') yield it;
    if (it.t === 'group') yield* allUses(it.items);
  }
}

/** How many times a page draws the symbol `ref`, `use`s inside groups included. */
const uses = (page, ref) => [...allUses(page.items)].filter((it) => it.ref === ref).length;

/** The clips a page puts a whole scene behind: how a page crops one. A frame's opening is not one. */
function sceneCrops(page, ref) {
  return page.items.filter((it) => it.t === 'group' && it.clip !== undefined && it.items.some((c) => c.t === 'use' && c.ref === ref));
}

/** The report cut down to one page, for the invariants that are about a single page. */
const only = (report, book, pageNo) => ({
  book,
  report: { ...report, textBoxes: report.textBoxes.filter((b) => b.page === pageNo), artZones: report.artZones.filter((z) => z.page === pageNo) },
});

/**
 * Nothing a reader has to see is drawn off the paper. Art may bleed off a page (a scene does), but
 * a portrait frame or a name that walked off the edge is a layout that ran out of room, which the
 * shared invariants do not look for because a format-1 block never could.
 */
function offPage(report, pageNo) {
  const out = [];
  const beyond = (b) => b.x < -1 || b.y < -1 || b.x + b.w > PAGE.w + 1 || b.y + b.h > PAGE.h + 1;
  for (const b of report.textBoxes.filter((t) => t.page === pageNo && beyond(t))) out.push(`page ${pageNo}: "${b.s}" is off the page`);
  for (const z of report.artZones.filter((a) => a.page === pageNo && a.kind === 'face' && beyond(a))) out.push(`page ${pageNo}: a frame at ${Math.round(z.x)},${Math.round(z.y)} is off the page`);
  return out;
}

/** Every layout invariant that is about one page, over one page. */
function layoutFaults(book, report, pageNo) {
  const one = only(report, book, pageNo);
  return [...sizes(one), ...noTextOverlap(one), ...noTextInBusyArt(one), ...offPage(report, pageNo)];
}

/* ------------------------------------------------------------------ the pages draw at all */

test('every family page composes, describes itself from the plan, and keeps the layout rules', async () => {
  for (const fixture of ['story-large', 'story-half-siblings', 'story-twelve-siblings', 'story-three-spouses', 'story-unknown-names', 'story-devanagari', 'story-leaf', 'story-eldest', 'story-roots']) {
    const { book, report, plans } = await compose(fixture);
    assert.deepEqual(validateBook(book), [], `${fixture}: not a valid Book`);
    const drawn = report.pages.filter((p) => MINE.includes(p.archetype));
    assert.ok(drawn.length, `${fixture} draws none of #257's pages`);
    for (const info of drawn) {
      const plan = plans.find((p) => p.pageNo === info.page);
      // The plan decides who a page is about and what limits it; an archetype only reports it.
      assert.deepEqual([...info.people], [...plan.people], `${fixture} page ${info.page}: drew a different cast`);
      assert.equal(info.variant, plan.variant, `${fixture} page ${info.page}: drew another variant`);
      assert.equal(info.density, plan.density, `${fixture} page ${info.page}: reported another density`);
      assert.deepEqual(layoutFaults(book, report, info.page), [], `${fixture} page ${info.page}`);
      for (const id of plan.people) assert.ok(report.shown[id]?.includes(info.page), `${fixture} page ${info.page}: ${id} is on it but not shown`);
    }
  }
});

test('the banyan actually gets drawn: a family recorded deep enough emits a roots page', async () => {
  // #257 round 2: no other fixture's roots chapter reaches far enough above F to make the planner
  // ask for a `banyan` page at all, so the archetype had never had a render pass of its own -
  // `story-roots` (tools/make_sample_tree.py) records four generations above F on purpose. This
  // would throw on the missing fixture file before it could even get to the assertions below, which
  // is the regression this guards: a banyan page drawn from a plan, not a stub standing in for one.
  const { report, plans } = await compose('story-roots');
  const banyan = report.pages.find((p) => p.archetype === 'banyan');
  assert.ok(banyan, 'story-roots did not make the planner ask for a banyan page');
  const plan = plans.find((p) => p.pageNo === banyan.page);
  // The fixture's one unnamed, departed great-grandmother must be on the page: a banyan with only
  // named, living-record ancestors would leave the lamp-unknown-under-mala ordering (finding 3)
  // untested by the one fixture built to reach this archetype at all.
  assert.ok(plan.people.includes('ggm-p'), 'the unnamed, departed great-grandmother is not on the banyan page');
});

test('every variant the planner may ask for draws, and draws differently', async () => {
  for (const [archetype, variants, fixture] of [
    ['banyan', ['roots', 'canopy'], 'story-large'],
    ['courtyards', ['facing', 'mirrored'], 'story-large'],
    ['gathering', ['band', 'doorways', 'steps'], 'story-large'],
  ]) {
    const drawn = new Set();
    for (const variant of variants) {
      const { book, report } = await compose(fixture, { archetype, rewrite: () => ({ variant }) });
      for (const pageNo of pagesOf(report, archetype)) {
        assert.deepEqual(layoutFaults(book, report, pageNo), [], `${archetype}/${variant} page ${pageNo}`);
        drawn.add(JSON.stringify(book.pages[pageNo - 1].items));
      }
    }
    assert.ok(drawn.size > 1, `${archetype}'s variants all drew the same page: they are not different art placements`);
  }
});

/* ------------------------------------------------------------------ the density caps */

/*
 * The design system's caps are the plan's (`DENSITY`): 8 on a family page, 12 on a gathering. A
 * page at its cap is the one a layout is most likely to break on, and the fixtures do not always
 * reach it, so these draw one on purpose.
 */

/** The first `n` people of `kin`'s biggest circle, as one page plan for `archetype`. */
const crowd = (archetype, density, n) => (page, story) => {
  const pool = [...story.kin.people.keys()].filter((id) => id !== story.kin.featured);
  const people = pool.slice(0, n);
  assert.equal(people.length, n, 'the fixture does not hold enough people to fill a page');
  return { archetype, density, people, groups: [{ key: 'crowd:', people }] };
};

test('a short page\'s rows are centred in the band, not pinned to the top of an empty sheet', async () => {
  // Round 1 finding 14: pages 3-9 of the story-large contact sheet were seven consecutive cream
  // pages whose lower two-thirds were pale void, because SLACK caps how much of a short page's
  // spare room the gaps between its one or two rows can soak up. A page of one small row (F's own
  // two siblings) has a whole page's worth of room to spare.
  const people = ['f-sib0', 'f-sib1'];
  const { report } = await compose('story-large', {
    archetype: 'gathering',
    rewrite: () => ({ archetype: 'gathering', density: 'family', people, groups: [{ key: 'siblings:', people }] }),
  });
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.people.length === people.length);
  assert.ok(info, 'the rewrite did not produce the expected gathering page');
  const faces = report.artZones.filter((z) => z.page === info.page && z.kind === 'face');
  assert.ok(faces.length, 'the page has no face zones to measure');
  const rowMid = (Math.min(...faces.map((z) => z.y)) + Math.max(...faces.map((z) => z.y + z.h))) / 2;
  // The band the row was laid out in runs from just under the title to the safe area's foot
  // (no note or tailpiece changes that on a page this empty). Centred means the row's own
  // vertical middle lands close to the band's middle, not in its top third.
  const band = { top: 90, bottom: SAFE.bottom };
  const bandMid = (band.top + band.bottom) / 2;
  assert.ok(Math.abs(rowMid - bandMid) < (band.bottom - band.top) * 0.2,
    `the row's middle (${Math.round(rowMid)}) is not near the band's middle (${Math.round(bandMid)})`);
});

test('a family page at its cap of eight is still readable', async () => {
  const { book, report } = await compose('story-large', { archetype: 'gathering', rewrite: crowd('gathering', 'family', 8) });
  const pageNo = pagesOf(report, 'gathering')[0];
  assert.deepEqual(layoutFaults(book, report, pageNo), []);
  assert.equal(report.pages[pageNo - 1].people.length, 8);
});

test('a gathering at its cap of twelve is still readable, on the tree and in the courtyards too', async () => {
  for (const archetype of MINE) {
    const { book, report } = await compose('story-large', { archetype, rewrite: crowd(archetype, 'gathering', 12) });
    for (const pageNo of pagesOf(report, archetype)) {
      assert.deepEqual(layoutFaults(book, report, pageNo), [], `${archetype} with twelve people`);
      assert.equal(report.pages[pageNo - 1].people.length, 12);
    }
  }
});

test('everybody on a crowded page is still drawn, never quietly dropped', async () => {
  for (const archetype of MINE) {
    const { report, plans } = await compose('story-large', { archetype, rewrite: crowd(archetype, 'gathering', 12) });
    const pageNo = pagesOf(report, archetype)[0];
    for (const id of plans.find((p) => p.pageNo === pageNo).people) {
      assert.ok(report.shown[id]?.includes(pageNo), `${archetype}: ${id} is on the page and was not drawn`);
    }
  }
});

/* ------------------------------------------------------------------ the notation */

test('a mala hangs on the frame of everybody who has died, and on nobody else', async () => {
  for (const fixture of ['story-large', 'story-unknown-names', 'story-three-spouses']) {
    const { book, report, family } = await compose(fixture);
    for (const info of report.pages.filter((p) => MINE.includes(p.archetype))) {
      const departed = info.people.filter((id) => family.byId.get(id).deceased).length;
      assert.equal(uses(book.pages[info.page - 1], 'pc-mala-departed'), departed,
        `${fixture} page ${info.page}: ${departed} of the people on it have died`);
    }
  }
});

/**
 * Somebody both unnamed and departed must read as unnamed first: the dashed `lamp-unknown` rim is
 * the app's one notation for "name not known", and a mala drawn on top of it hides that notation
 * (#257 round 2, finding 3). `people.js`'s `portrait` must draw the mala first and the lamp over
 * it, never the other way round. A page of exactly one such person, on its own, so nobody else's
 * mala or lamp can be mistaken for this one's.
 */
test('the name-not-known lamp draws over a departed person\'s mala, never under it', async () => {
  const people = ['son-wife'];   // Raj Kumar's wife: unnamed, and the record says she has died
  const { book, report, family } = await compose('story-unknown-names', {
    archetype: 'gathering',
    rewrite: () => ({ archetype: 'gathering', density: 'family', people, groups: [{ key: 'solo:', people }] }),
  });
  assert.ok(!family.byId.get('son-wife').name && family.byId.get('son-wife').deceased, 'the fixture changed under this test');
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.people.length === 1);
  assert.ok(info, 'the rewrite did not produce a one-person gathering page');
  const items = book.pages[info.page - 1].items;
  const malaIndex = items.findIndex((it) => it.t === 'use' && it.ref === 'pc-mala-departed');
  const lampIndex = items.findIndex((it) => it.t === 'use' && it.ref === 'pc-lamp-unknown');
  assert.ok(malaIndex >= 0 && lampIndex >= 0, 'expected both a mala and a lamp-unknown on the page');
  assert.ok(malaIndex < lampIndex, 'the mala was drawn over the lamp-unknown rim, not under it');
});

test('a name nobody wrote down is a lamp, never an invented face', async () => {
  const { book, report, family } = await compose('story-unknown-names');
  const lamps = { 'pc-lamp-unknown': 0, 'pc-aala': 0 };
  let nameless = 0;
  for (const info of report.pages.filter((p) => MINE.includes(p.archetype))) {
    nameless += info.people.filter((id) => !family.byId.get(id).name).length;
    for (const ref of Object.keys(lamps)) lamps[ref] += uses(book.pages[info.page - 1], ref);
  }
  assert.ok(nameless > 0, 'the fixture has nobody without a name');
  assert.equal(lamps['pc-lamp-unknown'] + lamps['pc-aala'], nameless, 'one lamp for each name not known, and no more');
});

test('the same fact never prints three times over one caption: "late" once, and only where nothing else says it', async () => {
  // Raj Kumar's wife (unnamed, WIDOWED, no dates recorded): her name line ("Raj Kumar's late
  // wife") is the only place that can say she has died, so it keeps "late" and the bare-word
  // dates line "Late" - which would only repeat that same fact as an orphan word - is dropped
  // (round 1 finding 10).
  const { report } = await compose('story-unknown-names');
  const mine = report.pages.filter((p) => MINE.includes(p.archetype));
  const boxes = report.textBoxes.filter((b) => mine.some((p) => p.page === b.page));
  // A wrapped name line is more than one text box ("Raj Kumar’s late" then "wife"), so the
  // name-kind boxes are joined into one string in their drawn order before searching it.
  const names = boxes.filter((b) => b.kind === 'name').map((b) => b.s).join(' ');

  assert.match(names, /Raj Kumar.s late wife/, 'Raj Kumar\'s wife\'s name line dropped "late" although nothing else on the page says she has died');
  assert.ok(!boxes.some((b) => b.kind === 'lifespan' && b.s === 'Late'), 'the orphan word "Late" still prints beside a name line that already says so');

  // Shyam Lal's wife (unnamed, WIDOWED, full dates recorded): the dates line prints real years,
  // so her name line drops "late" rather than saying the same thing a second way (finding 26).
  assert.match(names, /Shyam Lal.s wife/, 'Shyam Lal\'s wife has no name line on the page');
  assert.ok(!/Shyam Lal.s late wife/.test(names), 'Shyam Lal\'s wife\'s name line still says "late" although her dates print beside it');
  assert.ok(boxes.some((b) => b.kind === 'lifespan' && b.s === '1909 – 1981'), 'her dates did not print in the approved spaced form');
});

test('three spouses stand in three pairs with F, not shoulder to shoulder on one shared ground line', async () => {
  // Round 1 finding 25: a former wife, a late wife and the current wife stood shoulder to
  // shoulder at equal size on one shared ground line - the device the design system reserves for
  // siblings - and F himself was not even on the page, so nothing connected any of them to
  // anything. Each of the three now stands paired with F on her own ground line.
  const { book, report } = await compose('story-three-spouses');
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.people.length === 3);
  assert.ok(info, 'the fixture no longer has its spouses page');
  const ground = [...paths(book.pages[info.page - 1].items)].filter((it) => it.stroke && it.sw === 1.6 && it.op === 0.85);
  assert.ok(ground.length === 0 || new Set(ground.map((it) => it.d)).size > 1,
    'the three still share one ground line');
  // F stands on the page three times over - once beside each wife - not once, unconnected.
  const frameRefs = new Set(['pc-medallion-carved', 'pc-medallion-petals', 'pc-lamp-unknown']);
  const frames = [...allUses(book.pages[info.page - 1].items)].filter((it) => !it.fill && frameRefs.has(it.ref)).length;
  assert.equal(frames, 6, `expected F paired with each of the three wives (6 frames), found ${frames}`);
});

test('a marigold string joins two people the record married, and nobody else', async () => {
  // The `steps` variant closes a page with the lotus divider rather than the tailpiece's own
  // short mala, so every `mala` left on the page is a marriage and nothing else.
  const steps = { rewrite: () => ({ variant: 'steps' }), archetype: 'gathering' };

  // F's three spouses are married to F, never to each other (round 1 finding 25): the current
  // wife gets a mala to F, the late one a diya, and the former one - divorced - neither, but none
  // of the three gets anything joining her to either of the other two.
  const { book, report, kin } = await compose('story-three-spouses', steps);
  const spouses = report.pages.find((p) => p.archetype === 'gathering' && p.people.length === 3);
  assert.ok(spouses, 'the three-spouses fixture no longer has its spouses page');
  const page3 = book.pages[spouses.page - 1];
  const roles = new Map(spouses.people.map((id) => [id, kin.people.get(id).role]));
  assert.deepEqual(new Set(roles.values()), new Set(['current', 'former', 'late']), 'the fixture changed under this test');
  assert.equal(uses(page3, 'pc-mala'), 1, 'not exactly one string, to the current wife');
  assert.equal(uses(page3, 'pc-diya'), 1, 'not exactly one diya, to the late wife');

  // The parents of a family that did marry are joined by one.
  const parents = await compose('story-devanagari', steps);
  const page = parents.report.pages.find((p) => p.archetype === 'gathering' && p.people.some((id) => parents.kin.people.get(id).circle === 'parents'));
  const married = parents.book.pages[page.page - 1];
  const joined = uses(married, 'pc-mala') + uses(married, 'pc-diya');
  assert.ok(joined > 0, 'the page drew no string and no diya between two people the record married');
});

test('kin captions are the reviewed Hindi words when the reader asked for them', async () => {
  const en = await compose('story-large', { words: 'en' });
  const hi = await compose('story-large', { words: 'hi' });
  const captions = ({ book, report }) => report.textBoxes
    .filter((b) => b.kind === 'caption' && report.pages[b.page - 1] && MINE.includes(report.pages[b.page - 1].archetype))
    .map((b) => b.s);
  const english = captions(en);
  const hindi = captions(hi);
  assert.ok(english.includes('grandfather'), `no English kin word on a family page: ${english.join(', ')}`);
  assert.ok(hindi.some((s) => /[ऀ-ॿ]/.test(s)), `no Devanagari kin word with words: 'hi': ${hindi.join(', ')}`);
  assert.ok(!hindi.includes('grandfather'), 'an English kin word survived words: \'hi\'');
});

/* ------------------------------------------------------------------ the two courtyards */

test('a family that knows only one side draws both houses, empty rather than cut away', async () => {
  // Round 1 finding 4: cutting a hole out of the scene left a rectangle of bare paper where that
  // house's own sky and windows had been (the scene is one drawing, not layers this page can pick
  // apart), a stray edge where the cut crossed the floor's plank lines, and the ladi light that
  // spans both roofs stopping dead over nothing. The design critic's own fix, taken here: keep
  // both houses whole, and draw nobody under the one nobody is known for.
  const one = await compose('story-half-siblings');   // one grandparent, on the father's side
  const page = one.report.pages.find((p) => p.archetype === 'courtyards');
  assert.ok(page, 'the fixture no longer plans a courtyards page');
  assert.equal(page.people.length, 1);
  const drawnPage = one.book.pages[page.page - 1];
  assert.equal(sceneCrops(drawnPage, 'pc-aangan').length, 0, 'the scene was cut, which is exactly what round 1 flagged');
  assert.equal(uses(drawnPage, 'pc-aangan'), 1, 'the scene itself is not drawn whole');

  // Nobody stands under the house whose side is not known: exactly one portrait frame draws on
  // the page, not a second, invented one for the other house. (The scene itself still reports
  // both houses' walls as busy `face` zones, whether or not anybody stands there - that is the
  // architecture, not a portrait, and is correct either way.) A frame casts a shadow - a second
  // `use` of the same symbol, tinted ink - so only the ones with no fill of their own are counted.
  const frameRefs = new Set(['pc-medallion-carved', 'pc-medallion-petals', 'pc-lamp-unknown']);
  const frames = [...allUses(drawnPage.items)].filter((it) => !it.fill && frameRefs.has(it.ref)).length;
  assert.equal(frames, page.people.length, 'a portrait was drawn for a house with nobody under it');

  // Both sides known: both houses stand, exactly as they would with only one known.
  const both = await compose('story-large');
  const twoSided = both.report.pages.find((p) => p.archetype === 'courtyards');
  assert.equal(twoSided.people.length, 4);
  assert.equal(sceneCrops(both.book.pages[twoSided.page - 1], 'pc-aangan').length, 0);
});

test('a lamp kept in a house\'s wall gets a note explaining it, when the family gave no note of its own', async () => {
  // Round 1 finding 5: the aala reads as decoration without a sentence beside it, and a niche
  // sits inside the scene's own busy art (no text may go there) while the household's caption
  // stack that would otherwise explain it can be hundreds of points away.
  const { book, report, family } = await compose('story-unknown-names', {
    archetype: 'gathering',
    rewrite: (page, story) => {
      const people = [...story.kin.circles.grandparents];
      return { archetype: 'courtyards', variant: 'facing', density: 'gathering', people, groups: [{ key: 'grandparents:', people }] };
    },
  });
  const page = report.pages.find((p) => p.archetype === 'courtyards');
  assert.ok(page, 'the rewrite did not produce a courtyards page');
  assert.ok(page.people.some((id) => !family.byId.get(id).name), 'the fixture has no nameless grandparent for a niche to hold');
  const drawn = book.pages[page.page - 1];
  assert.ok(uses(drawn, 'pc-aala') > 0, 'the fixture no longer lights a niche on this page');
  const noteText = report.textBoxes.find((b) => b.page === page.page && b.kind === 'caption' && /lamp is kept/i.test(b.s));
  assert.ok(noteText, 'the lit niche has no explanation anywhere on the page');
});

test('both houses have niches, so the notation for a name not known does not depend on which side of the family somebody is on', async () => {
  // This fixture's one nameless grandparent, Shyam Lal's wife, is on the FATHER's side - the
  // house `courtyards` used to draw with no niches in it at all, so she got the plainer
  // lamp-unknown medallion while an equivalent maternal grandparent would have got the aala in
  // the wall (#257 round 2, finding 6). Mirroring the niches onto the father's house too means
  // she gets the same notation a maternal grandparent would.
  const { book, report, family, kin } = await compose('story-unknown-names', {
    archetype: 'gathering',
    rewrite: (page, story) => {
      const people = [...story.kin.circles.grandparents];
      return { archetype: 'courtyards', variant: 'facing', density: 'gathering', people, groups: [{ key: 'grandparents:', people }] };
    },
  });
  const page = report.pages.find((p) => p.archetype === 'courtyards');
  const nameless = page.people.filter((id) => !family.byId.get(id).name);
  assert.ok(nameless.length > 0, 'the fixture has no nameless grandparent');
  assert.ok(nameless.every((id) => kin.people.get(id).side === 'paternal'), 'the fixture changed under this test');
  const drawn = book.pages[page.page - 1];
  assert.equal(uses(drawn, 'pc-aala'), Math.min(nameless.length, 2), 'the father\'s house still has no niche for a name not known');
  assert.equal(uses(drawn, 'pc-lamp-unknown'), Math.max(0, nameless.length - 2), 'somebody past the father\'s two niches got neither a niche nor a lamp');
});

/* ------------------------------------------------------------------ the gathering's composition */

test('children stand below their parents, and siblings share one ground line', async () => {
  const { book, report, kin, family } = await compose('story-devanagari');
  const info = report.pages.find((p) => p.archetype === 'gathering' && new Set(p.people.map((id) => kin.people.get(id).gen)).size > 1);
  assert.ok(info, 'no gathering page in this fixture holds two generations');
  assert.deepEqual(validateBook(book), []);

  // Each person's own name line says where on the page they stand.
  const where = new Map();
  for (const id of info.people) {
    const name = family.byId.get(id).name;
    const box = report.textBoxes.find((b) => b.page === info.page && b.kind === 'name' && b.s === name);
    assert.ok(box, `${id} has no name line on the page`);
    where.set(id, { y: box.y, gen: kin.people.get(id).gen });
  }
  const rows = [...new Set([...where.values()].map((v) => v.gen))].sort((a, b) => a - b);
  for (let i = 1; i < rows.length; i++) {
    const above = Math.max(...[...where.values()].filter((v) => v.gen === rows[i - 1]).map((v) => v.y));
    const below = Math.min(...[...where.values()].filter((v) => v.gen === rows[i]).map((v) => v.y));
    assert.ok(above < below, `generation ${rows[i]} is not drawn below generation ${rows[i - 1]}`);
  }

  // Siblings - one generation, one household - share one ground line, so their names do too.
  for (const g of rows) {
    const ys = [...where.values()].filter((v) => v.gen === g).map((v) => v.y);
    assert.ok(Math.max(...ys) - Math.min(...ys) < 1, `one generation's people are on ${new Set(ys).size} ground lines`);
  }
});

/** Every path item in `items`, however deep inside a group it is nested. */
function* paths(items) {
  for (const it of items) {
    if (it.t === 'path') yield it;
    if (it.t === 'group') yield* paths(it.items);
  }
}

test('the ground line passes under the frames, not through their rims and malas', async () => {
  const { book, report } = await compose('story-devanagari');
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.variant !== 'steps');
  assert.ok(info, 'no gathering page in this fixture uses a ground line at all');
  const zone = report.artZones.find((z) => z.page === info.page && z.kind === 'face');
  assert.ok(zone, `page ${info.page} has no face zone to measure the ground line against`);
  // `groundLine` (paper.js) is the one stroke on the page at this exact width and opacity.
  const ground = [...paths(book.pages[info.page - 1].items)].find((it) => it.stroke && it.sw === 1.6 && it.op === 0.85);
  assert.ok(ground, `page ${info.page} drew no ground line`);
  const ys = pathPoints(ground.d).map(([, y]) => y);
  const faceBottom = zone.y + zone.h;   // cy + d * HANG, where the drawing itself ends
  for (const y of ys) assert.ok(y >= faceBottom - 1, `a ground line point at y=${y} rises to or above the frames' own bottom (${faceBottom})`);
});

test('a solo frame stands on the step, never a ground line meant for siblings sharing it', async () => {
  const { book, report } = await compose('story-devanagari', {
    archetype: 'gathering',
    rewrite: (page, story) => {
      const [solo] = story.kin.circles.children;   // f-kid: F's only child, on her own
      assert.ok(solo, 'the fixture no longer has a solo child to test with');
      return { archetype: 'gathering', variant: 'band', density: 'family', people: [solo], groups: [{ key: 'solo:', people: [solo] }] };
    },
  });
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.people.length === 1 && p.variant === 'band');
  assert.ok(info, 'the rewrite did not produce a one-person band-variant page');
  const items = book.pages[info.page - 1].items;
  const ground = [...paths(items)].some((it) => it.stroke && it.sw === 1.6 && it.op === 0.85);
  assert.ok(!ground, 'a solo frame still got the shared ground line');
  // `step` draws two flat rects (the plinth and its lighter tread), plus its ink shadow: three.
  const stepRects = items.filter((it) => it.t === 'rect' && it.fill && it.h && it.h < 12);
  assert.ok(stepRects.length >= 3, 'a solo frame got no step under it either');
});

test('the step sits clear of the caption below it, not printing through the name line (#257 round 3, regression 1)', async () => {
  // The same solo-child rewrite as the test above: a lone cluster always takes the step (finding
  // 16), and the step is the one shelf that can overlap a caption - a thin ground line cannot, at
  // the same `y` (docs above `drawRow`, family.js).
  const { book, report } = await compose('story-devanagari', {
    archetype: 'gathering',
    rewrite: (page, story) => {
      const [solo] = story.kin.circles.children;
      assert.ok(solo, 'the fixture no longer has a solo child to test with');
      return { archetype: 'gathering', variant: 'band', density: 'family', people: [solo], groups: [{ key: 'solo:', people: [solo] }] };
    },
  });
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.people.length === 1 && p.variant === 'band');
  assert.ok(info, 'the rewrite did not produce a one-person band-variant page');
  const items = book.pages[info.page - 1].items;
  // The step's own full drawn extent: the plinth and the lighter paper-shadow tread under it - the
  // envelope a caption below has to clear, not just the plinth's own top edge.
  const stepRects = items.filter((it) => it.t === 'rect' && it.fill && it.h && it.h < 12);
  assert.ok(stepRects.length >= 3, 'a solo frame got no step under it');
  const stepBottom = Math.max(...stepRects.map((r) => r.y + r.h));
  const nameBox = report.textBoxes.find((b) => b.page === info.page && b.kind === 'name');
  assert.ok(nameBox, 'the solo frame has no name line on the page');
  assert.ok(nameBox.y >= stepBottom - 0.5,
    `the step (drawn down to y=${stepBottom.toFixed(1)}) crosses the caption's name line (its ink starts at y=${nameBox.y.toFixed(1)})`);
});

test('an aunt is drawn dramatically smaller than the parent she stands under', async () => {
  const { report, kin } = await compose('story-leaf');
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.people.some((id) => kin.people.get(id).role === 'aunt-uncle'));
  assert.ok(info, 'the fixture no longer draws aunts and uncles on the parents page');
  const zones = report.artZones.filter((z) => z.page === info.page && z.kind === 'face').map((z) => z.w);
  const biggest = Math.max(...zones), smallest = Math.min(...zones);
  // SECONDARY is 0.55 (#257 round 2, finding 18): at the old 0.72 an aunt read as barely
  // smaller than the parent she stood under. A generous band either side of 0.55 still catches
  // that regression without pinning the exact ratio `fitRow`'s own clamping can shift a little.
  const ratio = smallest / biggest;
  assert.ok(ratio < 0.65, `an aunt is only ${ratio.toFixed(2)}x the parent's size - not dramatically smaller`);
});

test('a two-word name wraps at full size instead of being floored to fit on one line', async () => {
  // "Manoj Sharma" and its five row-mates (story-large's aunts and uncles, "Mother and father")
  // each fit their own frame's width only by wrapping onto two lines - `ctx.fit` alone judges the
  // whole string standing on ONE line, so it floors a two-word name to TYPE.nameMin(9) the moment
  // the unbroken string does not fit, even though "Manoj" and "Sharma" on their own two lines have
  // room to spare at nearly the full 10.5 (#257 round 2, finding 11: sized from the frame slot,
  // not the room the words actually break into - true of a crowded row, not just a one- or
  // two-person one). Every name here wraps to two short lines, so none should be anywhere near the
  // floor.
  const { report } = await compose('story-large');
  const page = report.pages.find((p) => p.archetype === 'gathering' && p.people.length >= 6 && p.people.length <= 8 && p.page > 4);
  assert.ok(page, 'the fixture no longer draws a six-or-more-person aunts-and-uncles row');
  const names = report.textBoxes.filter((b) => b.page === page.page && b.kind === 'name');
  assert.ok(names.length >= 6, `expected at least 6 name lines on page ${page.page}, found ${names.length}`);
  const floored = names.filter((b) => b.size <= TYPE.nameMin + 0.1);
  assert.deepEqual(floored.map((b) => b.s), [], 'a name wrapped onto two short lines was still floored to the minimum size');
});

test('a row of siblings straddling the elder cutoff reads as one life stage, not two', async () => {
  // Satish Sharma (b. 1966, and so 60 against this book's 2026 year) through Kavita Sharma
  // (b. 1972): read one at a time, the elder cutoff falls between siblings born six years apart,
  // and the eldest got silver hair and spectacles while the rest stayed young (round 1 finding
  // 21). A cluster of two or more now takes one stage for the whole row.
  const people = ['p76', 'p78', 'p73', 'p79'];   // p75 carries a photo (medallion-carved, no avatar)
  const { book, report, family } = await compose('story-large', {
    archetype: 'gathering',
    rewrite: () => ({ archetype: 'gathering', density: 'family', people, groups: [{ key: 'siblings:', people }] }),
  });
  for (const id of people) assert.ok(!family.byId.get(id).name.includes('?'), 'the fixture changed under this test');
  const info = report.pages.find((p) => p.archetype === 'gathering' && p.people.length === people.length);
  assert.ok(info, 'the rewrite did not produce the expected gathering page');
  const stages = [...allUses(book.pages[info.page - 1].items)]
    .map((it) => /^pc-avatar-(?:female|male|person)-(child|youth|adult|elder)-[ab]$/.exec(it.ref)?.[1])
    .filter(Boolean);
  assert.equal(stages.length, people.length, `expected ${people.length} avatars, found ${stages.length}`);
  assert.equal(new Set(stages).size, 1, `the row split across life stages: ${stages.join(', ')}`);
});

test('a half-sibling group is drawn as its own household, not merged into one row', async () => {
  const { report, plans } = await compose('story-half-siblings');
  const plan = plans.find((p) => p.archetype === 'gathering' && p.chapter === 'siblings');
  assert.ok(plan, 'the fixture no longer draws a siblings page');
  assert.ok(plan.groups.length > 1, 'the fixture no longer splits its siblings by the parents they share');

  // The siblings are one generation, so they stand on one ground line: one band of face zones.
  const bands = new Map();
  for (const z of report.artZones.filter((a) => a.page === plan.pageNo && a.kind === 'face')) {
    const key = Math.round(z.y);
    bands.set(key, [...(bands.get(key) ?? []), z]);
  }
  const row = [...bands.values()].sort((a, b) => b.length - a.length)[0].sort((a, b) => a.x - b.x);
  assert.equal(row.length, plan.people.length, 'the siblings are not all on one ground line');
  const gaps = row.slice(1).map((z, i) => z.x - row[i].x);
  assert.ok(Math.max(...gaps) > Math.min(...gaps) + 1, 'every frame is evenly spaced: the households do not read apart');
});
