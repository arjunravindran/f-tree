/*
 * Family pages (#257): the banyan of ancestors, the two courtyards of grandparents, and the
 * gathering band that draws parents, siblings, spouses and children. Several people, composed by
 * relationship - a marigold string for a marriage, children below their parents, one ground line
 * for siblings - and never a label or a connecting line.
 *
 * ## What each one draws
 *
 *   banyan      the earliest names the record holds, in medallions on the banyan's own `root-*`
 *               zones, eldest highest, with "N generations before F" under the chapter title.
 *   courtyards  the paternal and maternal grandparents as two households on the `aangan` scene.
 *               The scene always draws both houses, so a family that knows only one side has the
 *               other house cut out of the paper and its household stood in the middle of the
 *               courtyard instead.
 *   gathering   everybody else the story's circles hold: parents with their brothers and sisters
 *               smaller beside them, siblings on one ground line, spouses and the family that
 *               joined with them, children and grandchildren.
 *
 * ## The one composition the gathering uses
 *
 * A row for each generation `kin.js` placed the page's people at, eldest first, so children always
 * stand below their parents; within a row, one cluster for each of the plan's own groups - a
 * household - on its own ground line, with a marigold string over a married pair. People a circle
 * out from the story (an aunt, an in-law) are drawn smaller, on a second line of the same
 * generation, which is how "their siblings appear smaller" is drawn rather than said.
 *
 * Nothing here re-decides who is on a page, how they group, or what number it carries: that is
 * `story/plan.js`'s work, and this only reads the `PagePlan` it is handed.
 *
 * Composer code: deterministic, static relative imports, no clock, no locale, no DOM, no random.
 */

import { PAGE } from '../../format.js';
import { chapterCopy, kinCaption, nameOf, rootsLine, stillToBeFoundCaption } from '../copy.js';
import {
  SAFE, TYPE, doorway, folio, groundLine, handmadePaper,
  noteCard, noteHeight, sanjhiBand, step, tailpiece, titleBlock,
} from './parts/paper.js';
import { FRAME, HANG, RIM, caption, captionHeight, marriage, married, nameLineCount, pageNote, portrait } from './parts/people.js';

/** A frame's slot is this much wider than its opening: the petal rim, plus air. */
const PITCH = 1.34;
/** The extra air a married pair takes, so the marigold string between them is not hidden. */
const WED = 0.42;
/** Between two households on one row, and between one row and the next. */
const CLUSTER_GAP = 20;
const ROW_GAP = 16;
/**
 * How much smaller somebody a circle out from the story is drawn. 0.55, not the 0.72 this used to
 * be: at 0.72 an aunt read as barely smaller than the parent she stood under (round 1 finding 18);
 * the approved frames make the featured pair dramatically larger than everyone beside them.
 */
const SECONDARY = 0.55;
/** The most air a page puts above a row when it has room to spare. */
const SLACK = 34;
/**
 * The room a hung toran needs above a row, on the variant that draws households as doorways: at
 * `size: 15` its mango leaves hang about 25 pt below the cord (`mango-leaf`'s own viewBox), so the
 * cord itself must clear the frame's own top by at least that much or the leaves droop into the
 * medallions (#257 round 2, finding 17). `sizeRows` reserves this as headroom, and `drawRow` hangs
 * the cord this far above the frame top, so the two always agree.
 */
const TORAN_ROOM = 30;

const PAGE_MID = PAGE.w / 2;

/* ------------------------------------------------------------------ shared pieces */

/** The chapter's words, or empty ones where this template names no copy for the chapter. */
const words = (ctx, page, kin) => chapterCopy(page.copyKey, { family: ctx.family, kin, tpl: ctx.tpl })
  ?? { title: null, line: null };

/** A scene's zones, and the same zones by name, as one placement puts them on the page. */
function sceneZones(ctx, id, placement) {
  const all = ctx.art.zones(id, placement);
  const by = {};
  for (const z of all) if (z.name !== undefined) by[z.name] = z;
  return { all, by };
}

/**
 * Tells the QA harness what the scene covers, so "no words over busy art" is checked against
 * something. A page that cut part of the scene away reports only what is left of each zone.
 */
function reportScene(ctx, zones, keep) {
  for (const z of zones) {
    if (z.kind === 'text') continue;
    const box = keep ? keep(z) : z;
    if (box) ctx.zone(z.kind, { x: box.x, y: box.y, w: box.w, h: box.h });
  }
}

