// Standalone on purpose: pure Kotlin/JVM, no Android Gradle plugin, so it builds and tests anywhere
// a JDK and Maven Central are reachable. Run it with `../gradlew -p kutumb-core test`.
// Wiring it into the root build (include(":kutumb-core")) is left for the Android Studio session.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kutumb-core"
