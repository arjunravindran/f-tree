package com.vibethroughcode.ftree.book

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The one signature in the template scheme (#214), and the refusals around it.
 *
 * The keypair is generated here and the fixtures are signed here, so no key material and no signed
 * blob is committed to prove any of this - which is only possible because `TemplateManifest.verify`
 * takes the public key as an argument rather than reading `BuildConfig` itself.
 *
 * What a manifest's *listing* rules are is `BookCatalogTest`'s subject, held to the shared table.
 * This file asserts only the seam: that a format this app cannot draw does not reach the picker.
 */
class TemplateManifestTest {

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(bytes: ByteArray, pair: KeyPair = keys): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(bytes)
                sign()
            }
        )

    private fun manifest(
        seq: Long = 4,
        format: Int = BookCatalog.FORMAT,
        entries: String = """{ "id": "holi", "name": "Holi", "format": 2, "tier": "free", "sha256": "$HASH", "bytes": 812 }""",
    ) = """{ "format": $format, "seq": $seq, "templates": [$entries] }""".toByteArray()

    private fun refusal(body: () -> Unit): TemplateManifest.Refusal = try {
        body()
        fail("expected a refusal")
        error("unreachable")
    } catch (e: TemplateManifest.RefusedException) {
        e.refusal
    }

    @Test
    fun `a manifest signed by the pinned key is accepted, with its hashes`() {
        val bytes = manifest()
        val verified = TemplateManifest.verify(bytes, sign(bytes), publicKey, minSeq = 0)

        assertEquals(4L, verified.seq)
        assertEquals(listOf("holi"), verified.entries.map { it.id })
        assertEquals(mapOf("holi" to HASH), verified.hashes)
        assertEquals(mapOf("holi" to 812L), verified.bytes)
    }

    @Test
    fun `a manifest signed by a different key is refused`() {
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val bytes = manifest()

        assertEquals(
            TemplateManifest.Refusal.SIGNATURE,
            refusal { TemplateManifest.verify(bytes, sign(bytes, other), publicKey, minSeq = 0) },
        )
    }

    @Test
    fun `a manifest with no signature at all is refused`() {
        val bytes = manifest()

        assertEquals(
            TemplateManifest.Refusal.SIGNATURE,
            refusal { TemplateManifest.verify(bytes, "", publicKey, minSeq = 0) },
        )
    }

    @Test
    fun `bytes changed after signing are refused, even by one character`() {
        val signature = sign(manifest())
        val tampered = manifest(entries = """{ "id": "holi", "name": "Holl", "format": 2, "tier": "free", "sha256": "$HASH", "bytes": 812 }""")

        assertEquals(
            TemplateManifest.Refusal.SIGNATURE,
            refusal { TemplateManifest.verify(tampered, signature, publicKey, minSeq = 0) },
        )
    }

    @Test
    fun `a key that cannot be read is refused rather than trusted`() {
        val bytes = manifest()

        assertEquals(
            TemplateManifest.Refusal.KEY,
            refusal { TemplateManifest.verify(bytes, sign(bytes), "not-a-key", minSeq = 0) },
        )
    }

    @Test
    fun `a seq no newer than the one already verified is refused as a replay`() {
        val bytes = manifest(seq = 7)

        for (already in listOf(7L, 8L, 99L)) {
            assertEquals(
                TemplateManifest.Refusal.ROLLBACK,
                refusal { TemplateManifest.verify(bytes, sign(bytes), publicKey, minSeq = already) },
            )
        }
        // One higher is the whole difference between a replay and an update.
        assertEquals(7L, TemplateManifest.verify(bytes, sign(bytes), publicKey, minSeq = 6).seq)
    }

    @Test
    fun `a manifest with no seq, or a catalogue format this app does not read, is refused`() {
        val noSeq = """{ "format": ${BookCatalog.FORMAT}, "templates": [] }""".toByteArray()
        assertEquals(
            TemplateManifest.Refusal.MALFORMED,
            refusal { TemplateManifest.verify(noSeq, sign(noSeq), publicKey, minSeq = 0) },
        )

        val newerCatalogue = manifest(format = BookCatalog.FORMAT + 1)
        assertEquals(
            TemplateManifest.Refusal.MALFORMED,
            refusal { TemplateManifest.verify(newerCatalogue, sign(newerCatalogue), publicKey, minSeq = 0) },
        )
    }

    @Test
    fun `an entry the picker would offer with no hash to check fails the whole manifest`() {
        val bytes = manifest(entries = """{ "id": "holi", "name": "Holi", "format": 2, "tier": "free" }""")

        assertEquals(
            TemplateManifest.Refusal.MALFORMED,
            refusal { TemplateManifest.verify(bytes, sign(bytes), publicKey, minSeq = 0) },
        )
    }

    @Test
    fun `a template written for a format this app cannot draw never reaches the picker`() {
        val future = BookCatalog.MAX_TEMPLATE_FORMAT + 1
        val bytes = manifest(
            entries = """{ "id": "holi", "name": "Holi", "format": $future, "tier": "free", "sha256": "$HASH", "bytes": 812 }""",
        )
        val verified = TemplateManifest.verify(bytes, sign(bytes), publicKey, minSeq = 0)

        // Listed in the manifest, and deliberately not offered: BookCatalog is the one filter.
        assertEquals(listOf("holi"), verified.entries.map { it.id })
        assertTrue(BookCatalog.listing(verified.entries, "2026-09-30").isEmpty())
    }

    @Test
    fun `an asset is found in a release payload by name, and only over https`() {
        val body = """
            { "tag_name": "v0.12.0", "assets": [
              { "name": "templates.json", "browser_download_url": "https://github.com/x/y/releases/download/v0.12.0/templates.json", "size": 640 },
              { "name": "plain.json", "browser_download_url": "http://github.com/x/y/releases/download/v0.12.0/plain.json", "size": 10 }
            ] }
        """.trimIndent()

        assertEquals(
            "https://github.com/x/y/releases/download/v0.12.0/templates.json",
            TemplateManifest.assetUrl(body, TemplateManifest.MANIFEST_ASSET),
        )
        assertEquals(640L, TemplateManifest.assetSize(body, TemplateManifest.MANIFEST_ASSET))
        assertEquals(null, TemplateManifest.assetUrl(body, "plain.json"))
        assertEquals(null, TemplateManifest.assetUrl(body, "absent.json"))
        assertEquals(null, TemplateManifest.assetUrl("not json", TemplateManifest.MANIFEST_ASSET))
    }

    @Test
    fun `an asset is found in a list of releases too, which is what the beta channel reads`() {
        val body = """
            [ { "tag_name": "v0.12.0-beta.1", "assets": [
                { "name": "templates.json", "browser_download_url": "https://example.invalid/templates.json", "size": 1 } ] } ]
        """.trimIndent()

        assertEquals("https://example.invalid/templates.json", TemplateManifest.assetUrl(body, TemplateManifest.MANIFEST_ASSET))
    }

    private companion object {
        const val HASH = "0000000000000000000000000000000000000000000000000000000000000abc"
    }
}
