package com.vibethroughcode.ftree.update

import com.vibethroughcode.ftree.book.repoRoot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The claim that one file owns the network, kept true by a test rather than by a habit.
 *
 * Downloaded book templates (#214) were the first feature since the updater to want the network, and
 * they were built to borrow `UpdateClient` rather than open a connection of their own. Nothing
 * enforced that before; this does, because the honest version of "nothing else leaves the device" is
 * one a reader can check, and the way they check it is that there is only one place to look.
 */
class NetworkSurfaceTest {

    private val sources: List<File> = File(repoRoot, "app/src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun `only UpdateClient opens a connection`() {
        val opening = sources.filter { file ->
            file.readText().contains("openConnection(")
        }

        assertEquals(listOf("UpdateClient.kt"), opening.map { it.name }.sorted())
    }

    @Test
    fun `the app fetches from the two endpoints the updater names, and no others`() {
        // The build declares four addresses, and only two of them are fetched: `RELEASES_PAGE_URL`
        // and `SITE_URL` are handed to a browser, which is the reader leaving the app rather than the
        // app reaching out. Templates deliberately added no fifth: they are assets of the releases
        // the first two already describe.
        val fields = Regex("\"([A-Z_]+)\",?\\s*\\n?\\s*\"\\\\\"https://")
            .findAll(File(repoRoot, "app/build.gradle.kts").readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(setOf("UPDATE_RELEASE_URL", "UPDATE_RELEASES_URL", "RELEASES_PAGE_URL", "SITE_URL"), fields)
        assertEquals(
            listOf("UPDATE_RELEASES_URL", "UPDATE_RELEASE_URL"),
            fields.filter { File(repoRoot, "app/src/main/java/com/vibethroughcode/ftree/update/UpdateClient.kt").readText().contains(it) }.sorted(),
        )
    }

    @Test
    fun `a downloaded template is only ever fetched over https`() {
        // The scheme check lives in `UpdateClient.open`, and the asset lookup refuses a plain-http
        // download URL before it gets there: two refusals, because a template URL comes off the
        // network rather than out of the build.
        val manifest = File(repoRoot, "app/src/main/java/com/vibethroughcode/ftree/book/TemplateManifest.kt").readText()

        assert(manifest.contains("startsWith(\"https://\")")) { "the asset lookup must refuse a non-https download URL" }
    }
}
