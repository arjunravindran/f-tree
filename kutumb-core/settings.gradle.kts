// Standalone on purpose: pure Kotlin/JVM, no Android Gradle plugin, so it builds and tests anywhere
// a JDK and Maven Central are reachable. Run it with `../gradlew -p kutumb-core test`.
// It is also included from the root build, where this file is ignored and the root catalog applies.
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
    // The same catalog as the root build, so versions live in one place.
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "kutumb-core"
