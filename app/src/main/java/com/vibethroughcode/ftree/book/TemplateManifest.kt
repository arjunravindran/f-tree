package com.vibethroughcode.ftree.book

import com.vibethroughcode.ftree.update.ReleasePayload
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The trusted mapping between a template's id and the bytes it is allowed to have (#214).
 *
 * There is **one** signature in the whole scheme, and it is over this file. A template is never
 * signed, never carries a key, and is never checked against a second key: its authenticity is the
 * SHA-256 that a manifest this key signed vouches for. So there is one signature check here, and
 * one hash check per template ([TemplateDownloader]), and no third mechanism to keep in step.
 *
 * The manifest is a `catalog.json` with two additions - a `seq` at the root and `sha256`/`bytes` on
 * each entry - which is why [BookCatalog] parses it unchanged: it reads `format` and `templates` and
 * ignores every other key, so the listing rules, including the `MAX_TEMPLATE_FORMAT` filter that
 * keeps a template this app cannot draw out of the picker, are the shipped catalogue's rules rather
 * than a second set written for downloads.
 *
 * What is signed is the file's **exact bytes**, so nothing here canonicalises anything: a canonical
 * form would be a second parser, and a second parser is a second thing to disagree.
 */
object TemplateManifest {

    /** The manifest's asset name in a release, and the detached signature beside it. */
    const val MANIFEST_ASSET = "templates.json"
    const val SIGNATURE_ASSET = "templates.json.sig"

    /** A verified manifest: what it lists, what each file's hash must be, and how new it is. */
    data class Verified(
        val seq: Long,
        val entries: List<BookCatalog.Entry>,
        /** id to the hash the manifest vouches for, lower-case hex. */
        val hashes: Map<String, String>,
        /** id to the file's size in bytes, which only helps a download report progress. */
        val bytes: Map<String, Long>,
    )

    /** Why a manifest was refused. Every one of these is a refusal, never a best-effort render. */
    enum class Refusal {
        /** The key could not be read, so nothing can be trusted. */
        KEY,
        /** A different key, no signature at all, or bytes that changed after signing. */
        SIGNATURE,
        /** Not a catalogue this app can read, or an entry with no hash to check its file against. */
        MALFORMED,
        /** As old as, or older than, a manifest already verified: a replay. */
        ROLLBACK,
    }

    class RefusedException(val refusal: Refusal, cause: Throwable? = null) : Exception(refusal.name, cause)

    /**
     * Verifies [manifest] against [signature] and [publicKeyBase64], then against [minSeq].
     *
     * Takes the key as an argument rather than reading `BuildConfig` itself, so the tests sign with a
     * keypair they generate and no key material is committed to prove any of this.
     *
     * @param manifest the file's exact bytes, which is what was signed.
     * @param signature base64, as `templates.json.sig` carries it.
     * @param minSeq the highest `seq` already verified; a manifest must beat it, not match it.
     */
    fun verify(manifest: ByteArray, signature: String, publicKeyBase64: String, minSeq: Long): Verified {
        val key = readKey(publicKeyBase64)
        if (!signatureMatches(manifest, signature, key)) throw RefusedException(Refusal.SIGNATURE)

        // Parsed only once the signature holds: an unverified manifest is bytes, not a catalogue.
        val root = runCatching { Json.parseToJsonElement(manifest.decodeToString()) as? JsonObject }
            .getOrNull() ?: throw RefusedException(Refusal.MALFORMED)
        val entries = BookCatalog.read(root) ?: throw RefusedException(Refusal.MALFORMED)
        val seq = root.number("seq") ?: throw RefusedException(Refusal.MALFORMED)
        if (seq <= minSeq) throw RefusedException(Refusal.ROLLBACK)

        val hashes = mutableMapOf<String, String>()
        val bytes = mutableMapOf<String, Long>()
        for (element in (root["templates"] as? JsonArray).orEmpty()) {
            val entry = element as? JsonObject ?: continue
            val id = entry.text("id") ?: continue
            if (entries.none { it.id == id } || id in hashes) continue
            // An entry the picker would otherwise offer, with no hash its file can be checked
            // against, fails the whole manifest instead of dropping a row: dropping it quietly
            // would be the one path that could put an unverifiable template in front of a reader.
            hashes[id] = entry.text("sha256")?.lowercase()?.takeIf(HEX::matches)
                ?: throw RefusedException(Refusal.MALFORMED)
            bytes[id] = entry.number("bytes") ?: 0L
        }
        if (hashes.size != entries.size) throw RefusedException(Refusal.MALFORMED)

        return Verified(seq = seq, entries = entries, hashes = hashes, bytes = bytes)
    }

    /**
     * The download URL of the asset named [name] in [releaseBody], or null when it is not there.
     *
     * Deliberately not `readRelease`/`chooseFrom`: those answer whether a release is newer than the
     * installed app and report `UpToDate` when it is not, which would hide the assets of the very
     * release this app is running. This asks a different question of the same payload, and reuses
     * its models rather than a second set.
     */
    fun assetUrl(releaseBody: String, name: String): String? =
        asset(releaseBody, name)?.takeIf { it.downloadUrl.startsWith("https://") }?.downloadUrl

    /** The size GitHub reports for [name], used only to report download progress. */
    fun assetSize(releaseBody: String, name: String): Long = asset(releaseBody, name)?.size ?: 0L

    private fun asset(releaseBody: String, name: String) = runCatching {
        val element = Json.parseToJsonElement(releaseBody)
        // `releases?per_page=20` answers with a list, `releases/latest` with one release.
        val first = (element as? JsonArray)?.firstOrNull() ?: element
        json.decodeFromJsonElement(ReleasePayload.serializer(), first)
    }.getOrNull()?.assets?.firstOrNull { it.name == name }

    private fun readKey(publicKeyBase64: String): PublicKey = runCatching {
        val der = Base64.getDecoder().decode(publicKeyBase64.trim())
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }.getOrElse { throw RefusedException(Refusal.KEY, it) }

    /**
     * ECDSA over P-256 rather than Ed25519, which `java.security` only learned at API 33 while this
     * app supports 26. Nothing else in the design depends on which curve signs the manifest.
     */
    private fun signatureMatches(manifest: ByteArray, signature: String, key: PublicKey): Boolean = try {
        val bytes = Base64.getDecoder().decode(signature.trim())
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(manifest)
            verify(bytes)
        }
    } catch (e: GeneralSecurityException) {
        false // A malformed signature is a failed one, not a crash.
    } catch (e: IllegalArgumentException) {
        false // Neither is one that is not base64.
    }

    private val HEX = Regex("^[0-9a-f]{64}$")

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
