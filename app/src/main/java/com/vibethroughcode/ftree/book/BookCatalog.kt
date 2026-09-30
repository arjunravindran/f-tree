package com.vibethroughcode.ftree.book

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The catalogue: which templates this release offers, in what order, and when each is in season.
 *
 * A port of `site/book/catalog.js`, case for case, held to the same table (`catalog-cases.json`),
 * so Android and the desktop feature a festival template on the same days. See that file for the
 * rules; the short of it is that a season is a window of days per year, both ends included, that
 * nothing here reads a clock, and that an entry this app cannot read, or whose template format is
 * newer than the composer it ships with understands, is left out rather than guessed at.
 *
 * An entry's `format` is only ever compared as a number here - this object never needs to know a
 * template format's shape, only whether [MAX_TEMPLATE_FORMAT] is high enough to draw it. Template
 * format 2 (`site/book/template.js`'s paper-cut schema, #243) is the storybook, and it was hidden
 * exactly this way while it was being built: it shipped as a catalogue row the shells skipped,
 * because [MAX_TEMPLATE_FORMAT] stayed 1 until the composer and both painters (#244, #246) landed.
 * #259 raised it to 2, which is the whole of the swap on this side - no shell offers a book it
 * cannot paint, and none ever did.
 */
object BookCatalog {

    const val FORMAT = 1

    /**
     * The highest template format the staged composer can draw - `MAX_TEMPLATE_FORMAT` in
     * `site/book/template.js`, which `BookCatalogTest` reads out of that file to hold the two
     * together. Not format 1's schema version (`TEMPLATE_FORMAT` there); the two were one constant
     * until #259 and are different questions.
     */
    const val MAX_TEMPLATE_FORMAT = 3

    data class Entry(val id: String, val name: String, val format: Int, val tier: String, val featured: List<Pair<String, String>>)

    data class Listed(val id: String, val name: String, val tier: String, val featured: Boolean)

    private val ID = Regex("^[a-z][a-z0-9-]{1,31}$")
    private val DAY = Regex("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$")

    /** The catalogue's entries, or `null` when the file as a whole cannot be read. Never throws. */
    fun read(text: String): List<Entry>? = runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let(::read)

    fun read(json: JsonElement): List<Entry>? {
        val root = json as? JsonObject ?: return null
        if (root["format"].int() != FORMAT) return null
        val list = root["templates"] as? JsonArray ?: return null
        val seen = mutableSetOf<String>()
        return list.mapNotNull(::entry).filter { seen.add(it.id) }
    }

    private fun entry(element: JsonElement): Entry? {
        val t = element as? JsonObject ?: return null
        val id = t["id"].string()?.takeIf { ID.matches(it) } ?: return null
        val name = t["name"].string()?.takeIf { it.isNotBlank() && it.length <= 40 } ?: return null
        val format = t["format"].int()?.takeIf { it >= 1 } ?: return null
        val tier = t["tier"].string()?.takeIf { ID.matches(it) } ?: return null
        val featured = when (val f = t["featured"]) {
            null -> emptyList()
            is JsonObject -> f.values.map { window ->
                val days = (window as? JsonArray)?.map { it.string() }
                if (days == null || days.size != 2 || days.any { it == null || !DAY.matches(it) }) return null
                val (from, to) = days.map { it!! }
                if (from > to) return null
                from to to
            }
            else -> return null
        }
        return Entry(id, name, format, tier, featured)
    }

    fun available(catalog: List<Entry>?, supported: Int = MAX_TEMPLATE_FORMAT): List<Entry> =
        catalog.orEmpty().filter { it.format <= supported }

    /** The ids in season on [today] (`yyyy-MM-dd`, the reader's local date), in catalogue order. */
    fun featuredAt(catalog: List<Entry>?, today: String, supported: Int = MAX_TEMPLATE_FORMAT): List<String> =
        available(catalog, supported).filter { e -> e.featured.any { (from, to) -> from <= today && today <= to } }.map { it.id }

    /** The picker's list: what is in season first, then everything else in catalogue order. */
    fun listing(catalog: List<Entry>?, today: String, supported: Int = MAX_TEMPLATE_FORMAT): List<Listed> {
        val season = featuredAt(catalog, today, supported).toSet()
        val all = available(catalog, supported).map { Listed(it.id, it.name, it.tier, it.id in season) }
        return all.filter { it.featured } + all.filterNot { it.featured }
    }

    /** What the book opens on: the template in season, or the first one listed. */
    fun opening(catalog: List<Entry>?, today: String, supported: Int = MAX_TEMPLATE_FORMAT): String? =
        listing(catalog, today, supported).firstOrNull()?.id

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
