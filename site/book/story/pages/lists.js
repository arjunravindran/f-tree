/*
 * The pages that close the book out (#258): the lane of the wider family, the book in numbers, the
 * register that guarantees everyone in scope appears, the lamps for names still to be found, and
 * the legacy page back to F.
 *
 * These carry the book's honesty constraints: the register is never cut, its page references are
 * the plan's own numbers, the counts are kin.js's own, and no living person's age appears anywhere.
 * None of them works out who a page is about, how the people group or what number the page carries;
 * `story/plan.js` decided all of that before anything was drawn, and these only draw it.
 *
 * Composer code: deterministic, no clock, no locale, no DOM, no Math.random, static imports only.
 */

import { PAGE, path, rect, group } from '../../format.js';
import { starfield } from '../../blocks/art.js';
import { DENSITY } from '../plan.js';
import { chapterVars, countInCircle, kinCaption, nameOf, noteCaption, numberFact, renderCopy, rootsLine, stillToBeFoundCaption } from '../copy.js';
import { diyaRow, rangoli } from '../../art/procedural/index.js';
import { SAFE, familySeed, lifeDates, motif, sanjhiBand, scene, tailpiece, titleBlock } from './parts/furniture.js';
import { ROW, personRow, sectionHeading, splitColumns } from './parts/register-rows.js';

/** A chapter's own words, filled from what the family and the circles know (`copy.js`). */
const copyFor = (ctx, story, page, vars = {}) =>
  renderCopy(ctx.tpl.copy?.[page.copyKey], { ...chapterVars(page.copyKey, ctx.family, story.kin), ...vars });

/** The name a page prints for somebody, and whether it is a name they were actually recorded with. */
function whoIs(ctx, story, id) {
  const own = Boolean(ctx.family.byId.get(id)?.name);
  return { own, name: nameOf(ctx.family, story.kin, id) ?? 'A name still to be found' };
}

/* ------------------------------------------------------------------ our lane */

/**
 * The name over a house's door. A lane is read by surname, so it is the surname the people in this
 * house share, where two or more of them do and no earlier house on the page took it already;
 * failing that the first named person's own first name; and where nobody in the house is named at
 * all, no plate rather than a made-up one.
 */
function houseName(family, people, taken) {
  const named = people.map((id) => family.byId.get(id)?.name).filter(Boolean);
  const counts = new Map();
  for (const n of named) {
    const surname = n.trim().split(/\s+/).pop();
    counts.set(surname, (counts.get(surname) ?? 0) + 1);
  }
  let best = null;
  for (const [surname, n] of counts) if (n >= 2 && !taken.has(surname) && (!best || n > best[1])) best = [surname, n];
  if (best) return best[0];
  return named.length ? named[0].trim().split(/\s+/)[0] : null;
}

/** Whoever a house hangs off: the aunt or uncle whose branch it is, or its first member. */
const houseHead = (kin, people) => people.find((id) => kin.people.get(id)?.role === 'aunt-uncle') ?? people[0];

/**
 * Our lane: a house for every branch of the family, drawn on the `haveli-lane` scene.
 *
 * The plan decided the houses - one per aunt's or uncle's branch, a large branch over several
 * houses, at most `DENSITY.houses` a page - so this only fills the scene's own plates and name
 * boards with them, in the order the plan put them in.
 *
 * Two things the scene cannot decide for itself. The dates go under the names only while the
 * fullest house on the page has room for two lines a person, so that one crowded house does not
 * leave the page set three different ways. And the scene always draws four houses, so a page with
 * fewer takes the middle ones and hangs a marigold string over each board it has no name for: an
 * empty nameboard reads as a mistake, a garlanded one as a door this family has not opened yet.
 */
