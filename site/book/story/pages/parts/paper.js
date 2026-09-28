/*
 * The paper a family page (#257) is printed on: its ground, its title block, the ornaments each
 * variant hangs on it, and the one handwritten note it may carry.
 *
 * Nothing here knows about people - `people.js` draws those - so the two can be read apart: this
 * file only ever answers "what does this page look like before anybody is on it".
 *
 * Shared within #257 only (site/book/story/pages/parts/, AGENT-RULES "Wave 3"). Composer code:
 * deterministic, static relative imports, no clock, no locale, no DOM, no Math.random.
 */

import { path, rect, group, PathData, PAGE } from '../../../format.js';
import { seeded } from '../../../art/seed.js';
import { toran } from '../../../art/procedural/index.js';

/** The text-safe area (docs/book-design-system.md, "Page furniture"). */
export const SAFE = Object.freeze({ left: 42, right: PAGE.w - 42, top: 54, bottom: PAGE.h - 50 });

/** Type sizes. A family page uses the title, the hand voice, and the three caption sizes. */
export const TYPE = Object.freeze({ title: 30, titleSmall: 21, hand: 13, name: 10.5, nameMin: 9, dates: 9, kin: 10, note: 11 });

/**
 * The seed every seeded thing on a family page takes: the family and the chapter, never the page
 * number (site/book/art/README.md, rule 6), so an edit elsewhere in the book does not reshuffle
 * this page's paper.
 */
export const pageSeed = (ctx, page, what) => `${ctx.family.title}|${page.chapters.join('+')}|${what}`;

/** A hand-cut closed blob of `n` points around (cx, cy), radius `r`, wobbled by `rand`. */
function blob(cx, cy, r, n, rand, squash = 1) {
  const pts = [];
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2;
    const rr = r * (0.78 + rand() * 0.44);
    pts.push([cx + Math.cos(a) * rr, cy + Math.sin(a) * rr * squash]);
  }
  const d = new PathData().M((pts[0][0] + pts[n - 1][0]) / 2, (pts[0][1] + pts[n - 1][1]) / 2);
  for (let i = 0; i < n; i++) {
    const p = pts[i], q = pts[(i + 1) % n];
    d.Q(p[0], p[1], (p[0] + q[0]) / 2, (p[1] + q[1]) / 2);
  }
  return String(d.Z());
}

/**
 * A day page's handmade paper: the ground, then seven large faint clouds of `paperDeep`
 * (docs/book-design-system.md, "Paper and depth" - seven clouds, and no speck grain, which would
 * cost thousands of marks). The pages that stand on a scene get this from the scene instead.
 */
export function handmadePaper(ctx, page) {
  const rand = seeded(pageSeed(ctx, page, 'paper'));
  const clouds = [];
  for (let i = 0; i < 7; i++) {
    const cx = 40 + rand() * (PAGE.w - 80);
    const cy = 60 + rand() * (PAGE.h - 120);
    clouds.push(path(blob(cx, cy, 90 + rand() * 70, 7, rand, 0.62), { fill: ctx.P.paperDeep }));
  }
  // The seven clouds are one group at one opacity, not seven independently-opaque layers: drawn
  // separately at 0.26 each they compound wherever two overlap (to about 0.45) and show as
  // hard-edged lenses, very visible on an otherwise empty page (#257 round 2, finding 20). #256
  // makes the identical change in its own parts/page.js at the same 0.18, so the two agree.
  return [rect(0, 0, PAGE.w, PAGE.h, { fill: ctx.P.paper }), group(clouds, { op: 0.18 })];
}

/**
 * The chapter's words at the top of a page: the title in `display` and, under it, the page's one
 * hand-written line. A continuation page takes the smaller title and no line, so the chapter's
 * second page never reads as the chapter starting again (design system, "Continuation pages").
 *
 * `box` is where they may sit - a scene's own `title` zone, or the safe area on a page with no
 * scene. Returns the items and the y the page may start drawing people at.
 */
export function titleBlock(ctx, page, { title, line }, box) {
  const items = [];
  const cx = box.x + box.w / 2;
  let y = box.y;
  if (title) {
    const size = page.continued ? TYPE.titleSmall : TYPE.title;
    y += size * 1.12;
    items.push(ctx.line(cx, y, title, 'display', ctx.fit(title, 'display', size, box.w, TYPE.titleSmall), ctx.P.ink, { align: 'middle', width: box.w, kind: 'title' }));
  }
  if (line && !page.continued) {
    y += TYPE.hand * 1.5;
    items.push(ctx.line(cx, y, line, 'hand', ctx.fit(line, 'hand', TYPE.hand, box.w, 10.5), ctx.P.inkSoft, { align: 'middle', width: box.w, kind: 'body' }));
  }
  return { items, bottom: y + 10 };
}

