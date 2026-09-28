/*
 * The register's rows (#258): a section heading, and one line per person - their cameo, their
 * name, when they lived, and the page they are on.
 *
 * The register is the book's completeness guarantee (docs/storybook-plan.md, belief 3): everyone in
 * scope is on it, nobody is cut, and every page reference is the number `story/plan.js` gave that
 * page before anything was drawn. Nothing here decides who is listed or how the list breaks across
 * pages - the plan did both - so a row is only ever ink.
 *
 * The measurements are the design system's own: the register is set 9.6 on 21, a cameo is 22 pt
 * across, and a page holds 48 rows counting one for each section heading (`DENSITY.register`).
 * `lists.test.mjs` holds the arithmetic to the real font metrics rather than an assumed line
 * height, because the plan paginates by that count and a row that is taller than it claims would
 * run a full page off the foot of the paper.
 *
 * Composer code: deterministic, no clock, no locale, no DOM, no Math.random, static imports only.
 */

import { path, circle, group } from '../../../format.js';
import { CIRCLES } from '../../kin.js';
import { kinCaption, nameOf, stillToBeFoundCaption } from '../../copy.js';
import { avatarFor } from '../../avatars.js';
import { lifeDates } from './furniture.js';

/** The register is set 9.6 on 21 (docs/book-design-system.md, "Typography"). */
export const ROW = 21;
export const NAME_SIZE = 9.6;
const DATE_SIZE = 8.4;
const REF_SIZE = 9;
const HEADING_SIZE = 12.5;
/** A register cameo is 22 pt across (design system, "People"). */
const CAMEO = 22;
const GAP = 6;            // between the cameo and the name, and either side of the leader
const REF_COLUMN = 16;    // the page-reference column at the right of a row

/**
 * What each of `kin.js`'s circles is called at the head of its register section. Every circle has
 * one: a section with no heading would be a list of names with nothing saying who they are, and a
 * circle nobody had named would print its internal id at a reader.
 *
 * They are section headings, not chapter titles - the chapters those people appear on are named by
 * the template's own copy - so they say who the people are rather than repeating a chapter's
 * words: a reader looking someone up reads down these.
 */
export const SECTION_TITLES = Object.freeze({
  self: 'At the centre',
  parents: 'Parents',
  spouses: 'Married into this house',
  children: 'Children',
  siblings: 'Brothers and sisters',
  grandparents: 'Grandparents',
  descendants: 'Grandchildren, and after',
  ancestors: 'Earlier generations',
  branches: 'Aunts, uncles and cousins',
  'in-laws': 'In-laws',
  lane: 'The wider family',
  elsewhere: 'Also in the family',
});

for (const c of CIRCLES) if (!SECTION_TITLES[c]) throw new Error(`register-rows.js: kin.js's "${c}" circle has no section heading`);

/** The heading over a section, and the hairline under it. One row tall, as the plan counted it. */
export function sectionHeading(ctx, key, x, y, w, continued) {
  const { P } = ctx;
  const title = continued ? `${SECTION_TITLES[key]}, continued` : SECTION_TITLES[key];
  const size = ctx.fit(title, 'display', HEADING_SIZE, w - 10, 9);
  return [
    ctx.line(x, y, title, 'display', size, P.clay, { width: w - 10, kind: 'title' }),
    path(`M ${x} ${y + 5.5} L ${x + w} ${y + 5.5}`, { stroke: P.gold, sw: 0.6, op: 0.7 }),
    circle(x + w, y + 5.5, 1.8, { fill: P.gold }),
  ];
}

/**
 * The cameo at the head of a row: the person's photograph where they have one, the paper-cut bust
 * the record chooses for them (`story/avatars.js`) where they do not, and `lamp-unknown` - the
 * dashed brass perimeter with a lamp inside - for a person whose name nobody recorded, which is
 * what the design system asks for at cameo size rather than a full aala niche.
 *
 * No mala ever hangs here. A register cameo is "a bust in a plain gold bezel, no garland".
 */
