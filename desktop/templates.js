/*
 * Book templates that arrive by download (#214), on the desktop: the signed catalogue, one template
 * at a time, and removal. Ported from `book/TemplateManifest.kt` and `book/TemplateDownloader.kt`,
 * whose reasoning applies here unchanged.
 *
 * There is ONE signature in the whole scheme, and it is over the manifest. A template is never
 * signed and never checked against a key: its authenticity is the SHA-256 that a manifest this key
 * signed vouches for. A check fetches the manifest and its signature and nothing else; a template's
 * own file is fetched only when the reader chooses it. Nothing here runs on a timer.
 *
 * Nothing in this file touches `electron`, the network or the settings file directly. Those arrive
 * as arguments, which is what lets `templates.test.js` prove the whole scheme with a keypair it
 * generates and no committed key material.
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const MANIFEST_ASSET = 'templates.json';
const SIGNATURE_ASSET = 'templates.json.sig';

// Prefixed because an id is a catalogue id and `templates` is a legal one: without the prefix a
// template called that would be stored over the manifest itself.
const CACHE_PREFIX = 't-';

const ID = /^[a-z][a-z0-9-]{1,31}$/;
const HEX = /^[0-9a-f]{64}$/;

const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

/**
 * Verifies `manifest` (the file's exact bytes, which is what was signed) against `signature` and
 * `publicKey`, then against `minSeq`. Order is the rule and the first failure ends it: signature,
 * then `seq`, then shape. The manifest is parsed only once the signature holds, because an
 * unverified manifest is bytes, not a catalogue.
 *
 * Returns `{ verified }` or `{ refusal }`, where a refusal is `key`, `signature`, `malformed` or
 * `rollback`. Nothing throws.
 */
function verifyManifest(manifest, signature, publicKey, minSeq) {
  let key;
  try {
    key = crypto.createPublicKey({ key: Buffer.from(String(publicKey).trim(), 'base64'), format: 'der', type: 'spki' });
  } catch {
    return { refusal: 'key' };
  }
  let matches = false;
  try {
    matches = crypto.verify('sha256', manifest, key, Buffer.from(String(signature).trim(), 'base64'));
  } catch {
    matches = false; // A malformed signature is a failed one, not a crash.
  }
  if (!matches) return { refusal: 'signature' };

  let root;
  try {
    root = JSON.parse(manifest.toString('utf8'));
  } catch {
    return { refusal: 'malformed' };
  }
  if (!root || typeof root !== 'object' || Array.isArray(root)) return { refusal: 'malformed' };
  if (!Number.isSafeInteger(root.seq)) return { refusal: 'malformed' };
  if (root.seq <= minSeq) return { refusal: 'rollback' };

  if (root.format !== 1 || !Array.isArray(root.templates)) return { refusal: 'malformed' };
  const entries = [];
  const hashes = new Map();
  const bytes = new Map();
  for (const raw of root.templates) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue;
    if (typeof raw.id !== 'string' || !ID.test(raw.id) || hashes.has(raw.id)) continue;
    // An entry with no hash its file can be checked against fails the whole manifest instead of
    // dropping a row: dropping it quietly would be the one path that could put an unverifiable
    // template in front of a reader.
    if (typeof raw.sha256 !== 'string' || !HEX.test(raw.sha256.toLowerCase())) return { refusal: 'malformed' };
    hashes.set(raw.id, raw.sha256.toLowerCase());
    bytes.set(raw.id, Number.isSafeInteger(raw.bytes) && raw.bytes > 0 ? raw.bytes : 0);
    entries.push(raw);
  }
  return { verified: { seq: root.seq, entries, hashes, bytes } };
}

/**
 * The URL and size of the asset called `name` in the newest release that carries every name in
 * `require`, or null. Any tag qualifies: the catalogue lives on whichever release last carried it,
 * which today is an Android one. Only an https URL is ever returned.
 */
