package com.vibethroughcode.ftree.book

import com.vibethroughcode.ftree.update.ApkGuard
import com.vibethroughcode.ftree.update.UpdateException
import com.vibethroughcode.ftree.update.UpdateFailure
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The template download lifecycle (#214): check the catalogue, download one on a tap, remove a copy.
 *
 * The network is two lambdas, so this runs on the JVM with no device and no connection - and the log
 * of what they were asked for is what proves the behaviour that matters most here: **a check fetches
 * the catalogue and nothing else.**
 */
class TemplateDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    private var switchedOn = true
    private val seq = object : TemplateDownloader.SeqStore {
        override var value: Long = 0
    }

    /** What the fakes were asked to fetch, in order: the assertion that matters. */
    private val asked = mutableListOf<String>()

    /** Asset name to the bytes the "release" serves. */
    private val served = mutableMapOf<String, String>()

    /** Asset names that fail mid-transfer, as an interrupted download does. */
    private val interrupted = mutableSetOf<String>()

    private lateinit var directory: File

    private fun downloader(): TemplateDownloader {
        directory = folder.newFolder("templates-${counter++}")
        return TemplateDownloader(
            directory = directory,
            enabled = { switchedOn },
            seq = seq,
            publicKey = publicKey,
            fetch = {
                asked += "release"
                releaseBody()
            },
            transfer = { url, destination, _ ->
                val name = url.substringAfterLast('/')
                asked += name
                if (name in interrupted) throw UpdateException(UpdateFailure.TRUNCATED)
                val body = served[name] ?: throw UpdateException(UpdateFailure.SERVER)
                destination.writeText(body)
            },
        )
    }

    private fun releaseBody(): String {
        val assets = served.keys.joinToString(",") { name ->
            """{ "name": "$name", "browser_download_url": "https://example.invalid/$name", "size": ${served.getValue(name).length} }"""
        }
        return """{ "tag_name": "v9.9.9", "assets": [$assets] }"""
    }

    /** Publishes a catalogue of the given templates, signed, with each hash taken from its content. */
    private fun publish(seqValue: Long, vararg templates: Pair<String, String>, format: Int = 2) {
        val entries = templates.joinToString(",") { (id, body) ->
            """{ "id": "$id", "name": "${id.replaceFirstChar(Char::uppercase)}", "format": $format, "tier": "free",
                 "sha256": "${sha256(body)}", "bytes": ${body.length} }"""
        }
        val manifest = """{ "format": ${BookCatalog.FORMAT}, "seq": $seqValue, "templates": [$entries] }"""
        served[TemplateManifest.MANIFEST_ASSET] = manifest
        served[TemplateManifest.SIGNATURE_ASSET] = sign(manifest)
        templates.forEach { (id, body) -> served["$id.json"] = body }
    }

    private fun sign(text: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(text.toByteArray())
            sign()
        }
    )

    private fun sha256(text: String): String {
        val file = folder.newFile("hash-${counter++}")
        file.writeText(text)
        return ApkGuard.sha256(file)
    }

    private fun templateBody(id: String) = """{"format":2,"id":"$id"}"""

    @Test
    fun `a check fetches the catalogue and its signature, and no template at all`() = runTest {
        publish(1, "holi" to templateBody("holi"), "onam" to templateBody("onam"))
        val downloader = downloader()

        val result = downloader.refreshCatalogue()

        assertEquals(TemplateDownloader.Refresh.Updated(offered = 2), result)
        assertEquals(
            listOf("release", TemplateManifest.MANIFEST_ASSET, TemplateManifest.SIGNATURE_ASSET),
            asked,
        )
        assertFalse(downloader.fileFor("holi").exists())
        assertFalse(downloader.fileFor("onam").exists())
        assertEquals(1L, seq.value)
    }

    @Test
    fun `a template is fetched only when it is asked for, and then it is usable`() = runTest {
        publish(1, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()
        asked.clear()

        downloader.download("holi")

        assertEquals(listOf("release", "holi.json"), asked)
        assertTrue(downloader.fileFor("holi").isFile)
        assertEquals(templateBody("holi"), downloader.fileFor("holi").readText())
        assertTrue(downloader.status.value.isEmpty())
    }

    @Test
    fun `a template whose bytes are not the ones vouched for is refused and deleted`() = runTest {
        publish(1, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()
        // The catalogue still says what it said; the file served has been swapped.
        served["holi.json"] = """{"format":2,"id":"holi","extra":"substituted"}"""

        downloader.download("holi")

        assertEquals(TemplateDownloader.Status.Failed(UpdateFailure.CHECKSUM), downloader.status.value["holi"])
        assertFalse(downloader.fileFor("holi").exists())
    }

    @Test
    fun `an interrupted download leaves no template behind`() = runTest {
        publish(1, "holi" to templateBody("holi"))
        interrupted += "holi.json"
        val downloader = downloader()
        downloader.refreshCatalogue()

        downloader.download("holi")

        assertEquals(TemplateDownloader.Status.Failed(UpdateFailure.TRUNCATED), downloader.status.value["holi"])
        assertFalse(downloader.fileFor("holi").exists())
    }

    @Test
    fun `a newer catalogue that vouches for different bytes drops the copy on disk`() = runTest {
        publish(1, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()
        downloader.download("holi")
        assertTrue(downloader.fileFor("holi").isFile)

        publish(2, "holi" to """{"format":2,"id":"holi","v":2}""")
        val result = downloader.refreshCatalogue()

        assertEquals(TemplateDownloader.Refresh.Updated(offered = 1), result)
        // Back to being offered as a download, rather than drawn from bytes nothing stands behind.
        assertFalse(downloader.fileFor("holi").exists())
        assertEquals(2L, seq.value)
    }

    @Test
    fun `a withdrawn template is deleted and no longer listed`() = runTest {
        publish(1, "holi" to templateBody("holi"), "onam" to templateBody("onam"))
        val downloader = downloader()
        downloader.refreshCatalogue()
        downloader.download("holi")

        served.remove("holi.json")
        publish(2, "onam" to templateBody("onam"))
        downloader.refreshCatalogue()

        assertFalse(downloader.fileFor("holi").exists())
        assertEquals(listOf("onam"), downloader.verified()?.entries?.map { it.id })
    }

    @Test
    fun `an older catalogue is refused as unchanged, and the trusted one stays`() = runTest {
        publish(3, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()

        publish(2, "holi" to templateBody("holi"), "onam" to templateBody("onam"))
        val result = downloader.refreshCatalogue()

        assertEquals(TemplateDownloader.Refresh.Unchanged, result)
        assertEquals(3L, seq.value)
        assertEquals(listOf("holi"), downloader.verified()?.entries?.map { it.id })
    }

    @Test
    fun `a catalogue signed by another key is refused and replaces nothing`() = runTest {
        publish(1, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()

        publish(2, "holi" to templateBody("holi"), "onam" to templateBody("onam"))
        served[TemplateManifest.SIGNATURE_ASSET] = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                val other = KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()
                initSign(other.private)
                update(served.getValue(TemplateManifest.MANIFEST_ASSET).toByteArray())
                sign()
            }
        )
        val result = downloader.refreshCatalogue()

        assertEquals(TemplateDownloader.Refresh.Refused(TemplateManifest.Refusal.SIGNATURE), result)
        assertEquals(1L, seq.value)
        assertEquals(listOf("holi"), downloader.verified()?.entries?.map { it.id })
    }

    @Test
    fun `removing a copy frees the space and leaves the catalogue and the seq alone`() = runTest {
        publish(5, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()
        downloader.download("holi")
        asked.clear()

        downloader.remove("holi")

        assertFalse(downloader.fileFor("holi").exists())
        assertEquals(5L, seq.value)
        assertEquals(listOf("holi"), downloader.verified()?.entries?.map { it.id })

        // And it can be had again, which is the point of removal being about space and nothing else.
        downloader.download("holi")
        assertTrue(downloader.fileFor("holi").isFile)
        assertEquals(listOf("release", "holi.json"), asked)
    }

    @Test
    fun `with the switch off nothing is asked of the network, and what is downloaded still works`() = runTest {
        publish(1, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()
        downloader.download("holi")
        asked.clear()

        switchedOn = false
        assertEquals(TemplateDownloader.Refresh.Off, downloader.refreshCatalogue())
        downloader.download("onam")
        assertEquals(emptyList<String>(), asked)

        // The copy already on the device is still verified and still readable: turning the switch
        // off stops the network, it does not take a book away.
        assertTrue(downloader.fileFor("holi").isFile)
        assertNotNull(downloader.verified())

        // And the high-water mark survives the switch, so an older catalogue cannot be replayed.
        switchedOn = true
        assertEquals(1L, seq.value)
        publish(1, "holi" to templateBody("holi"), "onam" to templateBody("onam"))
        assertEquals(TemplateDownloader.Refresh.Unchanged, downloader.refreshCatalogue())
    }

    @Test
    fun `a release with no catalogue is not an error, and changes nothing`() = runTest {
        val downloader = downloader()

        assertEquals(TemplateDownloader.Refresh.None, downloader.refreshCatalogue())
        assertEquals(0L, seq.value)
        assertNull(downloader.verified())
    }

    @Test
    fun `a template written for a format this app cannot draw is never fetched`() = runTest {
        publish(1, "future" to templateBody("future"), format = BookCatalog.MAX_TEMPLATE_FORMAT + 1)
        val downloader = downloader()
        downloader.refreshCatalogue()
        asked.clear()

        downloader.download("future")

        assertEquals(emptyList<String>(), asked)
        assertFalse(downloader.fileFor("future").exists())
    }

    @Test
    fun `a catalogue swapped on disk for an older one is refused on read`() = runTest {
        publish(4, "holi" to templateBody("holi"))
        val downloader = downloader()
        downloader.refreshCatalogue()

        // A real, correctly signed catalogue - just an older one, put there behind the app's back.
        publish(2, "holi" to templateBody("holi"))
        File(directory, TemplateManifest.MANIFEST_ASSET).writeText(served.getValue(TemplateManifest.MANIFEST_ASSET))
        File(directory, TemplateManifest.SIGNATURE_ASSET).writeText(served.getValue(TemplateManifest.SIGNATURE_ASSET))

        assertNull(downloader.verified())
    }

    private companion object {
        var counter = 0
    }
}