function cameo(ctx, story, id, cx, cy) {
  const r = CAMEO / 2;
  const p = ctx.family.byId.get(id);
  ctx.zone('face', { x: cx - r, y: cy - r, w: CAMEO, h: CAMEO });
  // A photograph first, whether or not a name was recorded with it: `ctx.portrait` draws a person
  // with no name inside the dashed brass perimeter, which is the notation, and a family that kept
  // the picture of somebody whose name was lost should not have it replaced by a lamp.
  if (p?.photo && ctx.options.photos) return ctx.portrait(p, cx, cy, r, { ring: ctx.P.gold, unknownRing: ctx.P.brass });
  if (!p?.name) return [ctx.art.place('lamp-unknown', { x: cx, y: cy, w: CAMEO })];
  const stage = { year: ctx.now.year, gen: story.kin.people.get(id)?.gen ?? null, featuredBy: ctx.family.byId.get(story.kin.featured)?.by ?? null };
  const inner = [ctx.art.place(avatarFor(p, stage), { x: cx, y: cy, w: CAMEO, anchor: 'center' })];
  return [ctx.art.frame('cameo', { x: cx - r, y: cy - r, w: CAMEO, h: CAMEO }, inner, { shadow: { dx: 0.6, dy: 0.8 } })];
}

/**
 * A qualifier for anyone in scope whose own name collides with somebody else's on the register:
 * the same name, the same page reference (or the same absence of one) *and* the same printed
 * dates - the three things a row actually shows, so two "Tara Sharma"s with different birth years
 * are not touched when their own row already tells them apart. Two rows that collide on all three
 * are a real breach of the register's one job - a reader following either row's reference cannot
 * tell which person they found - so `personRow` carries the qualifier this returns for anyone it
 * names (round 2, finding 4). Computed over the whole family, not one page's rows, because the two
 * rows sharing a name are not always on the same register page.
 *
 * The qualifier is the kin word first, since it says who they are; where `kin.js` cannot join them
 * to the featured person at all (an "elsewhere" row), the section they are listed under; and where
 * even that is the same for two colliding people (two cousins on the same side, say), a plain count
 * among themselves - the one thing guaranteed to differ, chosen only as the last resort it is.
 */
export function duplicateRegisterNames(ctx, story) {
  const { family } = ctx;
  const { kin } = story;
  const featuredName = nameOf(family, kin, kin.featured);
  const bySignature = new Map();
  for (const id of kin.people.keys()) {
    const p = family.byId.get(id);
    if (!p?.name) continue;
    const ref = story.plan.pagesOf.get(id)?.[0] ?? 'none';
    const sig = `${p.name}|${ref}|${lifeDates(p)}`;
    bySignature.set(sig, [...(bySignature.get(sig) ?? []), id]);
  }
  const qualifiers = new Map();
  for (const ids of bySignature.values()) {
    if (ids.length < 2) continue;
    const used = new Set();
    ids.forEach((id, i) => {
      let word = kinCaption(kin, family, id, featuredName) ?? SECTION_TITLES[kin.people.get(id)?.circle];
      if (!word || used.has(word)) word = `${i + 1} of ${ids.length}`;
      used.add(word);
      qualifiers.set(id, word);
    });
  }
  return qualifiers;
}

/**
 * One person's row, on the baseline `y`, in a column `w` wide from `x`.
 *
 * The name is the one name a book ever prints for somebody (`copy.js`'s `nameOf`): their own, or
 * the relative they are named through ("Shyam Lal's wife"), which is set in the hand in brass -
 * the notation for a name not known - so the register never says "Unknown". The page reference is
 * the plan's own number for the story page this person is on; somebody the story pages had no room
 * for is listed with no reference rather than a number pointing at the wrong page.
 *
 * `portraits` false leaves the cameos off and pulls the names left: a book whose register runs to
 * several pages cannot afford a bust per row against the 10 MB budget, and it is the picture that
 * goes, never a person.
 *
 * `disambiguate` (`duplicateRegisterNames`'s own return, an id -> qualifier map) carries a word
 * alongside anyone whose name, dates and page reference are not enough to tell them from somebody
 * else in the register - never fewer rows, never a row silently dropped, just one more fact on the
 * rows that need it.
 */