function findAsset(releasesText, name, require = [name]) {
  let releases;
  try {
    // The desktop's own `fetchReleases` parses before handing it over; the tests hand over text.
    releases = typeof releasesText === 'string' ? JSON.parse(releasesText) : releasesText;
  } catch {
    return null;
  }
  // `releases?per_page=20` answers with a list, `releases/latest` with one release.
  if (!Array.isArray(releases)) releases = [releases];
  const candidates = releases
    .filter((r) => r && typeof r === 'object' && !r.draft && Array.isArray(r.assets))
    .map((r, index) => ({ r, index, time: Date.parse(r.published_at) || 0 }))
    // GitHub lists newest first; published_at settles it if a list ever arrives otherwise.
    .sort((a, b) => b.time - a.time || a.index - b.index);
  const has = (r, n) => r.assets.find((a) => a && a.name === n
    && typeof a.browser_download_url === 'string' && a.browser_download_url.startsWith('https://'));
  for (const { r } of candidates) {
    if (!require.every((n) => has(r, n))) continue;
    const asset = has(r, name);
    return { url: asset.browser_download_url, size: Number.isSafeInteger(asset.size) ? asset.size : 0 };
  }
  return null;
}

function createTemplates({ directory, settings, publicKey, fetchReleases, download, maxFormat = Infinity }) {
  const configured = () => typeof publicKey === 'string' && publicKey.trim() !== '';
  const enabled = () => settings.get('bookTemplates') === true;
  const mark = () => {
    const n = settings.get('templatesSeq');
    return Number.isSafeInteger(n) ? n : 0;
  };
  const fileFor = (id) => path.join(directory, `${CACHE_PREFIX}${id}.json`);
  // What the composer this release ships can actually draw. The page filters the catalogue for
  // display; this is the same rule applied before a byte is fetched, so a template written for a
  // newer app is never downloaded and never counted as on offer.
  const drawable = (entry) => Number.isSafeInteger(entry?.format) && entry.format <= maxFormat;
  const quietly = (fn) => { try { return fn(); } catch { return undefined; } };
  const remove_ = (file) => quietly(() => fs.rmSync(file, { force: true }));
  const failure = (e) => ({ state: 'failed', reason: (e && e.message) || 'network' });

  /*
   * The catalogue this app has verified, or null when there is none it still trusts.
   *
   * Re-verified on every read rather than trusted for being on disk: the signature is checked
   * again and the `seq` must still be at least the high-water mark, so a manifest swapped out under
   * the app - for an older real one included - is refused instead of read.
   */
  function verified() {
    if (!configured()) return null;
    const result = quietly(() => verifyManifest(
      fs.readFileSync(path.join(directory, MANIFEST_ASSET)),
      fs.readFileSync(path.join(directory, SIGNATURE_ASSET), 'utf8'),
      publicKey,
      // One below the mark, because the stored manifest is allowed to *be* the mark.
      mark() - 1,
    ));
    return (result && result.verified) || null;
  }

  // Gone from the manifest, or vouched for with a different hash: either way the copy on disk is
  // no longer a template this app can stand behind, so it goes and the template is offered again.
  function prune(manifest) {
    for (const name of quietly(() => fs.readdirSync(directory)) || []) {
      if (!name.startsWith(CACHE_PREFIX) || !name.endsWith('.json')) continue;
      const id = name.slice(CACHE_PREFIX.length, -'.json'.length);
      const expected = manifest.hashes.get(id);
      const actual = quietly(() => sha256(fs.readFileSync(path.join(directory, name))));
      if (!expected || actual !== expected) remove_(path.join(directory, name));
    }
  }

  async function refreshCatalogue() {
    if (!enabled() || !configured()) return { state: 'off' };
    const manifestFile = path.join(directory, `${MANIFEST_ASSET}.new`);
    const signatureFile = path.join(directory, `${SIGNATURE_ASSET}.new`);
    try {
      fs.mkdirSync(directory, { recursive: true });
      const releases = await fetchReleases();
      const manifestAsset = findAsset(releases, MANIFEST_ASSET, [MANIFEST_ASSET, SIGNATURE_ASSET]);
      const signatureAsset = findAsset(releases, SIGNATURE_ASSET, [MANIFEST_ASSET, SIGNATURE_ASSET]);
      // A repository with no catalogue is a state of the repository, not an error.
      if (!manifestAsset || !signatureAsset) return { state: 'none' };

      await download(manifestAsset.url, manifestFile, manifestAsset.size);
      await download(signatureAsset.url, signatureFile, signatureAsset.size);

      const result = verifyManifest(fs.readFileSync(manifestFile), fs.readFileSync(signatureFile, 'utf8'),
        publicKey, mark());
      // Not newer is not news and not an alarm; an attacker replaying an old catalogue is
      // indistinguishable from it, and both are refused.
      if (result.refusal === 'rollback') return { state: 'unchanged' };
      if (result.refusal) return { state: 'refused', reason: result.refusal };

      // Committed only once verified, so a refused catalogue never replaces a trusted one and a
      // half-written file is never read as either.
      try {
        fs.renameSync(manifestFile, path.join(directory, MANIFEST_ASSET));
        fs.renameSync(signatureFile, path.join(directory, SIGNATURE_ASSET));
      } catch {
        return { state: 'failed', reason: 'storage' };
      }
      settings.set('templatesSeq', result.verified.seq);
      prune(result.verified);
      return { state: 'updated', offered: result.verified.entries.filter(drawable).length };
    } catch (e) {
      return failure(e);
    } finally {
      remove_(manifestFile);
      remove_(signatureFile);
    }
  }

  /*
   * Fetches one template, because the reader asked for that one. The release list is read again
   * rather than remembered at check time, so the URL is the one GitHub gives now and nothing stored
   * can go stale. A file that fails its hash is deleted rather than kept for a retry to trip on.
   */
  async function fetchTemplate(id) {
    if (!enabled() || !configured()) return { ok: false, reason: 'off' };
    const manifest = verified();
    const expected = manifest && manifest.hashes.get(id);
    if (!expected) return { ok: false, reason: 'unlisted' };
    if (!manifest.entries.some((e) => e.id === id && drawable(e))) return { ok: false, reason: 'unlisted' };

    const file = fileFor(id);
    try {
      const asset = findAsset(await fetchReleases(), `${id}.json`);
      if (!asset) return { ok: false, reason: 'no-release' };
      await download(asset.url, file, manifest.bytes.get(id) || asset.size);
      if (sha256(fs.readFileSync(file)) !== expected) {
        remove_(file);
        return { ok: false, reason: 'checksum' };
      }
      return { ok: true };
    } catch (e) {
      remove_(file);
      return { ok: false, reason: (e && e.message) || 'network' };
    }
  }

  // Only the local copy: the manifest, its signature and the `seq` stay, so the template is still
  // listed and offered as a download again. Reclaiming space is not a decision about trust.
  function remove(id) {
    if (ID.test(String(id))) remove_(fileFor(id));
  }

  // A template already on disk is not gated on the switch: turning it off stops the network, it
  // does not take a book away. It is still only offered while the manifest vouches for its bytes.
  function cached() {
    const manifest = verified();
    if (!manifest) return [];
    const found = [];
    for (const [id, expected] of manifest.hashes) {
      const bytes = quietly(() => fs.readFileSync(fileFor(id)));
      if (!bytes || sha256(bytes) !== expected) continue;
      const template = quietly(() => JSON.parse(bytes.toString('utf8')));
      if (template !== undefined) found.push({ id, template });
    }
    return found;
  }

  return { verified, refreshCatalogue, fetchTemplate, remove, cached };
}

module.exports = { createTemplates, verifyManifest, MANIFEST_ASSET, SIGNATURE_ASSET, CACHE_PREFIX };