/**
 * The folio the design system asks for: a small lit diya beside the page number, and the credit
 * opposite it. `ctx.footer` writes both lines; this only hangs the lamp, clear of the number's own
 * box, so every family page's foot reads the same.
 */
export function folio(ctx) {
  return [ctx.art.place('diya-small', { x: PAGE.w - 62, y: PAGE.h - 15, w: 17 }), ...ctx.footer(ctx.P.inkSoft)];
}

/**
 * A Sanjhi band along the top of the page: one tile symbol laid end to end
 * (docs/book-design-system.md, "Sanjhi bands"). It runs along the top rather than the foot because
 * `ctx.footer` fixes the folio at 22 pt from the bottom, and a band deep enough to read would print
 * under it - "keep folios and footers clear of the band".
 */
export function sanjhiBand(ctx, page) {
  const w = 44;
  const items = [];
  for (let x = 0; x < PAGE.w; x += w) items.push(ctx.art.place('band-sanjhi', { x, y: 0, w }));
  ctx.zone('busy', { x: 0, y: 0, w: PAGE.w, h: 38 });
  return [group(items)];
}

/**
 * A toran hung over a household: the doorway the design system says a household is. `cluster` is
 * the span the household's frames take; the cord is hung a little wider than they are.
 */
export function doorway(ctx, page, { x1, x2, y }, what) {
  const pad = 14;
  const hung = toran(ctx.art, ctx.P, x1 - pad, x2 + pad, y, pageSeed(ctx, page, `toran-${what}`), { size: 15, gap: 0.78, sag: 0.05 });
  if (hung) ctx.zone('busy', { x: x1 - pad - 8, y: y - 10, w: x2 - x1 + 2 * pad + 16, h: 30 });
  return hung ? [hung] : [];
}

/** The cut-paper step a household stands on: a plinth of `stone`, with the paper shadow. */
export function step(ctx, { x1, x2, y }) {
  const w = x2 - x1 + 26, h = 9;
  const x = x1 - 13;
  return [
    rect(x + 1.7, y + 2.3, w, h, { fill: ctx.P.ink, op: 0.22 }),
    rect(x, y, w, h, { fill: ctx.P.stone }),
    rect(x + 6, y + h, w - 12, h * 0.7, { fill: ctx.P.paperDeep }),
  ];
}

/**
 * The hand-cut line a row of people stands on (design system: "Siblings share one ground line").
 * Drawn before the frames, so they stand on it rather than beside it.
 */
export function groundLine(ctx, page, { x1, x2, y }, what) {
  const rand = seeded(pageSeed(ctx, page, `ground-${what}`));
  const d = new PathData().M(x1 - 12, y);
  const steps = 7;
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    d.L(x1 - 12 + (x2 - x1 + 24) * t, y + (rand() - 0.5) * 1.8);
  }
  return [path(String(d), { stroke: ctx.P.stone, sw: 1.6, op: 0.85 })];
}

/**
 * The book's own voice: a torn card with a strip of tape, carrying one person's note. At most one
 * to a page (design system, "Page furniture"), so a page asks for this once and reserves its
 * height before it lays anybody out.
 */
export function noteCard(ctx, page, note, { cx, top, w }) {
  const lines = ctx.lines(cx, top + 22, note, 'hand', TYPE.note, ctx.P.ink, { width: w - 36, maxLines: 3, align: 'middle', lead: TYPE.note * 1.45, kind: 'caption' });
  const h = lines.bottom - top + 18;
  const x = cx - w / 2;
  return {
    items: [
      rect(x + 1.7, top + 2.3, w, h, { fill: ctx.P.ink, op: 0.22 }),
      rect(x, top, w, h, { fill: ctx.P.card }),
      rect(cx - 22, top - 5, 44, 11, { fill: ctx.P.gold, op: 0.45 }),
      ...lines.items,
    ],
    height: h + 12,
  };
}

/** How tall `noteCard` will be, so a page can take it off the body's height before laying out. */
export function noteHeight(ctx, note, w) {
  if (!note) return 0;
  const broken = ctx.lines(0, 0, note, 'hand', TYPE.note, ctx.P.ink, { width: w - 36, maxLines: 3, lead: TYPE.note * 1.45 });
  return (broken.count - 1) * TYPE.note * 1.45 + 40 + 12;
}

/**
 * The tailpiece a chapter that ends early closes with: a lit diya and a short mala
 * (docs/book-design-system.md, "Page furniture"). It is what stands in the quiet foot of a page
 * that has no handwritten note to carry.
 */
export const tailpiece = (ctx, cx, y) => [
  ctx.art.place('mala', { x: cx, y, w: 150 }),
  ctx.art.place('diya', { x: cx, y: y + 34, w: 26 }),
];

/** A rectangle as an absolute path, for a group clip (format 2). */
export const clipRect = (x, y, w, h) => String(new PathData().M(x, y).L(x + w, y).L(x + w, y + h).L(x, y + h).Z());
