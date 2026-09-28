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

import { avatarFor, lifeStage, STAGES } from '../../avatars.js';
import { kinCaption, nameOf, noteCaption, stillToBeFoundCaption } from '../../copy.js';
import { lifeDates } from './dates.js';
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

/** A birth year that reads as roughly the middle of each life stage, against the book's year. */
const STAGE_AGE = Object.freeze({ child: 8, youth: 20, adult: 42, elder: 68 });

/**
 * One row's life stage, when it needs one: siblings a year or two apart on either side of the
 * elder cutoff (60) used to read as two generations apart, because each frame's avatar was chosen
 * from that one person's own birth year alone (round 1 finding 21). Rather than moving the cutoff
 * - which would just move the same problem to the next pair of siblings a year either side of the
 * new one - a row of two or more takes ONE stage: whichever stage most of the row's people are
 * already at on their own (a tie favours the younger stage, so a row is never aged up by default).
 * A row where everybody already agrees returns `null`, so an ordinary row's avatars are never
 * touched.
 */
export function rowStage(ctx, story, ids) {
  if (ids.length < 2) return null;
  const counts = new Map();
  for (const id of ids) {
    const s = lifeStage(ctx.family.byId.get(id), stageOf(ctx, story, id));
    counts.set(s, (counts.get(s) ?? 0) + 1);
  }
  if (counts.size < 2) return null;
  let best = null, bestCount = -1;
  for (const s of STAGES) {   // STAGES' own order, so a tie favours the younger stage
    const c = counts.get(s) ?? 0;
    if (c > bestCount) { best = s; bestCount = c; }
  }
  return best;
}

/**
 * One person's frame, centred on `(cx, cy)` at `d` points across the opening.
 *
 * The frame is a thin ring, so it takes the plain paper shadow scaled to its size rather than the
 * soft one, which shows as concentric rings on a ring (site/book/art/README.md, "Frames").
 */
export function portrait(ctx, story, id, cx, cy, d, forcedStage = null) {
  const p = ctx.family.byId.get(id);
  const box = { x: cx - d / 2, y: cy - d / 2, w: d, h: d };
  const k = Math.max(0.6, d / 76);
  const shadow = { dx: 1.7 * k, dy: 2.3 * k };
  const items = [];
  // The mala, when it applies, goes down first: it hangs *behind* the frame it is tied to (the
  // approved frames tuck its ends under the ring's edge), and the dashed lamp-unknown rim - the
  // app's own notation for a name not known - must never be hidden under it (#257 round 2,
  // finding 3). Pushing it before the frame is what makes both true regardless of which frame
  // this person gets.
  if (p.deceased) items.push(ctx.art.place('mala-departed', { x: cx, y: cy, s: d / 100, departed: true }));
  if (!p.name) {
    // Name not known: the dashed brass perimeter with a lamp inside, which is the notation at
    // medallion size. The person is named by their relation in the caption below.
    ctx.show(id);
    items.push(ctx.art.place('lamp-unknown', { x: cx, y: cy, w: d }));
  } else if (p.photo && ctx.options.photos) {
    items.push(ctx.art.frame('medallion-carved', box, ctx.portrait(p, cx, cy, d / 2, { ring: ctx.P.gold, unknownRing: ctx.P.brass }), { shadow }));
  } else {
    ctx.show(id);
    // `forcedStage`, from `rowStage`: a row of two or more takes one life stage, so siblings a
    // year either side of the elder cutoff never read as two generations apart (finding 21). A
    // synthetic birth year picks the avatar at that stage without touching the record's own - the
    // register, the numbers page and everything else still reads this person's true age.
    const avatarPerson = forcedStage ? { ...p, by: ctx.now.year - STAGE_AGE[forcedStage], dy: null } : p;
    items.push(ctx.art.frame('medallion-petals', box, [ctx.art.place(avatarFor(avatarPerson, stageOf(ctx, story, id)), { x: cx, y: cy, w: d })], { shadow }));
  }
  ctx.zone('face', { x: cx - d * RIM, y: cy - d * RIM, w: 2 * d * RIM, h: d * (RIM + HANG) });
  return items;
}

/** The English word this file's `namedBy`-derived phrases carry for a deceased person's spouse. */
const LATE_RE = /\blate\s+/i;

/**
 * The name line under a frame: the person's own name, or - for somebody the record never named -
 * the relation they are named by, in the hand face. One rule, so measuring a caption and drawing
 * it can never disagree about what it says.
 *
 * A relation phrase built from `namedBy` ("Raj Kumar's late wife") or `stillToBeFoundCaption`
 * carries the English word "late" whenever the person it names them by is recorded as WIDOWED and
 * departed - independently of whatever this same caption's own dates line or kin word says, which
 * is how the same fact ends up printed three times (#257 round 2, findings 10 and 26). `late` is
 * dropped from the displayed text once the dates line below is going to say so anyway with real
 * information of its own (a year); where the dates line has nothing but the bare word "Late" to
 * offer, this stays the one place that says it.
 */