function lane(ctx, page, story) {
  const { P, family } = ctx;
  const { kin } = story;
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const s = scene(ctx, 'haveli-lane', { mirrored: page.variant === 'lane-mirrored' });
  const copy = copyFor(ctx, story, page);
  const items = [...s.items, ...titleBlock(ctx, page, copy, s.zone('title'), { ink: P.ink, soft: P.inkSoft })];

  if (page.groups.length > DENSITY.houses) {
    throw new Error(`the lane has ${page.groups.length} houses on page ${page.pageNo}, and the scene has ${DENSITY.houses} - story/plan.js's lanePages splits them, so this page was not planned by it`);
  }
  const plates = s.across('plate', DENSITY.houses);
  const boards = s.across('house', DENSITY.houses);
  const offset = Math.floor((DENSITY.houses - page.groups.length) / 2);
  const featuredName = nameOf(family, kin, kin.featured);
  const fullest = Math.max(0, ...page.groups.map((h) => h.people.length));
  const withDates = fullest <= 4;
  const taken = new Set();

  page.groups.forEach((house, i) => {
    const plate = plates[offset + i], board = boards[offset + i];
    const cx = board.x + board.w / 2;
    const name = houseName(family, house.people, taken);
    if (name) {
      taken.add(name);
      items.push(ctx.line(plate.x + plate.w / 2, plate.y + 10.5, name, 'strong', ctx.fit(name, 'strong', 9.5, plate.w, 9), P.ink, { align: 'middle', width: plate.w, kind: 'name' }));
    }
    const word = kinCaption(kin, family, houseHead(kin, house.people), featuredName);
    if (word) items.push(ctx.line(cx, board.y + 9, word, 'hand', ctx.fit(word, 'hand', 9.5, board.w, 8), P.clay, { align: 'middle', width: board.w, kind: 'caption' }));

    const unit = Math.min(withDates ? 23 : 13.5, (board.h - 18) / Math.max(1, house.people.length));
    house.people.forEach((id, j) => {
      ctx.show(id);
      const top = board.y + 18 + j * unit;
      const { own, name: who } = whoIs(ctx, story, id);
      items.push(ctx.line(cx, top + 9, who, own ? 'strong' : 'hand', ctx.fit(who, own ? 'strong' : 'hand', 9.5, board.w, 9), own ? P.ink : P.brass, { align: 'middle', width: board.w, kind: 'name' }));
      const dates = withDates ? lifeDates(family.byId.get(id)) : '';
      if (dates) items.push(ctx.line(cx, top + 18.5, dates, 'text', 8.4, P.inkSoft, { align: 'middle', width: board.w, kind: 'lifespan' }));
    });
  });

  for (let i = 0; i < DENSITY.houses; i++) {
    if (i >= offset && i < offset + page.groups.length) continue;
    const plate = plates[i];
    items.push(ctx.art.place('mala', { x: plate.x + plate.w / 2, y: plate.y - 1, w: plate.w + 16, shadow: { dx: 0.8, dy: 1.1 } }));
  }

  items.push(...ctx.footer(P.inkSoft));
  return ctx.page(copy?.title ?? 'Our lane', items, P.paper);
}

/* ------------------------------------------------------------------ in numbers */

/*
 * What the numbers page may count, most telling first, with the motif that illustrates each.
 *
 * Every count is a `kin.js` circle's own (`countInCircle`) and every sentence is `copy.js`'s
 * (`numberFact`), so a figure here cannot disagree with the chapter it describes, and nothing on
 * this page is close enough to a birth year to become somebody's age. The nouns English does not
 * pluralise with an "s" say both forms, because `numberFact` will not guess.
 *
 * None of the motifs is a lamp. Lamps count people in this book, and a lamp beside a figure on
 * this page would read as counting the wrong thing.
 */
export const FIGURES = [
  { motif: 'mango-leaf', circle: 'siblings', noun: 'sibling' },
  { motif: 'lotus', circle: 'children', noun: { one: 'child', many: 'children' } },
  { motif: 'marigold', circle: 'branches', role: 'cousin', noun: 'cousin' },
  { motif: 'peepal', circle: 'branches', role: 'aunt-uncle', noun: { one: 'aunt or uncle', many: 'aunts and uncles' } },
  { motif: 'marigold-bead', circle: 'descendants', role: 'grandchild', noun: { one: 'grandchild', many: 'grandchildren' } },
  { motif: 'mango-leaf', circle: 'grandparents', noun: { one: 'grandparent', many: 'grandparents' } },
  { motif: 'marigold', circle: 'in-laws', noun: { one: 'relative by marriage', many: 'relatives by marriage' } },
  { motif: 'lotus', circle: 'lane', noun: { one: 'more relative in the family', many: 'more relatives in the family' } },
];

/** How many vignettes the page has room for without crowding. */
const MAX_FIGURES = 6;

/** The figures this family can actually support. A count of nobody is not a fact. */
export function figuresFor(family, kin) {
  const out = [];
  for (const f of FIGURES) {
    const line = numberFact(family, kin, countInCircle(kin, f.circle, f.role ?? null), f.noun);
    if (line) out.push({ motif: f.motif, line });
    if (out.length === MAX_FIGURES) break;
  }
  return out;
}

