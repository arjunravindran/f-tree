/*
 * The page furniture the closing chapters share (#258): the scene under a page, the title block
 * over it, a Sanjhi band, and the two small facts every one of these pages prints the same way -
 * when somebody lived, and what a page's art is seeded from.
 *
 * Only `pages/lists.js` and its own parts use this. It is deliberately not a general page kit: the
 * archetypes each issue owns are its own, and a helper that three issues shared would become a
 * fourth owner of the look.
 *
 * Composer code: deterministic, no clock, no locale, no DOM, no Math.random, static imports only.
 */

import { PAGE, group } from '../../../format.js';

/** The design system's text-safe area (docs/book-design-system.md, "Page furniture"). */
export const SAFE = Object.freeze({ x: 42, y: 54, w: PAGE.w - 84, bottom: PAGE.h - 50 });

/**
 * A whole-page scene (`art/papercut/scenes.js`), with every zone it marks recorded for the QA
 * harness, and its named zones looked up by name.
 *
 * `mirrored` draws the scene left-for-right - the second variant every scene archetype has. The
 * zones come back mirrored with it (`art.zones` transforms them), so a page that asks for
 * `house-1` gets the box that house actually landed in, whichever way round the lane runs.
 */
export function scene(ctx, id, { mirrored = false } = {}) {
  const placement = { x: 0, y: 0, w: PAGE.w, anchor: 'top-left', ...(mirrored ? { flip: 'x' } : {}) };
  const zones = ctx.art.zones(id, placement);
  for (const z of zones) ctx.zone(z.kind, z);
  const named = new Map(zones.filter((z) => z.name !== undefined).map((z) => [z.name, z]));
  return {
    items: [ctx.art.place(id, placement)],
    /** A named zone's box in page points. An unknown name fails here, not silently off-page. */
    zone(name) {
      const z = named.get(name);
      if (!z) throw new Error(`the "${id}" scene has no zone called "${name}" - its zones are ${[...named.keys()].join(', ')}`);
      return z;
    },
    /** Named zones in reading order (left to right), for a scene drawn either way round. */
    across(prefix, n) {
      return Array.from({ length: n }, (_, i) => this.zone(`${prefix}-${i + 1}`)).sort((a, b) => a.x - b.x);
    },
  };
}

/**
 * The chapter title, and the template's own line under it in the hand.
 *
 * A chapter's second and later pages say so in the title ("Our lane, continued") rather than
 * repeating the line, which is the idiom the approved register frame uses for a section that runs
 * over a page. The title shrinks to fit its zone rather than running out of it, and never below
 * `min`.
 */
export function titleBlock(ctx, page, copy, zone, { ink, soft, size = 34, min = 20, align = 'start', lead = 1.45 } = {}) {
  const x = align === 'middle' ? zone.x + zone.w / 2 : zone.x;
  const title = page.continued && copy?.title ? `${copy.title}, continued` : copy?.title;
  const items = [];
  let y = zone.y + size * 0.86;
  if (title) {
    const fitted = ctx.fit(title, 'display', size, zone.w, min);
    items.push(ctx.line(x, y, title, 'display', fitted, ink, { align, width: zone.w, kind: 'title' }));
    y += fitted * 0.42;
  }
  if (copy?.line && !page.continued) {
    const body = ctx.lines(x, y + 13, copy.line, 'hand', 13, soft, { width: zone.w, maxLines: 2, lead: 13 * lead, align, kind: 'body' });
    items.push(...body.items);
  }
  return items;
}

/**
 * A motif placed at `(x, y)` with its longer side `size` across, whatever shape its own drawing
 * is: a mango leaf is tall and a marigold is round, and a page laying several of them in a row
 * wants them to weigh the same rather than to share one width.
 */
export function motif(ctx, id, x, y, size, options = {}) {
  const unit = ctx.art.box(id, { x: 0, y: 0, s: 1, anchor: 'center' });
  const placement = { x, y, anchor: 'center', ...(unit.h > unit.w ? { h: size } : { w: size }), ...options };
  ctx.zone('busy', ctx.art.box(id, placement));
  return ctx.art.place(id, placement);
}

/**
 * A Sanjhi band along the top (or, mirrored, the foot) of a page: one tile symbol laid end to end,
 * never a drawing per tile (site/book/art/README.md, "reuse, don't copy"). The band is marked busy,
 * so nothing is laid out over its cut-outs.
 */
export function sanjhiBand(ctx, { foot = false, tint } = {}) {
  const tile = 44, depth = 42;
  // `flip: 'y'` mirrors the tile inside its own box, so a foot band has to be placed at the band's
  // own top edge; placed at the page's foot it would be drawn entirely off the paper.
  const y = foot ? PAGE.h - depth : 0;
  const items = [];
  for (let i = 0; i * tile < PAGE.w; i++) {
    items.push(ctx.art.place('band-sanjhi', { x: i * tile, y, w: tile, anchor: 'top-left', ...(foot ? { flip: 'y' } : {}), ...(tint ? { tint } : {}) }));
  }
  ctx.zone('busy', { x: 0, y, w: PAGE.w, h: depth });
  return [group(items)];
}


/**
 * When somebody lived, in the compact form a list column holds: "1935 – 1999" for the departed,
 * "b. 1970" for the living, and nothing at all where no year is recorded.
 *
 * Years only, even when the reader asked for full dates (`options.livingDates`): a register row is
 * one line wide, and a book that is forwarded past the people who chose it should not carry a
 * living person's full birth date in a column this small. An age is never printed anywhere.
 */
export function lifeDates(p) {
  if (!p) return '';
  if (!p.deceased) return p.by ? `b. ${p.by}` : '';
  if (p.by && p.dy) return `${p.by} – ${p.dy}`;
  if (p.by) return `b. ${p.by}`;
  if (p.dy) return `d. ${p.dy}`;
  return 'Late';
}

/**
 * The seed for a page's seeded art. It is the family's own - its title and the person the story is
 * told around - and never the page number, so an edit that moves a page does not reshuffle its
 * picture (site/book/art/README.md, rule 6). `what` separates two seeded things on one page.
 */
export const familySeed = (ctx, story, what) => `${ctx.family.title}|${story.kin.featured ?? 'nobody'}|${what}`;
