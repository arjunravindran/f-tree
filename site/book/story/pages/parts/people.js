/*
 * Drawing one person large (#256): the hero at an arch window, the hero in a medallion, and the
 * name, dates and kin word under either.
 *
 * Only the hero pages use this - one or two people, given the room to be looked at - so the rules
 * it keeps are the ones the design system sets for that size:
 *
 *   - **The art invents nobody.** With a photograph, the photograph. Without one, at hero size the
 *     figure is seen from behind (`hero-*`), never a faceless front bust, which at 200 pt reads as
 *     a UI placeholder; in a medallion, the front bust the record chooses (`story/avatars.js`).
 *   - **Name not known** is a lit lamp behind the dashed brass perimeter, and the person is named
 *     by relation in the hand face ("Shyam Lal's wife") - `copy.js`'s `nameOf`, never "Unknown".
 *   - **The departed** get the mala on the frame, and only they: `art.place('mala-departed', ...)`
 *     refuses without `{ departed: true }`, and the record is what says so.
 *   - **A marriage is a marigold string between two frames**, and a couple where one has died is
 *     joined by a diya instead of a string - never a mauli thread.
 *
 * Composer code: deterministic, no clock, no locale, no DOM, static relative imports only.
 */

import { image } from '../../../format.js';
import { lifeLine, lifeYears } from '../../../family.js';
import { LIBRARY } from '../../../art/index.js';
import { avatarFor, heroFor } from '../../avatars.js';
import { nameOf, kinCaption } from '../../copy.js';

/**
 * About how many pixels a photograph printed `pt` points across is worth: roughly 170 to the inch,
 * never under 96 nor over 200. The same rule - and the same numbers - as `ctx.portrait`
 * (compose.js), because a photograph costs a book the same whichever frame it is mounted in.
 * Story pages mount their own (a carved ring, an arch), so they size it the same way rather than
 * inventing a second budget. docs/storybook-plan.md would let a hero ask for up to 512; raising
 * the cap is compose.js's to do, and until it does a hero is held to the book's own 200.
 */
const photoPixels = (pt) => Math.min(200, Math.max(96, Math.ceil((pt / 72) * 170)));

/**
 * The box a frame's opening lands in, when the whole drawing is laid in `outer`. Read off the
 * compiled art rather than copied into a constant here, so a redrawn frame moves its own opening.
 * `outer` must carry the drawing's own aspect (`frameOuter`), which is how `art.frame`'s
 * proportional fit then puts the drawing back exactly in `outer`.
 */
export function openingIn(id, outer) {
  const a = LIBRARY.symbols[id];
  if (!a?.opening) throw new Error(`art: "${id}" has no opening, so it cannot be laid out as a frame`);
  const [vx, vy, vw, vh] = a.vb;
  const [ox, oy, ow, oh] = a.opening;
  const kx = outer.w / vw, ky = outer.h / vh;
  return { x: outer.x + kx * (ox - vx), y: outer.y + ky * (oy - vy), w: kx * ow, h: ky * oh };
}

/** A box of `w` points wide with the drawing's own proportions, its top-left at (x, y). */
export function frameOuter(id, x, y, w) {
  const [, , vw, vh] = LIBRARY.symbols[id].vb;
  return { x, y, w, h: (w * vh) / vw };
}

/**
 * One person inside a frame, at hero size.
 *
 *   `id`     the frame drawing: 'arch-jharokha' for a window, 'medallion*' for a round one
 *   `outer`  where the whole frame goes (`frameOuter`)
 *   `view`   items drawn behind the figure, clipped to the opening (a sky, a wall)
 *
 * Returns the frame's items. The face zone is recorded so no caption is ever laid over a face.
 */
