/*
 * Page furniture the hero pages share (#256): the paper a day page is printed on, the night ground
 * a scene sits on, the Sanjhi band, the folio, and the two small chores every storybook page owes
 * the QA harness - recording a scene's zones, and fitting a title to the room it has.
 *
 * Nothing here decides what a page is about. It is the furniture of `docs/book-design-system.md`
 * ("Page furniture", "Paper and depth"), in one place so the cover, the opening, the waiting page,
 * a portrait hero and the closing cannot drift apart.
 *
 * Composer code: deterministic, no clock, no locale, no DOM, no Math.random, static relative
 * imports only.
 */

import { PAGE, rect, path, circle, group, PathData } from '../../../format.js';
import { seeded } from '../../../art/seed.js';

const { w: W, h: H } = PAGE;

/**
 * The text-safe area: A4 inset 42 pt at the sides, 54 at the top and 50 at the foot
 * (book-design-system.md, "Margins"). Art may bleed past it; words may not.
 */
export const SAFE = Object.freeze({ x: 42, y: 54, w: W - 84, h: H - 104, right: W - 42, bottom: H - 50 });

/** A full-page scene's placement, mirrored for a `-mirrored` variant. */
export const scenePlacement = (mirror) => ({ x: 0, y: 0, anchor: 'top-left', w: W, ...(mirror ? { flip: 'x' } : {}) });

/**
 * Places a full-page scene, tells the report where its zones are, and hands back those zones by
 * name. Every zone is recorded, not just the busy ones: `text` zones are how a later reader sees
 * that the words really were put where the scene left room for them.
 */
export function scene(ctx, id, placement) {
  const zones = new Map();
  for (const z of ctx.art.zones(id, placement)) {
    ctx.zone(z.kind, z);
    if (z.name !== undefined) zones.set(z.name, z);
  }
  const at = (name) => {
    const z = zones.get(name);
    // A page asks for the room a scene promised it by name. A scene that no longer marks that
    // room has to say so here, rather than have the page lay its words over the picture.
    if (!z) throw new Error(`art: the "${id}" scene has no zone called "${name}" - the named zones are ${[...zones.keys()].join(', ')}, from <rect data-zone data-name> in art/src/papercut/scenes/${id}.svg`);
    return z;
  };
  return { item: ctx.art.place(id, placement), zones, at };
}

/** The middle of a box, for text centred in a zone. */
export const midX = (box) => box.x + box.w / 2;

/**
 * Handmade paper: the flat day ground and seven large, faint clouds of fibre
 * (book-design-system.md, "Paper and depth"). No speck grain - thousands of tiny marks bloat the
 * PDF - and the seed is the family's own, so one family's paper is always the same paper.
 *
 * Round 2: the seven clouds used to carry their own opacity each, so wherever two overlapped the
 * PDF blended them twice - about 0.22 became about 0.39, with a hard seam at the overlap's own
 * edge. Drawing them opaque inside one group and setting the group's opacity instead composites
 * the whole cloud as one flat layer first, so the book pays for one blend, not one per overlap.
 */
export function paperGround(ctx, seed) {
  const rand = seeded(`${seed} paper`);
  const items = [];
  for (let i = 0; i < 7; i++) {
    const cx = rand() * W, cy = rand() * H;
    items.push(path(blob(cx, cy, 90 + rand() * 160, 60 + rand() * 120, rand), { fill: ctx.P.paperDeep }));
  }
  return group(items, { op: 0.18 });
}

/**
 * A closed, hand-cut blob: points around an ellipse, each pushed in or out a little, joined by
 * quadratics through their midpoints so the outline is smooth and never geometrically perfect.
 */
function blob(cx, cy, rx, ry, rand, n = 9) {
  const pts = [];
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2;
    const k = 0.82 + rand() * 0.34;
    pts.push([cx + Math.cos(a) * rx * k, cy + Math.sin(a) * ry * k]);
  }
  const mid = (i) => [(pts[i][0] + pts[(i + 1) % n][0]) / 2, (pts[i][1] + pts[(i + 1) % n][1]) / 2];
  const d = new PathData().M(...mid(n - 1));
  for (let i = 0; i < n; i++) d.Q(pts[i][0], pts[i][1], ...mid(i));
  return String(d.Z());
}

/**
 * A Sanjhi band across the top of a day page: one paper colour, about 30 pt deep, with keri and
 * lotus shapes cut through it, laid as whole tiles end to end so the book pays for the tile once
 * (book-design-system.md, "Sanjhi bands"; site/book/art/README.md, "reuse, don't copy").
 *
 * It runs along the top rather than the foot, where the approved frames draw it, because
 * `ctx.footer` prints the folio at a fixed 22 pt from the foot: a 30 pt band there would print the
 * page number on top of the band, and "keep folios and footers clear of the band" is the rule the
 * band has to give way to. Raised on #239 for the design system to settle.
 */
export const BAND_TILES = 18;

