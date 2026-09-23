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

import { PAGE, rect, path, group, PathData } from '../../../format.js';
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
  return { item: ctx.art.place(id, placement), zones, at: (name) => zones.get(name) };
}

/** The middle of a box, for text centred in a zone. */
export const midX = (box) => box.x + box.w / 2;

/**
 * Handmade paper: the flat day ground and seven large, faint clouds of fibre
 * (book-design-system.md, "Paper and depth"). No speck grain - thousands of tiny marks bloat the
 * PDF - and the seed is the family's own, so one family's paper is always the same paper.
 */
export function paperGround(ctx, seed) {
  const rand = seeded(`${seed} paper`);
  const items = [];
  for (let i = 0; i < 7; i++) {
    const cx = rand() * W, cy = rand() * H;
    items.push(path(blob(cx, cy, 90 + rand() * 160, 60 + rand() * 120, rand), { fill: ctx.P.paperDeep, op: 0.22 }));
  }
  return group(items);
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

export function sanjhiBand(ctx) {
  const w = W / BAND_TILES;
  const items = [];
  for (let i = 0; i < BAND_TILES; i++) items.push(ctx.art.place('band-sanjhi', { x: i * w, y: 0, anchor: 'top-left', w }));
  const box = ctx.art.box('band-sanjhi', { x: 0, y: 0, anchor: 'top-left', w });
  ctx.zone('busy', { x: 0, y: 0, w: W, h: box.h });
  return group(items);
}

/**
 * The folio: a small lit diya and the page number at the outer foot, the credit quietly opposite
 * (book-design-system.md, "Folio"). `ctx.footer` owns the words and the page number; this adds the
 * lamp beside them, clear of the number's own column.
 */
export function folio(ctx, ink) {
  return [ctx.art.place('diya-small', { x: W - 78, y: H - 17, w: 11 }), ...ctx.footer(ink)];
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
 * The tailpiece a page that ends early closes with: a lit diya over a short marigold string
 * (book-design-system.md, "Page furniture"). Ornament, on a page that counts nobody.
 */
export function tailpiece(ctx, cx, y) {
  return [
    ctx.art.place('mala', { x: cx, y, w: 150 }),
    ctx.art.place('diya', { x: cx, y: y + 2, w: 26 }),
  ];
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
