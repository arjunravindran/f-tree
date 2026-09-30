/*
 * The chart: the family tree itself, on one page the size of the family (#315).
 *
 * Not a book. The other two templates arrange a family into pages someone reads in order; this one
 * draws the tree people already read on screen, at its own size, so a reader can zoom into it
 * instead of squinting at a tree that was shrunk to fit a sheet. Every decision below follows from
 * that, and there are only a few of them.
 *
 * **The layout is not computed here.** `layoutArchive` (site/playground/layout.js) places the nodes
 * and works out every connector, and it is the same function behind the chart on screen and behind
 * Heirloom's tree page. This module turns its answer into the book's drawing primitives and decides
 * how large a page that needs. `orientation: 'columns'` - generations left to right, eldest on the
 * left, as the Android app arranges them.
 *
 * **Columns rather than rows** because generation depth is bounded and generation width is not. A
 * family of 200 is 17062x978 as rows, past the largest page a PDF can hold, and 1722x7421 as
 * columns. Rows turn a wide generation into a ribbon; columns turn deep families into a tall scroll,
 * which is the direction a reader pans anyway.
 *
 * **The page is the tree's own bounding box plus one margin**, which is the whole of the dynamic
 * spacing and the reason there is no empty space to be lost in. Note that `layout.width/height` are
 * *not* that box - they are the canvas the on-screen renderer scales, and they run to 30% larger
 * (the sample family is 1942x558 of tree inside a 2076x788 canvas). So the box is measured here,
 * over the cards and every connector that is actually drawn.
 *
 * `MAX_PAGE` is the only reason anything is ever scaled, and on every fixture we have it is never
 * reached. The scale is arithmetic rather than a group transform so that the coordinates in the book
 * are the coordinates on the page: the QA report reads text boxes straight out of the items, and a
 * transform above them would put every box in the wrong place.
 */

import { rect, path, PathData, MIN_PAGE, MAX_PAGE } from './format.js';
import { lifeYears } from './family.js';
import { layoutArchive } from '../playground/layout.js';

/**
 * How the shelves of a family with unconnected branches are packed - passed straight to
 * `layoutArchive`, and fixed, because a number chosen from the data would still have to be the same
 * number every time. 0.8 is what `family.js` uses for the same reason.
 */
const CHART_ASPECT = 0.8;

/*
 * The paper around the tree. One margin on three sides, and room for the title above it - which is
 * part of the page rather than floating over the tree, so the arithmetic stays something a test can
 * state exactly: page = tree + these.
 */
export const CHART_PAD = Object.freeze({ top: 84, right: 56, bottom: 56, left: 56 });

/* Text. A card is 188 x 64 at 1:1, which is room for a name at a comfortable size rather than a
 * legible-at-a-squint one; `NAME_MIN` is the floor a very long name shrinks to before it is allowed
 * to run to a second line. */
const NAME_SIZE = 13;
const NAME_MIN = 8;
const YEARS_SIZE = 9.5;
const TITLE_SIZE = 18;
const CARD_PAD = 10;

/** Everything drawn for one chart, given a layout already placed. Exported for the layout tests. */
export function chartLayout(family) {
  const layout = layoutArchive(family.graph, { orientation: 'columns', aspect: CHART_ASPECT });
  const M = layout.metrics;
  const drawn = [];
  for (const list of [layout.couples, layout.descents, layout.siblings]) drawn.push(...list);

  /* The tree's own extent: the cards, and every connector, because a descent bus can reach left of
   * the parent card it leaves and a shelf's connectors can sit outside its nodes. */
  const box = { x0: Infinity, y0: Infinity, x1: -Infinity, y1: -Infinity };
  const hold = (x, y) => {
    box.x0 = Math.min(box.x0, x); box.y0 = Math.min(box.y0, y);
    box.x1 = Math.max(box.x1, x); box.y1 = Math.max(box.y1, y);
  };
  for (const n of layout.nodes) { hold(n.x, n.y); hold(n.x + M.NODE_W, n.y + M.NODE_H); }
  for (const link of drawn) for (const [ax, ay, bx, by] of link.segments) { hold(ax, ay); hold(bx, by); }

  // A family with nobody in it has no tree to measure, and still gets a page with its title on it.
  const empty = !layout.nodes.length;
  if (empty) { box.x0 = 0; box.y0 = 0; box.x1 = MIN_PAGE - CHART_PAD.left - CHART_PAD.right; box.y1 = 0; }

  const treeW = box.x1 - box.x0;
  const treeH = box.y1 - box.y0;
  const padX = CHART_PAD.left + CHART_PAD.right;
  const padY = CHART_PAD.top + CHART_PAD.bottom;

  /*
   * Scaled only to stay inside the largest page a PDF can hold, and then uniformly, so the tree is
   * never distorted to fit anything. On every fixture this is exactly 1.
   */
  const scale = Math.min(1, (MAX_PAGE - padX) / Math.max(treeW, 1), (MAX_PAGE - padY) / Math.max(treeH, 1));

  /* A page is whole points (format.js), and never under MIN_PAGE - a lone person is 188 x 64 of
   * tree, which is a narrower page than a PDF reader will open. Where the floor bites, the extra
   * goes evenly on both sides rather than leaving the tree against a corner. */
  const size = {
    w: Math.max(MIN_PAGE, Math.ceil(treeW * scale + padX)),
    h: Math.max(MIN_PAGE, Math.ceil(treeH * scale + padY)),
  };
  const slackX = (size.w - (treeW * scale + padX)) / 2;
  const slackY = (size.h - (treeH * scale + padY)) / 2;

  const tx = (x) => CHART_PAD.left + slackX + (x - box.x0) * scale;
  const ty = (y) => CHART_PAD.top + slackY + (y - box.y0) * scale;
  return { layout, M, drawn, size, scale, tx, ty, tree: { w: treeW, h: treeH }, empty };
}

