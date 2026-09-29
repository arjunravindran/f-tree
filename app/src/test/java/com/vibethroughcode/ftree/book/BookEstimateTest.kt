package com.vibethroughcode.ftree.book

import com.vibethroughcode.ftree.transfer.ExportJson
import com.vibethroughcode.ftree.transfer.TreeDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * [BookEstimate] against the composer that defines it (#245).
 *
 * The size the book screen quotes is one of the few numbers this app works out for itself rather
 * than reading out of the book, so it is one of the few places the two shells can disagree without
 * anything failing. Every test here runs the real `site/book/compose.js` in node over the same book
 * and demands the same answer - counts first, so a disagreement says which of the five terms drifted
 * rather than only that the total did.
 *
 * The other half of #245's ask lives on a device: `compose.js`'s comment on ART_PDF says "#246 and
 * #259 must measure it there too and raise these if Android writes more", and the coefficients were
 * fitted on Chromium. `PdfDocument` is not something a JVM unit test has, so that measurement is in
 * `BookPdfTest.writeAndCheck`, against real books this app really wrote.
 */
class BookEstimateTest {

    private fun conformance() = File(repoRoot, "site/book/golden/format2-conformance.json")
    private fun heirloom() = File(repoRoot, "site/book/golden/sample-heirloom.json")

    /** What the JavaScript half makes of a book: the five counts, and the lossless estimate. */
    private fun javaScript(book: File): Pair<BookEstimate.Stats, Long> {
        val script = """
            import { readFileSync } from 'node:fs';
            import { artStats, estimateBytes } from '${File(repoRoot, "site/book/compose.js").toURI()}';
            const book = JSON.parse(readFileSync(process.argv[1], 'utf8'));
            process.stdout.write(JSON.stringify({ stats: artStats(book), estimate: estimateBytes(book, { lossless: true }) }));
        """.trimIndent()
        val out = Json.parseToJsonElement(runNode(script, book.absolutePath)).jsonObject
        val stats = out.getValue("stats").jsonObject
        fun count(key: String) = stats.getValue(key).jsonPrimitive.long
        return BookEstimate.Stats(
            bytes = count("bytes"),
            translucent = count("translucent"),
            layers = count("layers"),
            gradients = count("gradients"),
            clips = count("clips"),
        ) to out.getValue("estimate").jsonPrimitive.long
    }

    private fun agreesWithTheComposer(file: File) {
        val book = readBook(file.readText())
        val (stats, estimate) = javaScript(file)
        assertEquals("the art counts", stats, BookEstimate.artStats(book))
        assertEquals("the estimate", estimate, BookEstimate.bytes(book))
    }

    @Test
    fun `a format 2 book is counted exactly as the composer counts it`() {
        agreesWithTheComposer(conformance())
    }

    @Test
    fun `a format 1 book is estimated exactly as the composer estimates it`() {
        agreesWithTheComposer(heirloom())

        // Format 1 has drawings, and they are deliberately not priced: its per-page constant was
        // fitted with the starfields already in it, so adding an art term would count them twice.
        // The counts are not zero, which is what makes the gate in `bytes` worth having.
        val book = readBook(heirloom().readText())
        assertTrue("heirloom draws things", BookEstimate.artStats(book).bytes > 0)
        val withoutArt = BookEstimate.BASE_BYTES + book.pages.size * BookEstimate.PAGE_BYTES +
            book.photos.sumOf { it.px.toDouble() * it.px * BookEstimate.LOSSLESS_BYTES_PER_PIXEL }
        assertEquals(Math.round(withoutArt), BookEstimate.bytes(book))
    }

    @Test
    fun `the storybook the app really ships is counted as the composer counts it`() {
        agreesWithTheComposer(storybook())
    }

    /**
     * The regression this issue exists for. Before #245 this app estimated
     * `420_000 + pages * 18_000 + photos` on every book, a format-1 formula with no term for
     * paper-cut geometry at all - so it under-reported a storybook, which is almost entirely art,
     * in the one direction the estimate is not allowed to be wrong in.
     */
    @Test
    fun `a storybook is estimated far above the flat per-page constant it used to get`() {
        val book = readBook(storybook().readText())
        val old = BookEstimate.BASE_BYTES + book.pages.size * BookEstimate.PAGE_BYTES +
            book.photos.sumOf { (it.px.toLong() * it.px * 18) / 10 }
        val now = BookEstimate.bytes(book)
        assertTrue("a storybook is mostly art, so the art term must dominate: was $old, now $now", now > old * 2)
    }

    /** The storybook as the app ships it: the real composer, the real Diwali template. */
    private fun storybook(): File {
        val docJson = ExportJson.encodeToString(TreeDocument.serializer(), sampleDocument())
        val input = File.createTempFile("book-input", ".json").apply { deleteOnExit(); writeText(docJson) }
        val script = """
            import { readFileSync } from 'node:fs';
            import { composeBook } from '${File(repoRoot, "site/book/compose.js").toURI()}';
            const doc = JSON.parse(readFileSync(process.argv[1], 'utf8'));
            const template = JSON.parse(readFileSync('${File(repoRoot, "site/book/templates/diwali.json").absolutePath}', 'utf8'));
            process.stdout.write(JSON.stringify(composeBook(doc, { now: '2026-09-15' }, template)));
        """.trimIndent()
        val book = runNode(script, input.absolutePath)
        val out = File.createTempFile("storybook", ".json").apply { deleteOnExit(); writeText(book) }
        assertEquals("the Diwali template is the storybook, so this must be format 2", 2, readBook(book).format)
        return out
    }

    private fun sampleDocument(): TreeDocument {
        ZipInputStream(File(repoRoot, "site/playground/sample-family.ftree").inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == TreeDocument.ENTRY_JSON) return ExportJson.decodeFromString(TreeDocument.serializer(), String(zip.readBytes()))
            }
        }
        error("no tree.json in the sample")
    }
}
