/*
 * The only bridge between the page and the machine.
 *
 * The renderer is the website's viewer, loaded from disk and running with node integration off,
 * so it cannot reach the filesystem on its own. Everything it is allowed to do is named here and
 * nowhere else: choose a file, read the one it was given, remember which one that was. A tree is
 * somebody's family, and the reason the app never uploads it is the same reason this list is
 * short and explicit rather than a general-purpose `fs`.
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('ftreeDesktop', {
  platform: process.platform,
  version: () => ipcRenderer.invoke('app:version'),

  /**
   * What the reader has chosen, and one way to change it.
   *
   * `set` returns the settings as they *became*, not as they were asked to be. Two reasons: the
   * cross-setting rules can change more than the one key that was set -- turning updates off also
   * clears what the last check found -- and turning betas on asks a question the reader can answer
   * no to. A dialog that assumed its own value would then show the opposite of the truth.
   */
  /**
   * True only when the app was started by the smoke harness.
   *
   * The page hangs a few test hooks on `window` when this is set -- reaching into module scope from
   * `executeJavaScript` is otherwise impossible, because app.js is a module. Read from the
   * environment of the *main* process, which a page cannot set, so a build somebody is using never
   * carries them.
   */
  smoke: Boolean(process.env.FTREE_SMOKE),

  /** Opens the picture picker and hands back the bytes, or null if nobody chose one. */
  choosePhoto: () => ipcRenderer.invoke('photo:choose'),

  settings: () => ipcRenderer.invoke('settings:get'),

  /**
   * Told whenever a setting changes, whoever changed it.
   *
   * The native menu can change these without the page knowing, and so can another window. One
   * value that is one per app should not become one copy per surface.
   */
  onSettingsChanged: (fn) => ipcRenderer.on('settings:changed', (_event, next) => fn(next)),
  setSetting: (key, value) => ipcRenderer.invoke('settings:set', { key, value }),

  /**
   * This installation's own id, stamped into a tree started here.
   *
   * Not cosmetic: a tree written with an empty origin cannot be told apart from every other
   * desktop's, and importing two of them into one another merges strangers. See identity.js.
   */
  installationId: () => ipcRenderer.invoke('app:installationId'),

  /** Opens the system file picker and returns { name, bytes } or null if it was cancelled. */
  chooseTree: () => ipcRenderer.invoke('tree:choose'),

  /**
   * Picks a second tree to merge into the one already open.
   *
   * Separate from `chooseTree` because the two are different acts: opening replaces what is on
   * screen, importing adds to it, and only opening should be remembered for next launch.
   */
  chooseImportTree: () => ipcRenderer.invoke('tree:chooseImport'),

  /** The tree opened last time, reopened on launch so the app starts where it was left. */
  lastTree: () => ipcRenderer.invoke('tree:last'),
  forgetTree: () => ipcRenderer.invoke('tree:forget'),

  /**
   * Saves a tree the page has already serialised and verified.
   *
   * Bytes, never a document: the page owns the format -- the writer, the reader it checks itself
   * with, and the tree. Sending a document for the other side to encode would put a second
   * encoder in the app and leave the verification checking something other than what gets
   * written. The main process backs up what was there (backups.js), writes to a temporary file,
   * fsyncs it and renames atomically over the target.
   *
   * `quiet` is autosave's: a failure comes back as `{ ok: false, reason, code }` for the page to
   * show in its own bar, rather than as a native error dialog every few seconds.
   */
  saveTree: (bytes, path, { quiet = false } = {}) => ipcRenderer.invoke('tree:save', { bytes, path, quiet }),
  saveTreeAs: (bytes, suggest) => ipcRenderer.invoke('tree:saveAs', { bytes, suggest }),

  /**
   * How a save the quit prompt asked for ended: 'saved', 'cancelled' or 'failed'.
   *
   * The prompt used to poll for the tree turning clean and give up after ten seconds -- which, for a
   * new tree, is while the reader is still choosing a folder in the Save dialog.
   */
  reportSaveOutcome: (outcome) => ipcRenderer.send('tree:saveOutcome', outcome),

  /** Lets the window refuse to close on unsaved work, and marks the title bar as edited. */
  setDirty: (dirty) => ipcRenderer.send('tree:dirty', dirty),

  /** The menu and the OS both open files; the page hears about it the same way either way. */
  onOpenTree: (handler) => ipcRenderer.on('tree:opened', (_e, tree) => handler(tree)),
  onMenuCommand: (handler) => ipcRenderer.on('menu:command', (_e, command) => handler(command)),

  /**
   * Nearby sharing, as one object rather than a dozen loose names.
   *
   * Everything here is a *request*. The page cannot open a socket -- the viewer session refuses the
   * network outright, and nothing below hands it one. It asks the main process, which checks what
   * it was asked (the address, the device, the counts, the import problem) before the facade in
   * `nearby/` hears of it, and it is told what happened through `onEvent`.
   *
   * Bytes go one way only, and only the page's own: `send` takes what the page's writer produced and
   * verified, as saving does. What arrives comes back as bytes for the import review, the same
   * shape `chooseImportTree` returns, so the review needs nothing new.
   */
  nearby: {
    /** 'send' or 'receive'. Receiving makes this machine visible until `close`. */
    open: (mode) => ipcRenderer.invoke('nearby:open', mode),
    close: () => ipcRenderer.invoke('nearby:close'),
    /** The receive screen's code, asked for again whenever the last one is spent or expires. */
    qr: () => ipcRenderer.invoke('nearby:qr'),
    /** The generated device name, once nearby sharing is on; null while it is off. */
    identity: () => ipcRenderer.invoke('nearby:identity'),
    /** `{ bytes, counts, name }` and either `peerKey` from the list or a typed `address`. */
    send: (request) => ipcRenderer.invoke('nearby:send', request),
    confirmCode: (matched) => ipcRenderer.invoke('nearby:confirmCode', matched),
    accept: () => ipcRenderer.invoke('nearby:accept'),
    decline: () => ipcRenderer.invoke('nearby:decline'),
    cancel: () => ipcRenderer.invoke('nearby:cancel'),
    /** After the review has been opened or refused: `null`, or the importer's own reason. */
    importFinished: (problem) => ipcRenderer.invoke('nearby:importFinished', problem),
    /** Whether there is a tree to send, so the File menu can grey "Send" when there is not. */
    canSend: (can) => ipcRenderer.send('nearby:canSend', can),
    onEvent: (handler) => ipcRenderer.on('nearby:event', (_e, event) => handler(event)),
  },

  /**
   * The family book (#200, #207): a designed PDF, composed and previewed entirely on this page
   * (`renderer/book.js`, `site/book/`), turned into an actual PDF by a hidden, network-refused
   * window this side prints from Chromium's own pipeline.
   */
  book: {
    /**
     * The templates and the policy file, as JSON.
     *
     * Read from the staged `site/book/` rather than fetched: the page cannot `fetch()` over
     * `file://`, and asking the main process for bytes it already has on disk is simpler than
     * teaching the renderer a second way to reach the filesystem.
     */
    assets: () => ipcRenderer.invoke('book:assets'),
    /* Templates that arrive by download (#214). Each one is a deliberate act by the reader: the
     * dialog asks for a refresh when it opens the picker, and for a fetch only on a tap. */
    templateRefresh: () => ipcRenderer.invoke('book:templateRefresh'),
    templateFetch: (id) => ipcRenderer.invoke('book:templateFetch', id),
    templateRemove: (id) => ipcRenderer.invoke('book:templateRemove', id),
    /**
     * Prints the given pages -- already-fitted SVG, outerHTML, photographs already as `data:`
     * URLs -- to a PDF the reader chooses where to save. `{ path }`, or `{ canceled: true }`, or
     * `{ error }` with a message the dialog can show as its own.
     */
    save: (request) => ipcRenderer.invoke('book:save', request),
    showInFolder: (path) => ipcRenderer.invoke('book:showInFolder', path),
  },

  /**
   * Birthday reminders (#154): one notification a day, shown only while this window exists.
   *
   * The page owns the schedule (`renderer/reminders.js`'s `dueNow` and `digest`, driven by
   * `renderer/app.js`'s `checkReminders`) because it is the side that already holds the open tree;
   * this side only knows how to ask the OS to show a notification and hears back when it is
   * clicked.
   */
  reminders: {
    /** Whether this desktop can show a notification at all -- asked once, at boot. */
    supported: () => ipcRenderer.invoke('reminders:supported'),
    /** `{ title, body, personId }`, straight from `digest()`. */
    notify: (payload) => ipcRenderer.invoke('reminders:notify', payload),
    /** A shown notification was clicked: the person to open, or null to land on the band instead. */
    onOpen: (handler) => ipcRenderer.on('reminders:open', (_e, personId) => handler(personId)),
    /**
     * Smoke-only: moves the hour `dueNow` reads without moving the date, read from the *main*
     * process's environment so a build somebody is using never carries it. `dueNow` itself asks for
     * a `Date`, not an hour, so `app.js`'s `remindersClock` is what applies this before calling it --
     * see the note there for why the harness needs it at all: "at or past nine" cannot depend on
     * what hour a CI runner happens to be started at.
     */
    smokeHourOverride: process.env.FTREE_SMOKE_REMINDER_HOUR
      ? Number(process.env.FTREE_SMOKE_REMINDER_HOUR) : null,
  },
});
