/*
 * The haveli facade behind an arch window (#256, round 2 design critique finding 2): a brick wall,
 * a kangura parapet lit with diyas, a marigold toran across the doorway, two jaali windows, two lit
 * aala-like niches at the foot and a peepal vine draping the top corners.
 *
 * `docs/book-design-system.md` lists this vocabulary under "architecture" (shikhara, dome, chhatri,
 * a haveli facade with its kangura parapet and jaali windows), but only the arch itself, the aala
 * niche and a handful of flora are compiled into `LIBRARY.symbols` (`site/book/art/README.md`'s own
 * asset list) - the rest exists only as `art/style-frames/motifs.mjs`, a dev-only authoring tool
 * that writes the six pre-built SCENES (`tools/book_scenes.mjs`) and is unreachable from the
 * composer (it imports node:fs). Commissioning a compiled `haveli` symbol is #253's to do (round 2:
 * "do not commission new assets"), so this wall is drawn the same way `paperGround` and `sanjhiBand`
 * already draw page furniture: plain `format.js` primitives, deterministic, no new asset - plus the
 * motifs that already exist (`aala`'s own dashed brass perimeter is reserved for "name not known",
 * so the niches here are a plain alcove of the same silhouette, never `aala` itself).
 *
 * Composer code: deterministic, no clock, no locale, no DOM, no Math.random, static relative
 * imports only.
 */

import { rect, path, circle, group, PathData } from '../../../format.js';
import { seeded } from '../../../art/seed.js';
import { toran } from '../../../art/procedural/index.js';

/**
 * The crenellated silhouette of a kangura parapet: a band with alternating merlons along its top
 * edge, as one filled path (site/book/art/README.md, "reuse, don't copy" - a merlon is geometry,
 * not a motif, so it costs nothing to repeat in one path rather than placing a symbol many times).
 */
function kangura(x0, x1, topY, merlonH, bandH, mw) {
  const n = Math.max(2, Math.round((x1 - x0) / mw));
  const w = (x1 - x0) / n;
  const d = new PathData().M(x0, topY + merlonH);
  for (let i = 0; i < n; i++) {
    const bx = x0 + i * w;
    if (i % 2 === 0) d.L(bx, topY).L(bx + w, topY).L(bx + w, topY + merlonH);
    else d.L(bx + w, topY + merlonH);
  }
  d.L(x1, topY + merlonH + bandH).L(x0, topY + merlonH + bandH).Z();
  return { d: String(d), n, w };
}

/** A small lit jaali (lattice) window: a pale frame, a few dashed lattice lines, a sill. */
function jaaliWindow(P, x, y, w, h, flip) {
  const items = [rect(x, y, w, h, { r: 3, fill: P.card, op: 0.92 })];
  const lattice = new PathData();
  for (let i = 1; i < 4; i++) lattice.M(x, y + (h * i) / 4).L(x + w, y + (h * i) / 4);
  for (let i = 1; i < 3; i++) lattice.M(x + (w * i) / 3, y).L(x + (w * i) / 3, y + h);
  items.push(path(String(lattice), { stroke: P.stone, sw: 0.7, op: 0.55 }));
  items.push(rect(x, y, w, h, { stroke: P.clay, sw: 1 }));
  items.push(rect(x - (flip ? 3 : 0), y + h, w + 3, 2.6, { fill: P.clay }));
  return items;
}

/** A small lit alcove at the wall's foot: a dark niche, a warm glow, a lamp - never `aala`, which
 * is the app's own "name not known" notation (dashed brass) and would misname an empty corner. */
function nicheLit(art, P, x, y, w, h) {
  return [
    rect(x, y, w, h, { r: w * 0.42, fill: P.deep }),
    ...glowDiscs(P, x + w / 2, y + h * 0.66, w * 0.46, P.flame),
    art.place('diya-small', { x: x + w / 2, y: y + h * 0.78, w: w * 0.56 }),
  ];
}

/** Three translucent discs, plain alpha (book-design-system.md, "Light"): the same shape the
 * cover's zone glow uses, kept local so a two-motif niche does not have to import page furniture. */
function glowDiscs(P, cx, cy, r, colour) {
  return [
    circle(cx, cy, r, { fill: colour, op: 0.14 }),
    circle(cx, cy, r * 0.6, { fill: colour, op: 0.22 }),
    circle(cx, cy, r * 0.3, { fill: colour, op: 0.3 }),
  ];
}

/**
 * The vine draping one top corner: two or three peepal leaves cascading inward, alternating tint.
 */
function vine(art, x, y, seed, flip) {
  const rand = seeded(seed);
  const items = [];
  for (let i = 0; i < 3; i++) {
    const dx = (flip ? -1 : 1) * (10 + i * 13 + rand() * 4);
    const dy = i * 15 + rand() * 5;
    items.push(art.place('peepal', {
      x: x + dx, y: y + dy, s: 0.62 - i * 0.06, flip: flip ? 'x' : undefined, tint: i % 2 ? 'leafDeep' : 'leaf',
    }));
  }
  return items;
}

/**
 * The whole facade, sized around `outer` (an arch's own `frameOuter` box): draw this BEFORE the
 * arch frame itself, so the window sits in the wall rather than floating on bare paper.
 */
export function haveliFacade(ctx, outer, seed) {
  const { P, art } = ctx;
  const margin = 34;
  const wallX = outer.x - margin, wallW = outer.w + margin * 2;
  const wallTop = outer.y - 58, wallBottom = outer.y + outer.h + 40;
  const items = [rect(wallX, wallTop, wallW, wallBottom - wallTop, { fill: P.stone })];

  // brick joints: a handful of faint horizontal lines, one path, no per-line translucent layer
  const joints = new PathData();
  for (let jy = wallTop + 22; jy < wallBottom - 12; jy += 26) joints.M(wallX + 4, jy).L(wallX + wallW - 4, jy);
  items.push(path(String(joints), { stroke: P.clay, sw: 0.8, op: 0.14 }));

  // the kangura parapet, its diyas, and the toran hung just under it, across the doorway itself
  const { d: parapetD, n, w: mw } = kangura(wallX, wallX + wallW, wallTop, 11, 16, 24);
  items.push(path(parapetD, { fill: P.clay }));
  for (let i = 0; i < n; i += 2) items.push(art.place('diya-small', { x: wallX + i * mw + mw / 2, y: wallTop - 2, w: 10 }));
  items.push(toran(art, P, outer.x + 16, outer.x + outer.w - 16, outer.y + 8, `${seed} facade-toran`, { size: 15, gap: 0.72 }));

  // two jaali windows, above the arch's shoulders
  const jw = 30, jh = 40, jy = wallTop + 40;
  items.push(...jaaliWindow(P, wallX + 12, jy, jw, jh, false));
  items.push(...jaaliWindow(P, wallX + wallW - 12 - jw, jy, jw, jh, true));

  // two lit niches, flanking the sill
  const nw = 26, nh = 36, ny = outer.y + outer.h - nh + 6;
  items.push(...nicheLit(art, P, wallX + 6, ny, nw, nh));
  items.push(...nicheLit(art, P, wallX + wallW - 6 - nw, ny, nw, nh));

  // a peepal vine draping each top corner
  items.push(...vine(art, wallX + 10, wallTop + 2, `${seed} vine-l`, false));
  items.push(...vine(art, wallX + wallW - 10, wallTop + 2, `${seed} vine-r`, true));

  return [group(items)];
}
