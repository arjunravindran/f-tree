/*
 * Which .ftree, if any, the app was asked to open when it was started.
 *
 * Double-clicking a tree in the file manager, or running `f-tree-desktop path/to/tree.ftree`,
 * hands the path to the app on its command line. macOS is the exception: it delivers the path as an
 * `open-file` event instead, which main.js handles separately. Everywhere else this is the only
 * way the path ever arrives, and until it was read the app started, found the last tree in
 * session.json and opened that -- so the file the reader had just chosen was quietly ignored.
 *
 * Pure on purpose. Which entry of argv is the file depends on how the app was started, and that is
 * the part worth a test.
 */

const fs = require('node:fs');
const path = require('node:path');

/*
 * The first entry of `argv` that names an existing `.ftree`, as an absolute path, or null.
 *
 * `argv[0]` is always the binary. Unpackaged, `electron . tree.ftree` puts the app's own folder at
 * `argv[1]` and the file at `argv[2]`; packaged, there is no folder and the file is `argv[1]`. A
 * relative path is resolved against `cwd` rather than the process's own, because a second
 * instance's arguments arrive from a different process whose working directory is the one the
 * reader typed the path in.
 *
 * Flags are skipped, and so is anything that is not a file that is there: a path that has since
 * gone is not worth a dialog at launch, and the app simply starts as it would have.
 */
function ftreeFromArgv(argv, { isPackaged, cwd }) {
  const first = isPackaged ? 1 : 2;
  for (const argument of (argv || []).slice(first)) {
    if (typeof argument !== 'string' || argument.startsWith('-')) continue;
    if (!argument.toLowerCase().endsWith('.ftree')) continue;
    const resolved = path.resolve(cwd, argument);
    try {
      if (fs.statSync(resolved).isFile()) return resolved;
    } catch {
      // Not there. Try the next one.
    }
  }
  return null;
}

module.exports = { ftreeFromArgv };