/**
 * In numbers: the family counted from where the featured person stands, as illustrated vignettes.
 *
 * The sentence carries its own number, in words, so nothing here prints a bare numeral beside it:
 * "twelve cousins" said twice, once as a figure and once as a word, is the same fact twice.
 */
function numbers(ctx, page, story) {
  const { P, family } = ctx;
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const banded = page.variant === 'vignettes-mirrored';
  const copy = copyFor(ctx, story, page);
  const items = banded ? sanjhiBand(ctx, { tint: 'clay' }) : corners(ctx);
  items.push(...titleBlock(ctx, page, copy, { x: SAFE.x, y: banded ? 96 : 84, w: SAFE.w, h: 80 }, { ink: P.ink, soft: P.inkSoft }));

  const figures = figuresFor(family, story.kin);
  const colW = (SAFE.w - 34) / 2;
  const top = 200;
  const rowH = (SAFE.bottom - 66 - top) / Math.max(1, Math.ceil(figures.length / 2));
  figures.forEach(({ motif: id, line }, i) => {
    const x = SAFE.x + (i % 2) * (colW + 34);
    const middle = top + (Math.floor(i / 2) + 0.5) * rowH;
    items.push(motif(ctx, id, x + 38, middle, 68, { shadow: { dx: 1.2, dy: 1.6 } }));
    items.push(...ctx.lines(x + 86, middle - 8, line, 'hand', 13.5, P.ink, { width: colW - 92, maxLines: 3, lead: 18, kind: 'body' }).items);
  });
  if (figures.length) items.push(ctx.art.place('divider-lotus', { x: PAGE.w / 2, y: SAFE.bottom - 16, w: 150, op: 0.7 }));

  items.push(...ctx.footer(P.inkSoft));
  return ctx.page(copy?.title ?? 'In numbers', items, P.paper);
}

/** A paisley in each top corner: the day page's quiet alternative to a Sanjhi band. */
const corners = (ctx) => [
  ctx.art.place('corner-paisley', { x: 18, y: 18, w: 58, anchor: 'top-left', op: 0.55 }),
  ctx.art.place('corner-paisley', { x: PAGE.w - 18, y: 18, w: 58, anchor: 'top-right', flip: 'x', op: 0.55 }),
];

/* ------------------------------------------------------------------ everyone: the register */

/*
 * A register page draws a bust for every row, which is the approved look, and about 590 KB of
 * estimated PDF for a full page of 48: the ground behind each bust is a gradient, and a gradient is
 * the most expensive thing `compose.js`'s `artTerm` counts. A family whose register runs past
 * `PORTRAIT_PAGES` pages would spend most of a 10 MB book on them, so past that the whole register
 * drops the cameos together - decided once, from the plan, so every register page in one book looks
 * the same rather than the cameos stopping mid-list. It is always the picture that goes, never a
 * person: the rows, the names and the page references are identical either way.
 *
 * Three pages is about 144 people. Of the QA fixtures only the two largest (180 and 200 people) are
 * over it.
 */
const PORTRAIT_PAGES = 3;

/**
 * This page's rows, in two columns: a row for each section heading, a row for each person.
 *
 * The plan counted the page's 48 rows exactly this way, so this never adds or drops one - except at
 * the column break, where a section running across it repeats its heading over the second column.
 * A column of names under no heading at all says nothing about who they are, and one extra heading
 * still leaves the page well inside the paper.
 */
function registerColumns(page, carried) {
  const rows = [];
  page.groups.forEach((section, i) => {
    rows.push({ heading: section.key, continued: i === 0 && section.key === carried });
    for (const id of section.people) rows.push({ id });
  });
  const [first, second] = splitColumns(rows);
  if (second.length && !second[0].heading) {
    const open = [...first].reverse().find((r) => r.heading);
    if (open) second.unshift({ heading: open.heading, continued: true });
  }
  return [first, second];
}

/**
 * Everyone: the register of everyone in scope, by the circle they belong to, with the page each is
 * on. This page is the book's completeness guarantee, and it is never cut.
 *
 * The plan paginated it - 48 rows a page, a section heading costing one of them - so this draws the
 * rows it was given and never decides to carry one over. A section that started on the page before
 * says "continued" over it, the way the approved frame does.
 */