/** The plan's own groups, cut down to the people a row draws, with the empty groups dropped. */
const clustersOf = (page, pick) => page.groups.map((g) => g.people.filter(pick)).filter((c) => c.length);

/**
 * One cluster's frame centres: a slot each, and wider air around a married pair so the marigold
 * string between the two frames is seen rather than hidden behind them.
 */
function slots(ctx, ids, x0, d) {
  const pitch = d * PITCH;
  const centres = [];
  let x = x0;
  for (let i = 0; i < ids.length; i++) {
    centres.push(x + pitch / 2);
    x += pitch;
    if (i + 1 < ids.length && married(ctx, ids[i], ids[i + 1])) x += d * WED;
  }
  return { centres, width: x - x0 };
}

/** How wide a row of clusters is at frame size `d`, in slots, married-pair air and the gaps. */
function rowWidth(ctx, clusters, d) {
  let w = (clusters.length - 1) * CLUSTER_GAP;
  for (const ids of clusters) w += slots(ctx, ids, 0, d).width;
  return w;
}

/** The largest frame size, no bigger than `max`, that a row of clusters fits the safe width at. */
function fitRow(ctx, clusters, max) {
  const unit = rowWidth(ctx, clusters, 1) - (clusters.length - 1) * CLUSTER_GAP;
  const room = SAFE.right - SAFE.left - (clusters.length - 1) * CLUSTER_GAP;
  return Math.min(max, room / unit);
}

/** How many lines the longest name in a row takes, which every caption on it reserves. */
const rowNameLines = (ctx, story, ids, width) => Math.max(1, ...ids.map((id) => nameLineCount(ctx, story, id, width)));

/** The tallest caption in a row, which sets how much room the row's words need. */
function rowCaptionHeight(ctx, story, clusters, d) {
  const width = d * PITCH - 6;
  const ids = clusters.flat();
  const lines = rowNameLines(ctx, story, ids, width);
  return Math.max(...ids.map((id) => captionHeight(ctx, story, id, width, lines)));
}

/**
 * Draws one row of households: the ground they stand on (or the step, or the toran over the
 * doorway), the frames, the marigold strings, and the words under each frame.
 */
function drawRow(ctx, story, page, { clusters, d, cy }, variant, tag) {
  const pitch = d * PITCH;
  const total = rowWidth(ctx, clusters, d);
  const nameLines = rowNameLines(ctx, story, clusters.flat(), pitch - 6);
  const behind = [];
  const front = [];
  const captions = [];
  let x = PAGE_MID - total / 2;
  clusters.forEach((ids, i) => {
    const { centres, width } = slots(ctx, ids, x, d);
    // The ground line passes under the frames, not through them: the drawing hangs to `d * HANG`
    // (the mala included), so the line sits a few points past that rather than at the frame's own
    // equator (`d * 0.5`), which used to cut every rim and mala in half (#257 round 2, finding 13).
    const span = { x1: centres[0] - d / 2, x2: centres[centres.length - 1] + d / 2, y: cy + d * HANG + 4 };
    // The ground line means "siblings share one ground line" - under a solo frame it is a
    // meaningless shelf, so a cluster of one takes the step instead, whatever the row's own
    // variant is (#257 round 2, finding 16).
    if (variant === 'steps' || ids.length === 1) behind.push(...step(ctx, span));
    else behind.push(...groundLine(ctx, page, span, `${tag}-${i}`));
    if (variant === 'doorways') behind.push(...doorway(ctx, page, { x1: span.x1, x2: span.x2, y: cy - d * RIM - TORAN_ROOM }, `${tag}-${i}`));
    for (let j = 0; j + 1 < ids.length; j++) {
      if (married(ctx, ids[j], ids[j + 1])) behind.push(...marriage(ctx, ids[j], ids[j + 1], { x1: centres[j], x2: centres[j + 1], cy, d }));
    }
    ids.forEach((id, j) => {
      front.push(...portrait(ctx, story, id, centres[j], cy, d));
      captions.push(...caption(ctx, story, id, { cx: centres[j], top: cy + d * HANG + 6, width: pitch - 6, nameLines }).items);
    });
    x += width + CLUSTER_GAP;
  });
  return [...behind, ...front, ...captions];
}

/* ------------------------------------------------------------------ the banyan of ancestors */

/**
 * The `banyan` scene's six medallion zones, as two columns - the left of the tree and the right -
 * each eldest first, since the scene draws the eldest highest. Read off the zones' own boxes
 * rather than their names, so a mirrored placement still gives the left-hand column first.
 */
