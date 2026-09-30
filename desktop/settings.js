/*
 * What the reader has chosen, and the rules about what one choice does to another.
 *
 * Pure: no disk, no Electron, no menu. `main.js` reads and writes the file; this decides what a
 * settings object may become. Written that way because the interesting part is not storage -- it is
 * that some of these settings are not independent of each other, and the places that get that wrong
 * are places nobody looks at twice.
 *
 * Ported from `app/.../update/UpdatePreferences.kt`, with its reasoning. Two of the rules there
 * exist for failures that are invisible until they bite:
 *
 *   turning update checking off clears the remembered result, because "leaving a remembered result
 *   behind would let a stale banner outlive the setting";
 *
 *   changing channel clears the skipped version, because "a version skipped on one channel means
 *   nothing on the other: leaving it behind would silently hide the first release the reader has
 *   just asked to be offered".
 *
 * Nearby sharing has no rule of that kind, on purpose: switching it off does not forget the name.
 * A remembered update result is a cache that can go stale; a name is something somebody chose.
 */

const { sanitise: sanitiseName } = require('./nearby/names');

/**
 * Off until switched on, deliberately, for the two that reach the network.
 *
 * This app makes no request of any kind unless somebody has asked it to, and a default of "on"
 * would quietly make that untrue for everybody who never opened the menu. The rest are about how
 * the app looks and reads, and default to what most readers want.
 */
const DEFAULTS = Object.freeze({
  /** English or Hindi kinship words. Inert until the Hindi vocabulary lands (#124). */
  familyWords: 'en',
  /** Whether the chart draws photographs at all. */
  photosOnChart: true,
  /** 'light' or 'dark'. */
  theme: 'light',
  checkForUpdates: false,
  betaReleases: false,
  /**
   * Whether the app may ask GitHub which book templates are on offer, and fetch one when the reader
   * asks for it (#214). Off like the rest, and nothing about it is automatic: turning it on
   * downloads no template, and a template arrives only when it is chosen in the book dialog.
   */
  bookTemplates: false,
  /**
   * The highest catalogue `seq` this app has ever verified, which a catalogue must beat to be
   * accepted.
   *
   * The one value here that turning its switch off does **not** clear, and the exception is the
   * point: this is anti-rollback state rather than a remembered result. Clearing it would let a
   * withdrawn catalogue be replayed by switching off and on again, which is the move it exists to
   * refuse. Removing a downloaded template does not touch it either.
   */
  templatesSeq: 0,
  /** When the updater last got an answer, as epoch milliseconds. 0 means never. */
  lastCheckedAt: 0,
  /** A version the reader has dismissed; they are not asked about it again. */
  skippedVersion: null,
  /**
   * Nearby sharing, the third setting that reaches a network, and off for the same reason as the
   * other two. Off means more than "hidden": nothing is constructed, no socket is bound, and the
   * device id is not even minted until somebody switches this on.
   */
  nearbySharing: false,
  /**
   * What this machine calls itself to other devices nearby, or null for the generated name. Never
   * the hostname -- see `nearby/names.js` -- because a default of "priya-laptop" would broadcast a
   * real person's name to a whole cafe without ever saying so.
   */
  nearbyName: null,
  /**
   * Birthday reminders (#154): a note at nine in the morning on a birthday, one a day however many
   * share it, while f-tree is open or on the same-day catch-up when it is next opened. Off by
   * default for the same reason the network settings are: a notification nobody asked for is not a
   * quiet default, it is a surprise.
   */
  reminders: false,
  /**
   * Whether the digest also covers the departed's remembrance days -- a birth remembrance ("would
   * have been 90") and a death anniversary. The "Coming up" list always shows them; this is only
   * about whether a notification does. Meaningless while `reminders` itself is off, the same way
   * `betaReleases` is meaningless while `checkForUpdates` is off -- see `mayCheckForUpdates` below.
   */
  reminderRemembrance: false,
  /** 'day' (on the day) or 'before' (the day before). Meaningless while `reminders` is off. */
  reminderLead: 'day',
  /**
   * The last local date, as `YYYY-MM-DD`, a digest was shown on -- so an app left open all day gets
   * one notification and not one every time it regains focus. Cleared by nothing: a new day is a
   * new value, and there is no cross-setting rule here the way there is for the updater, because
   * turning reminders off already makes `dueNow` return null regardless of what this holds.
   */
  remindersShownOn: null,
  /**
   * How many times each family-book feature has been used on this machine, keyed by feature name
   * (`"book.export"`, `"book.template"`) -- the desktop half of `UsageLedger` (#156). Nothing
   * reads this yet; the shipped policy grants every feature in full, so no rule ever asks what it
   * says. It exists now so a future quota rule is a data change, not a new place to start
   * counting from zero for everybody already using the app.
   *
   * Local and resettable by design, same as the Android `SharedPreferences` copy: clearing this
   * file (or reinstalling) forgets the count. That is accepted as a *soft* allowance -- see
   * `docs/premium.md` -- not a security boundary this setting is pretending to be.
   */
  bookUsage: {},
});

