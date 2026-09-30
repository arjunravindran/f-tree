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
 * The designed templates are not in the package, and that is the point (#214, #314).
 *
 * From desktop-v0.10.0-beta.1 Heirloom arrives by download, exactly as it does on Android from
 * v0.11.0-beta.2, and the storybook joins it here: `catalog.json` still lists them, the package
 * simply does not carry the files, and a listed template whose file is missing is left out rather
 * than failing the rest.
 *
 * The chart is asserted beside them so that "nothing ships" could never pass this test. That
 * assertion is the reason the chart had to exist before Diwali could leave: a package carrying no
 * template at all would make the book a feature you cannot use offline, or before finding the
 * switch. It used to be Diwali holding this line, and the chart is the better thing to hold it -
 * a few hundred bytes of JSON with no art behind it.
 */
test('the templates that arrive by download are left out of the package, and the chart is not', () => {
  const filter = entry.filter;
  for (const id of ['heirloom', 'diwali']) {
    assert.ok(
      filter.includes(`!templates/${id}.json`),
      `the package must not carry ${id}.json -- it is a download now`,
    );
  }
  assert.ok(
    !filter.some((p) => p.startsWith('!') && p.includes('chart')),
    'the chart is the template this release ships; nothing may exclude it',
  );
  // And it is really there to ship, rather than a filter protecting a file that does not exist.
  assert.ok(
    require('node:fs').existsSync(require('node:path').join(__dirname, '../site/book/templates/chart.json')),
    'chart.json must exist: it is the only template the package carries',
  );
});

/*
 * The two lists that must agree, in three places.
 *
 * `build.extraResources` keeps a template out of the package; `BY_DOWNLOAD` in main.js keeps an
 * unpackaged run (`npm start`, the smoke harness) from reading the repository's own copy and showing
 * a template no reader has. A template in one list and not the other is a dev run that disagrees
 * with a release, which is exactly the kind of thing nobody notices until a reader reports it.
 */
test('the packaging filter and BY_DOWNLOAD name the same templates', () => {
  const main = require('node:fs').readFileSync(require('node:path').join(__dirname, 'main.js'), 'utf8');
  const listed = main.match(/const BY_DOWNLOAD = new Set\(\[([^\]]*)\]\)/)?.[1] ?? '';
  const byDownload = [...listed.matchAll(/'([a-z][a-z0-9-]*)'/g)].map((m) => m[1]).sort();
  const excluded = entry.filter
    .filter((p) => p.startsWith('!templates/'))
    .map((p) => p.replace('!templates/', '').replace(/\.json$/, ''))
    .sort();
  assert.deepStrictEqual(byDownload, excluded, 'package.json and main.js disagree about which templates arrive by download');
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
