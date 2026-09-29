/*
 * The compact life-dates form a story page prints beside a name: "1935 – 1999" for the departed,
 * "b. 1970" for the living, and nothing at all where no year is recorded.
 *
 * `family.js`'s own `lifeYears` is format 1's (Heirloom's), and its output must stay
 * byte-identical - a bare year for the living, an unspaced en dash for a span
 * ("1905–1978"), which reads as an unfinished lifespan beside a full, spaced one. This is the
 * format-2-only short form the design critic asked for (#257 round 2, findings 10/12/26), approved
 * against the frames: a bare year beside a lifespan reads as unfinished, so the living get "b."
 * instead. #258 (`pages/parts/furniture.js`) needs the identical strings for its own register
 * rows and lifted its own copy into this module; the two must never disagree.
 *
 * Composer code: deterministic, no clock, no locale, no DOM, no Math.random.
 */

/**
 * When somebody lived, in the compact form a name line or a list column holds: "1935 – 1999" for
 * the departed, "b. 1970" for the living, and nothing at all where no year is recorded.
 *
 * Years only, even when the reader asked for full dates (`options.livingDates`): a book that is
 * forwarded past the people who chose it should not carry a living person's full birth date. An
 * age is never printed anywhere.
 */
export function lifeDates(p) {
  if (!p) return '';
  if (!p.deceased) return p.by ? `b. ${p.by}` : '';
  if (p.by && p.dy) return `${p.by} – ${p.dy}`;
  if (p.by) return `b. ${p.by}`;
  if (p.dy) return `d. ${p.dy}`;
  return 'Late';
}