function rootColumns(zones) {
  const roots = zones.filter((z) => z.kind === 'face' && z.name !== undefined && z.name.startsWith('root-'));
  const by = (a, b) => a.y - b.y || a.x - b.x;
  return [
    roots.filter((z) => z.x + z.w / 2 < PAGE_MID).sort(by),
    roots.filter((z) => z.x + z.w / 2 >= PAGE_MID).sort(by),
  ];
}

/**
 * The page's ancestors down the two sides of the tree: the father's line on the left, the mother's
 * on the right. A family that knows only one line still fills both sides, eldest first, rather
 * than hanging every medallion off one branch.
 */
function rootSides(story, people) {
  const left = people.filter((id) => story.kin.people.get(id)?.side === 'paternal');
  const right = people.filter((id) => story.kin.people.get(id)?.side !== 'paternal');
  if (left.length && right.length) return [left, right];
  const one = left.length ? left : right;
  return [one.slice(0, Math.ceil(one.length / 2)), one.slice(Math.ceil(one.length / 2))];
}

/** `ids` spread over `n` zones, the fuller zones last, so the eldest hang alone and highest. */
function spread(ids, n) {
  const out = [];
  let at = 0;
  for (let i = 0; i < n; i++) {
    const size = Math.floor((ids.length - at) / (n - i));
    out.push(ids.slice(at, at + size));
    at += size;
  }
  return out;
}

/**
 * The roots chapter: the earliest names the record holds, in medallions hung on the banyan, and
 * named on the ground below it. The tree is marked busy from edge to edge, so the page's words sit
 * in the scene's own two text zones - the title above the canopy and the names below the roots -
 * in the same two columns the medallions hang in.
 */
function banyan(ctx, page, story) {
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const placement = { x: 0, y: 0, w: PAGE.w, ...(page.variant === 'canopy' ? { flip: 'x' } : {}) };
  const { all, by } = sceneZones(ctx, 'banyan', placement);
  const items = [ctx.art.place('banyan', placement)];
  reportScene(ctx, all);

  const copy = words(ctx, page, story.kin);
  items.push(...titleBlock(ctx, page, { title: copy.title, line: rootsLine(ctx.family, story.kin) }, by.title).items);

  const columns = rootColumns(all);
  const sides = rootSides(story, page.people);
  columns.forEach((zones, c) => {
    spread(sides[c], zones.length).forEach((ids, i) => {
      if (!ids.length) return;
      const z = zones[i];
      const d = Math.min(z.w, (z.w * 1.18) / ids.length);
      const pitch = d * 1.06;
      const x0 = z.x + z.w / 2 - (ids.length * pitch) / 2;
      ids.forEach((id, j) => items.push(...portrait(ctx, story, id, x0 + pitch * (j + 0.5), z.y + z.h / 2, d)));
    });
  });

  // The names are listed by side, not by where a medallion ended up, so nobody on the page can be
  // left unnamed by a zone that did not take them.
  items.push(...rootNames(ctx, story, copy.line, by.story, sides));
  items.push(...folio(ctx));
  return ctx.page(page.chapter, items, ctx.P.paper);
}

/**
 * A list of names, in two columns, each with its kin word beside it in the hand face. It is what a
 * page uses where a caption cannot sit under its own frame: under the banyan, whose every inch is
 * busy art, and under a household too crowded to give each frame its own words.
 */
function nameList(ctx, story, box, columns, top = box.y) {
  const items = [];
  const colW = (box.w - 30) / 2;
  const rows = Math.max(1, ...columns.map((ids) => ids.length));
  // A long column closes the leading up rather than running off the foot of the page.
  const lead = Math.max(11.5, Math.min(TYPE.name * 1.36, (box.y + box.h - top - 4) / rows));
  columns.forEach((ids, c) => {
    ids.forEach((id, i) => items.push(...nameRun(ctx, story, id, { x: box.x + c * (colW + 30), y: top + lead * (i + 1), width: colW })));
  });
  return items;
}

/** The two halves of `ids`, for a two-column list that fills the left column first. */
const halves = (ids) => [ids.slice(0, Math.ceil(ids.length / 2)), ids.slice(Math.ceil(ids.length / 2))];

/** The names under the tree, under the chapter's own line, in the columns the medallions hang in. */
function rootNames(ctx, story, line, box, columns) {
  const items = [];
  let top = box.y;
  if (line) {
    top += TYPE.name * 1.1;
    items.push(ctx.line(box.x + box.w / 2, top, line, 'text', 11, ctx.P.inkSoft, { align: 'middle', width: box.w, kind: 'body' }));
    top += 8;
  }
  return [...items, ...nameList(ctx, story, box, columns, top)];
}

