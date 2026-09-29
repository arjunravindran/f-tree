# Keeps that exist ONLY so the instrumented tests can run against a minified build (#260).
#
# `testProguardFiles` applies these to R8's run for the test variant and never to the shipping
# APK, which is the whole point: the test harness needs classes the app itself never touches, and
# keeping them in a release people install would be paying for a test in every download.
#
# The runner dies before a single test starts without them - androidx.tracing.Trace first, then
# kotlin.LazyKt from androidx.test's own TestDirCalculator - because R8 strips from the app what
# only the test APK uses, and AGP omits from the test APK what the app's dependency graph already
# claimed. Without this, "release build" could never be exercised on a device at all, which is the
# half of #260's gate that would have caught #297 in a shipping build.
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class org.junit.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn androidx.test.**
-dontwarn org.junit.**
