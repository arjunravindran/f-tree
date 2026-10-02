# kutumb-core

Pure Kotlin/JVM logic for the Kutumb spec (`docs/kutumb/SPEC.md`): trust, recovery, game, sync outbox.
No Android or AndroidX dependency. Standalone Gradle build so it runs without the Android SDK.

    ./gradlew test        # from this directory

Packages under `com.vibethroughcode.ftree.kutumb`: `trust`, `recovery`, `game`, `sync`.

## For the Android Studio session

- **Wire-up:** `:kutumb-core` is included in the root `settings.gradle.kts` and the app depends on it. Its own tests run with `./gradlew :kutumb-core:test` (or `./gradlew test` inside this folder, with `JAVA_HOME` set). Whether root `testDebugUnitTest` also runs them has not been checked, so CI should call the module's `test` task explicitly.
- **Signature scheme:** the JDK has no BIP-340 Schnorr/secp256k1, so the app implements `SignatureScheme` as `Bip340Scheme` (secp256k1-kmp). The core's own tests use a stand-in scheme, and `sync/` takes ECDH as an injected `Ecdh`.
- **Not modelled (spec is silent/undecided):** points per correct answer (`FactResolver.DEFAULT_POINTS = 1`, a parameter), wrong-guess penalty, re-resolving a fact (refused as final), rotation for a person this device has no record of (rejected `UNKNOWN_PERSON`, so a `VOUCHED` contact is never created here), a vouch whose voucher rotated away before it arrived (rejected, apart from out-of-order batches).
- `FactAnswer.id` is added to the schema; `FactResolution.winningAnswerId` needs something to point at.
