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

import { PAGE, path, rect, circle, group } from '../../format.js';
import { starfield } from '../../blocks/art.js';
import { DENSITY } from '../plan.js';
import { chapterVars, countInCircle, kinCaption, nameOf, noteCaption, numberFact, renderCopy, rootsLine, stillToBeFoundCaption } from '../copy.js';
import { diyaRow, rangoli } from '../../art/procedural/index.js';
import { seeded } from '../../art/seed.js';
import { SAFE, familySeed, lifeDates, motif, sanjhiBand, scene, titleBlock } from './parts/furniture.js';
import { ROW, duplicateRegisterNames, personRow, sectionHeading, splitColumns } from './parts/register-rows.js';

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
 * failing that, the fullest name this house has - a bare given name reads as a shop sign, not a
 * household, so a surname already spoken for by an earlier house on the page still leaves the
 * fuller "given name and surname" on the plate rather than degrading further (round 2, finding 10;
 * approved frame 4 uses "Bhola Prasad", never "Bhola").
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
  return named.length ? named[0] : null;
}

/**
 * The aunt or uncle a house's branch descends from - `kin.js`'s own `branch` field, which every
 * member of a branch carries whether or not that ancestor is one of the people standing in this
 * particular house. Captioning the branch's own anchor, not whichever member happens to be first,
 * is what lets the caption describe the whole household ("father's half-sister") rather than one
 * person in it ("nephew"), the way approved frame 4 reads (round 2, finding 11).
 */
function houseBranch(kin, people) {
  for (const id of people) {
    const b = kin.people.get(id)?.branch;
    if (b) return b;
  }
  return people[0];
}

/** Whether two of a house's own named people share a name - the register's page reference alone
 * cannot tell them apart there, so the lane owes them their dates even in a crowded house
 * (round 2, finding 9). */
function hasDuplicateName(family, people) {
  const seen = new Set();
  for (const id of people) {
    const n = family.byId.get(id)?.name;
    if (!n) continue;
    if (seen.has(n)) return true;
    seen.add(n);
  }
  return false;
}

/**
 * Which of the scene's `DENSITY.houses` slots this page's groups stand in, and which are blank.
 * Groups keep the plan's own order and, whenever more than one slot is empty, stay centred exactly
 * as before (an even run of blanks either side is already balanced). A *single* blank, though, used
 * to fall at `Math.floor` of an odd split every time - always the rightmost slot, the lane's most
 * saturated colour - so it read as the first thing on the page rather than an unused frame
 * (round 2, finding 8). It now lands in one of the two interior slots, chosen from the family's own
 * seed rather than always the same one.
 */
function laneSlots(ctx, story, groupCount) {
  const total = DENSITY.houses;
  const blanks = total - groupCount;
  if (blanks === 1 && total === 4) {
    const blank = seeded(familySeed(ctx, story, 'lane-blank'))() < 0.5 ? 1 : 2;
    const slots = [];
    for (let i = 0; i < total; i++) if (i !== blank) slots.push(i);
    return { slots, blankSlots: [blank] };
  }
  const offset = Math.floor(blanks / 2);
  const slots = Array.from({ length: groupCount }, (_, i) => offset + i);
  const blankSlots = [];
  for (let i = 0; i < total; i++) if (i < offset || i >= offset + groupCount) blankSlots.push(i);
  return { slots, blankSlots };
}

/** An absolute rectangle path, `M L L L Z`, wound from `(x, y)` by `(w, h)` - the one shape this
 * file clips with (`laneClip`); a negative `w` winds it the other way. */
const rectPath = (x, y, w, h) => `M ${x} ${y} L ${x + w} ${y} L ${x + w} ${y + h} L ${x} ${y + h} Z`;

