/*
 * Hero pages (#256): the cover, the opening, a page waiting for its family, a portrait hero, and
 * the closing. One person or none, one moment on a scene: the pages that carry the most weight,
 * because the cover is the chat thumbnail and the opening is the reader's first look at F.
 *
 * `pages/index.js` holds the contract every archetype keeps (`describePage`, `show`, `line`'s
 * `kind`, `zone`). What is particular to these five:
 *
 *   - **The cover counts.** One lamp for each person in scope and not one more, through
 *     `diyaRow` (#255), laid in as many rows as it takes for the lamps not to overlap. It is the
 *     one page that must read at 150 px wide, so its words are set big and its art is the picture
 *     behind them, never over them.
 *   - **The cover is the family's, not the featured person's** (docs/storybook-plan.md's decision
 *     table): it names nobody, and it reports nobody as shown. The register is what promises that
 *     everyone appears.
 *   - **The opening is the reader's first look at F**, at an arch window: their photograph, or -
 *     with none - the figure seen from behind, looking at the view. Its words are `copy.js`'s:
 *     the template's own title, and the composed `openingLine`.
 *   - **The waiting page** is a book with nobody to feature: an arch with a lamp lit in it and
 *     nobody at the window, and an invitation in the app's own words.
 *   - **A portrait hero** is one or two people who need the room - a chapter with too few for a
 *     gathering - with as little text as says who they are.
 *   - **The closing** is the acquisition loop: a new-moon sky, "Is someone missing?", the QR code
 *     that opens the website, and the quiet footer.
 *
 * Composer code: deterministic, no clock, no locale, no DOM, no Math.random, static relative
 * imports only.
 */

import { PAGE, PathData, circle, path, rect, group } from '../../format.js';
import { SITE_QR } from '../../qr.js';
import { qrPath } from '../../blocks/art.js';
import { diyaRow, rangoli, toran } from '../../art/procedural/index.js';
import { chapterCopy, chapterVars, fillPlaceholders, nameOf, noteCaption, openingLine, pickLine } from '../copy.js';
import { SAFE, folio, midX, noteCard, paperGround, sanjhiBand, scene, scenePlacement, tailpiece, titleBlock } from './parts/page.js';
import { frameOuter, framedPerson, joinFrames, nameStack, openingIn, yearsCaption } from './parts/people.js';

const { w: W, h: H } = PAGE;

/* ------------------------------------------------------------------ the cover */

/**
 * How the cover's lamps are laid out. One lamp is one person, so the only thing a big family
 * changes is how the lamps are arranged: more rows, further off, each lamp smaller, exactly as the
 * approved frame draws the river receding. The rows never take a lamp away and never add one.
 */
const MAX_LAMP_ROWS = 9;
const NEAR_LAMP = 26;   // pt wide, the nearest row
const FAR_LAMP = 12;    // pt wide, the furthest
/*
 * A lamp keeps its own glow while it is near enough for the glow to read. Further off it is drawn
 * as `diya-small`, the same lamp without the glow discs: `ghat-night` already lays one warm glow
 * over the whole lamps zone, "whatever their number" (tools/book_scenes.mjs), so a small lamp's own
 * discs would only add cost. That cost is the reason the rule is here rather than left to taste - a
 * glowing lamp is about 17 KB of estimated PDF and a small one about 7.5 (compose.js's `ART_PDF`),
 * and a two-hundred-person cover lights two hundred of them.
 */
const GLOWS_ABOVE = 16;

/** Rows of lamps for `n` people: enough rows that a row's lamps sit side by side, not on top. */
export function lampRows(n, box) {
  if (n <= 0) return [];
  const rows = Math.max(1, Math.min(MAX_LAMP_ROWS, Math.round(Math.sqrt(n / 2.6))));
  const base = Math.floor(n / rows), extra = n % rows;
  const out = [];
  let at = 0;
  for (let i = 0; i < rows; i++) {
    // The far rows are the fuller ones: perspective crowds what is furthest away.
    const count = base + (i < extra ? 1 : 0);
    const t = rows === 1 ? 1 : i / (rows - 1);          // 0 far, 1 near
    const spread = 0.46 + 0.54 * Math.pow(t, 0.8);
    const cy = box.y + box.h * (0.12 + 0.82 * t);
    const half = (box.w * spread) / 2;
    const cx = box.x + box.w * (0.5 + 0.06 * (1 - t));   // the far rows sit a little upstream
    // the rows near the foot grow fastest, which is how a receding row of lamps really looks
    const w = Math.min(FAR_LAMP + (NEAR_LAMP - FAR_LAMP) * t * t, count > 1 ? ((2 * half) / (count - 1)) * 0.95 : NEAR_LAMP);
    out.push({ from: at, count, x1: cx - half, x2: cx + half, y: cy, w });
    at += count;
  }
  return out;
}

