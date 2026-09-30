/*
 * `templates.json`: the catalogue a release publishes for download (#214).
 *
 * This writes exactly what docs/family-book.md's "Publishing one" describes by hand - the shipped
 * catalogue, with a `seq` at the root and every entry's `sha256` and `bytes` - and it stops there.
 * It does not sign, and it never sees the key: the private half is offline and stays offline, and
 * `openssl dgst -sha256 -sign` over these exact bytes is still a step somebody runs themselves.
 *
 *   node tools/template_manifest.mjs --seq 2 > templates.json
 *
 * **Every entry, not only the downloadable ones.** `TemplateManifest.verify` refuses the whole
 * manifest when a single row it would list has no hash to check a file against (`hashes.size !=
 * entries.size`), and that is deliberate - dropping such a row quietly is the one path that could
 * put an unverifiable template in front of a reader. So the chart is hashed here too, although it
 * ships in the build and is never fetched: `BookTemplates` skips an id it already has. A manifest
 * built by hand that left the chart out would have been refused wholesale, and the refusal would
 * have looked like a bad signature.
 *
 * `--seq` must be higher than the last one published, or every shell will refuse the result as a
 * rollback - which is the anti-replay rule working, not a bug. Check the newest release's
 * templates.json for the number it used.
 */

import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TEMPLATES = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../site/book/templates');

export function manifest(seq, dir = TEMPLATES) {
  if (!Number.isSafeInteger(seq) || seq < 1) throw new Error('--seq <n> is required, and must be a whole number above 0');
  const catalog = JSON.parse(readFileSync(path.join(dir, 'catalog.json'), 'utf8'));

  /*
   * The bytes of the file as it sits, not of anything re-serialised: the hash has to be of exactly
   * what the shells will download and re-hash, and re-encoding the JSON would change it.
   */
  const templates = catalog.templates.map((entry) => {
    const file = path.join(dir, `${entry.id}.json`);
    let bytes;
    try {
      bytes = readFileSync(file);
    } catch {
      throw new Error(`catalog.json lists "${entry.id}" but ${entry.id}.json is not here - `
        + 'every entry needs a hash, or the manifest is refused whole');
    }
    return { ...entry, sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length };
  });
  return { format: catalog.format, seq, templates };
}

function main(argv) {
  const at = argv.indexOf('--seq');
  const seq = at === -1 ? NaN : Number(argv[at + 1]);
  return `${JSON.stringify(manifest(seq), null, 2)}\n`;
}

/* Only when run, so the tests below can import it. */
if (process.argv[1] && import.meta.url === `file://${path.resolve(process.argv[1])}`) {
  try {
    process.stdout.write(main(process.argv.slice(2)));
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exit(1);
  }
}

export { main };