export function personRow(ctx, story, id, x, y, w, { portraits = true, disambiguate = null } = {}) {
  const { P, family } = ctx;
  const kin = story.kin;
  ctx.show(id);
  const p = family.byId.get(id);
  const items = [];
  const left = portraits ? x + CAMEO + GAP : x;
  if (portraits) items.push(...cameo(ctx, story, id, x + CAMEO / 2, y - NAME_SIZE * 0.32));

  const ref = story.plan.pagesOf.get(id)?.[0] ?? null;
  const right = x + w;
  if (ref !== null) items.push(ctx.line(right, y, String(ref), 'text', REF_SIZE, P.clay, { align: 'end', width: REF_COLUMN, kind: 'caption' }));

  const dates = lifeDates(p);
  let dateLeft = right - REF_COLUMN - GAP;
  if (dates) {
    const dw = ctx.measure(dates, 'text', DATE_SIZE);
    items.push(ctx.line(dateLeft, y, dates, 'text', DATE_SIZE, P.inkSoft, { align: 'end', width: dw, kind: 'lifespan' }));
    dateLeft -= dw;
  }

  // Their own name, or the one the family calls them by; never "Unknown", never empty. A name
  // that collides with another row's own name, dates and page reference carries its qualifier too,
  // in the same line - the register never adds a second line a page's row-count did not plan for.
  const own = Boolean(p?.name);
  let name = nameOf(family, kin, id) ?? stillToBeFoundCaption(family, kin, id) ?? 'A name still to be found';
  const qualifier = own ? disambiguate?.get(id) : null;
  if (qualifier) name = `${name} (${qualifier})`;
  const room = Math.max(24, dateLeft - GAP - left);
  const size = ctx.fit(name, own ? 'strong' : 'hand', NAME_SIZE, room, 9);
  const nameW = Math.min(room, ctx.measure(name, own ? 'strong' : 'hand', size));
  items.push(ctx.line(left, y, name, own ? 'strong' : 'hand', size, own ? P.ink : P.brass, { width: nameW, kind: 'name' }));

  // The leader that carries the eye from a name to its dates, drawn only where there is room for
  // more than a couple of dots. Denser than a hairline (round 2, finding 5): the approved frame's
  // leaders read at a glance, and `op: 0.45` on a short gap did not.
  const from = left + nameW + GAP, to = dateLeft - GAP;
  if (to - from > 8) items.push(path(`M ${from} ${y - 2.6} L ${to} ${y - 2.6}`, { stroke: P.inkSoft, sw: 0.9, dash: [0.1, 3.2], cap: 'round', op: 0.55 }));
  return [group(items)];
}

/** Below this many rows the register reads better down one column than across two short ones. */
const MIN_COLUMNS = 8;

/** A section split by a column break should leave at least this many rows on each side of it. */
const MIN_SECTION_SPLIT = 2;

/**
 * Whether breaking at `at` (the first row of the second column) leaves a section orphaned: a
 * heading with nothing, or with only one name, on one side of the break. `rows[at]` itself opening
 * a fresh section is always a clean break - the two columns then say different things and neither
 * carries a stray continuation.
 */
function orphansASection(rows, at) {
  if (at <= 0 || at >= rows.length) return false;
  if (rows[at].heading) return false;
  let h = at - 1;
  while (h >= 0 && !rows[h].heading) h -= 1;
  if (h < 0) return false;
  const before = at - 1 - h;
  let end = at;
  while (end < rows.length && !rows[end].heading) end += 1;
  const after = end - at;
  return before < MIN_SECTION_SPLIT || after < MIN_SECTION_SPLIT;
}

/**
 * Where a page's rows break between its two columns: as near the middle as the rows allow, never
 * leaving a section heading alone - or with only one name under it - on either side of the break
 * (round 2, finding 3: two headings, "Brothers and sisters" and its own "continued", each over a
 * single name, is worse than either column running a little long or a little short), and not split
 * at all for a register short enough that two columns would be two stubs - a family of one would
 * otherwise get a heading in one column and the same heading, marked "continued", over the single
 * name in the other.
 *
 * `rows` is the page's rows in order, each `{ heading }` or not.
 */
export function splitColumns(rows) {
  if (rows.length < MIN_COLUMNS) return [rows, []];
  const middle = Math.ceil(rows.length / 2);
  let at = middle;
  for (let d = 0; d <= rows.length; d += 1) {
    if (middle + d < rows.length && !orphansASection(rows, middle + d)) { at = middle + d; break; }
    if (d > 0 && middle - d > 0 && !orphansASection(rows, middle - d)) { at = middle - d; break; }
  }
  return [rows.slice(0, at), rows.slice(at)];
}
