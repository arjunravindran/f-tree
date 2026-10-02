# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Quick Reference

**f-tree** is a family tree app for Android, with companion desktop (Windows/Linux) and web viewers. It's built around modeling real families as graphs with optional fields, featuring pure logic for relationship rules and chart layout.

### Build & Run

```bash
./gradlew assembleDebug            # Build debug APK
./gradlew installDebug             # Install and run on connected device
./gradlew testDebugUnitTest        # JVM unit tests (pure logic)
./gradlew connectedDebugAndroidTest # Instrumented tests (needs device)
./gradlew lintDebug                # Lint checks
```

### Test Data

Skip manual form entry with the debug seeder:

```bash
# Deliberately awkward family: unknowns, two marriages, half-siblings, missing dates
adb shell am broadcast -a com.vibethroughcode.ftree.SEED \
    -n com.vibethroughcode.ftree/.debug.SeedReceiver --es mode family

# Large tree for perf testing (adjustable size)
adb shell am broadcast -a com.vibethroughcode.ftree.SEED \
    -n com.vibethroughcode.ftree/.debug.SeedReceiver --es mode large --ei size 2000

# Clear all data
adb shell am broadcast -a com.vibethroughcode.ftree.SEED \
    -n com.vibethroughcode.ftree/.debug.SeedReceiver --es mode clear
```

### Toolchain

| Component | Version |
|-----------|---------|
| JDK | 17+ |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.3.2 |
| Kotlin | 2.3.21 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

---

## Architecture

### Package Layout

The app is organized around a core principle: **anything worth reasoning about is pure logic, tested on the JVM without Android dependencies.**

```
app/src/main/java/com/vibethroughcode/ftree/
├── data/        Room entities, DAOs, repository, photo storage
├── graph/       Pure graph logic: traversal, kinship rules, chart layout
├── transfer/    .ftree format, export, import, duplicate matching
├── update/      Optional in-place updater (off by default, opt-in)
├── nearby/      Nearby sharing: discovery, handshake, transfer
├── ui/          Jetpack Compose screens (one package per feature area)
├── book/        Family book PDF generation (uses embedded WebView)
├── entitlement/ Policy switch for future premium features
└── reminders/   Birthday reminders
```

### Data Model

A **directed graph of people and typed edges**, because real families are not trees.

**Entities:**
- `people` — All descriptive fields (name, birth date, gender, photo) are optional. A null name represents an unknown person.
- `relationships` — Three types: `PARENT` (directed), `SPOUSE` (symmetric), `SIBLING` (symmetric, only for unknown parents). Symmetric edges stored in canonical id order for duplicate prevention via unique index.
- `person_origins` — Tracks import provenance so re-imports recognize people with certainty instead of guessing by name.

**Key design decisions:**
- **Siblings are derived in SQL**, not stored. They stay correct when a parent is added later; explicit `SIBLING` edges exist only when parents are unknown.
- **Partial ISO-8601 dates** (`1938`, `1938-04`, `1938-04-17`, `--04-17` for birthdays without year). Precision itself is the statement about what is known.
- **Enum names persisted by name**, not ordinals, so a new relationship type needs no migration and files from newer releases gracefully degrade.

### The Chart

The chart is **ego-centric and performant**:
- Draws one person's ancestors above, descendants below, siblings beside, everything else is one tap away.
- Only the current person's neighborhood is loaded, a generation at a time (batched queries).
- Everything painted into a **single `Canvas`** for thousands of people without lag.
- Pan/zoom state lives in float state read only in the draw lambda, so dragging re-runs only the draw phase.
- Photographs are decoded off the main thread at 160px in RGB_565 (~50KB each), held in snapshot state, capped to viewport size.

**Notation:**
- Dashed brass edges represent unrecorded names (gaps in family knowledge).
- Doubled rule shows marriage; children hang from one connector, half-siblings from their own.
- Circular portraits colored by gender when no photograph; framing is enforced (never partial crops).

### Compact View

A text-based alternative to the chart, composable rather than drawn:
- Same family layout as the chart (already calculated), just formatted as text.
- Generations run top-to-bottom; a tap re-centers on that person.
- No back button needed; the graph is symmetric, so every tap undoes the previous one.
- Built from the chart's layout output to ensure views never disagree about who is in the family.
- Tapping someone opens the same person sheet the charts open.