/** The sky wash's own bottom edge (`lane`, below): the `haveli-lane` scene's shared "roofs" zone's
 * own top edge, `art/src/papercut/scenes/haveli-lane.svg`, plus a little of the room it reserves.
 * That zone belongs to the town's own skyline behind the houses, not to any one house's roofline -
 * round 3 found it standing in for one anyway (`houseHole`, below). */
const SKY_WASH_DEPTH = 296;

/** The `haveli-lane` scene's own doorstep zones' bottom edge, read off its authored SVG - a house's
 * own hole or wash still reaches this far down (round 3, part 2 tightens it to the doorstep zone's
 * own *top* edge instead, so the otla and the street beneath a house survive its own cut). */
const HOUSE_STEP_Y = 594;
const HOUSE_HOLE_PAD = 8;

/** Each of the lane's four houses' own wall height - `tools/book_scenes.mjs`'s `haveliLane`'s own
 * `houses` array (`h`), the only place the lane's staggered rooflines are recorded, because the
 * scene draws a real uneven skyline rather than four identical boxes. Keyed by the house number a
 * `plate-N`/`house-N`/`doorstep-N` zone's own name carries, never by page slot, so a mirrored lane
 * still washes and cuts the house it means to (`houseNumber`, below).
 *
 * Round 3: a single shared top (280, this array's tallest house's own roof minus its own pad) stood
 * in for every house's own box. The tallest house it was measured from wore it correctly; the other
 * three carried a box reaching well above their own roof - a hard-edged panel standing over the
 * house against the sky, and, because it was the same height on every house regardless of page, a
 * flat line cut across what should be an uneven skyline.
 */
const HOUSE_WALL_HEIGHT = { 1: 236, 2: 262, 3: 244, 4: 272 };

/** The house number a lane zone's own name carries (`plate-3` -> 3), never the page slot it was
 * sorted into - a mirrored lane reorders `plates`/`boards`/`doorsteps` left to right, but a zone's
 * own name always names the same one of the scene's four authored houses. */
function houseNumber(zoneName) {
  const n = Number(String(zoneName).split('-').pop());
  if (!HOUSE_WALL_HEIGHT[n]) throw new Error(`lane: "${zoneName}" doesn't name one of the lane's four houses`);
  return n;
}

/** The hole a blank house slot punches in the lane scene, or the box its wash tints (`lane`,
 * below): this one house's plate, board and doorstep zones' combined width, padded a little, from a
 * little above that particular house's own roofline down to the doorstep zones' own bottom edge. */
function houseHole(plate, board, doorstep) {
  const x0 = Math.min(plate.x, board.x, doorstep.x) - HOUSE_HOLE_PAD;
  const x1 = Math.max(plate.x + plate.w, board.x + board.w, doorstep.x + doorstep.w) + HOUSE_HOLE_PAD;
  const y0 = doorstep.y - HOUSE_WALL_HEIGHT[houseNumber(board.name)] - HOUSE_HOLE_PAD;
  return { x: x0, y: y0, w: x1 - x0, h: HOUSE_STEP_Y - y0 };
}

/**
 * One or more house-shaped holes punched through the whole lane scene: the page wound one way,
 * each hole wound the other, so format 2's nonzero fill rule leaves them empty rather than filled -
 * the sky, the street and the other houses all stay (round 2, finding 7, the same group-clip
 * technique #257's `cutHouse` uses on the courtyards scene). A blank house used to keep its full
 * furnished front and hang a marigold string over its empty nameboard - the book's own mourning
 * notation, over a house nobody has died in.
 */
function laneClip(holes) {
  return rectPath(0, 0, PAGE.w, PAGE.h) + holes.map((h) => rectPath(h.x + h.w, h.y, -h.w, h.h)).join('');
}

