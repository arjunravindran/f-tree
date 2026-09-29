/*
 * Which file the app was started with (#190).
 *
 * The offsets are the part that is easy to get wrong and impossible to see from a running app:
 * `electron . tree.ftree` and a packaged `f-tree-desktop tree.ftree` put the same path at different
 * places in argv.
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { ftreeFromArgv } = require('./open-args.js');

const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'ftree-open-args-'));
test.after(() => fs.rmSync(folder, { recursive: true, force: true }));

const make = (name) => {
  const file = path.join(folder, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, 'x');
  return file;
};

const tree = make('a.ftree');
const other = make('b.ftree');
const packaged = { isPackaged: true, cwd: '/' };

test('a packaged launch has the file at argv[1]', () => {
  assert.strictEqual(ftreeFromArgv(['/usr/bin/f-tree-desktop', tree], packaged), tree);
});

test('an unpackaged launch skips the app folder at argv[1]', () => {
  // `electron . tree.ftree`: the file is at argv[2]. A .ftree at argv[1] is where the app's own
  // path goes there, so it is not read as a file to open.
  assert.strictEqual(ftreeFromArgv(['electron', '.', tree], { isPackaged: false, cwd: '/' }), tree);
  assert.strictEqual(ftreeFromArgv(['electron', tree], { isPackaged: false, cwd: '/' }), null);
});

test('flags are skipped', () => {
  assert.strictEqual(ftreeFromArgv(['app', '--no-sandbox', '--flag=x.ftree', tree], packaged), tree);
});

test('anything that is not a .ftree is ignored', () => {
  const text = make('notes.txt');
  assert.strictEqual(ftreeFromArgv(['app', text], packaged), null);
});

test('the extension is matched without regard to case', () => {
  const upper = make('LOUD.FTREE');
  assert.strictEqual(ftreeFromArgv(['app', upper], packaged), upper);
});

test('a .ftree that does not exist is ignored', () => {
  const missing = path.join(folder, 'gone.ftree');
  assert.strictEqual(ftreeFromArgv(['app', missing], packaged), null);
  assert.strictEqual(ftreeFromArgv(['app', missing, tree], packaged), tree);
});

test('a directory named like a tree is not a tree', () => {
  const directory = path.join(folder, 'dir.ftree');
  fs.mkdirSync(directory);
  assert.strictEqual(ftreeFromArgv(['app', directory], packaged), null);
});

test('a relative path is resolved against the given cwd', () => {
  const nested = make('sub/c.ftree');
  assert.strictEqual(
    ftreeFromArgv(['app', 'c.ftree'], { isPackaged: true, cwd: path.dirname(nested) }), nested);
});

test('nothing to open', () => {
  assert.strictEqual(ftreeFromArgv([], packaged), null);
  assert.strictEqual(ftreeFromArgv(['app'], packaged), null);
  assert.strictEqual(ftreeFromArgv(undefined, { isPackaged: false, cwd: '/' }), null);
});

test('with several paths the first one wins', () => {
  assert.strictEqual(ftreeFromArgv(['app', other, tree], packaged), other);
});