### Kinship Rules

Relationship names are computed from the path through the family graph:

- **English**: Two numbers (generations up to common ancestor, generations back down). Captures what English kinship terms actually express.
- **Hindi**: Five or more distinct words for what English calls "uncle" — side of family matters. The model carries `KinshipPath` alongside distances, recording genders and birth order where dates exist. `HindiKinship.kt` is pure, JVM-tested logic returning an enum; the words live in `res/values/kinship_hi.xml` for review by native speakers.

Each relationship must **agree with its opposite**: if B is A's "मामा" then A must be B's "भांजा" or "भांजी", validated by test across every pair.

### Testing Philosophy

Two classes of tests, matching the architecture:

1. **JVM unit tests** (`testDebugUnitTest`): Pure logic in `graph/` and `transfer/`. Fast, no device needed, most comprehensive coverage. The tricky parts (layout, kinship, duplicate matching) are here.
2. **Instrumented tests** (`connectedDebugAndroidTest`): Database, Room queries, Compose UI flows. Needs a connected device. Run locally before pushing if you touched `data/`, `transfer/`, or screens.

### No Dependency Injection Framework

[`AppContainer.kt`](app/src/main/java/com/vibethroughcode/ftree/AppContainer.kt) wires the repository by hand. For an app this size, a DI framework adds indirection without removing complexity.

---

## Key Design Decisions

These are **load-bearing** — several look surprising until you understand the constraint:

| Decision | Why |
|----------|-----|
| Directed edges, not GEDCOM's family/union table | Maps directly to "add a parent"; simplifies merge logic |
| Siblings derived, not stored | Stays correct as parents are added; avoids O(n²) edges |
| Partial dates, no approximate flag | Precision itself is the claim about what is known |
| Enum names persisted, not ordinals | Readable in DB and exports; stable across R8 minification |
| One Canvas for the chart | At hundreds of people, a Composable per node costs more than drawing |
| Compact derived from chart's layout, not re-walked | Two views cannot disagree; switching costs nothing |
| Backup file instead of transactional undo | Far simpler promise; cannot itself go wrong |
| KinshipPath carried alongside distances | English answers and web viewer unaffected by adding Hindi |
| Hindi rules return enum, not string | Puts the risky part under JVM test; words in a list reviewers check |
| Descriptive term vs. guessing चाचा/ताऊ | Guessing is wrong half the time; families notice immediately |
| Circular photos stored as squares | Framing enforced; you can't frame a crescent of empty space |
| Nearby sharing is file-based and stops | Everything that decides family behavior (matching, conflicts) handled by import logic that was already there |
| UDP beacon, not mDNS | ~80 lines each side, same logic both languages; mDNS has OS-specific quirks |

---

## Changes Requiring Discussion

Before opening a PR for these, open an issue first — to save your time:

- **New dependencies** (list is deliberately short: Compose, Room, kotlinx-serialization, Coil, Navigation)
- **Changes to `.ftree` format** or database schema
- **New screens or navigation destinations**
- **Anything touching `update/`** — the only networked code; its checks are security-relevant
- **Kinship rules** — changes must satisfy every ordered pair of a whole family

---

## Contributing Guidelines

### Quality Standards

A good PR:
1. **Does one thing** — a layout fix and a new setting are two PRs.
2. **Adds a test for logic that could be wrong** — especially in `graph/` and `transfer/`, where it costs nothing (no emulator needed).
3. **Says what it's for in user terms** — "Half-siblings drawn under wrong connector" beats "fix TreeLayoutEngine offset".
4. **Keeps notation consistent** — brass = unknown, doubled rule = marriage, dashed edge = unrecorded name. Same everywhere (chart, compact, web viewer).
5. **Matches surrounding style** — comment density and naming vary by file; match your target file, not a global standard.

### Branch and Commit Conventions

- **Branch names**: `feat/…`, `fix/…`, `chore/…`, `docs/…`
- **Commit messages**: Conventional (e.g., `fix(chart): …`) — checked by CI but not enforced by hook

### Where to Put New Code