/** The settings this app knows about, and what counts as a value for each. */
const SHAPE = {
  familyWords: (v) => (v === 'hi' ? 'hi' : 'en'),
  photosOnChart: (v) => v !== false,
  theme: (v) => (v === 'dark' ? 'dark' : 'light'),
  checkForUpdates: (v) => v === true,
  betaReleases: (v) => v === true,
  bookTemplates: (v) => v === true,
  templatesSeq: (v) => (Number.isFinite(v) && v > 0 ? Math.floor(v) : 0),
  lastCheckedAt: (v) => (Number.isFinite(v) && v > 0 ? Math.floor(v) : 0),
  skippedVersion: (v) => (typeof v === 'string' && v.trim() ? v.trim() : null),
  nearbySharing: (v) => v === true,
  /*
   * Sanitised here as well as by the facade, and by the same function, because this is the copy
   * that is written to disk and read back on every launch. A name that went in with a bidirectional
   * override in it would otherwise come back out with one -- and that override is the one character
   * that lets a device draw itself as somebody else's.
   */
  nearbyName: (v) => (typeof v === 'string' ? sanitiseName(v) : null),
  reminders: (v) => v === true,
  reminderRemembrance: (v) => v === true,
  reminderLead: (v) => (v === 'before' ? 'before' : 'day'),
  /** A real `YYYY-MM-DD` or nothing -- never trusted further than that, same as everything above. */
  remindersShownOn: (v) => (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : null),
  /**
   * Keys and values are both filtered rather than trusted, same as everywhere else here. The
   * result is always a fresh object, never the same reference twice -- see the note in `normalise`
   * about why that matters for this one setting in particular.
   */
  bookUsage: (v) => {
    if (!v || typeof v !== 'object' || Array.isArray(v)) return {};
    const out = {};
    for (const [feature, count] of Object.entries(v)) {
      if (typeof feature === 'string' && feature && Number.isInteger(count) && count >= 0) {
        out[feature] = count;
      }
    }
    return out;
  },
};

/**
 * A stored object made safe to use.
 *
 * Every value is put through its own rule rather than trusted, and anything unrecognised is
 * dropped. The file lives in a directory the reader can edit, and a settings file that has been
 * hand-edited into nonsense -- or written by a newer version of the app -- should give an app that
 * starts with sensible defaults rather than one that behaves strangely.
 */
function normalise(stored) {
  const raw = stored && typeof stored === 'object' ? stored : {};
  const out = {};
  for (const [key, clean] of Object.entries(SHAPE)) {
    // Every value is put through `clean`, even the default -- not just the ones present in `raw`.
    // Every other default here is a primitive, where handing out `DEFAULTS[key]` directly and
    // handing out `clean(DEFAULTS[key])` are the same thing. `bookUsage` defaults to `{}`, the one
    // object-valued setting, and `clean` is what guarantees a caller who mutates the object they
    // got back can never reach into `DEFAULTS.bookUsage` or into another caller's settings.
    out[key] = clean(key in raw ? raw[key] : DEFAULTS[key]);
  }
  return out;
}

/**
 * Applies a change, with the rules one setting has on another.
 *
 * Returns a whole new settings object rather than mutating, so the caller can compare and decide
 * whether anything needs writing or rebuilding.
 */
function applyChange(current, key, value) {
  if (!(key in SHAPE)) return normalise(current);

  const next = { ...normalise(current), [key]: SHAPE[key](value) };

  if (key === 'checkForUpdates' && next.checkForUpdates === false) {
    // Leaving a remembered result behind would let a stale banner outlive the setting.
    next.lastCheckedAt = 0;
    next.skippedVersion = null;
    // `templatesSeq` is deliberately left alone -- see its note in DEFAULTS. It is the one thing
    // here that must survive a switch, because forgetting it is what a replay would need.
  }

  if (key === 'betaReleases' && next.betaReleases !== normalise(current).betaReleases) {
    /*
     * A version skipped on one channel means nothing on the other.
     *
     * Somebody who skipped 0.5.0 on the stable channel and then asks for betas is asking to be
     * offered what is new. Leaving the skip behind would silently withhold the first release they
     * turned this on for, and they would have no way of telling why.
     */
    next.skippedVersion = null;
  }

  return next;
}

/**
 * Whether the updater may make a request at all.
 *
 * The beta setting is separate from this rather than being a third state of it, because they answer
 * different questions: whether the app may ask GitHub anything, and which answer it will accept.
 * Turning betas on while checking is off does nothing, which is the honest arrangement -- no
 * setting here can start a request on its own.
 */
function mayCheckForUpdates(settings) {
  return normalise(settings).checkForUpdates === true;
}

/**
 * Whether the app may ask about book templates, or fetch one (#214).
 *
 * Gated on [mayCheckForUpdates] as well as its own switch, because it is the same request to the
 * same place: a reader who has not allowed the app to talk to GitHub has not allowed this either.
 */
function mayFetchTemplates(settings) {
  const s = normalise(settings);
  return s.checkForUpdates === true && s.bookTemplates === true;
}

/** Whether a found version should be offered, or has already been declined. */
function shouldOffer(settings, version) {
  const { skippedVersion } = normalise(settings);
  return !skippedVersion || skippedVersion !== version;
}

module.exports = {
  DEFAULT_SETTINGS: DEFAULTS,
  normalise,
  applyChange,
  mayCheckForUpdates,
  mayFetchTemplates,
  shouldOffer,
};