/**
 * The lamp field: `ids` in the order the book knows them, one lamp each, the ones whose names are
 * not known drawn as the dashed brass lamp `diyaRow` keeps for exactly that.
 */
function lamps(ctx, ids, box, seed) {
  const unknown = new Set(ids.map((id, i) => (ctx.family.byId.get(id)?.name ? -1 : i)).filter((i) => i >= 0));
  const items = [];
  for (const [i, row] of lampRows(ids.length, box).entries()) {
    const at = new Set([...unknown].filter((k) => k >= row.from && k < row.from + row.count).map((k) => k - row.from));
    const drawn = diyaRow(ctx.art, row.x1, row.y, row.x2, row.y, row.count, `${seed} lamps ${i}`, { w: row.w, jitter: 0.3, unknownAt: at, lamp: row.w >= GLOWS_ABOVE ? 'diya' : 'diya-small', unknownLamp: 'diya-unknown' });
    if (drawn) items.push(drawn);
  }
  return items;
}

/**
 * The family at the ghat: a few figures seen from behind, watching the lamps. They are scenery and
 * count nobody - always the same small group, whatever the family's size, drawn flat in the night's
 * own colours so no reader can mistake one for a relative (book-design-system.md, principle 1).
 */
const GHAT_FIGURES = Object.freeze([
  { art: 'hero-female-adult', at: 0.14, h: 0.6, tint: 'haze' },
  { art: 'hero-person-adult', at: 0.36, h: 0.68, tint: 'deep' },
  { art: 'hero-person-child', at: 0.55, h: 0.42, tint: 'glow' },
  { art: 'hero-male-adult', at: 0.78, h: 0.64, tint: 'night' },
]);

const figures = (ctx, box) => GHAT_FIGURES.map((f) => ctx.art.place(f.art, {
  x: box.x + box.w * f.at, y: box.y + box.h, anchor: 'bottom-center', h: box.h * f.h, tint: f.tint,
}));

function cover(ctx, page, story) {
  const { P, tpl, family, art } = ctx;
  describe(ctx, page);
  const mirror = page.variant.endsWith('-mirrored');
  const seed = family.title;
  const place = scenePlacement(mirror);
  const ghat = scene(ctx, 'ghat-night', place);
  const ids = [...story.kin.people.keys()];
  const vars = { ...chapterVars('cover', family, story.kin), n: ids.length };

  const items = [ghat.item];
  const top = ghat.at('toran');
  items.push(toran(art, P, top.x, top.x + top.w, top.y + 22, `${seed} toran`, { size: 26 }));
  for (const at of [0.19, 0.81]) {
    // hung well below the toran on a thread, as the approved cover hangs them
    const x = top.x + top.w * at, y = top.y + 64;
    items.push(path(new PathData().M(x, top.y + 34).L(x, y).toString(), { stroke: P.gold, sw: 0.8, op: 0.7 }));
    items.push(art.place('kandil', { x, y, w: 44 }));
    ctx.zone('busy', { x: x - 26, y: top.y + 30, w: 52, h: 96 });
  }
  items.push(...figures(ctx, ghat.at('figures')));
  items.push(...lamps(ctx, ids, ghat.at('lamps'), seed));
  // The one rangoli this book may afford beside "Still to be found"'s: laid flat on the landing,
  // tilted onto the floor the way the approved frames tilt theirs.
  const rx = mirror ? 148 : W - 148;
  items.push(group([rangoli(P, 0, 0, 52, `${seed} rangoli`)], { tf: [1, 0, 0, 0.34, rx, H - 48], op: 0.7 }));
  ctx.zone('busy', { x: rx - 56, y: H - 66, w: 112, h: 40 });

  const title = ghat.at('title');
  const cx = midX(title), width = title.w;
  // Set as large as the zone allows: the cover is read at 150 px in a chat app before it is ever
  // read at full size, and the greeting and the family's own line are what have to survive that.
  const greetingSize = ctx.fit(tpl.cover.greeting, 'display', 52, width, 26);
  const subtitle = fillPlaceholders(tpl.cover.subtitle, vars);
  const subtitleSize = ctx.fit(subtitle, 'display', 36, width, 18);
  items.push(ctx.line(cx, title.y + 56, tpl.cover.greeting, 'display', greetingSize, P.gold, { align: 'middle', width, kind: 'title' }));
  items.push(ctx.line(cx, title.y + 56 + greetingSize * 0.96, subtitle, 'display', subtitleSize, P.card, { align: 'middle', width, kind: 'title' }));
  const line = fillPlaceholders(pickLine(tpl.cover.line, vars.n), vars);
  if (line) {
    items.push(...ctx.lines(cx, title.y + 62 + greetingSize * 0.96 + subtitleSize * 1.2, line, 'hand', 16, P.flame, { width, maxLines: 2, lead: 22, align: 'middle', kind: 'caption' }).items);
  }
  if (ctx.attribution) {
    const credit = ghat.at('credit');
    items.push(ctx.line(credit.x + 4, credit.y + 15, 'Made with f-tree', 'text', 7.5, P.flame, { width: credit.w, op: 0.75, kind: 'folio' }));
  }
  return ctx.page(tpl.cover.greeting, items, P.deep);
}

