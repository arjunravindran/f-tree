// Which of site/book the packaged desktop app carries (#247).
//
// The book's page ships through `extraResources`, filtered by the patterns in package.json. The
// storybook's authored art (site/book/art/src: SVG sources, swatches) and the style frames are for
// people making the art, and must stay out; the compiled art (site/book/art/papercut/*.js) and
// the procedural generators (site/book/art/procedural/*.js) are what the composer imports, and
// must ship, or the book screen fails to load its modules. This reads the patterns themselves, so
// `npm test` catches a wrong one without building a package; package.test.js asks the same of a
// built linux-unpacked when there is one.

const test = require('node:test');
const assert = require('node:assert');

const entry = require('./package.json').build.extraResources.find((e) => e.from === '../site/book');

test('the packaged book leaves out the authored art and keeps the compiled art', () => {
  assert.ok(entry, 'package.json no longer ships ../site/book as an extraResource');
  const filter = entry.filter;
  assert.strictEqual(filter[0], '**/*', 'the book ships whole, less what is excluded');
  for (const out of ['!art/src${/*}', '!art/style-frames${/*}', '!art/README.md', '!fixtures${/*}']) {
    assert.ok(filter.includes(out), `package.json must exclude ${out.slice(1)} from the packaged book`);
  }
  // Nothing may exclude the art the composer imports: not the modules, not their directories,
  // not a pattern broad enough to catch them.
  const ships = ['art/papercut/motifs.js', 'art/procedural/x.js', 'art/draw.js', 'art/index.js', 'art/seed.js'];
  const excludes = filter.filter((p) => p.startsWith('!')).map((p) => p.slice(1).replace('${/*}', ''));
  // any glob but the test-file one is treated as broad enough to catch them: better loud than lost
  const catches = (p, file) => p !== '*.test.mjs' && (/[*?{[]/.test(p) || file === p || file.startsWith(`${p}/`));
  for (const file of ships) {
    const hit = excludes.find((p) => catches(p, file));
    assert.strictEqual(hit, undefined, `the exclusion !${hit} would keep ${file} out of the package`);
  }
});

/*
 * Heirloom is not in the package, and that is the point (#214).
 *
 * From desktop-v0.10.0-beta.1 it arrives by download, exactly as it does on Android from
 * v0.11.0-beta.2: `catalog.json` still lists it, the package simply does not carry the file, and a
 * listed template whose file is missing is left out rather than failing the rest. Diwali is asserted
 * beside it so that "nothing ships" could never pass this test.
 */
test('Heirloom is left out of the package, and Diwali is not', () => {
  const filter = entry.filter;
  assert.ok(
    filter.includes('!templates/heirloom.json'),
    'the package must not carry heirloom.json -- it is a download now',
  );
  assert.ok(
    !filter.some((p) => p.startsWith('!') && p.includes('diwali')),
    'the storybook is the template this release ships; nothing may exclude it',
  );
});

/*
 * Both shells trust the same key, or neither can read the other's catalogue.
 *
 * There is one signature in the template scheme and one key behind it. If this file and
 * `app/build.gradle.kts` ever drifted apart, one shell would refuse a catalogue the other accepted,
 * and the failure would look like a corrupt download rather than a mismatched build.
 */
test('the desktop pins the same template key as the Android app', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const root = path.join(__dirname, '..');
  const desktop = fs.readFileSync(path.join(__dirname, 'main.js'), 'utf8')
    .match(/TEMPLATE_PUBLIC_KEY\s*=\s*'([^']*)'/);
  const android = fs.readFileSync(path.join(root, 'app', 'build.gradle.kts'), 'utf8')
    .match(/"TEMPLATE_PUBLIC_KEY",\s*"\\"([^\\"]*)\\""/);

  assert.ok(desktop, 'main.js no longer declares TEMPLATE_PUBLIC_KEY');
  assert.ok(android, 'app/build.gradle.kts no longer declares TEMPLATE_PUBLIC_KEY');
  assert.strictEqual(desktop[1], android[1], 'the two shells pin different template keys');
});