/**
 * A few accent tints the lane may wash across its sky and its houses - existing palette tokens,
 * never a new colour of their own. Chosen from the family's own seed together with which branches
 * actually stand on *this* page (its `groups`' own keys), never the page number
 * (site/book/art/README.md, rule 6, so moving a page elsewhere in the book never reshuffles it):
 * `story-large` used to run p10-p17 as the identical amber sky and the identical four house
 * colours, eight times over, with only the mirror telling one page from the next (round 2, finding
 * 12). The wash sits under everything drawn afterward, at low enough opacity that the scene's own
 * colours and the names printed over it both still read - it changes a page's mood, not its facts.
 */
const LANE_WASHES = ['rani', 'peacock', 'indigo', 'leaf', 'sindoor', 'wash'];

/** One of `LANE_WASHES`, seeded from the family, this page's own groups and a `what` that keeps
 * the sky's own pick independent of any one house's. */
function laneTint(ctx, story, page, what) {
  const key = page.groups.map((g) => g.key).join(',') || 'blank';
  const roll = seeded(`${familySeed(ctx, story, `lane-tint-${what}`)}|${key}`)();
  return LANE_WASHES[Math.floor(roll * LANE_WASHES.length)];
}

/**
 * Our lane: a house for every branch of the family, drawn on the `haveli-lane` scene.
 *
 * The plan decided the houses - one per aunt's or uncle's branch, a large branch over several
 * houses, at most `DENSITY.houses` a page - so this only fills the scene's own plates and name
 * boards with them, in the order the plan put them in.
 *
 * A few things the scene cannot decide for itself. A house's own dates go under its own names only
 * while that house has room for two lines a person - or wherever two of its own people share a
 * name - never suppressed for the whole page by one crowded house elsewhere on it (finding 9). And
 * the scene always draws `DENSITY.houses` houses, so a page with fewer cuts the unused ones out of
 * the paper instead of dressing them up with nothing behind the door (finding 7, finding 8).
 */
