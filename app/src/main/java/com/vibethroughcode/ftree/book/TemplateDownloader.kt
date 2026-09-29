package com.vibethroughcode.ftree.book

import com.vibethroughcode.ftree.update.ApkGuard
import com.vibethroughcode.ftree.update.UpdateException
import com.vibethroughcode.ftree.update.UpdateFailure
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Book templates that arrive by download (#214): the catalogue, one template at a time, and removal.
 *
 * Two steps, and the second one is always a tap. A check fetches the signed catalogue and **nothing
 * else** - it never downloads a template, however small or however new. A template's own file is
 * fetched only by [download], which the picker calls when the reader chooses it. Neither happens
 * while the switch is off, and nothing here is ever called on a timer.
 *
 * The network is [fetch] and [transfer], passed in rather than reached for, so the only code in the
 * app that opens a connection is still `UpdateClient` - and so all of this can be tested on the JVM
 * without a device or a network. [TemplateManifest] owns the trust: this class decides *when* to
 * verify, never *whether*.
 */
class TemplateDownloader(
    private val directory: File,
    private val enabled: () -> Boolean,
    private val seq: SeqStore,
    private val publicKey: String,
    private val fetch: suspend () -> String,
    private val transfer: suspend (url: String, destination: File, expectedBytes: Long) -> Unit,
) {

    /** The `seq` high-water mark, as an interface so a test needs no `SharedPreferences`. */
    interface SeqStore {
        var value: Long
    }

    /** What a tile is doing, for the ids that are doing anything. */
    sealed interface Status {
        data object Downloading : Status
        data class Failed(val failure: UpdateFailure) : Status
    }

    /** What a catalogue check came to. Each case is one sentence in Settings. */
    sealed interface Refresh {
        /** The switch is off, or no key is pinned in this build: nothing was asked of the network. */
        data object Off : Refresh
        /** The release carries no template catalogue, which is a state of the repository, not an error. */
        data object None : Refresh
        /** Nothing newer than the catalogue already verified. A replay looks like this too, deliberately. */
        data object Unchanged : Refresh
        data class Updated(val offered: Int) : Refresh
        data class Failed(val failure: UpdateFailure) : Refresh
        data class Refused(val refusal: TemplateManifest.Refusal) : Refresh
    }

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** What each tile is doing. An id absent from the map is doing nothing. */
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()

    /** False when no key is pinned in this build, which is what keeps the feature out of the way. */
    fun configured(): Boolean = publicKey.isNotBlank()

    /**
     * Whether a template may be fetched at all: the switch is on and this build pins a key.
     *
     * What the picker asks before offering a template it does not have. A template already on the
     * device is not gated on this - turning the switch off stops the network, it does not take a
     * book away.
     */
    fun canDownload(): Boolean = enabled() && configured()

    /**
     * The catalogue this app has verified, or null when there is none it still trusts.
     *
     * Re-verified on every read rather than trusted for being on disk: the signature is checked
     * again, and the `seq` must still be at least the high-water mark, so a manifest swapped out
     * under the app - for an older real one included - is refused instead of read.
     */
    fun verified(): TemplateManifest.Verified? {
        if (!configured()) return null
        val manifest = File(directory, TemplateManifest.MANIFEST_ASSET).takeIf { it.isFile } ?: return null
        val signature = File(directory, TemplateManifest.SIGNATURE_ASSET).takeIf { it.isFile } ?: return null
        return runCatching {
            // minSeq is one below the mark, because the stored manifest is allowed to *be* the mark.
            TemplateManifest.verify(manifest.readBytes(), signature.readText(), publicKey, seq.value - 1)
        }.getOrNull()?.takeIf { it.seq >= seq.value }
    }

    /**
     * The file a downloaded template lives in, whether or not it is there yet.
     *
     * Prefixed, because an id is a catalogue id and `templates` is a legal one: without the prefix a
     * template called that would be stored over the catalogue itself.
     */
    fun fileFor(id: String): File = File(directory, "$CACHE_PREFIX$id.json")

    /**
     * Fetches and verifies the catalogue, and nothing else.
     *
     * The reconciliation afterwards is local only: a copy the new catalogue no longer vouches for -
     * because the entry is gone, or because its hash changed - is deleted, so the template goes back
     * to being offered as a download rather than being drawn from bytes nothing stands behind.
     */
    suspend fun refreshCatalogue(): Refresh {
        if (!enabled() || !configured()) return Refresh.Off
        directory.mkdirs()

        val manifestFile = File(directory, "${TemplateManifest.MANIFEST_ASSET}.new")
        val signatureFile = File(directory, "${TemplateManifest.SIGNATURE_ASSET}.new")
        try {
            val release = fetch()
            val manifestUrl = TemplateManifest.assetUrl(release, TemplateManifest.MANIFEST_ASSET)
                ?: return Refresh.None
            val signatureUrl = TemplateManifest.assetUrl(release, TemplateManifest.SIGNATURE_ASSET)
                ?: return Refresh.None

            transfer(manifestUrl, manifestFile, TemplateManifest.assetSize(release, TemplateManifest.MANIFEST_ASSET))
            transfer(signatureUrl, signatureFile, TemplateManifest.assetSize(release, TemplateManifest.SIGNATURE_ASSET))

            val verified = try {
                TemplateManifest.verify(manifestFile.readBytes(), signatureFile.readText(), publicKey, seq.value)
            } catch (e: TemplateManifest.RefusedException) {
                // A catalogue no newer than the one already verified is not news and not an alarm;
                // an attacker replaying an old one is indistinguishable from it, and both are refused.
                return if (e.refusal == TemplateManifest.Refusal.ROLLBACK) Refresh.Unchanged
                else Refresh.Refused(e.refusal)
            }

            // Committed only once it has been verified, so a refused catalogue never replaces a
            // trusted one and a half-written file is never read as either.
            if (!manifestFile.renameTo(File(directory, TemplateManifest.MANIFEST_ASSET)) ||
                !signatureFile.renameTo(File(directory, TemplateManifest.SIGNATURE_ASSET))
            ) {
                return Refresh.Failed(UpdateFailure.STORAGE)
            }
            seq.value = verified.seq
            prune(verified)
            return Refresh.Updated(BookCatalog.available(verified.entries).size)
        } catch (e: UpdateException) {
            return Refresh.Failed(e.failure)
        } finally {
            manifestFile.delete()
            signatureFile.delete()
        }
    }

    /**
     * Fetches one template, because the reader asked for that one.
     *
     * The release is read again here rather than remembered at check time, so the URL is the one
     * GitHub gives now and there is nothing stored that can go stale. The file is checked against
     * the hash the signed catalogue vouches for, and a file that fails is deleted rather than kept
     * for a retry to stumble over.
     */
    suspend fun download(id: String) {
        if (!enabled() || !configured()) return
        val verified = verified() ?: return
        val expected = verified.hashes[id] ?: return
        // Never fetch a template this app could not draw: the catalogue's filter, not a second rule.
        if (BookCatalog.available(verified.entries).none { it.id == id }) return

        val file = fileFor(id)
        _status.update(id, Status.Downloading)
        try {
            val release = fetch()
            val url = TemplateManifest.assetUrl(release, "$id.json")
            if (url == null) {
                _status.update(id, Status.Failed(UpdateFailure.NO_RELEASES))
                return
            }
            transfer(url, file, verified.bytes[id] ?: 0L)
            if (ApkGuard.sha256(file) != expected) {
                file.delete()
                _status.update(id, Status.Failed(UpdateFailure.CHECKSUM))
                return
            }
            _status.update(id, null)
        } catch (e: UpdateException) {
            file.delete()
            _status.update(id, Status.Failed(e.failure))
        }
    }

    /**
     * Deletes the local copy of [id], and only that.
     *
     * The catalogue, its signature and the `seq` are untouched, so the template stays listed and is
     * offered as a download again. Reclaiming the space is not a decision about trust.
     */
    fun remove(id: String) {
        fileFor(id).delete()
        _status.update(id, null)
    }

    /** Forgets a tile's failure, so the reader can try again without being told why they can't. */
    fun dismiss(id: String) = _status.update(id, null)

    private fun prune(verified: TemplateManifest.Verified) {
        val keep = verified.hashes
        directory.listFiles { f -> f.isFile && f.name.startsWith(CACHE_PREFIX) && f.name.endsWith(".json") }
            .orEmpty()
            .forEach { file ->
                val id = file.name.removePrefix(CACHE_PREFIX).removeSuffix(".json")
                val expected = keep[id]
                // Gone from the catalogue, or vouched for with a different hash: either way the copy
                // on disk is no longer a template this app can stand behind, so it goes.
                if (expected == null || ApkGuard.sha256(file) != expected) file.delete()
            }
    }

    private fun MutableStateFlow<Map<String, Status>>.update(id: String, status: Status?) {
        value = if (status == null) value - id else value + (id to status)
    }

    private companion object {
        const val CACHE_PREFIX = "t-"
    }
}
