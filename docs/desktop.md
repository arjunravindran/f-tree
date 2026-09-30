# The desktop app

f-tree on Windows and Ubuntu. It reads a `.ftree`, and it writes one: a tree can be built here from
nothing by somebody who has never owned the phone app. Generations run as **rows**, ancestors at the
top, as the website viewer draws them — columns are the layout for a screen taller than it is wide,
and a laptop never is.

`desktop/` is an Electron shell around `site/playground/`. **One viewer, two shells**: the chart,
the index, the search, the kinship engine and the themes are the same files the website serves, so
a fix to the chart is a fix in both places rather than a fix and a note to remember the other one.
What the shell adds is what a browser tab cannot have — a real file picker, a native menu, and a
memory of which tree you were reading.

## Three ways to read a tree

| | | |
|---|---|---|
| **Chart** | `Ctrl+1` | The whole tree, drawn. Pan, zoom, search, click a person to edit them. |
| **People** | `Ctrl+2` | Everybody by name, grouped as the chart groups them, filterable to the living. The only place somebody with no recorded relatives is as visible as anybody else. Above the list, a **Coming up** band (#230) shows the next 30 days of birthdays, and a quieter **Remembering** group below them for the departed — see `docs/birthdays.md` for the rules and the reminder that reads the same list. |
| **Compact** | `Ctrl+3` | One person's family as generation bands, read as text at any size. |

Compact exists for the space between the other two. The chart is a picture: to read a name you
zoom, to reach a relative you pan, and a canvas holds nothing at all for a screen reader. The people
list is alphabetical and has no family in it. Compact is the missing middle — centred on somebody,
three generations up and down, with everybody's partners; deliberately **not** cousins or nieces,
which multiply a chart's width far faster than they add to what it tells you. Clicking a name walks
the reading to that person. Where the record goes further than the reading, it says so and offers
to go on.

## Saving: once, then automatic

A tree with a file is **written back to it about a second after each change**
([#149](https://github.com/thisisankit27/f-tree/issues/149)). The bar beside the file name says
where things stand:

| | |
|---|---|
| **Saved** | every change is on disk |
| **Saving…** | a change is in its pause, or being written |
| **Not saved yet** · *Save…* | a new tree that has never had a file. Save it once and it autosaves from then on |
| **Not saved** · *Retry* | a write failed. The reason is in a toast, once, and f-tree keeps retrying with backoff |

Changes are batched rather than written one by one (`renderer/autosave.js`). A write reads the
whole tree back to verify it (`renderer/save.js`), and a burst of edits needs one write, not ten. A
change that lands during a write is picked up straight after it. `Tree.markSaved` takes the
signature of what was *written*, so an edit made mid-write is never marked saved by mistake.

`Ctrl+S` still works, and just skips the pause. Closing the window first asks the page to write
anything still in its pause, and closes without a question if that was all. The quit prompt is left
for work that really isn't on disk: a new tree with no file yet, a person half-edited in the panel,
or a failed write. Opening or starting another tree asks the same question in those cases. Before
0.6 it quietly threw away a new tree that had never been saved.

The phone does not autosave either: its tree lives in a database, so there is never an unsaved tree
there. Writing back to the open file is the desktop's closest equivalent.

### Backups

Autosave makes a mistake permanent almost immediately, so earlier versions are kept
(`desktop/backups.js`), following Android's rule for pre-import backups: *keep a few, not a growing
pile*.

- One copy of the file as it was **before the first write of each run**, so opening a tree and
  spoiling it always leaves the version you opened.
- Then **at most one every ten minutes** while writes continue.
- The **newest five** per tree.

They live in the app's own data folder, one folder per tree. The folder is named after the file,
plus a hash of its path, so two `family.ftree`s never share one. Nothing is added beside your own
file. The old sibling `<name>.bak` is gone, because under autosave it would have been overwritten
every second. **File › Show backups** opens the folder. A backup is an ordinary `.ftree`: open it,
and use Save as to keep it.

## Editing a person

The person panel keeps its edits as a draft and writes them to the tree on **Save**, which is how
the phone's edit screen has always worked ([#148](https://github.com/thisisankit27/f-tree/issues/148)).
It used to commit each field as it lost focus. That meant nothing on screen said an edit had been
taken, and closing the panel with a field still focused depended on the browser firing `change`.

| | |
|---|---|
| **Save** | keeps the draft as one step in the history (the panel then says *Updated*); disabled while there is nothing unsaved |
| **Add** | the same button for somebody "Add a person" has just created, still blank |
| **Discard** | backs out of that addition. The blank card goes, with no trace in the history (`Tree.withdraw`) |
| **Delete this person** | for anybody already in the tree. Asks first when they have connections |
| leaving with edits | *Discard changes? / Your edits to this person won't be kept.*, in Android's words |

"Discard" is offered only while the person is a fresh, blank addition. On anybody else the button
removes them and every one of their connections, and "Discard" would read as "discard my edits",
which is not what happens.

**Deleting somebody with connections** offers Android's two choices: *Keep as unknown*, which clears
their details but keeps their place so the family still joins up (`Tree.clearDetails`), and *Delete
completely*. Somebody joined to nobody is deleted straight away. Either way the toast carries an
**Undo** button.

**Dates are typed into a segmented field**, `YYYY`-`MM`-`DD`, the hyphens drawn rather than typed
([#90](https://github.com/thisisankit27/f-tree/issues/90), `renderer/date-entry.js`). Digits fill a
slot and the caret moves on by itself — `19380417` fills all three in one go, and there is no
separator key to hunt for. A year alone (`1938`) or a year and month (`1938-04`) are whole answers on
their own. **Leaving the year blank records a birthday nobody remembers the year of** — `--04-17` — and
a death may not end before a birth that has a year to compare it against; overlapping partial dates
are fine, since "born 1938, died 1938" is real. Backspace in an empty slot steps back into the one
before it, so deleting runs back through the whole date one digit at a time. A death date ticks
*No longer living*, and unticking it clears the date.

Ctrl+Z inside a text field undoes the typing, not the last change to the tree. The menu owns the
accelerator, so without that exception it would take back a relative added a minute ago.

## Photographs

A face in the person panel, and **click it to see the photograph whole** — every other surface shows
it inside a circle, and a square cut from a group photograph is often the wrong square, so this is
where somebody checks what the file actually holds.

Add, replace or remove from the same panel. The picker returns **bytes, not a path**: a path is a
promise about somebody else's filesystem that this app cannot keep, and a tree full of pictures that
silently stop loading is worse than one with none.

The renderer does the encoding, and the numbers are not this app's to choose:

| | |
|---|---|
| square about the drag, longest edge | **512px** — `PhotoStore.STORED_EDGE` |
| JPEG quality | **85** — `PhotoStore.QUALITY` |
| smaller than 512 | left alone, never scaled up |

A desktop-written file has to be indistinguishable from a phone-written one, because the same tree is
carried back and forth and a photograph that changes size and weight every crossing is a file that
grows without anybody adding anything.

**Framing is not cropping.** The square is always the largest that fits, and dragging only chooses
where along the long edge it sits — enough to move a face out of a corner, and well short of
rebuilding an image editor. Arrow keys do it too. The circle drawn over the square is the shape every
surface will show it in; it is not stored that way, because a round image would need a PNG with an
alpha channel, several times the size, to save a shape that everything showing it already draws.

Stored as `photos/<uuid>.jpg` under a name nothing else in the tree is using — import brings
photographs in under names chosen by another machine, and two people sharing an entry means replacing
one person's face replaces the other's. `photosStillUsed()` prunes on save, so a removed photograph
stops riding along in every future write.

Honours **Photographs on the chart**: when it is off the chart is passed *no archive at all*, rather
than the real one and an instruction to ignore it.

## Family words: English or हिन्दी

Set in Preferences. When it is हिन्दी, an answer in the relation panel gains a second line under the
English one — `word (gloss)` — and **the English wording does not change anywhere**.

Under rather than instead, because the two are not the same statement. English says "uncle"; Hindi
says *which* uncle. A reader who set this preference is usually the one being asked to explain the
word to somebody else, and `दादी (father's mother)` is how a bilingual family actually says it.

Hindi has five words where English has one, and none of them can be reached by translating the
English. The choice reads `relate(...).kinship`: which parent the line went up through, who it came
back down through, and — for चाचा against ताऊ — which of the two was born first. That is why the path
model exists at all.

| | |
|---|---|
| `site/playground/kinship-hindi.js` | which word, ported from `graph/HindiKinship.kt` |
| `site/playground/kinship-hi.js` | how each of the 65 terms is spelled and what it means |

**Null is a real answer.** Hindi has no single word for a second cousin, or a relative through two
marriages. Where it has none the panel says the English sentence it would have said anyway — which is
what a Hindi speaker does in the same conversation, and better than a Devanagari compound nobody says.

Where the record cannot settle a birth order, the word is the descriptive one — पिता के भाई, which is
exactly what he is — and the panel says which two birth years would sharpen it. It does not guess:
ताऊ and चाचा are told apart by nothing but a date, and being wrong is noticed immediately.

Three tests hold it: the 43-case table from `HindiKinshipTest.kt`; a parity test that reads
`app/src/main/res/values/kinship_hi.xml` and asserts the JS table matches it exactly in both
directions; and the every-pair sweep, which cannot be satisfied by luck — *if B is A's मामा then A
must be B's भांजा or भांजी*, computed independently from opposite ends of the graph.

## Settings

The gear on the bar, `Settings > Preferences…`, or `Ctrl+,`: family words, photographs on the chart, appearance, the
two update settings, birthday reminders, and nearby sharing. The three that reach a network — the two update settings
and nearby sharing — are also checkboxes in the native `Settings` menu, and **both surfaces go through
the same `settings:set`**, which rebuilds the menu from the result.

That rebuild is the point rather than a detail. A native menu checkbox's `checked:` is a snapshot
taken when the menu was built: one surface over one value is fine forever, and two is fine until
somebody uses the second one. Before this, switching betas on in a dialog would have left the menu
saying they were off until the app restarted.

The rules live in `desktop/settings.js` — pure, no disk and no Electron, ported from
`update/UpdatePreferences.kt` with its reasoning. Some settings are not independent of each other:

| | |
|---|---|
| turning update checking **off** | clears the last-checked time and any skipped version — *"leaving a remembered result behind would let a stale banner outlive the setting"* |
| changing **channel** | clears the skipped version — *"a version skipped on one channel means nothing on the other: leaving it behind would silently hide the first release the reader has just asked to be offered"* |

Both `lastCheckedAt` and `skippedVersion` are new here. The desktop stored neither, so its update
dialog's "Not now" remembered nothing and the same release was offered on every launch until it was
taken — which teaches people to dismiss the dialog unread, and then the one release that matters is
dismissed the same way. **Skip this version** is now a separate answer from **Not now**: one is about
today, the other about this release. Only the automatic check honours a skip; asking from the menu
always gets an answer, because a question deserves one.

The three settings that reach a network — the two update switches and nearby sharing — are off until
switched on. Nothing leaves the device except when you deliberately send it, to a device you can see,
on a network you are already on, with no account and nothing in between. A default of "on" would
quietly make that untrue for everybody who never opened the menu. Betas and checking are separate settings rather than three
states of one, because they answer different questions — whether the app may ask GitHub anything,
and which answer it will accept — so betas with checking off makes no request at all, and the
checkbox is greyed rather than merely useless.

The theme lives in that file too, with everything else. `localStorage` keeps a mirror of it, and
only for the inline script that sets the theme before the first paint: the settings file is read
over IPC and there is no asking it anything that early. A cleared mirror costs one launch in the
system's colours, not a lost setting.

**Reminders** (#154) are off until switched on, the same as the other three, but for a different
reason: they reach nobody outside the machine, so being off by default is not about a network at
all, it is that a notification nobody asked for is a surprise rather than a quiet default. Two more
settings under it only mean anything once it is on — **When** (on the day or the day before) and
**Remembrance days**, whether the digest also covers the departed — the same dependent relationship
`betaReleases` has on `checkForUpdates`: a select and a checkbox that stay in the document, disabled,
rather than the dialog having to explain their absence. Turning the switch off needs no
cross-setting rule the way the updater does, because there is no remembered state left over that
could act on its own — see `docs/birthdays.md` for the rules a reminder and the "coming up" list
both read, and `desktop/renderer/reminders.js` for when one actually fires.

## How two people are related

The bar's relation button, `View > How are two people related?`, `Ctrl+R`, or the `R` key. Pick two people; the answer is a
sentence, a chain of people you can click through, and the chart cut down to just that line, with the
whole tree back when the question is closed. It is seeded from whoever is selected, because "how is
*this* person related to…" is the question somebody has in mind when they reach for it.

The engine is `relate` in `site/playground/model.js` — the same one the website uses, held to
`kinship-golden.txt`. It walks marriages and adoptions as well as blood, so it answers questions no
blood-only search can: "my wife's mother" is exactly what this feature gets asked.

Three sentences, and sometimes none:

| | |
|---|---|
| a blood relationship | *"Priya is Ankit's **first cousin**."* |
| a marriage at the far end | *"Madhu is married to Ankit's **first cousin once removed**."* — because "Ankit's first cousin once removed's wife" is a possessive chain nobody says out loud |
| a relative of somebody's spouse | *"Rekha is the **mother** of Ankit's wife."* |
| none of those | no sentence at all — the chain says it exactly, and a sentence amounting to "these two are related somehow" tells a reader nothing the chain does not |

It is reached four ways. The bar's **Find a relation** button (Android's `compare_arrows` mark and
its `relation_find` words) starts a fresh question. **How are we related?** starts from somebody
already on screen, which is the commoner form of the question
([#151](https://github.com/thisisankit27/f-tree/issues/151)): from the person panel, from any row of
the people list, and from the person the compact view is centred on. Starting from a person puts
them in the first slot even if an earlier question left somebody else there, and empties the second
for you to fill.

For a while the bar deliberately had no button for it. Measured on a 1095px window, one more icon
took the header from 56px to 93px because the row wrapped. #150 removed Undo, Redo and Save from the
bar. Saving is automatic, and undo lives in Ctrl+Z, the menu, and an Undo button in every toast that
announces something undoable. That freed the room, and the bar now also has **Preferences**.
Re-measured: one 56px row from 1440 down to 880px. Below 1240px the file name moves to the window's
title bar, and the bar keeps the save state.

The rules about who appears live in `site/playground/focus.js` and `site/playground/compact.js`,
ported from `graph/TreeLayoutEngine.kt` and `graph/CompactFamily.kt` with their test tables. On
Android, compact is derived from the focused chart, so the two cannot disagree; the desktop's chart
is the whole tree and there is no focused chart here, so compact makes that selection itself. That
is a deliberate difference, and the reason the selection rules sit in one shared module.

## Nearby sharing

**File › Send to a nearby device** and **File › Receive from a nearby device** move a tree between two
devices on the same Wi-Fi, directly, with nothing uploaded anywhere
([#166](https://github.com/thisisankit27/f-tree/issues/166)). The wire is specified in
[`nearby-protocol.md`](nearby-protocol.md); the Electron side of it is `desktop/nearby/`, behind one
facade (`nearby/index.js`), which is the only file `main.js` requires from there.

| | |
|---|---|
| **Off until switched on** | Preferences › Nearby sharing, the menu checkbox, or the dialog's own *Turn on*. Off, nothing is constructed: no socket, and no device id on disk. |
| **Visible only while the dialog is open** | Receiving makes this machine visible; closing the dialog, for any reason, gives every socket back. There is no "always visible" and no "accept without asking" ([#171](https://github.com/thisisankit27/f-tree/issues/171)). |
| **Sending only looks** | The send screen browses for receivers without announcing itself, so a machine trying to send never appears in anybody's list. |
| **Six digits, compared** | Both screens show the same code; the sender says whether they match. *No* stops everything, and is worded as what it means: somebody may be in the middle. |
| **It ends in the review** | An arrived file is read, deleted from the app's folder, and handed to the same import review File › Import opens. Nothing about the family is decided by nearby code. With nothing open, it opens as an untitled tree instead. |

The dialog is `renderer/nearby.js`; every sentence it shows is in `renderer/nearby-words.js`, whose
test fails when a reason in `nearby/problems.js` has no sentence. The page asks `preload.js`'s
`nearby` object for everything and cannot open a socket itself — the viewer session's
`refuseTheNetwork` is untouched, and `FTREE_SMOKE_NEARBY` asserts it from the page's side.

The device name is set in Preferences and committed when the field is left, never per keystroke,
because it is broadcast. The default is generated (*Quiet Heron*), never the hostname.

On a machine with several networks — Wi-Fi beside Docker bridges, Hyper-V switches or a VPN —
discovery announces on every real LAN adapter, each from itself and to its own subnet's broadcast,
and skips the virtual ones (`nearby/interfaces.js`). The address on the receive screen and in its QR
code is the adapter the system routes the local network through, never a container bridge.

### The QR code, and the file that is not ours

The receive screen shows its address as a QR code for a phone to scan. The encoder is **vendored,
unedited**: Kazuhiko Arase's [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator),
MIT, at `desktop/renderer/vendor/qr.js`.

| | |
|---|---|
| version | `qrcode-generator` 1.5.2, the file `qrcode.js` |
| fetched | `npm pack qrcode-generator@1.5.2`, matching the registry's `sha512-pItrW0Z9…Nw==`; jsDelivr serves the same bytes |
| SHA-256 | `18ae399f81182bc9de916e9c77b195df20cc58d6f2d55a62b085a299f1bf1780`, of everything after the provenance comment at the top of the file |

Vendored rather than written, because a subtly wrong QR encoder does not fail to draw: it draws a
code that scans as a *different string*, and in a pairing flow that is the worst available failure.
Vendored rather than an npm dependency, so `package.json` keeps no runtime dependencies. Kept
byte-identical so it stays diffable against upstream — `renderer/vendor/qr.test.js` hashes it and
fails on any edit. To update it, replace everything below the comment with the new upstream file and
change the version and hash in the comment, in the test, and here.

The same test holds the protocol's vector 15, *QR modules*: the link `vectors.txt` pins as
`qrlink | encode` draws exactly the matrix in `docs/nearby/qr-golden.txt` — the link on the first line,
then one row per line of `1` and `0`, quiet zone left out, so the Android suite can read the same file
and prove ZXing decodes the desktop's code as the link. That matrix was checked, when it was recorded, by
two decoders sharing no code with the encoder or each other (zxing-cpp and jsQR), both reading it back
as the link at error correction M, version 8. The code is drawn dark on white in both themes — many
scanners never try an inverted one — with the four-module quiet zone the standard asks for.

## Why Electron and not the app's own code

Compose Multiplatform would reuse the app's Kotlin, which is the better answer on paper. It is the
wrong one here: the app is `com.android.application` with Room, `Context`, `Intent` and Coil across
97 source files, and moving it into a multiplatform source set restructures the Gradle build of a
release that has live users on it.

The cost of the choice, stated plainly: this is a **second implementation of the app's behaviour**,
in a second language, kept in step by hand. Anything ported from Kotlin should come with the
Kotlin's own test cases so a divergence fails a test rather than surprising somebody's grandmother.

Installers are around 100MB. That is what Electron costs.

## The family book

**File › Make a family book…**, `Ctrl+P`, the toolbar's book icon beside Find a relation, or
**Family book from {first name}** in a person's panel (#200, #207) open a large dialog that
composes a designed PDF of the tree entirely on this machine. It is the desktop half of
[the family book](family-book.md); read that page first for the format, the composer and what the
dialog must show — this section is the Electron-specific half: how the same JavaScript composer
that runs in Android's hidden WebView turns into a real PDF here.

From desktop-v0.10.0-beta.1 a template can also arrive by download (#214). **Settings › Look for new
book templates** — off until switched on, and gated on update checking, because it is the same
request to the same place — lets the app fetch the signed catalogue, and nothing else. A template's
own file is fetched only when its chip is tapped, checked against the SHA-256 that catalogue vouches
for, and kept in `userData/templates`; *Remove copy* under the chosen chip deletes that copy and
returns the chip to *Download*. Heirloom arrived this way first and the storybook followed it, so
this release carries only the chart (#314) - which is the plain family tree, a few hundred bytes of
JSON, and enough that the book still works with no network and the switch off. `desktop/templates.js` holds the whole of it, verifying with
`node:crypto` against the key in `main.js`, and it reaches the network only through the two
functions `main.js` passes in — so `net.request` there is still the one way this app talks.

**The preview is the file.** `renderer/book.js` imports `site/book/compose.js` and `svg.js` as
ordinary ES modules — the same files `site/book/preview.html` uses in a browser — composes a `Book`
from the open tree, and paints every page's SVG straight into a scrolling column. Every option
(template, title, who's in it, photographs, full dates) recomposes the whole book, debounced by
150ms, so what is on screen is never an approximation of what gets saved.

**`printToPDF`, in a hidden, refused-network window.** Saving hands the *already-fitted* SVG pages
(`outerHTML`, after `svg.js`'s `fitText` has run against the loaded fonts) to `book:save`, which
writes them into a small HTML document — `@page { size: 595pt 842pt; margin: 0 }`, one `<div
class="page">` per page with `break-after: page`, and `.page:last-child { break-after: auto }` so
Chromium's print pipeline never adds a stray trailing blank page — and loads that document into a
`BrowserWindow` that is never shown: `show: false`, `sandbox: true`, no preload, and the *same*
partition the main window's session uses, so it inherits `refuseTheNetwork` rather than needing a
second copy of it. It renders a template's own art and a family's own names, which is exactly the
content that refusal exists for. `backgroundThrottling: false` matters here specifically: Chromium
throttles a backgrounded page's timers by default, and without this the wait for
`document.fonts.ready` below can stall in a way that is intermittent and hard to reproduce.

The document the print window loads also carries its own Content-Security-Policy —
`default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'` — on top of the
session-level network refusal above. Belt and braces: the session refusal is what actually stops
a request from leaving, but the CSP means the page cannot even *ask* for anything beyond an inline
style and a `data:` image or font, which is all a page built entirely from inlined SVG and
`data:`-URL fonts ever needs.

**Fonts, and the Devanagari gotcha.** The three book fonts (`docs/fonts.md`) are inlined as `data:`
URLs the same way Literata and JetBrains Mono are (`bookFontCss`, beside `localFontCss`) — read
once from the same `app/src/main/res/font/` directory the existing `*.ttf` packaging glob already
copies. `document.fonts.ready` resolving is not proof a font's Devanagari shaping data has actually
loaded — a font can report ready before that finishes — so the print window also awaits
`document.fonts.load` for each face against a real Devanagari sample, not only the Latin family
name, before `printToPDF` is called.

**Photographs never touch the composer.** It only ever asks for `{id, px}` — a person and a size.
The renderer resolves that itself: it reads the archive's own bytes for that person's stored photo,
draws them onto a `<canvas>` at the requested size, and hands back a `data:image/jpeg` URL at
quality 0.86. That is also why the desktop's PDFs come out smaller than Android's lossless ones —
acceptable, and said plainly in `docs/family-book.md`.

**The policy gate runs before every compose**, exactly as [premium.md](premium.md) describes it:
`plan → decide → compose(allowance)`. A cheap first read of the family (`readFamily` with no
allowance) gets the generation count and the population `decide()` needs; whatever it grants —
Allowed, Limited, or Locked — is what `composeBook` actually receives, and a Limited or Locked
decision shows its reason in the dialog. `settings.bookUsage` is `UsageLedger`'s desktop half
(`desktop/settings.js`), and it is only ever incremented after a save actually succeeds.

**Saving** reuses `desktop/atomic.js`'s `writeTreeFile` for the PDF's bytes, not a second
temp-then-rename implementation — the function is bytes-onto-a-path, not tree-specific despite its
name, and getting crash safety right once is the point.

**Smoke-tested end to end**, behind `FTREE_SMOKE_BOOK`: opens the dialog from the menu, checks
every shipped template offers a chip, sets a Devanagari title and saves it to a real PDF
(`FTREE_SMOKE_BOOK_SAVE_TO`, the save dialog's own narrow seam), then reads that PDF back and
checks it starts with `%PDF`, that its page count matches the book's, that `/FontFile2` is present
and `/Type3` is absent — static, embedded, selectable fonts, never the outline form Skia's PDF
backend can fall back to for a variable font — and that the file stays well under the 10MB budget.

## Layout

| | |
|---|---|
| `desktop/main.js` | the shell: window, menu, file dialogs, the session file, the smoke test |
| `desktop/preload.js` | the only bridge between page and machine, and a deliberately short one |
| `desktop/nearby/` | nearby sharing's protocol and sockets, main process only, behind `nearby/index.js` |
| `desktop/renderer/book.js` | the family book dialog: compose, preview, and hand fitted pages to `book:save` |
| `desktop/renderer/vendor/qr.js` | the QR encoder, vendored unedited — see *The QR code* above |
| `desktop/build/icon.png` | the mark from the website, at 512px |
| `site/playground/*` | the viewer, carried into the package as a resource |
| `site/book/*` | the book's composer and SVG painter, carried into the package as a resource |

The page reaches the machine only through the names in `preload.js`. A tree is somebody's family,
and the reason the app never uploads it is the reason that list is short and explicit rather than
a general-purpose `fs`.

## It refuses the network

`refuseTheNetwork` cancels every `http`, `https` and websocket request in the session. Not a
promise in a policy — the request is refused, so the claim holds whatever the page's markup asks
for now or later, and the one request the viewer does make (Google Fonts, right for a web page and
wrong for this) is logged and dropped.

Literata and JetBrains Mono are therefore read from disk: the same two files the Android app ships,
carried in as a resource and injected as `@font-face`. Without them the app falls back to the
system serif and monospace, which is worth a line in the log and not worth refusing to open
somebody's family tree over.

## Which way the generations run

`layoutArchive(graph, { orientation })` takes `'rows'` (the website — a landscape reader for a
whole archive on a big screen) or `'columns'` (the desktop, and the Android app).

Only one engine exists. The ordering, the crossing reduction and the packing are about *which*
person sits where in a generation, a question with no direction in it, so the column layout is the
same engine run with the card turned on its side and the answer transposed at the end. The
connectors are measured last, in finished screen coordinates, from a single orientation-agnostic
routine — which is why the renderer draws plain segments and knows nothing about which way the
page runs.

`tools/check_layout.mjs` runs its invariants in **both** orientations.

## Running it

```sh
cd desktop
npm install
npm start
```

The smoke test starts the real app, opens a real `.ftree`, and asserts the bridge is reachable, the
tree arrived, and the generations run in columns:

```sh
FTREE_SMOKE=/path/to/tree.ftree npm run smoke
# FTREE_SMOKE_SHOT=/tmp/shot.png also writes a screenshot
```

A desktop app is the one thing in this repository that cannot be checked by reading it: the shell,
the preload bridge and the viewer only meet each other once a window exists.

Each feature section is its own switch on top of the base smoke test, run only when its variable is
set (`smoke-gates.test.js` fails if this list and the harness's own gates disagree). The family
book's is `FTREE_SMOKE_BOOK`, which opens the dialog from the menu, checks every shipped template
offers a chip, sets a Devanagari title, saves a real PDF to `FTREE_SMOKE_BOOK_SAVE_TO` — the same
kind of narrow, env-gated seam `FTREE_SMOKE_SAVE_TO` is above, since a native save dialog cannot be
driven from a script — and then reads that PDF back to check it starts with `%PDF`, has one page
per composed page, embeds real (`/FontFile2`) rather than outline (`/Type3`) fonts, and stays under
the 10 MB budget:

```sh
FTREE_SMOKE=/path/to/tree.ftree FTREE_SMOKE_BOOK=1 \
  FTREE_SMOKE_BOOK_SAVE_TO=/tmp/book-smoke.pdf npm run smoke
```

`FTREE_SMOKE_ARGV` is the exception in that it is a path, not a switch, and the same path must
also be the launch's trailing argument -- it checks that a tree named on the command line (what a
double-click in the file manager runs, #190) is the tree opened at start, rather than the last one
remembered in `session.json`. It must name a different tree from `FTREE_SMOKE`, which the harness
opens itself later and which would otherwise hide a failure:

```sh
FTREE_SMOKE=/path/to/a.ftree FTREE_SMOKE_ARGV=/path/to/b.ftree \
  npx electron . --no-sandbox /path/to/b.ftree
```

CI runs this beside every other feature section — see `.github/workflows/desktop.yml` for the full
set of `FTREE_SMOKE_*` variables it sets, once against the source tree and once against the packaged
app.

## Building installers

```sh
npm run pack:linux   # AppImage + deb
npm run pack:win     # NSIS installer, on Windows
```

CI does both, on `ubuntu-latest` and `windows-latest`, for every change to `desktop/` or to the
viewer.

## Linux packaging, and the AppImage problem

Three Linux targets, in the order the download page offers them:

| | |
|---|---|
| `.deb` | the ordinary answer on Ubuntu, Debian and Mint |
| `.tar.gz` | a portable folder: unpack anywhere, run it, install nothing |
| `.AppImage` | for people who prefer them, with a caveat |

**An AppImage will not start on Ubuntu 24.04 or newer by double-clicking.** Those releases replaced
the FUSE 2 helper the AppImage runtime needs: `/usr/bin/fusermount` is now a symlink to
`fusermount3`, which speaks a different protocol, and the failure is
`fusermount: file descriptor 5 is not a socket, can't send fuse fd` in a terminal or nothing at all
from the desktop. **Installing `libfuse2` does not fix it** — the library is present and the helper
is what is missing — so the advice found all over the internet is wrong for these releases.
`--appimage-extract-and-run` works, and the `.deb` and `.tar.gz` need nothing.

That is why the updater only offers an AppImage to somebody already running one, where it is known
to work because they are running it. A `.deb` install is told a new version exists and sent to the
download page, because installing one needs root and the app has no business asking for it.

## Releases, and why they are pre-releases

A desktop release is tagged `desktop-v<version>` and published as a **pre-release**. Neither is
cosmetic.

The Android updater reads this repository's releases and parses each tag with
`^v?(\d+(?:\.\d+)*)(?:-(.+))?$`. `desktop-v0.1.0` cannot match, so a desktop release is invisible
to the ordinary channel and to the beta channel alike. The pre-release flag is the second,
independent guard: `releases/latest` — the endpoint the ordinary channel reads — skips
pre-releases, so it goes on answering with the newest *app* release.

Publishing a desktop build as an ordinary release would make `releases/latest` return something
with no APK on it, and every phone checking for updates would quietly stop being offered any. Two
guards, because there are live users on the app.

A desktop release is promoted to stable with `gh release edit desktop-vX --prerelease=false
--latest=false` — both flags, every time. Desktop 0.6.0 was promoted without the second, so from
2026-09-11 `releases/latest` answered with a desktop release. It did no visible harm only because no
stable Android release had shipped since; the same slip after one had would have hidden it from
every phone. The app now also falls back to the full release list when `releases/latest` has
nothing it can install, deciding the way the beta channel does minus the pre-releases, so a phone on
this version or later can no longer be misled this way. Older installs still depend on the flag.

## The updater

Two switches under **Settings**, both off until the reader turns them on, and the same two the
Android app offers for the same reasons:

| | |
|---|---|
| Check for updates automatically | one quiet look at startup; silent unless there is something to say |
| Offer me beta releases | asks first, and states the consequence, as the app's dialog does |

`update.js` holds the decision and nothing else — which release, for which platform, on which
channel — as a pure function, so the awkward cases are settled by the table in `update.test.js`
rather than against the network. That is the mitigation promised in #89 for porting rules into a
second language: a divergence fails a test.

Two rules differ from the Kotlin on purpose, and both are commented where they live:

- Every desktop release is a GitHub *pre-release* by design, so `prerelease` cannot mean "beta"
  here. The **suffix on the tag** does: `desktop-v0.2.0` is stable, `desktop-v0.2.0-beta.1` is not.
- An empty candidate list means "up to date" rather than "nothing usable" when it was the channel
  filter that emptied it. A reader who has not asked for betas, on the newest stable build, is up
  to date; telling them the repository is broken would point at the wrong thing.

Downloads are verified against the SHA-256 GitHub publishes for the asset **before** anything is
offered to run; a mismatch deletes the file and installs nothing. On Windows the installer can be
launched from the app. On Linux the AppImage is downloaded and revealed — a `.deb` needs `apt` and
an AppImage is the reader's file to put where they want it, so the app does not pretend otherwise.

**The updater asks GitHub about releases and nothing else, and only when switched on;** the request
is made from the main process. Beyond that, nothing leaves the device except when you deliberately
send it, to a device you can see, on a network you are already on, with no account and nothing in
between — nearby sharing, below. The window stays refused outright either way, so nothing the page
contains can ever call out.

## What it deliberately does not have

[#89](https://github.com/thisisankit27/f-tree/issues/89)'s parity checklist is done, so this section
is no longer a list of things being built. One item on it was closed by deciding *not* to do it, and
that is worth keeping written down — otherwise it reads as an oversight and somebody adds it.

**The focused chart.** Android draws a second chart centred on one person, their ancestors and
descendants only, everybody else hidden. A phone needs it: at that width the whole tree is unreadable
and the ego-centric view is the only way to see a line at all.

A laptop is the opposite shape. The whole tree fits across it, which is why both **JavaScript** shells
— this app and the website viewer — draw generations as rows, while the Android app keeps columns for
the screen it is on. **Compact** already answers the want the focused chart exists for, in a form the
canvas cannot manage: text at any size, three generations up and down, and reachable by a screen
reader. Two views of one person's line, one of them worse, is not parity.

`collectFocused` in `site/playground/focus.js` is the Kotlin's `TreeLayoutEngine.collect`, ported and
tested, because Compact is built on it. The engine can do this; the shell chooses not to offer it as
a chart.

**Columns, in these two shells.** The Android app draws them and always has — that is the right
layout for a phone, and #89 reversed the *desktop*, not the app. What the JavaScript engine keeps is
the ability to draw either, with CI checking both orientations, so the one neither JS shell currently
ships is the one that cannot rot unnoticed.

**A tray icon or a login item, for reminders (#154).** Desktop reminders work only while f-tree is
open, plus a same-day catch-up the moment it opens or a tree is opened — never a process quietly
running in the background between launches. That is a deliberate line, not a gap waiting to be
filled: an app that starts itself at login and stays resident to fire an alarm is a much bigger
promise than "a note while you're using it", on every platform this ships for, and it is not the
promise this app has made anywhere else. `desktop/renderer/reminders.js`'s `dueNow` is built around
that constraint rather than around it being missing — see `docs/birthdays.md` for the rules it
shares with the "coming up" list and with Android's own reminder.