function register(ctx, page, story) {
  const { P } = ctx;
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const banded = page.variant === 'columns';
  const copy = copyFor(ctx, story, page);
  const items = banded ? sanjhiBand(ctx, { tint: 'clay' }) : corners(ctx);
  items.push(...titleBlock(ctx, page, copy, { x: SAFE.x, y: banded ? 96 : 84, w: SAFE.w, h: 80 }, { ink: P.ink, soft: P.inkSoft }));

  // A section still running at the foot of the page before continues on this one.
  const before = story.plan.pages[page.pageNo - 2];
  const carried = before?.archetype === 'register' ? before.groups[before.groups.length - 1]?.key : null;
  const columns = registerColumns(page, carried);

  const portraits = story.plan.pages.filter((p) => p.archetype === 'register').length <= PORTRAIT_PAGES;
  const gutter = 28;
  const colW = (SAFE.w - gutter) / 2;
  const top = 214;
  columns.forEach((column, c) => {
    const x = SAFE.x + c * (colW + gutter);
    column.forEach((row, i) => {
      const y = top + i * ROW;
      if (row.heading) items.push(...sectionHeading(ctx, row.heading, x, y, colW, row.continued));
      else items.push(...personRow(ctx, story, row.id, x, y, colW, { portraits }));
    });
  });

  const foot = top + Math.max(...columns.map((c) => c.length)) * ROW;
  if (foot + 40 < SAFE.bottom) items.push(ctx.art.place('divider-lotus', { x: PAGE.w / 2, y: foot + 26, w: 150, op: 0.6 }));
  items.push(...ctx.footer(P.inkSoft));
  return ctx.page(copy?.title ?? 'Everyone', items, P.paper);
}

/* ------------------------------------------------------------------ still to be found */

/**
 * How a page names somebody whose own name the record lost, and what it says under them.
 *
 * The name is `copy.js`'s: the relative they are named through ("Shyam Lal's wife"), or, for
 * somebody no named neighbour can place, `stillToBeFoundCaption`'s own sentence ("Ankit's
 * great-grandmother, on the maternal side"). The line under it adds their kin word and when they
 * lived - but not a kin word the name has already said, so a page never prints "Raj Kumar's late
 * wife" over "late wife".
 */
function lostName(ctx, story, id, featuredName) {
  const { family } = ctx;
  const { kin } = story;
  const own = nameOf(family, kin, id);
  const name = own ?? stillToBeFoundCaption(family, kin, id) ?? 'A name still to be found';
  const word = own ? kinCaption(kin, family, id, featuredName) : null;
  const fresh = word && !name.toLowerCase().endsWith(word.toLowerCase()) ? word : null;
  return { name, under: [fresh, lifeDates(family.byId.get(id))].filter(Boolean).join(' · ') };
}

/** How many lamps this book lights for names still to be found: the plan's own count, not a tally. */
const lampsLit = (plan) => plan.pages.reduce((n, p) => n + (p.archetype === 'still-to-be-found' ? p.people.length : 0), 0);

/**
 * Still to be found: a lamp kept for every name nobody has written down yet, on the
 * `remembrance-night` scene, each placed by its relation to the featured person.
 *
 * Up to four names get the treatment the design system asks for at scene size - an aala, a niche in
 * the wall of their own house with a lamp kept in it - and their captions under them. Past that an
 * aala would be too small to read as one, so the page keeps one lamp each in a row of
 * `lamp-unknown` (`diyaRow`'s `unknownAt`, every lamp of it) and lists the names below: exactly one
 * lamp per person either way, which is the rule the whole book is built on.
 */