/** One line of the names list: the name, and the kin word beside it in the hand face. */
function nameRun(ctx, story, id, { x, y, width }) {
  const { kin } = story;
  const p = ctx.family.byId.get(id);
  const name = nameOf(ctx.family, kin, id) ?? stillToBeFoundCaption(ctx.family, kin, id);
  ctx.show(id);
  if (!name) return [];
  const word = p.name ? kinCaption(kin, ctx.family, id) : null;
  const role = p.name ? 'strong' : 'hand';
  const kinW = word ? ctx.measure(word, 'hand', TYPE.kin) + 8 : 0;
  const size = ctx.fit(name, role, TYPE.name, width - kinW, TYPE.nameMin);
  const nameW = Math.min(ctx.measure(name, role, size), width - kinW);
  const items = [ctx.line(x, y, name, role, size, p.name ? ctx.P.ink : ctx.P.brass, { width: nameW, kind: 'name' })];
  if (word) items.push(ctx.line(x + nameW + 8, y, word, 'hand', TYPE.kin, ctx.P.clay, { width: kinW, kind: 'caption' }));
  return items;
}

/* ------------------------------------------------------------------ the two courtyards */

/**
 * The courtyards chapter: the grandparents as two households, the father's house on one side and
 * the mother's on the other, under torans, with the lamps in the wall lit for anybody in that
 * household whose name was never written down.
 *
 * The scene always draws both houses, whole. A family that knows only one side draws nobody under
 * the other - the design critic's own choice between "mirror the surviving house to the page
 * centre" and "keep both houses and mark the unknown side as the empty one" (#257 round 2,
 * finding 4): cutting a hole in the scene left a stray rectangle of the wrong shade where the
 * house's own sky, floor and windows had been (the scene is one drawing, not layers this page can
 * pick apart), a stray edge where the cut crossed the floor's plank lines, and the ladi string
 * light that spans both roofs stopped dead over nothing. An empty house - its toran unhung, no
 * lamp in its wall, nobody standing at its door - reads as "not yet known" on its own, the way an
 * empty chair does at a table, without any of that.
 */
function courtyards(ctx, page, story) {
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const mirrored = page.variant === 'mirrored';
  const placement = { x: 0, y: 0, w: PAGE.w, ...(mirrored ? { flip: 'x' } : {}) };
  const { all, by } = sceneZones(ctx, 'aangan', placement);

  // The `aangan` scene only cuts niches into the right-hand house's wall, but an aala is a
  // complete drawing (its own arch and lamp - "still to be found" hangs it on a plain night wall
  // with no cutout under it at all), so the father's house gets the same two niches mirrored
  // across the page's own centre rather than leaving an unnamed paternal grandparent with only
  // the dashed lamp - the notation should not depend on which side of the family a person is on
  // (#257 round 2, finding 6).
  const mirrorZone = (z) => z && { ...z, x: PAGE.w - z.x - z.w };
  const rightNiches = [by['aala-right-1'], by['aala-right-2']].filter(Boolean);

  // The father's side takes the house `household-left` stands under, whichever side of the page a
  // mirrored placement puts it on; everybody else takes the other house.
  const houses = [
    { ids: page.people.filter((id) => story.kin.people.get(id)?.side === 'paternal'), frames: by['household-left'], text: by.left, toran: by['toran-left'], niches: rightNiches.map(mirrorZone) },
    { ids: page.people.filter((id) => story.kin.people.get(id)?.side !== 'paternal'), frames: by['household-right'], text: by.right, toran: by['toran-right'], niches: rightNiches },
  ];
  const shown = houses.filter((h) => h.ids.length);

  const items = [ctx.art.place('aangan', placement)];
  reportScene(ctx, all);

  const copy = words(ctx, page, story.kin);
  items.push(...titleBlock(ctx, page, copy, by.title).items);

  // Both households' words start on the same line, whichever text zone each one sits in, so two
  // courtyards drawn side by side read as one row rather than two that slipped.
  const wordsTop = Math.max(by.left.y, by.right.y);
  for (const house of shown) {
    items.push(...household(ctx, story, page, house, { frames: house.frames, text: { ...house.text, y: wordsTop }, toran: house.toran }));
  }

  const note = pageNote(ctx, page);
  const noteMid = by.note.x + by.note.w / 2;
  if (note) items.push(...noteCard(ctx, page, note, { cx: noteMid, top: by.note.y + 6, w: Math.min(by.note.w, 340) }).items);
  else items.push(...tailpiece(ctx, noteMid, by.note.y + 20));
  items.push(...folio(ctx));
  return ctx.page(page.chapter, items, ctx.P.paper);
}