function lane(ctx, page, story) {
  const { P, family } = ctx;
  const { kin } = story;
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const s = scene(ctx, 'haveli-lane', { mirrored: page.variant === 'lane-mirrored' });
  const copy = copyFor(ctx, story, page);

  if (page.groups.length > DENSITY.houses) {
    throw new Error(`the lane has ${page.groups.length} houses on page ${page.pageNo}, and the scene has ${DENSITY.houses} - story/plan.js's lanePages splits them, so this page was not planned by it`);
  }
  const plates = s.across('plate', DENSITY.houses);
  const boards = s.across('house', DENSITY.houses);
  const doorsteps = s.across('doorstep', DENSITY.houses);
  const { slots, blankSlots } = laneSlots(ctx, story, page.groups.length);

  const scenePic = blankSlots.length
    ? group([s.items[0]], { clip: laneClip(blankSlots.map((i) => houseHole(plates[i], boards[i], doorsteps[i]))) })
    : s.items[0];
  // The sky wash (round 2, finding 12): a translucent tint over the scene's own upper third, so a
  // family with several lane pages does not see the identical amber sky on every one of them.
  const skyWash = rect(0, 0, PAGE.w, SKY_WASH_DEPTH, { fill: P[laneTint(ctx, story, page, 'sky')], op: 0.2 });
  const items = [scenePic, skyWash, ...titleBlock(ctx, page, copy, s.zone('title'), { ink: P.ink, soft: P.inkSoft })];

  const featuredName = nameOf(family, kin, kin.featured);
  const taken = new Set();

  page.groups.forEach((house, i) => {
    const slot = slots[i];
    const plate = plates[slot], board = boards[slot];
    const cx = board.x + board.w / 2;
    // The house's own wash (round 2, finding 12): the same low-opacity tint idea as the sky,
    // over this one house's own footprint, seeded per house so the four houses on a page do not
    // all draw the same tint - and so a family whose lane runs several pages sees a different
    // house-to-colour pairing from one page to the next.
    const hole = houseHole(plate, board, doorsteps[slot]);
    items.push(rect(hole.x, hole.y, hole.w, hole.h, { fill: P[laneTint(ctx, story, page, `house-${slot}`)], op: 0.18 }));
    const name = houseName(family, house.people, taken);
    if (name) {
      taken.add(name);
      items.push(ctx.line(plate.x + plate.w / 2, plate.y + 10.5, name, 'strong', ctx.fit(name, 'strong', 9.5, plate.w, 9), P.ink, { align: 'middle', width: plate.w, kind: 'name' }));
    }
    const word = kinCaption(kin, family, houseBranch(kin, house.people), featuredName);
    if (word) items.push(ctx.line(cx, board.y + 9, word, 'hand', ctx.fit(word, 'hand', 9.5, board.w, 8), P.clay, { align: 'middle', width: board.w, kind: 'caption' }));

    const withDates = house.people.length <= 4 || hasDuplicateName(family, house.people);
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

  // The closing hand line the approved frame carries, and the lane's own furniture stopped short
  // of (round 2, finding 13): the scene's own name blocks leave the lower quarter of the page
  // bare, and a single line of the book's voice is the cheapest, safest way to close that without
  // a new toran on every door pushing the byte budget past what the cost analysis allows.
  const closingLine = 'Every door on this lane opens to family.';
  items.push(ctx.line(PAGE.w / 2, PAGE.h - 62, closingLine, 'hand', ctx.fit(closingLine, 'hand', 14, SAFE.w, 10), P.ink, { align: 'middle', width: SAFE.w, kind: 'body' }));

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
/*
 * The six most commonly shown together - siblings through grandparents, `MAX_FIGURES` below - each
 * get their own motif from the vocabulary (round 2, finding 24: `mango-leaf` and `marigold` used to
 * cover two circles apiece, so a family with both drew the same cut twice on one page). Only the
 * two lowest-priority circles, in-laws and the lane overflow, reuse an earlier motif - they are cut
 * by `MAX_FIGURES` whenever the six above them are all non-zero, so the reuse is rarely seen beside
 * its twin.
 */
export const FIGURES = [
  { motif: 'mango-leaf', circle: 'siblings', noun: 'sibling' },
  { motif: 'lotus', circle: 'children', noun: { one: 'child', many: 'children' } },
  { motif: 'marigold', circle: 'branches', role: 'cousin', noun: 'cousin' },
  { motif: 'peepal', circle: 'branches', role: 'aunt-uncle', noun: { one: 'aunt or uncle', many: 'aunts and uncles' } },
  { motif: 'marigold-bead', circle: 'descendants', role: 'grandchild', noun: { one: 'grandchild', many: 'grandchildren' } },
  { motif: 'kandil', circle: 'grandparents', noun: { one: 'grandparent', many: 'grandparents' } },
  { motif: 'marigold', circle: 'in-laws', noun: { one: 'relative by marriage', many: 'relatives by marriage' } },
  { motif: 'lotus', circle: 'lane', noun: { one: 'more relative in the family', many: 'more relatives in the family' } },
];

/** How many vignettes the page has room for without crowding. */
const MAX_FIGURES = 6;

/** The figures this family can actually support. A count of nobody is not a fact. */
export function figuresFor(family, kin) {
  const out = [];
  for (const f of FIGURES) {
    const n = countInCircle(kin, f.circle, f.role ?? null);
    const line = numberFact(family, kin, n, f.noun, out.length);
    if (line) out.push({ motif: f.motif, line, count: n });
    if (out.length === MAX_FIGURES) break;
  }
  return out;
}

/** Past this many, the figure's own repeated shapes would crowd into a smear rather than read as
 * separate pieces, so the vignette falls back to one motif standing for "several" instead. */
const COUNTABLE_FIGURE_MAX = 5;

/**
 * A figure vignette, not a bare icon (round 2, finding 25): a soft ink shadow, a paper-deep mat
 * disc and a thin gold ring, under two to five small copies of the motif arranged like petals
 * where `count` is small enough to lay out on its own - five lotus petals for five children, not
 * one lotus standing in for "some" - or one motif at full size where it isn't. The same symbol
 * placed several times, never a second drawing (site/book/art/README.md, "reuse, don't copy").
 */
export function figureVignette(ctx, id, cx, cy, size, count = 0) {
  const { P } = ctx;
  const r = size * 0.62;
  ctx.zone('busy', { x: cx - r, y: cy - r, w: r * 2, h: r * 2 });
  const items = [
    circle(cx + 1.6, cy + 2, r, { fill: P.ink, op: 0.08 }),
    circle(cx, cy, r, { fill: P.paperDeep }),
    circle(cx, cy, r, { stroke: P.gold, sw: 0.8, op: 0.55 }),
  ];
  if (count >= 2 && count <= COUNTABLE_FIGURE_MAX) {
    const petal = size * 0.42, spread = r * 0.56;
    for (let i = 0; i < count; i++) {
      const a = (i / count) * Math.PI * 2 - Math.PI / 2;
      items.push(motif(ctx, id, cx + Math.cos(a) * spread, cy + Math.sin(a) * spread, petal, { shadow: { dx: 0.6, dy: 0.9 } }));
    }
  } else {
    items.push(motif(ctx, id, cx, cy, size, { shadow: { dx: 1, dy: 1.4 } }));
  }
  return items;
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
  figures.forEach(({ motif: id, line, count }, i) => {
    const x = SAFE.x + (i % 2) * (colW + 34);
    const middle = top + (Math.floor(i / 2) + 0.5) * rowH;
    items.push(...figureVignette(ctx, id, x + 38, middle, 68, count));
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
 * The foot vignette every register page carries (round 2, finding 1): its rendered height, and the
 * gap either side of it. Sized so even a full 48-row page - two columns of 24, the tallest the plan
 * ever paginates - still has room to sit below `SAFE.bottom`.
 */
const FOOT_H = 54;
const FOOT_GAP = 12;

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
  const disambiguate = duplicateRegisterNames(ctx, story);
  const gutter = 28;
  // A register short enough to sit in one column takes the whole width rather than being left in
  // the left half with the right half bare (round 2, finding 2).
  const singleColumn = columns[1].length === 0;
  const colW = singleColumn ? SAFE.w : (SAFE.w - gutter) / 2;

  // A page with a lot fewer rows than it could hold is centred in the room below the title, with
  // the foot vignette's own space kept clear beneath it, rather than pinned to the top of an
  // otherwise empty sheet (round 2, finding 2). A full page keeps its old top - there is no room
  // to centre it, and it already reaches close to `SAFE.bottom`.
  const top0 = 214;
  const rowsMax = Math.max(1, ...columns.map((c) => c.length));
  const contentH = rowsMax * ROW;
  const spareRoom = SAFE.bottom - top0 - FOOT_GAP - FOOT_H - FOOT_GAP;
  const top = contentH < spareRoom ? top0 + (spareRoom - contentH) / 2 : top0;

  columns.forEach((column, c) => {
    const x = SAFE.x + c * (colW + gutter);
    column.forEach((row, i) => {
      const y = top + i * ROW;
      if (row.heading) items.push(...sectionHeading(ctx, row.heading, x, y, colW, row.continued));
      else items.push(...personRow(ctx, story, row.id, x, y, colW, { portraits, disambiguate }));
    });
  });

  // The foot vignette (round 2, finding 1): one drawing - a tulsi, a small deepstambh and a lotus
  // between them - reused on every register page, never a picture per row. This is what keeps the
  // register a page of the storybook rather than an index the pictures stopped partway through.
  const footBottom = top + contentH + FOOT_GAP + FOOT_H;
  items.push(ctx.art.place('register-foot', { x: PAGE.w / 2, y: footBottom, w: Math.min(SAFE.w * 0.72, 300) }));
  ctx.zone('busy', { x: SAFE.x, y: footBottom - FOOT_H, w: SAFE.w, h: FOOT_H });

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
 * wife" over "Late" (round 2, finding 16), and not a year at all for somebody living (round 2,
 * finding 18): the niche this page keeps a lamp in is the book's remembrance notation, which is
 * for the departed alone (docs/book-design-system.md, "Principles"), so a living person still to
 * be named reads "not yet placed in the tree" - the approved frame's own words - instead of an
 * age-shaped year that would read as somebody's death is being marked beside them.
 */
export function lostName(ctx, story, id, featuredName) {
  const { family } = ctx;
  const { kin } = story;
  const p = family.byId.get(id);
  const own = nameOf(family, kin, id);
  const name = own ?? stillToBeFoundCaption(family, kin, id) ?? 'A name still to be found';
  if (p && !p.deceased) return { name, under: 'not yet placed in the tree' };
  const word = own ? kinCaption(kin, family, id, featuredName) : null;
  const fresh = word && !name.toLowerCase().endsWith(word.toLowerCase()) ? word : null;
  const dates = lifeDates(p);
  const redundant = dates === 'Late' && name.toLowerCase().includes('late');
  return { name, under: [fresh, redundant ? null : dates].filter(Boolean).join(' · ') };
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
/** The `aala` motif's own width for a given height, from its compiled viewBox (72 x 84). */
const AALA_ASPECT = 72 / 84;

/**
 * The ornament the design system draws on an aala niche - a cusped arch rim, a marigold swag and a
 * flame finial - that #254's own asset does not carry yet (round 2, out of scope per the critique,
 * finding 17, but placement is this issue's to try): a dashed gold rim traced over the niche's own
 * arch, two small marigolds at its shoulders joined by a thread, and a small flame above its peak.
 * Approximate, not #254's real cut ornament, and dropped the day that asset lands.
 */
function nicheOrnament(ctx, cx, bottomY, height) {
  const { P } = ctx;
  const w = height * AALA_ASPECT;
  const top = bottomY - height;
  const shoulderY = top + height * 0.34, archY = top + height * 0.06;
  const left = cx - w * 0.42, right = cx + w * 0.42;
  const items = [
    path(`M ${left} ${shoulderY} Q ${cx} ${archY} ${right} ${shoulderY}`, { stroke: P.gold, sw: 1, dash: [0.01, 3.2], cap: 'round', op: 0.75 }),
    path(`M ${cx} ${top - 2} L ${cx - 3.2} ${top + 5} L ${cx + 3.2} ${top + 5} Z`, { fill: P.flame, op: 0.9 }),
    circle(cx, top - 4.5, 1.6, { fill: P.gold }),
  ];
  items.push(motif(ctx, 'marigold-bead', left, shoulderY + 2, 9));
  items.push(motif(ctx, 'marigold-bead', right, shoulderY + 2, 9));
  return items;
}

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
      items.push(...nicheOrnament(ctx, cx, niches.y + niches.h, height));
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

  // The words the approved frame closes this chapter on (round 2, finding 14): the lower third had
  // no words at all, which is the single biggest loss of feeling in the whole chapter. Two lines in
  // the hand, then the quiet aside underneath - no tailpiece beside them, the way the approved
  // frame draws it, since a decorative mala would compete with what the words are saying.
  const closing = s.zone('closing');
  const ccx = closing.x + closing.w / 2, cw = closing.w - 30;
  const CLOSING_LINES = [
    ['Some names are missing, but they are not forgotten.', 'hand', 15, P.flame],
    ['A lamp is kept for each of them, until someone remembers.', 'hand', 15, P.flame],
    ['Perhaps someone reading this remembers.', 'text', 10.5, P.card],
  ];
  let cy = closing.y + 26;
  for (const [s2, role, size, fill] of CLOSING_LINES) {
    items.push(ctx.line(ccx, cy, s2, role, ctx.fit(s2, role, size, cw, size * 0.75), fill, { align: 'middle', width: cw, kind: 'body' }));
    cy += size + 6;
  }

  // The one rangoli this chapter lays, tilted onto the floor as `style-frames/frames.mjs` draws it,
  // seeded from the family so two families never get the same pattern, and pulled in a little from
  // the zone's own bounds and up from the foot of the page (round 2, finding 15: at the zone's full
  // size the rangoli's own petals ran under the folio credit `ctx.footer` prints at `PAGE.h - 22`).
  const floor = s.zone('rangoli');
  // Shrunk and lifted off the zone's own centre: the folio credit's baseline sits at `PAGE.h - 22`
  // with the glyphs rising about 6 pt above it, so the rangoli's own lowest point (its squashed
  // ground ring, `R * 1.02 * 0.3` below its centre) has to clear `PAGE.h - 28` with room to spare.
  const R = Math.min(floor.w / 2, floor.h / 0.6) * 0.68;
  const rcx = floor.x + floor.w / 2, rcy = floor.y + floor.h * 0.3;
  items.push(group([rangoli(P, 0, 0, R, familySeed(ctx, story, 'rangoli'), { ground: P.paperDeep, colours: [P.gold, P.saffron, P.card, P.rani] })],
    { tf: [1, 0, 0, 0.3, rcx, rcy], op: 0.8 }));
  ctx.zone('busy', { x: rcx - R * 1.1, y: rcy - R * 0.34, w: R * 2.2, h: R * 0.68 + 8 });

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
    // The tape is gold, matching the note cards #256 and #257 draw with the same idiom (round 2,
    // finding 22) - `silver` was this page's own drift from the shared look.
    path(`M ${cx - 30} ${top - 6} L ${cx + 28} ${top - 9} L ${cx + 30} ${top + 5} L ${cx - 28} ${top + 8} Z`, { fill: P.gold, op: 0.8 }),
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

  // The lamp column and the note card sit the same distance either side of the page's own centre
  // - the axis the title is centred on too (round 2, finding 20: the three used to be three
  // unrelated numbers - the title at 50%, the column at ~20%, the card at ~65% - with no relation
  // between any of them).
  const OFFSET = 120;
  const axis = mirrored ? PAGE.w / 2 + OFFSET : PAGE.w / 2 - OFFSET;
  const cardCx = mirrored ? PAGE.w / 2 - OFFSET : PAGE.w / 2 + OFFSET;

  // The line climbs from the deepest generation on record down to the featured person's own lamp,
  // in the room between the title and the foot of the page - derived from that room, rather than
  // fixed numbers that left 250+ pt of unbroken sky above the first lamp for a family with only a
  // couple of generations behind it (finding 20), and generous enough for a long line that a lamp
  // never crowds its neighbour into an unreadable smear (finding 21).
  const gens = generationsBehind(kin);
  const ROOM_TOP = 168, ROOM_BOTTOM = SAFE.bottom - 8;
  const step = Math.min(70, (ROOM_BOTTOM - ROOM_TOP - 40) / Math.max(1, gens));
  // The column is centred in that room however tall it turns out to be, so a family with two
  // generations behind it does not leave half a night sky empty above the lamps.
  const base = Math.min(ROOM_BOTTOM, (ROOM_TOP + ROOM_BOTTOM) / 2 + (step * gens) / 2);
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
  if (words) items.push(...handCard(ctx, words, cardCx, (top + base) / 2 - 46, 210));

  items.push(...ctx.footer(P.silver));
  return ctx.page(copy?.title ?? 'One line of light', items, P.deep);
}

/** Archetype id -> draw(ctx, page, story). #258: lane, numbers, register, still-to-be-found, legacy. */
export const PAGES = Object.freeze({ lane, numbers, register, 'still-to-be-found': stillToBeFound, legacy });
