# kutumb-core

Pure Kotlin/JVM logic for the Kutumb spec (`docs/kutumb/SPEC.md`): trust, recovery, game, sync outbox.
No Android or AndroidX dependency. Standalone Gradle build so it runs without the Android SDK.

    ./gradlew test        # from this directory

Packages under `com.vibethroughcode.ftree.kutumb`: `trust`, `recovery`, `game`, `sync`.

## For the Android Studio session

- **Wire-up:** add `include(":kutumb-core")` to the root `settings.gradle.kts` (and move its plugin/JUnit versions into the version catalog). Root CI runs `./gradlew testDebugUnitTest`, which will not cover this module until then.
- **Signature scheme:** `SignatureScheme` is implemented with JDK Ed25519. Nostr uses BIP-340 Schnorr/secp256k1, which the JDK lacks. A secp256k1 library has to be picked and a `SignatureScheme` written for it; nothing above the interface changes.
- **Not modelled (spec is silent/undecided):** points per correct answer (`FactResolver.DEFAULT_POINTS = 1`, a parameter), wrong-guess penalty, re-resolving a fact (refused as final), rotation for a person this device has no record of (rejected `UNKNOWN_PERSON`, so a `VOUCHED` contact is never created here), a vouch whose voucher rotated away before it arrived (rejected, apart from out-of-order batches).
- `FactAnswer.id` is added to the schema; `FactResolution.winningAnswerId` needs something to point at.
