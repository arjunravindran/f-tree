/*
 * The family, read the way the book needs it.
 *
 * Everything here is derived from the .ftree document and the options, and from nothing else: no
 * clock (today arrives as `now`), no locale (names sort by a fold of their own characters, not by
 * whatever collation the engine's ICU happens to carry), no randomness. The same tree therefore
 * gives the same book in node, in Electron and in an Android WebView, which is what the golden
 * tests hold it to.
 */

import { buildGraph, branchFrom, restrictedGraph, displayDate } from '../playground/model.js';
import { layoutArchive } from '../playground/layout.js';
import { sortKey, byKey } from '../playground/sort-key.js';

const year = (value) => {
  const m = /^(\d{4})/.exec(value ?? '');
  return m ? Number(m[1]) : null;
};

const DEVANAGARI = /[\u0900-\u097F]/;

export { sortKey, byKey } from '../playground/sort-key.js';

/**
 * What a page may say about when somebody lived.
 *
 * The departed are history and keep every date the record holds. The living keep only the year of
 * their birth unless the reader chose otherwise: a book like this is forwarded to people nobody
 * chose, and a full birth date is half of somebody's identity to a stranger.
 */
export function lifeLine(p, { livingDates = false } = {}) {
  if (p.deceased) {
    const b = displayDate(p.birthDate), d = displayDate(p.deathDate);
    // A range only between two dates that both have a year: "17 April – 3 May 1978" would read as
    // one spring when the birthday's year is simply not known (#90).
    if (b && d && year(p.birthDate) && year(p.deathDate)) return `${b} – ${d}`;
    if (b && d) return `Born ${b} · Died ${d}`;
    if (b) return `Born ${b}`;
    if (d) return `Died ${d}`;
    return 'Late';
  }
  // Nothing rather than "Born null" when the record holds no year: the living keep only a year,
  // and a birthday without one is the more private half, not a substitute for it.
  const born = livingDates ? displayDate(p.birthDate) : year(p.birthDate);
  return born ? `Born ${born}` : '';
}

/** The short form under a name on the tree page: years only, whatever the setting. */
export function lifeYears(p) {
  const b = year(p.birthDate), d = year(p.deathDate);
  if (p.deceased) {
    if (b && d) return `${b}–${d}`;
    if (b) return `${b}–`;
    if (d) return `–${d}`;
    return 'Late';
  }
  return b ? String(b) : '';
}

const firstName = (p) => (p?.name ? p.name.split(/\s+/)[0] : null);

/**
 * A note is the family's own words, not the composer's, and it is opt-in (`options.notes`) - a
 * Diwali book is forwarded to people nobody chose, and a note is the one place in the record that
 * was written for someone in particular. So it is clamped, not just displayed: at most three lines,
 * the depth a torn handwritten card can hold, and free of control characters, since a note is
 * whatever an old import or a pasted document happened to carry and this is the one field of a
 * person's record a painter turns into a raw string of text.
 *
 * `isControlCode` is spelled out with plain decimal numbers rather than a regex character class of
 * hex escapes on purpose: a range of escapes naming exactly the control bytes it excludes is
 * source text a well-meaning editor can "helpfully" decode into the real bytes it names, which is a
 * corrupted file waiting to happen. \n and \r are kept, since a caller still splits lines on them.
 * \t is also kept out of `isControlCode` and handled separately, one line down: a tab pasted from a
 * spreadsheet or a word processor is a word separator, so deleting it outright would glue the words
 * on either side of it together (`"two\twords"` -> `"twowords"`), which reads as more broken than
 * the tab ever did. It becomes a single space instead.
 *
 * The bidi embedding, override and isolate controls (8234-8238, 8294-8297: LRE/RLE/PDF/LRO/RLO and
 * LRI/RLI/FSI/PDI) are stripped alongside the ASCII/C1 controls, for the same reason: a note is
 * pasted text a painter turns straight into a run of glyphs, and any of these can silently reorder
 * everything printed after them, including - once concatenated onto a page - text the note's author
 * never wrote. The mark controls LRM and RLM (8206, 8207) are kept: they only pick a direction for
 * the character they sit next to, never reorder anything around them, and mixed Hindi/English text
 * relies on them to lay out correctly.
 *
 * Never called for the register, which lists everyone by name alone - a note is a page's voice
 * about the person it is beside, not a fact to file next to their name.
 */
