/*
 * Downloaded book templates (#214), held to the same cases as `TemplateDownloadTest.kt` and
 * `TemplateManifestTest.kt`. The keypair is generated here and every fixture is signed here, so no
 * key material or signed blob is committed to prove any of it.
 */

const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { createTemplates } = require('./templates.js');

const pair = () => crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const spki = (k) => k.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const sign = (k, bytes) => crypto.sign('sha256', Buffer.from(bytes), k.privateKey).toString('base64');
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');

const DIWALI = JSON.stringify({ name: 'diwali', pages: [1, 2] });
const HOLI = JSON.stringify({ name: 'holi', pages: [3] });

const manifestOf = (seq, files) => JSON.stringify({
  format: 1,
  seq,
  templates: Object.entries(files).map(([id, body]) => ({
    id, name: id, format: 1, tier: 'festival', sha256: sha(body), bytes: Buffer.byteLength(body),
  })),
});

/** A world: one release list, a fake network that logs what it is asked for, and a temp cache. */
function world({ key = pair(), files = { diwali: DIWALI, holi: HOLI }, seq = 5, on = true, pinned } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ftree-templates-'));
  const state = {
    key,
    dir: path.join(dir, 'templates'),
    log: [],
    stored: { bookTemplates: on, templatesSeq: 0 },
    body: null,
    sig: null,
    files,
  };
  const publish = ({ manifest = manifestOf(seq, state.files), signer = key, signature, withSig = true } = {}) => {
    state.body = manifest;
    state.sig = signature ?? sign(signer, manifest);
    state.withSig = withSig;
  };
  publish();
  const asset = (name) => ({ name, size: 10, browser_download_url: `https://example.test/${name}` });
  state.fetchReleases = async () => {
    state.log.push('releases');
    const assets = [asset('templates.json'), ...(state.withSig ? [asset('templates.json.sig')] : []),
      ...Object.keys(state.files).map((id) => asset(`${id}.json`))];
    return JSON.stringify([
      { tag_name: 'v0.11.0', published_at: '2026-09-01T00:00:00Z', assets },
      { tag_name: 'v0.10.0', published_at: '2026-08-01T00:00:00Z', assets: [] },
    ]);
  };
  state.download = async (url, destination) => {
    const name = url.split('/').pop();
    state.log.push(name);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    if (name === 'templates.json') fs.writeFileSync(destination, state.body);
    else if (name === 'templates.json.sig') fs.writeFileSync(destination, state.sig);
    else fs.writeFileSync(destination, state.files[name.replace(/\.json$/, '')]);
  };
  state.templates = createTemplates({
    directory: state.dir,
    settings: { get: (k) => state.stored[k], set: (k, v) => { state.stored[k] = v; } },
    publicKey: pinned ?? spki(key),
    fetchReleases: state.fetchReleases,
    download: state.download,
  });
  state.cleanup = () => fs.rmSync(dir, { recursive: true, force: true });
  state.publish = publish;
  state.read = (name) => fs.readFileSync(path.join(state.dir, name), 'utf8');
  state.exists = (name) => fs.existsSync(path.join(state.dir, name));
  return state;
}

const withWorld = (name, options, body) => test(name, async () => {
  const w = world(options);
  try { await body(w); } finally { w.cleanup(); }
});

withWorld('a refresh fetches only the manifest and its signature', {}, async (w) => {
  const result = await w.templates.refreshCatalogue();
  assert.deepStrictEqual(result, { state: 'updated', offered: 2 });
  assert.deepStrictEqual(w.log, ['releases', 'templates.json', 'templates.json.sig']);
  assert.strictEqual(w.stored.templatesSeq, 5);
  assert.ok(!w.exists('t-diwali.json'));
  assert.deepStrictEqual(w.templates.cached(), []);
});

withWorld('a template is fetched only when asked for, and then it is readable', {}, async (w) => {
  await w.templates.refreshCatalogue();
  w.log.length = 0;
  assert.deepStrictEqual(await w.templates.fetchTemplate('diwali'), { ok: true });
  assert.deepStrictEqual(w.log, ['releases', 'diwali.json']);
  assert.deepStrictEqual(w.templates.cached(), [{ id: 'diwali', template: JSON.parse(DIWALI) }]);
});

withWorld('an id the manifest does not list is never fetched', {}, async (w) => {
  await w.templates.refreshCatalogue();
  w.log.length = 0;
  assert.strictEqual((await w.templates.fetchTemplate('../../etc')).ok, false);
  assert.strictEqual((await w.templates.fetchTemplate('unlisted')).ok, false);
  assert.deepStrictEqual(w.log, []);
});

withWorld('a manifest signed by a different key is refused', { pinned: spki(pair()) }, async (w) => {
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'refused', reason: 'signature' });
  assert.strictEqual(w.stored.templatesSeq, 0);
  assert.strictEqual(w.templates.verified(), null);
  assert.ok(!w.exists('templates.json'));
});

withWorld('a release with no signature offers no catalogue', {}, async (w) => {
  w.publish({ withSig: false });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'none' });
  assert.deepStrictEqual(w.log, ['releases']);
});

withWorld('an empty signature is refused', {}, async (w) => {
  w.publish({ signature: '' });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'refused', reason: 'signature' });
});

withWorld('bytes changed after signing are refused', {}, async (w) => {
  const signed = manifestOf(5, w.files);
  const signature = sign(w.key, signed);
  w.publish({ manifest: signed.replace('"seq":5', '"seq":9'), signature });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'refused', reason: 'signature' });
  assert.strictEqual(w.stored.templatesSeq, 0);
});