/* ------------------------------------------------------------------ the arch pages */

/**
 * The day seen through an arch window: one flat gradient from a washed sky down to the warm
 * middle distance, and the low sun the approved opening frame puts behind its figure. Both
 * painters draw a linear gradient and a circle, and neither costs a soft mask.
 */
function view(ctx, box) {
  const ref = ctx.gradient(`view-${Math.round(box.y)}-${Math.round(box.y + box.h)}`, {
    type: 'linear', x1: 0, y1: box.y, x2: 0, y2: box.y + box.h,
    stops: [[0, ctx.P.sky, 1], [0.58, ctx.P.dayHaze, 1], [1, ctx.P.dayMid, 1]],
  });
  return [
    rect(box.x, box.y, box.w, box.h, { fill: ref }),
    circle(box.x + box.w * 0.6, box.y + box.h * 0.27, Math.min(box.w, box.h) * 0.13, { fill: ctx.P.flame, op: 0.85 }),
  ];
}

/**
 * The shape the opening and the waiting page share: a day page with an arch window, and a block of
 * words. The variant decides which comes first - `arch` puts the window at the top and the words
 * under it, `window` turns the page over - which is the whole of the design system's "no two
 * consecutive pages share both a composition and an art placement".
 */
const ARCH_WIDTH = 296;

function archPage(ctx, page, { label, frame, words, sillLamps = 2 }) {
  const { P } = ctx;
  const high = page.variant === 'arch';
  const outer = frameOuter('arch-jharokha', (W - ARCH_WIDTH) / 2, high ? 104 : 326, ARCH_WIDTH);
  const items = [paperGround(ctx, ctx.family.title), sanjhiBand(ctx)];
  const opening = openingIn('arch-jharokha', outer);
  items.push(...frame(outer, opening));
  // Lamps on the sill. Nothing on this page counts people, so a lamp here is ornament, which is
  // the one condition the design system puts on a decorative lamp.
  const sill = sillLamps === 1 ? [0.5] : [0.24, 0.76];
  for (const k of sill) items.push(ctx.art.place('diya', { x: opening.x + opening.w * k, y: opening.y + opening.h + 5, w: sillLamps === 1 ? 34 : 24 }));
  ctx.zone('busy', { x: outer.x, y: outer.y, w: outer.w, h: outer.h + 10 });

  const block = words(high ? outer.y + outer.h + 52 : SAFE.y + 40);
  items.push(...block.items);
  // The page ends with a tailpiece where the words do, so neither half of it floats in white.
  if (high) items.push(...tailpiece(ctx, W / 2, Math.min(block.bottom + 64, SAFE.y + SAFE.h - 34)));
  items.push(...folio(ctx, P.inkSoft));
  return ctx.page(label, items, P.paper);
}

function openingHero(ctx, page, story) {
  const { P, family } = ctx;
  describe(ctx, page);
  const person = family.byId.get(page.people[0]);
  const copy = chapterCopy(page.copyKey, { family, kin: story.kin, tpl: ctx.tpl });
  const sentence = openingLine(family, story.kin) ?? copy?.line ?? null;
  const caption = yearsCaption(ctx, story, person);
  return archPage(ctx, page, {
    label: copy?.title || 'The opening',
    frame: (outer) => framedPerson(ctx, person, {
      id: 'arch-jharokha', outer, hero: true, gen: 0, featuredBy: person?.by ?? null,
      view: view(ctx, openingIn('arch-jharokha', outer)),
    }),
    words: (y) => {
      const items = [];
      let at = y;
      if (caption) {
        items.push(ctx.line(W / 2, at, caption, 'hand', 14.5, P.inkSoft, { align: 'middle', width: SAFE.w, kind: 'caption' }));
        at += 32;
      }
      const block = titleBlock(ctx, { title: copy?.title, line: sentence, cx: W / 2, y: at, width: 430, titleSize: 34, lineSize: 13, lead: 19, maxLines: 5 });
      items.push(...block.items);
      let bottom = block.bottom;
      // An eldest F has no roots or courtyards chapter: the opening is where the book says so,
      // rather than leaving the reader to notice two chapters missing (plan.js's `folds`).
      const first = nameOf(family, story.kin, story.kin.featured);
      if (page.folds.length && first) {
        bottom += 32;
        items.push(ctx.line(W / 2, bottom, `${first} is the first name this family remembers.`, 'hand', 13.5, P.clay, { align: 'middle', width: SAFE.w, kind: 'caption' }));
      }
      return { items, bottom };
    },
  });
}