/** One household: its toran, its frames under its own house, and its words below them. */
function household(ctx, story, page, house, { frames, text, toran }) {
  // A name nobody wrote down is a lamp kept in the wall of that person's own house: the aala
  // niche, which is the notation wherever a scene leaves room for a wall. Only the right-hand
  // house has niches, so everybody else takes the dashed brass lamp in a frame instead.
  const inWall = house.ids.filter((id) => !ctx.family.byId.get(id).name).slice(0, house.niches.length);
  const onFloor = house.ids.filter((id) => !inWall.includes(id));

  // A house front is only so wide. Past what fits on its doorstep at a legible size the household
  // stands in several rows and is named in a list below, rather than crushing a name into a
  // slot too narrow to read it in.
  const perRow = Math.max(1, Math.floor(frames.w / (FRAME.min * PITCH)));
  const rowCount = Math.max(1, Math.ceil(onFloor.length / perRow));
  const listed = rowCount > 1;
  const unit = listed ? perRow * PITCH : slots(ctx, onFloor, 0, 1).width || PITCH;
  const d = Math.max(FRAME.min, Math.min(FRAME.max, frames.h / rowCount / (RIM + HANG + 0.2), (frames.w - 8) / unit));
  const pitch = d * PITCH;
  const items = [];
  if (toran) items.push(...doorway(ctx, page, { x1: toran.x, x2: toran.x + toran.w, y: toran.y + toran.h / 2 }, `t${Math.round(toran.x)}`));

  inWall.forEach((id, i) => {
    const z = house.niches[i];
    ctx.show(id);
    items.push(ctx.art.place('aala', { x: z.x + z.w / 2, y: z.y + z.h, h: z.h * 0.96 }));
  });

  const rowTop = frames.y + d * RIM + 6;
  const rowPitch = frames.h / rowCount;
  for (let r = 0; r < rowCount; r++) {
    const ids = onFloor.slice(r * perRow, (r + 1) * perRow);
    if (!ids.length) continue;
    const { centres, width } = slots(ctx, ids, 0, d);
    const x0 = frames.x + frames.w / 2 - width / 2;
    const cy = rowTop + r * rowPitch;
    for (let j = 0; j + 1 < ids.length; j++) {
      if (married(ctx, ids[j], ids[j + 1])) items.push(...marriage(ctx, ids[j], ids[j + 1], { x1: x0 + centres[j], x2: x0 + centres[j + 1], cy, d }));
    }
    ids.forEach((id, j) => items.push(...portrait(ctx, story, id, x0 + centres[j], cy, d)));
    if (listed) continue;
    const capW = Math.min(pitch - 6, text.w / ids.length - 4);
    const nameLines = rowNameLines(ctx, story, ids, capW);
    ids.forEach((id, j) => items.push(...caption(ctx, story, id, { cx: x0 + centres[j], top: text.y - 2, width: capW, nameLines }).items));
  }
  if (listed) items.push(...nameList(ctx, story, text, halves(onFloor)));

  // Anybody whose lamp is in the wall is named by their relation, under the house they belong to.
  let bottom = text.y + (listed ? Math.ceil(onFloor.length / 2) * TYPE.name * 1.36 + 6 : captionHeight(ctx, story, onFloor[0] ?? inWall[0], text.w - 8, 1) + 8);
  inWall.forEach((id) => {
    const block = caption(ctx, story, id, { cx: text.x + text.w / 2, top: bottom, width: text.w - 8 });
    items.push(...block.items);
    bottom = block.bottom;
  });
  return items;
}

/* ------------------------------------------------------------------ the gathering */

/** Somebody a circle out from the story - an aunt, an in-law, a cousin - is drawn smaller. */
const OUTER_CIRCLES = new Set(['branches', 'in-laws', 'lane']);
const isOuter = (story, id) => OUTER_CIRCLES.has(story.kin.people.get(id)?.circle);

/**
 * The rows a gathering falls into: one for each generation `kin.js` placed these people at, eldest
 * first, and within a generation the story's own people before the circle beyond them. Children
 * therefore always stand below their parents, and an aunt below and smaller than her brother.
 */
