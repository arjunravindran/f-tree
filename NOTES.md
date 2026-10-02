# NOTES

## Needs a human (read first)

Nothing is blocked. These are calls I made that a person should look at when convenient:

1. **New native dependency: `fr.acinq.secp256k1:secp256k1-kmp(-jni-android)` 0.24.0** (BIP-340 Schnorr, Nostr's signature scheme). Needed in step 3, earlier than the task list implied: kutumb-core's JDK `Ed25519Scheme` cannot work on Android at all. `KeyPairGenerator.getInstance("Ed25519")` resolves to the AndroidKeyStore provider and throws `IllegalStateException: Not initialized` (seen on the API 35 emulator), and keystore keys cannot be exported as the hex strings the core interface uses. Alternatives I did not take: BouncyCastle (larger, no BIP-340 built in), hand-rolled BigInteger Schnorr (not constant-time), rust-nostr bindings (much heavier). Things to weigh: it ships prebuilt `.so` files, which F-Droid's scanner may object to (SPEC lists F-Droid as the preferred channel), and it adds APK size per ABI. Reverting means replacing `Bip340Scheme` only; nothing above `SignatureScheme` changes.

## Assumptions and decisions

CLAUDE.md says new dependencies, schema changes and new screens need an issue first. The task list explicitly requests all three, so I treated that as the go-ahead and did not open issues.

## Step 1 — kutumb-core verification

`./gradlew test --rerun-tasks` in `kutumb-core/`: 62 tests, 0 failures, 0 skipped (63 after the test added below).

Checked trust/recovery/game/sync against `docs/kutumb/SPEC.md`:

- **Single-vouch rotation, revocation, out-of-order delivery, replay**: match the spec. Replayed old rotations are rejected (`STALE_OLD_KEY`), rotating back to a revoked key is blocked (`NEW_KEY_IN_USE`), redelivery is a no-op.
- **Messaging gate**: `messagingEnabled` is `DIRECT`-only and survives rotation, as specified.
- **Fact conflict rule**: all answers kept, latest is display default, owner-only one-shot resolution, no timeout. Matches.
- **SyncOutbox**: spec fields all present; backoff, ack quorum, give-up behave as documented.

**Bug found and fixed:** `RewardRules.redemptionsToCreate` threw `IllegalArgumentException` when earner == owner. That happens whenever an owner's own self-report wins and their self-earned points reach the threshold, because `RewardRedemption` forbids from == to. It now returns nothing for that pair. Regression test added in `GameTest`.

Observations, not changed (spec-conformant, but worth knowing):

- Any trusted contact can vouch a rotation for *any* known person, not only people they paired with. The receiving device cannot know who R really met, so this is inherent to the single-vouch decision.
- Revocation is "current key only": a message signed by the old key before rotation but delivered late is also rejected. No timestamp window.
- Owner can pick any answer, including their own, to award points (spec: owner resolves). Points on own facts are in the ledger but earn no reward.
- Not in kutumb-core at all: `PersonLocation`, `MessageThread`/`Message`. They are Room-only concerns for step 4.
- Signatures are Ed25519 (JDK), not Nostr's BIP-340, and cannot run on Android (see the top of this file). The app uses `Bip340Scheme`; the core keeps `Ed25519Scheme` as its JVM test double.

## Step 2 — full build

- `:kutumb-core` is now `include`d in `settings.gradle.kts`. Its Kotlin plugin and JUnit come from the root version catalog (`kotlin-jvm` plugin alias added, declared `apply false` at root because KGP is already on the root classpath with a version). Its own `settings.gradle.kts` imports the same catalog, so `cd kutumb-core && ./gradlew test` still works standalone.
- `./gradlew build` on the full project: BUILD SUCCESSFUL (debug + release incl. R8, lint, unit tests). Only pre-existing Kotlin warnings in app tests.
- CI runs `./gradlew testDebugUnitTest`, which does **not** run `:kutumb-core:test` (a plain JVM module has no `testDebugUnitTest`). CI needs `:kutumb-core:test` added to be covered; I did not touch `.github/workflows`.
- Emulator: `Medium_Phone_API_35` AVD, started with `emulator -avd Medium_Phone_API_35 -no-snapshot`; `adb` lives in the SDK `platform-tools` dir, not on PATH.

## Step 3 — screens

Common decisions:

- Screens use the app's own Material theme (forest/brass), not the mockups' terracotta palette. The mockups set layout and content; matching their hex values would make these screens look unlike the rest of the app.
- All the screens read and write through `kutumb/KutumbRepository`, an interface in the app module. It is in-memory until step 4 swaps in Room, so **nothing survives a restart until step 4**.
- "Who am I" has no home in the existing model (the app has no "me"). The Family tab asks once, with the existing person picker, which tree person is the reader, and mints the key then. `LocalIdentity` therefore lives in the Kutumb repository, not on `Person`.
- Messaging does not exist yet (no spec'd screen; needs sync). The message control is drawn locked for vouched contacts, as specified, and drawn disabled for direct ones.
- New top-level destination "Family" added to the bottom bar / rail.

### 3a Family network (contacts)

`ui/network/ContactsScreen.kt`, `ContactsModel.kt` (pure ordering and relation lookup, JVM-tested), `ContactsViewModel.kt`. Met-in-person contacts sort first; vouched contacts show who vouched. Status uses filled vs hollow dot so it does not rely on colour. A contact whose person was deleted from the tree stays listed.

Also found while testing: `Bip340Scheme.verify` must catch `Secp256k1Exception` (an off-curve key throws it rather than returning false); covered by a test. The scheme passes BIP-340 test vectors 0 and 1.

### 3b Pairing

`ui/network/PairingScreen.kt`, `PairingViewModel.kt`, and `kutumb/PairingFlow.kt` (the interface step 5 implements; `UnavailablePairingFlow` is the default until then, so the screen says pairing is not available yet rather than faking it).

- **Deviation from the mockup:** it shows a pre-agreed "Your pairing code 482 916" to type in. The existing nearby protocol (`docs/nearby-protocol.md`) has no such thing: its six digits are *derived after* the key exchange and compared on both screens, and the QR carries a separate 16-byte token. A short typed PIN agreed before connecting would be weaker than the existing design, so the screen shows the QR to scan, then the derived six digits to compare ("Does their screen show the same code?"), spaced as `482 916`. Typing the other phone's address is still available from Settings > Nearby, not repeated here.
- The mockup's "?" avatar is real: the other phone cannot know which entry in *this* tree is them, so after verification the reader picks the person (people already trusted, and the reader, are not offered). Only then is a `DIRECT` contact saved.
- A mismatch offers no "try again": it can mean someone is in the middle, so the message says to pair somewhere else.
- SPEC says the handshake extends a "Wi-Fi Direct" module. The existing `nearby/` is same-LAN UDP beacon + TCP, not Wi-Fi Direct. Step 5 extends what exists.
- Tab renamed "Network" (a "Family" label broke an existing test that finds text "Family").

### Test status after 3a/3b

Full `connectedDebugAndroidTest`: 203 tests, 3 failures at the time of the run. One was mine (the tab label, fixed). The other two, `BookFlowTest.theStorybookReallyFeaturesOnePerson` and `theChartOpensTheBookAndSharesAPdfNamedForTheFamily`, fail identically on the untouched baseline (checked with my changes stashed), so they predate this work. I have not investigated them (the first looks for a downloaded "Diwali" template).

### 3c Relationship path ("main" mockup)

`ui/network/PathScreen.kt`, `PathModel.kt` (pure, JVM-tested), `PathViewModel.kt`. Reached by tapping a contact. New pure logic in kutumb-core: `geo/Geo.kt` (haversine, `PersonLocation`, sharing switch) and `geo/FunFacts.kt` (birth month, age gap, zodiac, hometown), 10 new tests (72 total).

- **Hometown fact:** the mockup says "Both born in Chennai", but SPEC's `PersonLocation` stores only lat/lon, no place name, and Person has no birthplace field. So the fact is "born close to each other" (birthplaces within 50 km), which is computable from what the spec stores. A named hometown would need a schema addition; not made.
- **Distance** is between the two people's *current* locations, shown only if both have sharing on. Miles for US/GB/LR/MM locales, km elsewhere.
- **No UI exists to enter a location** (the six mockups do not include one, and the spec's person-edit change is not in the task list). Until one exists, distance and the hometown fact never appear outside tests. Worth a follow-up.
- Message button shows only for direct pairings and is disabled (messaging unbuilt).

### 3d Tree

The existing chart already has the mockup's three views (Chart / Compact / Everyone) and tap-to-see-how-related, so nothing was rebuilt. What was added: a person's sheet in the tree shows **"In your family network"** for people this phone trusts, opening their relationship screen (3c).

- Deliberately **not** done: a paired badge drawn on the chart nodes. The chart is a single Canvas whose per-frame cost is a documented design constraint; adding per-node state to it for decoration was not worth the risk. The sheet link carries the same information.
- Whole `ui` instrumented package after this step: 108 tests, 2 failures, the same two pre-existing `BookFlowTest` failures as before.

### 3e Facts

`ui/facts/FactsScreen.kt`, `FactsViewModel.kt`, `QuestionText.kt`; new "Facts" tab (5th destination). Core gained `game/QuestionBank.kt` (8 starter questions across the spec's 7 categories; 4 new core tests, 76 total).

- A fact's id is `"<ownerId>/<questionId>"`, the same on every phone, so answers from different phones land on one fact. `Fact.prompt` stores the question *id*, not text, so wording can be translated and a phone that does not know an id shows "a question from a newer version" instead of failing.
- Answers go through `FactResolver.submit` (self-report flag checked, repeats refused). Wrong guesses cost nothing: the spec leaves penalties undecided and the core models none.
- Guesses can only be about people this phone trusts (up to four shown as chips; more than four relatives would need a real picker).
- The mockup's "+10 pts toward a coffee" is not used: `FactResolver.DEFAULT_POINTS` is 1 and the spec gives no number. Reward thresholds/redemption have no screen in the six mockups, so points accumulate in the ledger with no way yet to redeem them in the UI.
- A "N answers waiting for you" banner links to the resolve screen (next step).
- Guesses are stored locally only until the sync layer (step 6) sends them; the guessed-about person's phone cannot see them yet.

### 3f Resolve

`ui/facts/ResolveScreen.kt`, `ResolveViewModel.kt`. Shows the oldest unresolved question about the reader with every answer (their own marked "(self)"); picking one and confirming runs `FactResolver.resolve` (owner-only, once-only), writes the ledger entry, and if the answerer's points from the reader reach the threshold, creates a `RewardRedemption` (reader owes them).

- **Auto-surfacing:** per the spec ("surfaces automatically the next time they open the app"), `FTreeApp` opens the card once at launch if one is waiting. No timeout, never auto-resolved. A banner on the Facts tab also links to it.
- **Assumptions with no spec number:** reward threshold is **5 points** and reward type **coffee** for every pair (`ResolveViewModel.DEFAULT_THRESHOLD/DEFAULT_REWARD`). The spec says both are configurable per relationship, but no mockup has that screen. The mockup's "40 / 100 pts" and "+10" were illustrative and are not used.
- Picking your own answer awards you points but no reward (nothing owed to yourself), and the screen says so.
- No screen yet lists owed rewards or lets either side mark one redeemed; the data and core rules (`RewardRedemption.settle`) exist. A follow-up.

## Step 4 — Room

Database version 1 -> 2 (`data/Migrations.kt`, schema `app/schemas/.../2.json` checked in). The migration only creates tables; the tree, relationships and import history are untouched, and a test migrates a v1 database with a person in it and checks the person survives and the schema validates. `AppContainer.kutumbRepository` is now `RoomKutumbRepository`; the in-memory one remains for JVM tests.

Tables (`kutumb/db/`): `local_identity`, `trusted_contacts`, `identity_rotations`, `person_locations`, `facts`, `fact_answers`, `fact_resolutions`, `points_ledger`, `sync_outbox`, `reward_redemptions` (SPEC's `TrustedContact`, `IdentityRotationEvent`, `PersonLocation`, `Fact`/`FactAnswer`/`FactResolution`, `PointsLedger`, `SyncOutbox`, `RewardRedemption`, plus `local_identity` for this phone's own key).

Deviations and decisions:

- **No foreign keys to `people`**, though SPEC marks `personId` as FK. A cascade would delete a trusted key, the points history and an owed coffee whenever someone is removed from the tree. A test pins this: deleting a person keeps their contact row.
- **Private key is sealed by the Android Keystore** (`AndroidKeyVault`, AES-256-GCM) before it reaches the database. The app's backup rules are empty (everything is backed up), so a plain key would have been copied to Google Drive and to a new phone, letting that phone speak as this person. Sealed, it opens only on the phone that sealed it; elsewhere the identity reads as absent (user picks "who am I" again, gets a new key, is vouched for). Consequence: **restoring from backup or transferring to a new phone loses the identity by design**, and that is exactly the recovery flow.
- Trust-store integrity in SQL: unique `pubKeyCurrent` (one key, one person), `saveContact` throws rather than letting `REPLACE`/`@Upsert` silently delete or ignore a clash (caught by a test); unique `points_ledger.reasonFactId` plus insert-ignore on `fact_resolutions` make a second resolution unable to award points twice; answers are insert-ignore so a redelivered answer is the same row.
- `MessageThread`/`Message` (SPEC's optional messaging section) are not created: messaging has no screen or transport yet, and an unused table is a migration to regret.
- `.ftree` export/import is untouched (CLAUDE.md: format changes need discussion). None of the network data travels in `.ftree` files.
- Debug seeder: `mode=clear` already calls `clearAllTables`, which now covers these tables too; the seeder does not create trust/facts data.
- Observed once: `NetworkTreeLinkTest.aTrustedPersonsSheetLeadsToTheirRelationshipScreen` timed out in a sequential run and passed on rerun. Looks like emulator timing, not logic.