/**
 * The book with nobody to feature: an arch with a lamp lit in it and nobody at the window. Its
 * words are its own - the template's opening copy is written around a person this book has not
 * got, so filling it would print "This is." - and they are the app's own invitation.
 */
const WAITING_TITLE = 'A book waiting for its family';
const WAITING_LINE = 'There is no one in this family tree yet. Add the people you remember, with a name, a year or a photograph, and the next book will be about them.';
const WAITING_HAND = 'The lamp is lit, and the door is open.';

function waiting(ctx, page) {
  const { P } = ctx;
  describe(ctx, page);
  return archPage(ctx, page, {
    label: WAITING_TITLE,
    frame: (outer, opening) => [ctx.art.frame('arch-jharokha', opening, view(ctx, opening), { shadow: { dx: 1.7, dy: 2.3 } })],
    sillLamps: 1,
    words: (y) => {
      const block = titleBlock(ctx, { title: WAITING_TITLE, line: WAITING_LINE, cx: W / 2, y: y + 12, width: 420, titleSize: 30, lineSize: 13, lead: 19, maxLines: 5 });
      const bottom = block.bottom + 34;
      return { items: [...block.items, ctx.line(W / 2, bottom, WAITING_HAND, 'hand', 14, P.clay, { align: 'middle', width: SAFE.w, kind: 'caption' })], bottom };
    },
  });
}

/* ------------------------------------------------------------------ a portrait hero */

/**
 * One or two people with the room to be looked at: a chapter too small for a gathering
 * (`plan.js`'s hero density). `arch` stands them at windows, `niche` mounts them in medallions -
 * two placements of the same composition, so two such pages in a row never look alike.
 */
function portraitHero(ctx, page, story) {
  const { P, family } = ctx;
  describe(ctx, page);
  const arches = page.variant === 'arch';
  const people = page.people.map((id) => family.byId.get(id) ?? null);
  const copy = chapterCopy(page.copyKey, { family, kin: story.kin, tpl: ctx.tpl });
  const featuredName = nameOf(family, story.kin, story.kin.featured);
  const items = [paperGround(ctx, family.title), sanjhiBand(ctx)];

  const head = titleBlock(ctx, { title: copy?.title, line: page.continued ? null : copy?.line, cx: W / 2, y: SAFE.y + 46, width: 430, titleSize: 32, lineSize: 12.5, lead: 18, maxLines: 3 });
  items.push(...head.items);
  if (!arches) items.push(ctx.art.place('divider-lotus', { x: W / 2, y: head.bottom + 26, w: 150 }));

  const id = arches ? 'arch-jharokha' : 'medallion';
  const top = head.bottom + (arches ? 54 : 74);
  const one = people.length === 1;
  const width = one ? (arches ? 262 : 236) : (arches ? 210 : 190);
  const gap = one ? 0 : Math.min(60, W - 2 * SAFE.x - 2 * width);
  const left = (W - (people.length * width + gap)) / 2;
  const boxes = people.map((p, i) => frameOuter(id, left + i * (width + gap), top, width));

  let lowest = top;
  let noted = false;
  people.forEach((p, i) => {
    const outer = boxes[i];
    const opening = openingIn(id, outer);
    items.push(...framedPerson(ctx, p, {
      id: p?.photo && ctx.options.photos && !arches ? 'medallion-carved' : id,
      outer, hero: arches, gen: story.kin.people.get(p?.id)?.gen ?? null, featuredBy: family.byId.get(story.kin.featured)?.by ?? null,
      view: arches ? view(ctx, opening) : [],
    }));
    const stack = nameStack(ctx, story, p, { cx: outer.x + outer.w / 2, y: outer.y + outer.h + (arches ? 30 : 40), width: width + gap / 2, featuredName });
    items.push(...stack.items);
    lowest = Math.max(lowest, stack.bottom);
    // At most one handwritten note to a page (book-design-system.md), so the first person who has
    // one gets it, and it sits under both portraits rather than beside one of them.
    const note = noted || page.continued || !p ? null : noteFor(ctx, page, p);
    if (note) {
      noted = true;
      items.push(noteCard(ctx, note, { cx: W / 2, y: Math.min(lowest + 46, H - 176), width: 340 }).items);
    }
  });
  if (people.length === 2) {
    items.push(...joinFrames(ctx, people[0], people[1], {
      cx: W / 2, y: boxes[0].y + boxes[0].h * 0.42, width: Math.max(40, boxes[1].x - (boxes[0].x + boxes[0].w) + 30),
    }));
  }
  ctx.zone('busy', { x: boxes[0].x, y: top, w: boxes[boxes.length - 1].x + width - boxes[0].x, h: boxes[0].h });
  // A page that ends early closes with a tailpiece rather than a field of empty paper.
  if (!noted) items.push(...tailpiece(ctx, W / 2, Math.min(lowest + 74, SAFE.y + SAFE.h - 34)));
  items.push(...folio(ctx, P.inkSoft));
  return ctx.page(copy?.title || page.chapter, items, P.paper);
}

