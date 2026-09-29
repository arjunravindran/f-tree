/*
 * The catalogue (catalog.js), held to the table it shares with the Kotlin port.
 *
 * `catalog-cases.json` is read by `BookCatalogTest.kt` too, so the two shells can never feature
 * different templates on the same day. Each case is its own test(), so a failing row names itself.
 */

import test from 'node:test';
import assert from 'node:assert';
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { readCatalog, available, featuredAt, listing, openingTemplate } from './catalog.js';
import { validateTemplate, MAX_TEMPLATE_FORMAT } from './template.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const shippedText = readFileSync(path.join(HERE, 'templates', 'catalog.json'), 'utf8');
const table = JSON.parse(readFileSync(path.join(HERE, 'catalog-cases.json'), 'utf8'));

for (const c of table.cases) {
  test(c.name, () => {
    const catalog = readCatalog(c.catalog === 'shipped' ? shippedText : c.catalog);
    assert.deepStrictEqual(listing(catalog, c.today, c.supported).map((t) => t.id), c.expect.listed);
    assert.deepStrictEqual(featuredAt(catalog, c.today, c.supported), c.expect.featured);
    assert.strictEqual(openingTemplate(catalog, c.today, c.supported), c.expect.opening);
  });
}

test('every template the catalogue lists ships, validates, and names itself the same way', () => {
  const catalog = readCatalog(shippedText);
  assert.ok(catalog, 'the shipped catalogue must be readable');
  const raw = JSON.parse(shippedText);
  assert.strictEqual(catalog.templates.length, raw.templates.length, 'no shipped entry may be dropped as unreadable');
  for (const entry of available(catalog)) {
    const file = path.join(HERE, 'templates', `${entry.id}.json`);
    assert.ok(existsSync(file), `${entry.id}.json is listed but not shipped`);
    const template = JSON.parse(readFileSync(file, 'utf8'));
    assert.doesNotThrow(() => validateTemplate(template));
    assert.strictEqual(template.id, entry.id);
    assert.strictEqual(template.name, entry.name);
    assert.strictEqual(template.format, entry.format);
  }
});

test('every template file that ships is in the catalogue', () => {
  const listed = new Set(readCatalog(shippedText).templates.map((t) => t.id));
  for (const file of readdirSync(path.join(HERE, 'templates'))) {
    if (file === 'catalog.json' || !file.endsWith('.json')) continue;
    assert.ok(listed.has(file.replace(/\.json$/, '')), `${file} ships but the catalogue does not list it`);
  }
});

test('the default supported format is the one the composer reads', () => {
  const catalog = readCatalog({ format: 1, templates: [
    { id: 'now', name: 'Now', format: MAX_TEMPLATE_FORMAT, tier: 'free' },
    { id: 'later', name: 'Later', format: MAX_TEMPLATE_FORMAT + 1, tier: 'free' },
  ] });
  assert.deepStrictEqual(available(catalog).map((t) => t.id), ['now']);
});

test('readCatalog never throws, however the file arrives', () => {
  for (const bad of [null, undefined, 42, [], '{not json', '"text"', '[]', { format: 1, templates: 'x' }]) {
    assert.doesNotThrow(() => readCatalog(bad));
    assert.strictEqual(readCatalog(bad), null);
  }
  assert.deepStrictEqual(listing(null, '2026-01-01'), []);
  assert.strictEqual(openingTemplate(null, '2026-01-01'), null);
});