function stillToBeFound(ctx, page, story) {
  const { P, family } = ctx;
  const { kin } = story;
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const s = scene(ctx, 'remembrance-night', { mirrored: page.variant === 'lamps-mirrored' });
  // The chapter's `{n}` is how many lamps the book actually lights, which is the plan's own count
  // of the people it put on these pages.
  const copy = copyFor(ctx, story, page, { n: lampsLit(story.plan) });
  const items = [...s.items, ...titleBlock(ctx, page, copy, s.zone('title'), { ink: P.flame, soft: P.gold, align: 'middle' })];

  const featuredName = nameOf(family, kin, kin.featured);
  const who = page.people;
  const niches = s.zone('niches');
  const names = s.zone('names');

  if (who.length && who.length <= 4) {
    const cell = niches.w / who.length;
    const height = Math.min(niches.h * 0.92, cell * 0.95);
    who.forEach((id, i) => {
      ctx.show(id);
      const cx = niches.x + cell * (i + 0.5);
      items.push(ctx.art.place('aala', { x: cx, y: niches.y + niches.h, h: height }));
      const { name, under } = lostName(ctx, story, id, featuredName);
      const own = Boolean(family.byId.get(id)?.name);
      items.push(ctx.line(cx, names.y + 13, name, own ? 'strong' : 'hand', ctx.fit(name, own ? 'strong' : 'hand', 11, cell - 12, 9), own ? P.card : P.flame, { align: 'middle', width: cell - 12, kind: 'name' }));
      if (under) items.push(ctx.line(cx, names.y + 27, under, 'text', ctx.fit(under, 'text', 8.6, cell - 12, 8), P.silver, { align: 'middle', width: cell - 12, kind: 'caption' }));
      const note = noteCaption(page.copyKey, family, id, ctx.options);
      if (note && who.length <= 2) items.push(...ctx.lines(cx, names.y + 48, note, 'hand', 11, P.gold, { width: cell - 20, maxLines: 2, lead: 14, align: 'middle', kind: 'body' }).items);
    });
  } else if (who.length) {
    for (const id of who) ctx.show(id);
    const y = niches.y + niches.h * 0.74;
    const w = Math.min(34, (niches.w - 24) / who.length);
    items.push(diyaRow(ctx.art, niches.x + 16, y, niches.x + niches.w - 16, y, who.length, familySeed(ctx, story, 'still-to-be-found'), { w, unknownAt: () => true }));
    ctx.zone('busy', { x: niches.x, y: y - w * 1.2, w: niches.w, h: w * 1.4 });
    const cols = who.length <= 8 ? 2 : 3;
    const colW = names.w / cols;
    const lead = Math.min(17, names.h / Math.ceil(who.length / cols));
    who.forEach((id, i) => {
      const { name } = lostName(ctx, story, id, featuredName);
      const own = Boolean(family.byId.get(id)?.name);
      const cx = names.x + colW * ((i % cols) + 0.5);
      items.push(ctx.line(cx, names.y + 11 + Math.floor(i / cols) * lead, name, own ? 'strong' : 'hand', ctx.fit(name, own ? 'strong' : 'hand', 9.6, colW - 10, 9), own ? P.card : P.flame, { align: 'middle', width: colW - 10, kind: 'name' }));
    });
  }

  const closing = s.zone('closing');
  items.push(...tailpiece(ctx, closing.x + closing.w / 2, closing.y + 26, 150));

  // The one rangoli this chapter lays, tilted onto the floor as `style-frames/frames.mjs` draws it,
  // and seeded from the family so two families never get the same pattern.
  const floor = s.zone('rangoli');
  const R = Math.min(floor.w / 2, floor.h / 0.6);
  items.push(group([rangoli(P, 0, 0, R, familySeed(ctx, story, 'rangoli'), { ground: P.paperDeep, colours: [P.gold, P.saffron, P.card, P.rani] })],
    { tf: [1, 0, 0, 0.3, floor.x + floor.w / 2, floor.y + floor.h / 2], op: 0.8 }));

  items.push(...ctx.footer(P.silver));
  return ctx.page(copy?.title ?? 'Still to be found', items, P.night);
}

/* ------------------------------------------------------------------ legacy */

/**
 * How many generations stand behind the featured person, read off `kin.js`'s own entries: the
 * distinct generations among their parents, their grandparents and every ancestor beyond them.
 * Never a fixed number, and never a walk of its own.
 */
export function generationsBehind(kin) {
  const gens = new Set();
  for (const circle of ['parents', 'grandparents', 'ancestors']) {
    for (const id of kin.circles[circle] ?? []) {
      const g = kin.people.get(id)?.gen;
      if (g !== null && g !== undefined) gens.add(g);
    }
  }
  return gens.size;
}

/**
 * The torn card the book's own voice is written on (design system, "Page furniture"): one a page at
 * most, in the hand, with a strip of tape across the top and a hand-torn lower edge.
 */
function handCard(ctx, words, cx, top, w) {
  const { P } = ctx;
  const body = ctx.lines(cx, top + 30, words, 'hand', 12.5, P.ink, { width: w - 40, maxLines: 4, lead: 17.5, align: 'middle', kind: 'body' });
  const h = Math.max(62, body.bottom - top + 18);
  const x0 = cx - w / 2, x1 = cx + w / 2, y1 = top + h;
  // The lower edge is torn: four shallow curves across it, not a ruled line.
  const torn = (dx, dy) => `M ${x0 + dx} ${top + dy} L ${x1 + dx} ${top + dy} L ${x1 + dx} ${y1 - 5 + dy}`
    + ` C ${x1 - w * 0.25 + dx} ${y1 + 3 + dy} ${x0 + w * 0.6 + dx} ${y1 - 7 + dy} ${x0 + w * 0.45 + dx} ${y1 - 1 + dy}`
    + ` C ${x0 + w * 0.3 + dx} ${y1 + 5 + dy} ${x0 + w * 0.15 + dx} ${y1 - 6 + dy} ${x0 + dx} ${y1 - 2 + dy} Z`;
  ctx.zone('text', { x: x0, y: top, w, h });
  return [
    path(torn(2.2, 3), { fill: P.ink, op: 0.2 }),
    path(torn(0, 0), { fill: P.card }),
    path(`M ${cx - 30} ${top - 6} L ${cx + 28} ${top - 9} L ${cx + 30} ${top + 5} L ${cx - 28} ${top + 8} Z`, { fill: P.silver, op: 0.8 }),
    ...body.items,
  ];
}

