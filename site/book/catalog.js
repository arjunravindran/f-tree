/*
 * The catalogue: which templates this release offers, in what order, and when each is in season.
 *
 * A template is added by adding its file and one entry here - never by touching a screen. Both
 * shells read this file, list what `listing` returns, and open the book on `openingTemplate`, so a
 * festival template is featured on Android and the desktop on the same days.
 *
 * Seasons move. Diwali falls on a different date every year, so an entry carries one window per
 * year, `"2026": ["2026-10-18", "2026-11-15"]`, both days included. The key only names the season;
 * the window is what counts, so a New Year template's "2026" window may end in January 2027. A
 * year with no window simply is not featured - the template is still listed, all year round.
 *
 * Like the composer, nothing here reads a clock: `today` is passed in as the reader's local date,
 * so the Kotlin port (`book/BookCatalog.kt`) can be held to the same table, `catalog-cases.json`.
 *
 * Read like a template (template.js): as though a stranger wrote it, because one day entries may
 * arrive by download (#214). An entry this app cannot read is dropped on its own; an entry for a
 * template format newer than this app draws is kept out of the list, so an older install never
 * offers a book it would draw half-right.
 */

import { MAX_TEMPLATE_FORMAT } from './template.js';

export const CATALOG_FORMAT = 1;

const ID = /^[a-z][a-z0-9-]{1,31}$/;
const DAY = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

/**
 * The catalogue, or `null` when the file as a whole cannot be read. Never throws. Each template's
 * own file is `templates/<id>.json`; the id is all an entry says about where it lives.
 */
export function readCatalog(json) {
  let c;
  try {
    c = typeof json === 'string' ? JSON.parse(json) : json;
  } catch {
    return null;
  }
  if (!c || typeof c !== 'object' || Array.isArray(c) || c.format !== CATALOG_FORMAT || !Array.isArray(c.templates)) return null;
  const seen = new Set();
  const templates = [];
  for (const raw of c.templates) {
    const e = entry(raw);
    if (e && !seen.has(e.id)) {
      seen.add(e.id);
      templates.push(e);
    }
  }
  return { format: CATALOG_FORMAT, templates };
}

function entry(t) {
  if (!t || typeof t !== 'object' || Array.isArray(t)) return null;
  if (typeof t.id !== 'string' || !ID.test(t.id)) return null;
  if (typeof t.name !== 'string' || !t.name.trim() || t.name.length > 40) return null;
  if (!Number.isInteger(t.format) || t.format < 1) return null;
  // The tier is what the policy switch matches on (policy.js); an unknown one is the policy's to judge.
  if (typeof t.tier !== 'string' || !ID.test(t.tier)) return null;
  const featured = [];
  if (t.featured !== undefined) {
    if (!t.featured || typeof t.featured !== 'object' || Array.isArray(t.featured)) return null;
    for (const w of Object.values(t.featured)) {
      if (!Array.isArray(w) || w.length !== 2 || !w.every((d) => typeof d === 'string' && DAY.test(d)) || w[0] > w[1]) return null;
      featured.push([w[0], w[1]]);
    }
  }
  return { id: t.id, name: t.name, format: t.format, tier: t.tier, featured };
}

/** The entries this app can draw. */
export function available(catalog, supported = MAX_TEMPLATE_FORMAT) {
  return catalog ? catalog.templates.filter((t) => t.format <= supported) : [];
}

/** The ids in season on `today` (YYYY-MM-DD), in catalogue order. */
export function featuredAt(catalog, today, supported = MAX_TEMPLATE_FORMAT) {
  return available(catalog, supported)
    .filter((t) => t.featured.some(([from, to]) => from <= today && today <= to))
    .map((t) => t.id);
}

/** The picker's list: what is in season first, then everything else in catalogue order. */
export function listing(catalog, today, supported = MAX_TEMPLATE_FORMAT) {
  const season = new Set(featuredAt(catalog, today, supported));
  const all = available(catalog, supported).map((t) => ({ id: t.id, name: t.name, tier: t.tier, featured: season.has(t.id) }));
  return [...all.filter((t) => t.featured), ...all.filter((t) => !t.featured)];
}

/** What the book opens on: the template in season, or the first one listed. */
export function openingTemplate(catalog, today, supported = MAX_TEMPLATE_FORMAT) {
  return listing(catalog, today, supported)[0]?.id ?? null;
}