const isControlCode = (code) =>
  (code <= 31 && code !== 9 && code !== 10 && code !== 13) ||
  (code >= 127 && code <= 159) ||
  (code >= 8234 && code <= 8238) ||
  (code >= 8294 && code <= 8297);

export function clampNote(raw, maxLines = 3) {
  if (typeof raw !== 'string') return null;
  let cleaned = '';
  for (const ch of raw) {
    const code = ch.codePointAt(0);
    if (code === 9) cleaned += ' ';               // a tab is a word separator, not a byte to delete
    else if (!isControlCode(code)) cleaned += ch;
  }
  const lines = cleaned
    .split(/\r\n|\r|\n/)
    .map((l) => l.trim())
    .filter(Boolean)
    .slice(0, maxLines);
  return lines.length ? lines.join('\n') : null;
}

/** "The Sharma Family", from the surname most people in the book share. */
export function familyTitle(people) {
  const counts = new Map();
  for (const p of people) {
    const parts = p.name?.split(/\s+/) ?? [];
    if (parts.length < 2) continue;
    const surname = parts[parts.length - 1];
    counts.set(surname, (counts.get(surname) ?? 0) + 1);
  }
  let best = null;
  for (const [surname, n] of counts) {
    if (!best || n > best.n || (n === best.n && byKey(sortKey(surname), sortKey(best.surname)) < 0)) best = { surname, n };
  }
  if (!best) return 'Our Family';
  return DEVANAGARI.test(best.surname) ? `${best.surname} परिवार` : `The ${best.surname} Family`;
}

/**
 * The document as either shell writes it. Android's exporter leaves out what is empty - a tree of
 * one person has no `relationships` at all - where the desktop writes `[]`; both are the same tree.
 */
function normalise(doc) {
  return {
    ...doc,
    people: Array.isArray(doc?.people) ? doc.people : [],
    relationships: Array.isArray(doc?.relationships) ? doc.relationships : [],
  };
}

/**
 * Reads the document into the book's family.
 *
 * `options.scope` is `{ kind: 'everyone' }` or `{ kind: 'branch', personId }`;
 * `allowance.maxGenerations`, when the policy grants only part of the book, keeps that many
 * generations from the eldest down.
 */
export function readFamily(doc, options = {}, allowance = {}) {
  let graph = buildGraph(normalise(doc));
  const scope = options.scope ?? { kind: 'everyone' };
  if (scope.kind === 'branch') {
    if (!graph.people.has(scope.personId)) throw new Error(`branch: nobody with id ${scope.personId}`);
    graph = restrictedGraph(graph, branchFrom(graph, scope.personId));
  }

  let placed = place(graph);
  if (allowance.maxGenerations && placed.generations > allowance.maxGenerations) {
    const keep = [...placed.gen].filter(([, g]) => g !== null && g < allowance.maxGenerations).map(([id]) => id);
    graph = restrictedGraph(graph, new Set(keep));
    placed = place(graph);
  }

  const order = new Map(placed.layout.nodes.map((n, i) => [n.id, i]));
  const wantNotes = options.notes === true;
  const people = [...graph.people.values()].map((p) => ({
    id: p.id,
    name: p.name,
    gender: p.gender,
    birthDate: p.birthDate,
    deathDate: p.deathDate,
    deceased: p.deceased,
    photo: Boolean(p.photo),
    gen: placed.gen.get(p.id),          // null: not joined to the main family
    x: placed.x.get(p.id) ?? 0,
    by: year(p.birthDate),
    dy: year(p.deathDate),
    order: order.get(p.id) ?? 0,
    // Off by default (docs/storybook-plan.md): with `options.notes` unset, every shown person
    // carries `note: null`, so a template that has not been taught to look for one cannot show it
    // by accident. No block reads this field yet - it is here for the story pages (#256-258).
    note: wantNotes ? clampNote(p.notes) : null,
  }));
  const byId = new Map(people.map((p) => [p.id, p]));

  return {
    graph,
    layout: placed.layout,
    people,
    byId,
    generations: placed.generations,
    elsewhere: people.filter((p) => p.gen === null),
    title: options.title?.trim() || familyTitle(people),
    parentsOf: (id) => graph.parents(id).map((q) => byId.get(q.id)).filter(Boolean),
    spousesOf: (id) => graph.spouses(id).map((q) => byId.get(q.id)).filter(Boolean),
    childrenOf: (id) => graph.children(id).map((q) => byId.get(q.id)).filter(Boolean),
  };
}