/**
 * The person's own note, where the reader asked for notes and a chapter on this page may show one.
 * A household page carries several chapters; `copy.js`'s `noteCaption` owns both rules (opt-in, and
 * `plan.js`'s `NO_NOTES`), so this only asks it, once per chapter the page is drawn from.
 */
const noteFor = (ctx, page, person) => page.chapters.map((c) => noteCaption(c, ctx.family, person.id, ctx.options)).find(Boolean) ?? null;

/* ------------------------------------------------------------------ the closing */

const MISSING = 'Is someone missing?';
const SCAN = 'Scan to get f-tree';

function closing(ctx, page, story) {
  const { P, tpl, family } = ctx;
  describe(ctx, page);
  const mirror = page.variant.endsWith('-mirrored');
  const sky = scene(ctx, 'closing-sky', scenePlacement(mirror));
  const copy = chapterCopy(page.copyKey, { family, kin: story.kin, tpl });
  const vars = chapterVars('closing', family, story.kin);
  const items = [sky.item];

  const title = sky.at('title');
  const greeting = copy?.title || tpl.cover.greeting;
  const size = ctx.fit(greeting, 'display', 44, title.w, 24);
  items.push(ctx.line(midX(title), title.y + 56, greeting, 'display', size, P.gold, { align: 'middle', width: title.w, kind: 'title' }));
  const from = fillPlaceholders(tpl.cover.subtitle, vars);
  if (from) items.push(ctx.line(midX(title), title.y + 60 + size * 0.96, from, 'display', ctx.fit(from, 'display', 24, title.w, 16), P.card, { align: 'middle', width: title.w, kind: 'title' }));

  const missing = sky.at('missing');
  items.push(ctx.line(midX(missing), missing.y + 30, MISSING, 'display', 28, P.flame, { align: 'middle', width: missing.w, kind: 'title' }));
  if (copy?.line) items.push(...ctx.lines(midX(missing), missing.y + 62, copy.line, 'text', 12.5, P.card, { width: missing.w - 40, maxLines: 3, lead: 19, align: 'middle', kind: 'body' }).items);

  if (ctx.attribution) {
    // Lifted clear of the folio: `ctx.footer` prints the page number 22 pt from the foot, and a
    // plate that reached it would have the number printed across the code.
    const qr = sky.at('qr');
    const plate = Math.min(92, qr.w);
    const x = qr.x, y = qr.y - 22;
    items.push(rect(x, y, plate, plate, { r: 7, fill: P.card }));
    items.push(qrPath(SITE_QR, x + 7, y + 7, plate - 14, P.deep));
    items.push(ctx.line(x + plate / 2, y - 10, SCAN, 'text', 8.5, P.flame, { align: 'middle', width: plate + 40, kind: 'caption' }));
  }
  items.push(...folio(ctx, P.flame));
  return ctx.page(MISSING, items, P.deep);
}

/* ------------------------------------------------------------------ shared */

/** What every one of these pages tells the QA harness about itself, straight from the plan. */
const describe = (ctx, page) => ctx.describePage({
  archetype: page.archetype, variant: page.variant, people: page.people, density: page.density,
});

/** Archetype id -> draw(ctx, page, story). #256: cover, opening-hero, waiting, portrait-hero, closing. */
export const PAGES = Object.freeze({
  cover,
  'opening-hero': openingHero,
  waiting,
  'portrait-hero': portraitHero,
  closing,
});