export function framedPerson(ctx, person, { id, outer, view = [], hero = false, gen = null, featuredBy = null }) {
  const art = ctx.art;
  const opening = openingIn(id, outer);
  const inner = [...view];
  const stage = { year: ctx.now.year, gen, featuredBy };
  if (person) ctx.show(person.id);

  if (person?.photo && ctx.options.photos) {
    const px = photoPixels(Math.max(opening.w, opening.h));
    ctx.photos.set(person.id, Math.max(ctx.photos.get(person.id) ?? 0, px));
    inner.push(image(person.id, opening.x, opening.y, opening.w, opening.h, 'rect'));
  } else if (person?.name) {
    // No photograph: the figure the record chooses. At hero size that is the back view at a
    // window; inside a round frame it is the faceless bust, which fills the frame's own units.
    const drawing = hero ? heroFor(person, stage) : avatarFor(person, stage);
    inner.push(hero
      ? art.place(drawing, { x: opening.x + opening.w / 2, y: opening.y + opening.h, anchor: 'bottom-center', h: opening.h * 0.74 })
      : art.place(drawing, { x: opening.x, y: opening.y, anchor: 'top-left', w: opening.w }));
  } else {
    // Name not known: the lamp kept behind the dashed brass perimeter, still lit.
    const d = Math.min(opening.w, opening.h) * 0.82;
    inner.push(art.place('lamp-unknown', { x: opening.x + opening.w / 2, y: opening.y + opening.h / 2, w: d }));
  }

  const items = [art.frame(id, opening, inner, { shadow: { dx: 1.7, dy: 2.3 } })];
  if (person?.deceased) items.push(mala(art, { outer, opening, round: !hero }));
  ctx.zone('face', { x: opening.x, y: opening.y, w: opening.w, h: opening.h });
  return items;
}

/*
 * The mala hangs on the frame of somebody the record says has died, and nowhere else.
 *
 * `mala-departed` is drawn in the round frames' units, anchored on the frame's centre, and hangs
 * about seven tenths of the opening below it (site/book/art/README.md). A round frame takes it at
 * its centre, as the README says. An arch is not round and its centre is the figure's own head, so
 * the arch hangs it from the sill instead: the same drawing, at the opening's width, with the
 * garland's foot just past the frame's - never over the person, and never past the frame.
 */
const MALA_SPAN = 1.2;   // the drawing's viewBox is 120 units across where its frame is 100
const MALA_DROP = 0.7;   // and its foot is 70 units below the anchor

function mala(art, { outer, opening, round }) {
  const w = opening.w * MALA_SPAN;
  const y = round ? outer.y + outer.h / 2 : outer.y + outer.h + 2 - MALA_DROP * opening.w;
  return art.place('mala-departed', { x: outer.x + outer.w / 2, y, w, departed: true });
}

/**
 * The words under a portrait: the name (or the relation the person is named through), what the
 * record may say about when they lived, the kin word in the hand face, in that order.
 *
 * No living person is ever given an age (`lifeLine` keeps the living to a year), and a departed
 * person's dates are marked `lifespan`, the one kind of line the age check exempts.
 */
export function nameStack(ctx, story, person, { cx, y, width, featuredName, nameSize = 14, caption = true }) {
  const { kin } = story;
  const items = [];
  let at = y;
  const name = nameOf(ctx.family, kin, person?.id);
  if (name) {
    const size = ctx.fit(name, 'strong', nameSize, width, 9);
    items.push(ctx.line(cx, at, name, 'strong', size, ctx.P.ink, { align: 'middle', width, kind: 'name' }));
    at += size * 0.42 + 12;
  }
  const dates = person ? lifeLine(person, ctx.options) : '';
  if (dates) {
    items.push(ctx.line(cx, at, dates, 'text', 10.5, ctx.P.inkSoft, { align: 'middle', width, kind: person.deceased ? 'lifespan' : 'caption' }));
    at += 17;
  }
  const word = caption && person ? kinCaption(kin, ctx.family, person.id, featuredName) : null;
  if (word) {
    const size = ctx.fit(word, 'hand', 13, width, 8);
    items.push(ctx.line(cx, at, word, 'hand', size, ctx.P.clay, { align: 'middle', width, kind: 'caption' }));
    at += size + 4;
  }
  return { items, bottom: at };
}

/**
 * What joins two frames side by side: a marigold string for a marriage, and a diya between them
 * where one of the two has died - never a mauli thread (book-design-system.md, "Cultural care").
 * Nothing at all for two people the record does not marry to each other.
 */
export function joinFrames(ctx, a, b, { cx, y, width }) {
  if (!a || !b || !ctx.family.spousesOf(a.id).some((s) => s.id === b.id)) return [];
  if (a.deceased || b.deceased) return [ctx.art.place('diya', { x: cx, y: y + 10, w: 26 })];
  return [ctx.art.place('mala', { x: cx, y, w: width })];
}

/** The short "Ankit · 1990" line the opening prints over its title, in the book's own hand. */
export const yearsCaption = (ctx, story, person) => {
  const name = nameOf(ctx.family, story.kin, person?.id);
  if (!name) return null;
  const years = person ? lifeYears(person) : '';
  return years ? `${name} · ${years}` : name;
};