function rowsOf(story, page) {
  const gen = (id) => story.kin.people.get(id)?.gen ?? 0;
  const gens = [...new Set(page.people.map(gen))].sort((a, b) => a - b);
  const rows = [];
  for (const g of gens) {
    for (const outer of [false, true]) {
      const clusters = clustersOf(page, (id) => gen(id) === g && isOuter(story, id) === outer);
      if (clusters.length) rows.push({ clusters, outer });
    }
  }
  return rows;
}

/**
 * How big every frame is, and where each row sits: one size for the story's own people and a
 * smaller one for the circle beyond them, both cut down together until the rows fit the page.
 */
function sizeRows(ctx, story, rows, top, bottom, headroom) {
  const core = rows.filter((r) => !r.outer);
  const base = Math.min(FRAME.max, ...(core.length ? core : rows).map((r) => fitRow(ctx, r.clusters, FRAME.max)));
  const sizeOf = (row, d) => Math.min(row.outer ? d * SECONDARY : d, fitRow(ctx, row.clusters, FRAME.max));
  const height = (d) => rows.reduce((sum, row) => {
    const rd = sizeOf(row, d);
    return sum + headroom + rd * (RIM + HANG) + 6 + rowCaptionHeight(ctx, story, row.clusters, rd) + ROW_GAP;
  }, 0);

  const room = bottom - top;
  let d = base;
  if (height(d) > room) d = Math.max(FRAME.min, d * (room / height(d)));
  // Whatever room is left over is shared out between the rows, up to a point: a page of one row
  // should breathe, not float in the middle of an empty sheet.
  const slack = Math.min(SLACK, Math.max(0, room - height(d)) / (rows.length + 1));
  const out = [];
  let y = top + slack;
  for (const row of rows) {
    const rd = sizeOf(row, d);
    out.push({ clusters: row.clusters, d: rd, cy: y + headroom + rd * RIM });
    y += headroom + rd * (RIM + HANG) + 6 + rowCaptionHeight(ctx, story, row.clusters, rd) + ROW_GAP + slack;
  }
  return out;
}

/**
 * What closes a page whose chapter ran out before the paper did: a diya and a short mala under
 * the last row, or the lotus divider on a stepped page. A page whose rows reach the foot gets
 * neither, rather than a tailpiece printed over its own last caption.
 */
function closer(ctx, story, page, rows) {
  const last = rows[rows.length - 1];
  const bottom = last ? last.cy + last.d * HANG + 6 + rowCaptionHeight(ctx, story, last.clusters, last.d) : SAFE.top;
  if (page.variant === 'steps') {
    return bottom + 26 <= SAFE.bottom ? [ctx.art.place('divider-lotus', { x: PAGE_MID, y: SAFE.bottom - 14, w: 150 })] : [];
  }
  return bottom + 52 <= SAFE.bottom ? tailpiece(ctx, PAGE_MID, bottom + 18) : [];
}

/**
 * A page of the family - parents, siblings, spouses, children, or a small chapter's household -
 * composed as rows of generations on cream paper.
 */
function gathering(ctx, page, story) {
  ctx.describePage({ archetype: page.archetype, variant: page.variant, people: page.people, density: page.density });
  const items = handmadePaper(ctx, page);
  if (page.variant === 'band') items.push(...sanjhiBand(ctx, page));

  const copy = words(ctx, page, story.kin);
  const block = titleBlock(ctx, page, copy, { x: SAFE.left, y: page.variant === 'band' ? 62 : SAFE.top, w: SAFE.right - SAFE.left });
  items.push(...block.items);

  const note = pageNote(ctx, page);
  const noteH = noteHeight(ctx, note, 330);
  const rows = sizeRows(ctx, story, rowsOf(story, page), block.bottom + 8, SAFE.bottom - noteH, page.variant === 'doorways' ? TORAN_ROOM : 0);
  rows.forEach((row) => items.push(...drawRow(ctx, story, page, row, page.variant, `r${row.clusters.flat()[0]}`)));

  if (note) items.push(...noteCard(ctx, page, note, { cx: PAGE_MID, top: SAFE.bottom - noteH + 24, w: 330 }).items);
  else items.push(...closer(ctx, story, page, rows));
  items.push(...folio(ctx));
  return ctx.page(page.chapter, items, ctx.P.paper);
}

/** Archetype id -> draw(ctx, page, story). #257: banyan, courtyards, gathering. */
export const PAGES = Object.freeze({ banyan, courtyards, gathering });