withWorld('a seq that is equal or lower is unchanged and leaves the stored manifest alone', {}, async (w) => {
  await w.templates.refreshCatalogue();
  const before = w.read('templates.json');
  const sigBefore = w.read('templates.json.sig');

  w.publish({ manifest: manifestOf(5, { diwali: DIWALI }) });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'unchanged' });
  w.publish({ manifest: manifestOf(2, { diwali: DIWALI }) });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'unchanged' });

  assert.strictEqual(w.read('templates.json'), before);
  assert.strictEqual(w.read('templates.json.sig'), sigBefore);
  assert.strictEqual(w.stored.templatesSeq, 5);
  assert.strictEqual(w.templates.verified().seq, 5);
  assert.ok(!w.exists('templates.json.new'));
});

withWorld('a valid signature over a substituted template file is refused and the file deleted', {}, async (w) => {
  await w.templates.refreshCatalogue();
  w.files = { ...w.files, diwali: '{"name":"tampered"}' };
  const result = await w.templates.fetchTemplate('diwali');
  assert.deepStrictEqual(result, { ok: false, reason: 'checksum' });
  assert.ok(!w.exists('t-diwali.json'));
  assert.deepStrictEqual(w.templates.cached(), []);
});

withWorld('a newer manifest with a changed hash drops the stale cached copy', {}, async (w) => {
  await w.templates.refreshCatalogue();
  await w.templates.fetchTemplate('diwali');
  await w.templates.fetchTemplate('holi');

  w.files = { diwali: '{"name":"diwali","pages":[9]}', holi: HOLI };
  w.publish({ manifest: manifestOf(6, w.files) });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'updated', offered: 2 });

  assert.ok(!w.exists('t-diwali.json'));
  assert.ok(w.exists('t-holi.json'));
  assert.deepStrictEqual(w.templates.cached().map((c) => c.id), ['holi']);
});

withWorld('a withdrawn id is deleted and no longer offered', {}, async (w) => {
  await w.templates.refreshCatalogue();
  await w.templates.fetchTemplate('diwali');
  await w.templates.fetchTemplate('holi');

  w.files = { holi: HOLI };
  w.publish({ manifest: manifestOf(6, w.files) });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'updated', offered: 1 });

  assert.ok(!w.exists('t-diwali.json'));
  assert.deepStrictEqual(w.templates.cached().map((c) => c.id), ['holi']);
  assert.deepStrictEqual(w.templates.verified().entries.map((e) => e.id), ['holi']);
  assert.strictEqual((await w.templates.fetchTemplate('diwali')).ok, false);
});

withWorld('remove deletes only the file, and the template can be fetched again', {}, async (w) => {
  await w.templates.refreshCatalogue();
  await w.templates.fetchTemplate('diwali');
  const manifest = w.read('templates.json');
  const signature = w.read('templates.json.sig');

  w.templates.remove('diwali');

  assert.ok(!w.exists('t-diwali.json'));
  assert.strictEqual(w.read('templates.json'), manifest);
  assert.strictEqual(w.read('templates.json.sig'), signature);
  assert.strictEqual(w.stored.templatesSeq, 5);
  assert.strictEqual(w.templates.verified().seq, 5);
  assert.deepStrictEqual(await w.templates.fetchTemplate('diwali'), { ok: true });
  assert.deepStrictEqual(w.templates.cached().map((c) => c.id), ['diwali']);
});

withWorld('with the switch off nothing is requested, and templatesSeq survives off then on', {}, async (w) => {
  await w.templates.refreshCatalogue();
  await w.templates.fetchTemplate('diwali');
  w.log.length = 0;

  w.stored.bookTemplates = false;
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'off' });
  assert.strictEqual((await w.templates.fetchTemplate('holi')).ok, false);
  assert.deepStrictEqual(w.log, []);
  // Off stops the network; it does not take a book away.
  assert.deepStrictEqual(w.templates.cached().map((c) => c.id), ['diwali']);

  w.stored.bookTemplates = true;
  assert.strictEqual(w.stored.templatesSeq, 5);
  w.publish({ manifest: manifestOf(5, w.files) });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'unchanged' });
});

withWorld('a build with no pinned key makes no request', { pinned: '' }, async (w) => {
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'off' });
  assert.deepStrictEqual(w.log, []);
});

withWorld('a manifest swapped for an older real one is no longer trusted', {}, async (w) => {
  await w.templates.refreshCatalogue();
  const older = manifestOf(3, w.files);
  fs.writeFileSync(path.join(w.dir, 'templates.json'), older);
  fs.writeFileSync(path.join(w.dir, 'templates.json.sig'), sign(w.key, older));
  assert.strictEqual(w.templates.verified(), null);
  assert.deepStrictEqual(w.templates.cached(), []);
});

withWorld('an entry with no hash fails the whole manifest', {}, async (w) => {
  w.publish({ manifest: JSON.stringify({ format: 1, seq: 6, templates: [{ id: 'diwali', name: 'd', format: 1, tier: 'festival' }] }) });
  assert.deepStrictEqual(await w.templates.refreshCatalogue(), { state: 'refused', reason: 'malformed' });
  assert.strictEqual(w.stored.templatesSeq, 0);
});

withWorld('a failed transfer is a failure, not a refusal, and leaves nothing behind', {}, async (w) => {
  const failing = createTemplates({
    directory: w.dir,
    settings: { get: (k) => w.stored[k], set: () => {} },
    publicKey: spki(w.key),
    fetchReleases: w.fetchReleases,
    download: async () => { throw new Error('offline'); },
  });
  assert.deepStrictEqual(await failing.refreshCatalogue(), { state: 'failed', reason: 'offline' });
  assert.deepStrictEqual(fs.readdirSync(w.dir), []);
});
