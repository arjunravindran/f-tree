package com.vibethroughcode.ftree.book

import android.content.Context
import com.vibethroughcode.ftree.update.ApkGuard
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The templates the book screen may offer, in the order the catalogue gives for the day
 * ([BookCatalog]): what is in season first.
 *
 * Two sources, one list. Every release carries its own templates in its assets
 * (`book/site/book/templates/`), and those are simply there. A [TemplateDownloader] adds the ones a
 * signed catalogue offers (#214): the copies already downloaded, and the ones that could be, which
 * are listed with no JSON so the picker can offer them as a download rather than a book.
 *
 * Each template is kept as JSON and handed to the composer untouched: the composer is what validates
 * a template (`site/book/template.js`), and it refuses anything it does not understand. A template
 * the catalogue lists but this device does not carry is left out, not an error.
 */
class BookTemplates(
    private val context: Context,
    private val downloads: TemplateDownloader? = null,
) {

    /**
     * One row of the picker.
     *
     * [json] is null for a template that is offered but not on the device - there is nothing to draw
     * a cover with and nothing to compose, which is exactly what the tile says.
     */
    data class Template(
        val id: String,
        val name: String,
        val tier: String,
        val featured: Boolean,
        val json: JsonObject?,
        /** True when this came from a download rather than from the release, so it can be removed. */
        val downloaded: Boolean = false,
    )

    suspend fun offered(today: LocalDate): List<Template> = withContext(Dispatchers.IO) {
        val day = today.toString()
        val shipped = shipped(day)
        shipped + downloadable(day, shipped.map { it.id }.toSet())
    }

    private fun shipped(day: String): List<Template> {
        val catalog = runCatching { read("catalog.json") }.getOrNull()?.let(BookCatalog::read)
        return BookCatalog.listing(catalog, day).mapNotNull { entry ->
            runCatching { Json.parseToJsonElement(read("${entry.id}.json")).jsonObject }.getOrNull()
                ?.let { Template(entry.id, entry.name, entry.tier, entry.featured, it) }
        }
    }

    /**
     * What the signed catalogue offers: the copies on the device, then the ones that could be had.
     *
     * A cached file is re-hashed against the catalogue every time it is read, so bytes on disk are
     * never trusted for being on disk - a copy that no longer matches is offered as a download
     * instead of drawn. A template that is not here yet is listed only while it could actually be
     * fetched, because a tile offering a download the switch forbids would be a dead end.
     */
    private fun downloadable(day: String, already: Set<String>): List<Template> {
        val downloads = downloads ?: return emptyList()
        val verified = downloads.verified() ?: return emptyList()
        val offerable = downloads.canDownload()
        return BookCatalog.listing(verified.entries, day).mapNotNull { entry ->
            if (entry.id in already) return@mapNotNull null
            val file = downloads.fileFor(entry.id)
            val expected = verified.hashes[entry.id]
            val json = if (file.isFile && expected != null && ApkGuard.sha256(file) == expected) {
                runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            } else {
                null
            }
            if (json == null && !offerable) return@mapNotNull null
            Template(entry.id, entry.name, entry.tier, entry.featured, json, downloaded = json != null)
        }
    }

    private fun read(file: String): String = context.assets.open("$DIRECTORY/$file").bufferedReader().use { it.readText() }

    private companion object {
        const val DIRECTORY = "book/site/book/templates"
    }
}
