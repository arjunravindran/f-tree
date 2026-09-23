/*
 * The people on a family page (#257): one person's frame, the words under it, and the marigold
 * string that says two of them are married.
 *
 * The rules this file keeps, all from docs/book-design-system.md and docs/storybook-plan.md:
 *   - **The art invents nobody.** Somebody with a photograph is mounted in the carved ring, exactly
 *     as the family took it; somebody without one is a faceless paper-cut bust (`story/avatars.js`
 *     chooses which, from the record alone); somebody whose name was never written down is the
 *     dashed brass lamp, and is named by their relation instead.
 *   - **Group portraits get `medallion-petals`,** the petal rim - never a ring of marigolds, which
 *     is the book's marriage and mourning notation and would be read as one.
 *   - **A mala hangs only on a departed person's frame.** `art.place('mala-departed', ...)` throws
 *     without `{ departed: true }`, so this is the one place that reads `deceased` and passes it.
 *   - **Kinship is composition.** A marriage is a marigold string between two frames (a diya where
 *     one of the two has died - never a mauli thread); a caption is a kin word, never a label, and
 *     never a line drawn from one person to another.
 *
 * Shared within #257 only. Composer code: deterministic, no clock, no locale, no Math.random.
 */

import { lifeYears } from '../../../family.js';
import { avatarFor } from '../../avatars.js';
import { kinCaption, nameOf, noteCaption, stillToBeFoundCaption } from '../../copy.js';
import { TYPE } from './paper.js';

/** The story medallion's size range (design system, "People": 36-90 pt across). */
export const FRAME = Object.freeze({ max: 84, min: 36 });
/** How much of the drawing stands outside the opening: the petal rim, and the mala below it. */
export const RIM = 0.62;
export const HANG = 0.72;

/** The size the life stage is read at: the book's year, and where this person stands from F. */
const stageOf = (ctx, story, id) => ({
  year: ctx.now.year,
  gen: story.kin.people.get(id)?.gen ?? null,
  featuredBy: ctx.family.byId.get(story.kin.featured)?.by ?? null,
});

/**
 * One person's frame, centred on `(cx, cy)` at `d` points across the opening.
 *
 * The frame is a thin ring, so it takes the plain paper shadow scaled to its size rather than the
 * soft one, which shows as concentric rings on a ring (site/book/art/README.md, "Frames").
 */
export function portrait(ctx, story, id, cx, cy, d) {
  const p = ctx.family.byId.get(id);
  const box = { x: cx - d / 2, y: cy - d / 2, w: d, h: d };
  const k = Math.max(0.6, d / 76);
  const shadow = { dx: 1.7 * k, dy: 2.3 * k };
  const items = [];
  if (!p.name) {
    // Name not known: the dashed brass perimeter with a lamp inside, which is the notation at
    // medallion size. The person is named by their relation in the caption below.
    ctx.show(id);
    items.push(ctx.art.place('lamp-unknown', { x: cx, y: cy, w: d }));
  } else if (p.photo && ctx.options.photos) {
    items.push(ctx.art.frame('medallion-carved', box, ctx.portrait(p, cx, cy, d / 2, { ring: ctx.P.gold, unknownRing: ctx.P.brass }), { shadow }));
  } else {
    ctx.show(id);
    items.push(ctx.art.frame('medallion-petals', box, [ctx.art.place(avatarFor(p, stageOf(ctx, story, id)), { x: cx, y: cy, w: d })], { shadow }));
  }
  if (p.deceased) items.push(ctx.art.place('mala-departed', { x: cx, y: cy, s: d / 100, departed: true }));
  ctx.zone('face', { x: cx - d * RIM, y: cy - d * RIM, w: 2 * d * RIM, h: d * (RIM + HANG) });
  return items;
}

/**
 * The words under one frame: their name, when they lived, and what they are to the featured
 * person. Somebody the record never named is named by their relation instead ("Shyam Lal's wife",
 * or, where even that is unknown, "Ankit's grandmother, on the maternal side"), in the hand face
 * in `brass` - the book's one colour for *name not known*.
 *
 * Every line is centred in `width` and told to stay inside it, so two neighbouring captions can
 * never print over each other however long a name is.
 */