/**
 * The band's colour, one paper cut like the approved frames vary theirs (round 2, finding 11): the
 * source tile is one flat `clay` cut, so a silhouette `tint` recolours the whole tile for free -
 * no second tile, no extra bytes. `seed` is the page's own (its chapter, never the page number,
 * art/README.md rule 6), so the same chapter always gets the same colour and an unrelated edit
 * elsewhere never reshuffles it.
 */
const BAND_TINTS = Object.freeze(['clay', 'peacock', 'rani', 'wash']);
export const bandTint = (seed) => BAND_TINTS[Math.floor(seeded(`${seed} band`)() * BAND_TINTS.length)];

/*
 * The furniture a page carries: its band's tint and its tailpiece's form, decided together.
 *
 * Both are seeded on the page's CHAPTER, never its number, so an unrelated edit elsewhere never
 * reshuffles which one a chapter gets. #287 is a reopening of round 2's finding 11: seeding on the
 * chapter guarantees two different chapters differ, but it says nothing about NEIGHBOURS - and it
 * actively guarantees a collision for a chapter that runs over several pages, since every page of
 * it is handed the identical seed. `book-design-system.md`'s Variety rule ("no two consecutive
 * pages share both a composition and an art placement") was being broken several pages at a time.
 *
 * The obvious fix - choose against the previous page the way `plan.js`'s `nextVariant` does - would
 * destroy the property the chapter seed exists for: inserting a page upstream would reshuffle every
 * chapter's colour downstream. So the chapter's own choice stays the PREFERENCE, and only a page
 * that would repeat BOTH of its neighbour's steps aside.
 *
 * It is the tailpiece that steps, never the band: the band's tint IS the chapter's colour, and a
 * chapter that runs over four pages should keep its colour across them. The step is the length of
 * the run so far, so a five-page chapter cycles its tailpiece rather than alternating - and a run
 * length is stable under an edit further up the book, which a page number is not.
 */
const FURNITURE = new WeakMap();   // ctx -> what the page before this one wanted, and got

export function pageFurniture(ctx, seed) {
  let st = FURNITURE.get(ctx);
  if (!st) FURNITURE.set(ctx, (st = { pageNo: null, want: null, chosen: null, run: 0 }));
  // The band asks at the head of the page and the tailpiece at its foot: one decision, asked twice.
  if (st.pageNo === ctx.pageNo) return st.chosen;

  const want = { band: bandTint(seed), tailpiece: TAILPIECE_FORMS[Math.floor(seeded(`${seed} tailpiece`)() * TAILPIECE_FORMS.length)] };
  const repeats = st.want !== null && st.want.band === want.band && st.want.tailpiece === want.tailpiece;
  st.run = repeats ? st.run + 1 : 0;
  const chosen = st.run === 0 ? want : {
    band: want.band,
    tailpiece: TAILPIECE_FORMS[(TAILPIECE_FORMS.indexOf(want.tailpiece) + st.run) % TAILPIECE_FORMS.length],
  };
  Object.assign(st, { pageNo: ctx.pageNo, want, chosen });
  // The page reports what it took, so `qa/invariants.mjs` can hold every book to the Variety rule
  // without re-deriving a seed it should not have to know about.
  ctx.describePage?.({ band: chosen.band, tailpiece: chosen.tailpiece });
  return chosen;
}

/**
 * How far down the page the sanjhi band reaches - what a page drawing at the head has to keep
 * clear of. #287: the haveli's vines did not, and grew up through the band on the `arch` variant.
 */
export const bandBottom = (ctx) => ctx.art.box('band-sanjhi', { x: 0, y: 0, anchor: 'top-left', w: W / BAND_TILES }).h;

export function sanjhiBand(ctx, seed = 'sanjhi') {
  const tint = pageFurniture(ctx, seed).band;
  const w = W / BAND_TILES;
  const items = [];
  for (let i = 0; i < BAND_TILES; i++) items.push(ctx.art.place('band-sanjhi', { x: i * w, y: 0, anchor: 'top-left', w, tint }));
  const box = ctx.art.box('band-sanjhi', { x: 0, y: 0, anchor: 'top-left', w });
  ctx.zone('busy', { x: 0, y: 0, w: W, h: box.h });
  return group(items);
}

/**
 * The folio: a small lit diya and the page number at the outer foot, the credit quietly opposite
 * (book-design-system.md, "Folio"). `ctx.footer` owns the words and the page number; this adds the
 * lamp beside them, clear of the number's own column.
 */
/**
 * The top of the folio row - its lamp is the tallest thing in it. #287: the closing's QR caption
 * was placed against a hand-counted margin instead, and came up 7 pt short, so the caption and the
 * folio lamp overlapped on every odd page. A page that draws above the folio asks for this.
 */
export const folioTop = (ctx) => ctx.art.box('diya-small', { x: 0, y: H - 17, w: 11 }).y;

