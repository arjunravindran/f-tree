/*
 * `tools/template_manifest.mjs`: the catalogue a release publishes for download (#214, #314).
 *
 * What is worth testing is not the shape of the JSON but the one property the shells care about -
 * that `TemplateManifest.verify` and `desktop/templates.js` would accept it. The rule that is easy
 * to get wrong by hand, and that this exists to make impossible, is that **every** entry needs a
 * hash: a manifest missing one row's `sha256` is refused whole, and the refusal reads to a reader
 * like a corrupt download rather than like a release built wrong.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { manifest } from '../../tools/template_manifest.mjs';
import { readCatalog } from './catalog.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATES = path.join(here, 'templates');
const HEX = /^[0-9a-f]{64}$/;

test('every template the catalogue lists is vouched for, including the one that ships', () => {
  const m = manifest(2);
  const catalog = JSON.parse(readFileSync(path.join(TEMPLATES, 'catalog.json'), 'utf8'));

  assert.equal(m.seq, 2);
  assert.equal(m.format, catalog.format);
  assert.deepEqual(m.templates.map((t) => t.id), catalog.templates.map((t) => t.id),
    'the manifest lists what the catalogue lists, in the same order');

  /*
   * The rule from TemplateManifest.verify: a hash for every row the catalogue would read, or the
   * whole manifest is refused. The chart is included although it ships in the build and is never
   * fetched - BookTemplates skips an id it already has, and a row with no hash would have failed
   * Heirloom and the storybook along with it.
   */
  assert.equal(m.templates.length, readCatalog(m).templates.length);
  for (const entry of m.templates) {
    assert.ok(HEX.test(entry.sha256), `${entry.id} has no usable sha256`);
    assert.ok(Number.isSafeInteger(entry.bytes) && entry.bytes > 0, `${entry.id} has no byte count`);
  }
  assert.ok(m.templates.some((t) => t.id === 'chart'), 'the template that ships is vouched for too');
});

test('a hash is of the file exactly as it will be downloaded', () => {
  // Not of anything re-serialised: the shells re-hash the bytes they fetched, so a manifest built
  // from a reformatted copy would refuse every template it vouches for.
  for (const entry of manifest(1).templates) {
    const bytes = readFileSync(path.join(TEMPLATES, `${entry.id}.json`));
    assert.equal(entry.sha256, createHash('sha256').update(bytes).digest('hex'), entry.id);
    assert.equal(entry.bytes, bytes.length, entry.id);
  }
});

test('the catalogue it reads still reads as a catalogue', () => {
  // TemplateManifest hands the manifest to BookCatalog unchanged, which is why it may carry `seq`
  // and the extra per-entry keys at all: the listing rules are the shipped catalogue's rules.
  const read = readCatalog(manifest(7));
  assert.ok(read, 'a manifest must parse as a catalogue');
  assert.deepEqual(read.templates.map((t) => t.id), ['chart', 'heirloom', 'diwali']);
});

test('a seq that could be replayed, or is not a number, is refused before anything is written', () => {
  for (const bad of [0, -1, 1.5, NaN, undefined, '2']) {
    assert.throws(() => manifest(bad), /--seq/, String(bad));
  }
});

test('a catalogue naming a template that is not here fails loudly', () => {
  // The failure this replaces is silent: a row with no file gets no hash, and the manifest is
  // refused by every shell that reads it, long after the release was published.
  assert.throws(() => manifest(1, path.join(here, 'qa')), /catalog\.json|ENOENT|not here/);
});
