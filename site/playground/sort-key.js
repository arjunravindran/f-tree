/**
 * A key for alphabetical order that is the same on every engine. Latin diacritics fold away
 * (É files with E); Devanagari is left exactly as written - stripping its vowel signs would file
 * distinct names together, the very bug #113 records for duplicate matching.
 */
export function sortKey(name) {
  return name.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

export const byKey = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