function nameLine(ctx, story, id, width, showsYears) {
  const p = ctx.family.byId.get(id);
  const raw = nameOf(ctx.family, story.kin, id) ?? stillToBeFoundCaption(ctx.family, story.kin, id);
  const late = !p.name && typeof raw === 'string' && LATE_RE.test(raw);
  const text = late && showsYears ? raw.replace(LATE_RE, '') : raw;
  const role = p.name ? 'strong' : 'hand';
  return { text, role, size: text ? ctx.fit(text, role, TYPE.name, width, TYPE.nameMin) : TYPE.name, late };
}

/**
 * Everything `caption` and `captionHeight` need to agree on: the name line (already resolved
 * against the dates line, above), whether the dates line prints at all, and the kin word - left
 * out wherever it would repeat what the name line already says, on its own or through `late`.
 */
function captionParts(ctx, story, id, width) {
  const { kin } = story;
  const family = ctx.family;
  const p = family.byId.get(id);
  const years = lifeDates(p);
  // A bare "Late" (nothing but the word itself: no year survived) is dropped once the name line
  // already carries "late" - otherwise the one fact prints as an orphan word and, a second time,
  // as a whole line (finding 10). Any dates line with a real year stays: it is new information.
  const first = nameLine(ctx, story, id, width, Boolean(years) && years !== 'Late');
  const showsYears = Boolean(years) && !(years === 'Late' && first.late);
  const featuredName = nameOf(family, kin, kin.featured);
  const rawWord = kinCaption(kin, family, id, featuredName);
  // Left out where the name line already ends in exactly this relation - which happens whenever
  // `namedBy` (kin.js) named this person by the same relation `kinCaption` gives them here, not
  // only when the name is missing outright.
  const word = rawWord && !(first.text && first.text.endsWith(rawWord)) ? rawWord : null;
  return { first, years: showsYears ? years : null, word };
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
export function caption(ctx, story, id, { cx, top, width, nameLines = 0 }) {
  const p = ctx.family.byId.get(id);
  const { first, years, word } = captionParts(ctx, story, id, width);
  const items = [];
  let y = top;

  if (first.text) {
    y += TYPE.name;
    const broken = ctx.lines(cx, y, first.text, first.role, first.size, p.name ? ctx.P.ink : ctx.P.brass,
      { width, maxLines: 2, align: 'middle', lead: TYPE.name * 1.2, kind: 'name' });
    items.push(...broken.items);
    // A row reserves the same number of name lines for everybody on it, so the dates and the kin
    // words below line up across the row however long one person's name is.
    y += (Math.max(nameLines, broken.count) - 1) * TYPE.name * 1.2;
  }

  if (years) {
    y += TYPE.dates * 1.35;
    items.push(ctx.line(cx, y, years, 'text', TYPE.dates, ctx.P.inkSoft, { align: 'middle', width, kind: 'lifespan' }));
  }

  // The kin word, in the hand face in clay (design system, "Typography").
  if (word) {
    y += TYPE.kin * 1.4;
    items.push(ctx.line(cx, y, word, 'hand', ctx.fit(word, 'hand', TYPE.kin, width, 8), ctx.P.clay, { align: 'middle', width, kind: 'caption' }));
  }
  ctx.show(id);
  return { items, bottom: y + 4 };
}

/** How many lines `id`'s name takes at `width`: the row's tallest sets them all. */
export function nameLineCount(ctx, story, id, width) {
  const { first } = captionParts(ctx, story, id, width);
  return first.text ? ctx.lines(0, 0, first.text, first.role, first.size, ctx.P.ink, { width, maxLines: 2, lead: TYPE.name * 1.2 }).count : 0;
}

/** How tall `caption` will be for `id` with `nameLines` reserved for the name. */
export function captionHeight(ctx, story, id, width, nameLines) {
  const { first, years, word } = captionParts(ctx, story, id, width);
  let h = 4;
  if (first.text) h += TYPE.name + (Math.max(nameLines, 1) - 1) * TYPE.name * 1.2;
  if (years) h += TYPE.dates * 1.35;
  if (word) h += TYPE.kin * 1.4;
  return h;
}

/**
 * Whether `a` and `b` are married, from the record's own spouse edges. A direct neighbour lookup,
 * never `relate()`: the composer may not walk the whole graph once per pair of frames. A divorced
 * edge is excluded: `marriage` below draws a mala for anyone this says are married, and a former
 * spouse is exactly the one relation on the page a marigold string must not imply (round 1 finding
 * 25 draws F beside every spouse in turn, former included, so this is the one place that decides
 * whether the pair gets a string between them).
 */
export const married = (ctx, a, b) => ctx.family.graph.spouses(a).some((s) => s.id === b && s.subtype !== 'DIVORCED');

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