/**
 * Generations, counted from the eldest of the largest family.
 *
 * The layout's levels are only relative within one connected family, so a second, unconnected
 * family - or somebody recorded with no relationships at all - has no honest generation number in
 * this one's count. They are kept, on the tree page and in a section of their own, but not given a
 * number that would claim a relationship the record does not state.
 */
function place(graph) {
  const layout = layoutArchive(graph, { orientation: 'rows', aspect: 0.8 });
  const main = graph.components.reduce((best, c) => (!best || c.members.length > best.members.length ? c : best), null);
  const inMain = new Set(main && main.members.length > 1 ? main.members : []);
  let min = Infinity;
  for (const id of inMain) min = Math.min(min, layout.levels.get(id));
  const gen = new Map();
  for (const id of graph.people.keys()) gen.set(id, inMain.has(id) ? layout.levels.get(id) - min : null);
  const x = new Map(layout.nodes.map((n) => [n.id, n.x]));
  const generations = inMain.size ? Math.max(...[...inMain].map((id) => gen.get(id))) + 1 : 0;
  return { layout, gen, x, generations };
}

/**
 * The numbers page's facts. Every one is counted from the record; none is estimated. Nothing
 * reveals a living person's age: the longest life is looked for among the departed only.
 */
export function familyFacts(family) {
  const { people } = family;
  const years = people.flatMap((p) => [p.by, p.dy]).filter((y) => y !== null);
  const departed = people.filter((p) => p.deceased);
  const unknown = people.filter((p) => !p.name).length;

  let couples = 0;
  const seen = new Set();
  for (const p of people) for (const s of family.spousesOf(p.id)) {
    const key = p.id < s.id ? `${p.id} ${s.id}` : `${s.id} ${p.id}`;
    if (!seen.has(key)) { seen.add(key); couples++; }
  }

  // The largest family: the parent, or couple, with the most children in common.
  let largest = null;
  const families = new Map();
  for (const p of people) {
    const parents = family.parentsOf(p.id).map((q) => q.id).sort();
    if (!parents.length) continue;
    const key = parents.join(' ');
    families.set(key, (families.get(key) ?? 0) + 1);
  }
  for (const [key, n] of families) {
    if (n < 2) continue;
    if (!largest || n > largest.n || (n === largest.n && byKey(key, largest.key) < 0)) largest = { key, n };
  }
  const raisedBy = largest ? largest.key.split(' ').map((id) => firstName(family.byId.get(id))).filter(Boolean) : [];

  let longest = null;
  for (const p of departed) {
    if (p.by === null || p.dy === null || p.dy < p.by) continue;
    const span = p.dy - p.by;
    if (!longest || span > longest.years) longest = { years: span, name: p.name };
  }

  // A first name carried into more than one generation.
  const names = new Map();
  for (const p of people) {
    const f = firstName(p);
    if (!f || p.gen === null) continue;
    const e = names.get(f) ?? { n: 0, gens: new Set() };
    e.n++; e.gens.add(p.gen); names.set(f, e);
  }
  let repeated = null;
  for (const [name, e] of names) {
    if (e.gens.size < 2) continue;
    if (!repeated || e.n > repeated.n || (e.n === repeated.n && byKey(sortKey(name), sortKey(repeated.name)) < 0)) repeated = { name, n: e.n };
  }

  return {
    people: people.length,
    generations: family.generations,
    earliest: years.length ? Math.min(...years) : null,
    departed: departed.length,
    unknown,
    couples,
    largest: largest ? { children: largest.n, raisedBy } : null,
    longest,
    repeated,
    photos: people.filter((p) => p.photo).length,
  };
}