export function folio(ctx, ink) {
  // Round 2, finding 12: the lamp sits beside wherever `ctx.footer` (compose.js) put the page
  // number - the outer foot, alternating with page parity at format 2 - never a fixed corner.
  const numberRight = ctx.tpl.format !== 2 || ctx.pageNo % 2 === 1;
  return [ctx.art.place('diya-small', { x: numberRight ? W - 78 : 78, y: H - 17, w: 11 }), ...ctx.footer(ink)];
}

/**
 * A title, and the line under it, both fitted to `width` rather than allowed to run off the page:
 * a family whose name is three times as long as another's gets a smaller title, never a clipped
 * one. Returns the items and the baseline a page may carry on from.
 */
export function titleBlock(ctx, { title, line, cx, y, width, titleSize = 32, titleInk, lineSize = 13, lineInk, lead, maxLines = 4 }) {
  const items = [];
  let at = y;
  if (title) {
    const size = ctx.fit(title, 'display', titleSize, width, 20);
    items.push(ctx.line(cx, at, title, 'display', size, titleInk ?? ctx.P.ink, { align: 'middle', width, kind: 'title' }));
    at += size * 0.72 + 14;
  }
  if (line) {
    const broken = ctx.lines(cx, at + lineSize, line, 'text', lineSize, lineInk ?? ctx.P.inkSoft, { width, maxLines, lead: lead ?? lineSize * 1.46, align: 'middle', kind: 'body' });
    items.push(...broken.items);
    at = broken.bottom;
  }
  return { items, bottom: at };
}

/**
 * The tailpiece a page that ends early closes with: ornament, on a page that counts nobody
 * (book-design-system.md, "Page furniture"). Round 2 (finding 11): two pages in a row used to
 * close with the exact same garland at the exact same x, which the density rule's variety clause
 * forbids ("no two consecutive pages share both a composition and an art placement"). Three forms,
 * all from the existing motif vocabulary, picked by a seeded hash of the page's own chapter - never
 * the page number, so an unrelated edit elsewhere never reshuffles which one a chapter gets.
 */
const TAILPIECE_FORMS = Object.freeze(['garland', 'lotus', 'sprig']);

export function tailpiece(ctx, cx, y, seed = 'tailpiece') {
  const form = pageFurniture(ctx, seed).tailpiece;
  if (form === 'lotus') {
    return [
      ctx.art.place('divider-lotus', { x: cx, y, w: 132 }),
      ctx.art.place('diya', { x: cx, y: y + 20, w: 24 }),
    ];
  }
  if (form === 'sprig') {
    return [
      ctx.art.place('peepal', { x: cx - 30, y, s: 1.05, flip: 'x' }),
      ctx.art.place('diya', { x: cx, y: y + 2, w: 26 }),
      ctx.art.place('peepal', { x: cx + 30, y, s: 1.05 }),
    ];
  }
  return [
    ctx.art.place('mala', { x: cx, y, w: 150 }),
    ctx.art.place('diya', { x: cx, y: y + 2, w: 26 }),
  ];
}

/**
 * Lamplight as the design system asks for it: stacked translucent discs, plain alpha, never a
 * radial gradient (book-design-system.md, "Light" - a PDF draws a gradient with transparent stops
 * as a soft mask, which costs far more than three flat circles). Round 2: a `glowDiscs` cluster at
 * ZONE scale, for the warmth a per-lamp glow can no longer carry once a family is too large for
 * every lamp to keep its own (findings 6, 18).
 */
export function glowDiscs(P, cx, cy, r, colour = P.flame) {
  return group([
    circle(cx, cy, r, { fill: colour, op: 0.1 }),
    circle(cx, cy, r * 0.6, { fill: colour, op: 0.16 }),
    circle(cx, cy, r * 0.3, { fill: colour, op: 0.22 }),
  ]);
}

/**
 * The book's one handwritten note: a torn card with a strip of tape, at most one to a page
 * (book-design-system.md, "Page furniture"). `text` is already clamped to three lines by
 * `family.js`'s `clampNote`; this only lays them out.
 */
export function noteCard(ctx, text, { cx, y, width }) {
  const lines = text.split('\n');
  const size = 12.5;
  const h = lines.length * size * 1.4 + size * 1.2;
  // the paper shadow every cut layer casts: the same shape, offset (book-design-system.md)
  const items = [rect(cx - width / 2 + 1.7, y + 2.3, width, h, { fill: ctx.P.ink, op: 0.22 })];
  items.push(rect(cx - width / 2, y, width, h, { fill: ctx.P.card }));
  items.push(rect(cx - 18, y - 5, 36, 11, { fill: ctx.P.gold, op: 0.45 }));
  lines.forEach((l, i) => items.push(ctx.line(cx, y + size * 1.5 + i * size * 1.4, l, 'hand', size, ctx.P.ink, { align: 'middle', width: width - 16, kind: 'caption' })));
  return { items: group(items), bottom: y + h };
}