- **Pure logic** (relationships, layout, matching): `graph/` or `transfer/` — JVM-tested, no Android deps.
- **UI**: `ui/` — one package per screen area.
- **Networking**: `update/` or `nearby/` — kept isolated so network access is auditable.

---

## Release Process

### Local Release Build

Requires a `keystore.properties` at repo root:

```properties
storeFile=/absolute/path/to/f-tree-release.jks
storePassword=…
keyAlias=ftree
keyPassword=…
```

```bash
./gradlew assembleRelease
```

### CI-Driven Release (Recommended)

1. Bump `versionName` in `app/build.gradle.kts`
2. Tag and push:
   ```bash
   git tag -a v0.2.0 -m "f-tree 0.2.0" && git push origin v0.2.0
   ```
3. CI (`.github/workflows/release.yml`) builds, signs, verifies, and attaches the APK to the release.

**Pre-releases (beta)**: Tag with a suffix, e.g., `v0.6.0-beta.1`. Matches `versionName` of `0.6.0-beta.1`. GitHub's `releases/latest` skips pre-releases; the updater reads that endpoint, so stable users don't accidentally get betas.

### Release Build Checklist

- **Always test the release build before publishing** — `R8` minification has broken the app before (it once renamed an enum that navigation and the database resolve by name).
- `app/proguard-rules.pro` documents what must be kept and why.
- The app installs in-place over the old version, preserving the family tree data.

---

## Browser Viewer & Desktop

The repo includes:

- **`site/`**: Landing page and the browser viewer (reads `.ftree` files, dependency-free).
- **`desktop/`**: Windows and Linux desktop app (Electron-based). Shares the nearby sharing protocol with Android.

Both use the same `.ftree` format (ZIP with JSON). The family book uses a **shared JavaScript composer** (`site/book/compose.js`) run as a script engine inside Android's WebView and in Electron on desktop — same output, no drift between shells.

---

## Debugging Tips

### Seeder Modes

- `mode=family` — Small awkward tree (good for chart testing)
- `mode=large --ei size N` — N people, good for perf work
- `mode=clear` — Wipe database

### Database Inspection

Room's autogenerated DAOs are in `data/`. Query the database on a connected device:

```bash
adb shell "sqlite3 /data/data/com.vibethroughcode.ftree/databases/ftree.db"
```

### Canvas Drawing

The chart lives in `ui/tree/ChartCanvas.kt`. State is read-only inside the draw lambda; panning re-runs the draw phase only.

### Photograph Pipeline

`ui/person/CircleCrop.kt` — circle-to-square framing logic (JVM-tested). Photographs are decoded in `ui/tree/ChartPhotos.kt` for the canvas, off the main thread at 160px.

---

## Common Tasks

### Adding a Relationship Type

1. Add to the enum in `data/Relationship.kt` (if it exists) or `graph/Kinship.kt`
2. Update Room queries in relevant DAOs — check `Relationship` table constraints
3. Add JVM tests in `graph/KinshipTest.kt` or relevant test file
4. Update the import/export logic in `transfer/` if it affects the `.ftree` format

### Adding a New Screen

1. Create a composable in `ui/<feature>/<FeatureScreen>.kt`
2. Update navigation graph (usually in a navigation module)
3. Add any required database queries to `data/`
4. Write instrumented tests if the screen interacts with data or complex state

### Fixing Chart Layout

Layout logic is in `graph/TreeLayoutEngine.kt` and related files. These are pure functions taking data, returning coordinates and edges. Add a test case in `graph/TreeLayoutTest.kt` reproducing the issue, then fix the logic.

---

## Resources

- [Architecture deep-dive](docs/architecture.md) — Read before proposing structural changes
- [Building and testing](docs/building.md) — Detailed build/test instructions
- [Data model](docs/data-model.md) — Tables and fields
- [`.ftree` format spec](docs/ftree-format.md) — Export/import, merge rules
- [Nearby protocol](docs/nearby-protocol.md) — Discovery, handshake, transfer
- [Family book](docs/family-book.md) — PDF generation, composer/painter split
- [Hindi kinship](docs/kinship-hindi.md) — Terms and rules
- [Contributing](CONTRIBUTING.md) — Good first issues, PR expectations
- [Security policy](SECURITY.md) — How to report vulnerabilities