/**
 * The chart's one page.
 *
 * Ordered so that nothing a reader has to read is drawn under anything: the paper, the connectors,
 * then the cards over them, then the words.
 */
export function chartBook(ctx) {
  const { family, P } = ctx;
  const L = chartLayout(family);
  const { M, tx, ty, scale, size } = L;
  const s = (n) => n * scale;

  const items = [rect(0, 0, size.w, size.h, { fill: P.paper })];

  const lines = new PathData();
  for (const link of L.drawn) {
    for (const [ax, ay, bx, by] of link.segments) lines.M(tx(ax), ty(ay)).L(tx(bx), ty(by));
  }
  if (!lines.empty) items.push(path(String(lines), { stroke: P.line, sw: Math.max(0.5, s(1.1)), cap: 'round' }));

  /*
   * Deterministic order: `layout.nodes` is whatever the placement produced, so the cards are drawn
   * in reading order instead - down each generation, generation by generation. Two people can share
   * a position only if the layout put them there, so (x, y, id) is a total order.
   */
  const people = [...L.layout.nodes]
    .map((n) => ({ n, p: family.byId.get(n.id) }))
    .filter((e) => e.p)
    .sort((a, b) => a.n.x - b.n.x || a.n.y - b.n.y || (a.n.id < b.n.id ? -1 : 1));

  for (const { n, p } of people) {
    const x = tx(n.x), y = ty(n.y), w = s(M.NODE_W), h = s(M.NODE_H);
    items.push(rect(x, y, w, h, { fill: P.card, stroke: P.line, sw: Math.max(0.4, s(0.8)), r: s(4) }));
  }
  for (const { n, p } of people) {
    const x = tx(n.x), y = ty(n.y), w = s(M.NODE_W), h = s(M.NODE_H);
    const inner = w - 2 * s(CARD_PAD);
    const years = lifeYears(p);
    const name = p.name || 'Name not recorded';
    const nameSize = ctx.fit(name, 'strong', s(NAME_SIZE), inner, s(NAME_MIN));
    // Centred as a pair, so a person with no years recorded is not left sitting high in their card.
    const gap = years ? s(YEARS_SIZE) * 1.5 : 0;
    const top = y + (h - (nameSize + gap)) / 2 + nameSize * 0.78;
    ctx.show(p.id);
    items.push(ctx.line(x + w / 2, top, name, 'strong', nameSize, p.name ? P.ink : P.inkSoft,
      { align: 'middle', width: inner, kind: 'name', op: p.name ? undefined : 0.75 }));
    if (years) {
      items.push(ctx.line(x + w / 2, top + gap, years, 'text', s(YEARS_SIZE), p.deceased ? P.aged : P.inkSoft,
        { align: 'middle', width: inner, kind: 'caption' }));
    }
  }

  /*
   * The only words on the page that are not somebody's name: the family's own title at the head, and
   * the credit at the foot. The credit goes to the foot rather than opposite the title because a page
   * is as narrow as the family is small - a family of nobody gets a 200 pt page, and the two lines
   * collided there.
   */
  items.push(ctx.line(CHART_PAD.left, CHART_PAD.top - 36, family.title, 'strong', TITLE_SIZE, P.ink,
    { width: size.w - CHART_PAD.left - CHART_PAD.right, kind: 'body' }));
  if (ctx.attribution) {
    items.push(ctx.line(size.w - CHART_PAD.right, size.h - 22, 'Made with f-tree', 'text', 9, P.inkSoft,
      { align: 'end', width: 120, op: 0.75, kind: 'folio' }));
  }

  return { pages: [{ label: 'The family chart', items }], size };
}