/**
 * Legacy: one line of light back to the featured person - a lamp for every generation behind them,
 * up the line `kin.js` actually recorded, with their own lamp burning at the foot of it.
 *
 * The lamps count generations, not people, so nothing decorative is lit beside them and the
 * tailpiece is left off. The handwritten note is the template's own line, or, where the reader
 * asked for notes and the featured person has one, theirs.
 */
function legacy(ctx, page, story) {
  const { P, family } = ctx;
  const { kin } = story;
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const mirrored = page.variant === 'line-mirrored';
  const copy = copyFor(ctx, story, page);
  const sky = ctx.gradient('legacy-sky', {
    type: 'radial', cx: PAGE.w / 2, cy: PAGE.h * 0.66, r: PAGE.h * 0.8,
    stops: [[0, P.glow, 1], [0.5, P.night, 1], [1, P.deep, 1]],
  });
  const items = [
    rect(0, 0, PAGE.w, PAGE.h, { fill: sky }),
    starfield(familySeed(ctx, story, 'legacy-sky'), 80, { x: 0, y: 40, w: PAGE.w, h: PAGE.h * 0.55 }, P.flame),
  ];
  items.push(...titleBlock(ctx, page, { title: copy?.title }, { x: SAFE.x, y: 74, w: SAFE.w, h: 70 }, { ink: P.flame, align: 'middle' }));

  // The line climbs from the deepest generation on record down to the featured person's own lamp.
  const gens = generationsBehind(kin);
  const axis = mirrored ? 420 : 175;
  const step = Math.min(70, 300 / Math.max(1, gens));
  // The column is centred in the page's open middle however tall it turns out to be, so a family
  // with two generations behind it does not leave half a night sky empty above the lamps.
  const base = 460 + (step * gens) / 2 + 40;
  const top = base - step * (gens + 0.5);
  items.push(path(`M ${axis} ${base} L ${axis} ${top}`, { stroke: P.gold, sw: 1, op: 0.3 }));
  // A lamp never grows past the pitch that carries it: an imported line twenty generations deep
  // still has to read as one lamp per generation, not as a smear of overlapping ones.
  const lamp = (i, t) => Math.min(17 + 9 * t, step * 0.86);
  if (gens) items.push(diyaRow(ctx.art, axis, base - step * gens, axis, base - step, gens, familySeed(ctx, story, 'legacy'), { w: lamp, jitter: 0.1 }));

  items.push(ctx.art.place('diya', { x: axis, y: base, w: 38 }));
  ctx.zone('busy', ctx.art.box('diya', { x: axis, y: base, w: 38 }));
  ctx.show(kin.featured);
  const me = nameOf(family, kin, kin.featured);
  if (me) items.push(ctx.line(axis, base + 22, me, 'strong', ctx.fit(me, 'strong', 12, 190, 9), P.card, { align: 'middle', width: 190, kind: 'name' }));

  const roots = rootsLine(family, kin);
  if (roots) items.push(ctx.line(axis, top - 20, roots, 'hand', ctx.fit(roots, 'hand', 12, 190, 8), P.gold, { align: 'middle', width: 190, kind: 'caption' }));

  const words = noteCaption(page.copyKey, family, kin.featured, ctx.options) ?? copy?.line;
  if (words) items.push(...handCard(ctx, words, mirrored ? 185 : 415, (top + base) / 2 - 46, 210));

  items.push(...ctx.footer(P.silver));
  return ctx.page(copy?.title ?? 'One line of light', items, P.deep);
}

/** Archetype id -> draw(ctx, page, story). #258: lane, numbers, register, still-to-be-found, legacy. */
export const PAGES = Object.freeze({ lane, numbers, register, 'still-to-be-found': stillToBeFound, legacy });