export function caption(ctx, story, page, id, { cx, top, width }) {
  const { kin } = story;
  const family = ctx.family;
  const p = family.byId.get(id);
  const items = [];
  let y = top;
  const featuredName = nameOf(family, kin, kin.featured);

  const name = nameOf(family, kin, id);
  const placed = name ? null : stillToBeFoundCaption(family, kin, id);
  const first = name ?? placed;
  if (first) {
    y += TYPE.name;
    const role = p.name ? 'strong' : 'hand';
    const ink = p.name ? ctx.P.ink : ctx.P.brass;
    const broken = ctx.lines(cx, y, first, role, ctx.fit(first, role, TYPE.name, width, TYPE.nameMin), ink,
      { width, maxLines: 2, align: 'middle', lead: TYPE.name * 1.2, kind: 'name' });
    items.push(...broken.items);
    y = broken.bottom;
  }

  const years = lifeYears(p);
  if (years) {
    y += TYPE.dates * 1.35;
    items.push(ctx.line(cx, y, years, 'text', TYPE.dates, ctx.P.inkSoft, { align: 'middle', width, kind: 'lifespan' }));
  }

  // The kin word, in the hand face in clay (design system, "Typography"). Left out where the
  // name line already *is* the relation, which would print the same fact twice.
  const word = placed ? null : kinCaption(kin, family, id, featuredName);
  if (word) {
    y += TYPE.kin * 1.4;
    items.push(ctx.line(cx, y, word, 'hand', ctx.fit(word, 'hand', TYPE.kin, width, 8), ctx.P.clay, { align: 'middle', width, kind: 'caption' }));
  }
  ctx.show(id);
  return { items, bottom: y + 4 };
}

/** How tall `caption` will be for `id`: the tallest caption sets the row's height. */
export function captionHeight(ctx, story, id, width) {
  const family = ctx.family;
  const p = family.byId.get(id);
  const name = nameOf(family, story.kin, id) ?? stillToBeFoundCaption(family, story.kin, id);
  let h = 4;
  if (name) {
    const role = p.name ? 'strong' : 'hand';
    const size = ctx.fit(name, role, TYPE.name, width, TYPE.nameMin);
    h += TYPE.name + (ctx.lines(0, 0, name, role, size, ctx.P.ink, { width, maxLines: 2, lead: TYPE.name * 1.2 }).count - 1) * TYPE.name * 1.2;
  }
  if (lifeYears(p)) h += TYPE.dates * 1.35;
  if (!name || p.name) h += TYPE.kin * 1.4;
  return h;
}

/**
 * Whether `a` and `b` are married, from the record's own spouse edges. A direct neighbour lookup,
 * never `relate()`: the composer may not walk the whole graph once per pair of frames.
 */
export const married = (ctx, a, b) => ctx.family.graph.spouses(a).some((s) => s.id === b);

/**
 * The marigold string that says two frames are one marriage, draped over the pair. Where one of
 * the two has died the couple is joined by a diya between the frames instead - never a mauli
 * thread, and never a garland reaching toward the living partner (storybook-plan.md, "Cultural
 * care").
 */
export function marriage(ctx, a, b, { x1, x2, cy, d }) {
  const mid = (x1 + x2) / 2;
  const departed = ctx.family.byId.get(a)?.deceased || ctx.family.byId.get(b)?.deceased;
  if (departed) return [ctx.art.place('diya', { x: mid, y: cy + d * 0.34, w: Math.max(14, d * 0.34) })];
  return [ctx.art.place('mala', { x: mid, y: cy - d * 0.5, w: (x2 - x1) + d * 0.42 })];
}

/** The one note this page may carry: the first person on it who has one, or null. */
export function pageNote(ctx, page) {
  for (const id of page.people) {
    const note = noteCaption(page.chapter, ctx.family, id, ctx.options);
    if (note) return note;
  }
  return null;
}
