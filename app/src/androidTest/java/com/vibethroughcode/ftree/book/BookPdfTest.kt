package com.vibethroughcode.ftree.book

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.transfer.ExportJson
import com.vibethroughcode.ftree.transfer.PersonRecord
import com.vibethroughcode.ftree.transfer.RelationshipRecord
import com.vibethroughcode.ftree.transfer.TreeDocument
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The family book on a real device: the real WebView lays it out, the real painter writes the PDF,
 * and the platform's own PdfRenderer reads it back.
 *
 * What it proves, in the order a reader would care:
 *  - the book is laid out on the phone at all (the WebView runs the staged composer);
 *  - the PDF has the pages the composer made, each the size the book declares, and looks like the
 *    preview it came from;
 *  - the text is real embedded TrueType, with Devanagari in the font, not Type3 outlines or boxes;
 *  - a family with photographs stays within what a chat app will carry;
 *  - the WebView cannot reach anything but the app's own assets.
 */
@RunWith(AndroidJUnit4::class)
class BookPdfTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication
    private val composer = BookComposer(app)
    private val printer = BookPrinter(app, app.container.photoStore)

    @After
    fun close() = composer.close()

    /** Latin and Devanagari names, the departed, somebody nobody named, and photographs. */
    private fun document(): TreeDocument {
        fun p(id: String, name: String?, gender: String, birth: String?, death: String? = null, photo: Boolean = false) =
            PersonRecord(id = id, name = name, gender = gender, birthDate = birth, deathDate = death, deceased = death != null,
                photo = if (photo) "photos/$id" else null, notes = null, origins = emptyList())
        val people = listOf(
            p("a", "रामप्रसाद शर्मा", "MALE", "1921-03-14", "1994-11-02", photo = true),
            p("b", "सावित्री देवी", "FEMALE", "1926", "2009-01-20"),
            p("c", "Vinod Sharma", "MALE", "1948-07-09", "2017-05-11", photo = true),
            p("d", "Krishna Sharma", "FEMALE", "1952-02-01", photo = true),
            p("e", "क्षितिज त्रिपाठी", "MALE", "1978"),
            p("f", "Aarav Sharma", "MALE", "1990-04-17", photo = true),
            p("g", null, "UNSPECIFIED", null),
            p("h", "Isha Sharma", "FEMALE", "2015-03-08"),
        )
        var n = 0
        fun r(from: String, to: String, type: String) = RelationshipRecord(id = "r${n++}", from = from, to = to, type = type, subtype = null)
        val relationships = listOf(
            r("a", "b", "SPOUSE"), r("a", "c", "PARENT"), r("b", "c", "PARENT"), r("a", "e", "PARENT"),
            r("c", "d", "SPOUSE"), r("c", "f", "PARENT"), r("d", "f", "PARENT"),
            r("f", "g", "SPOUSE"), r("f", "h", "PARENT"), r("g", "h", "PARENT"),
        )
        return TreeDocument(exportedAt = "2026-09-15T00:00:00Z", sourceTreeId = "test", people = people, relationships = relationships)
    }

    /**
     * Read from the test's own assets rather than the app's.
     *
     * Heirloom arrives by download from v0.11.0-beta.2 (#214), so the release no longer carries it -
     * but a format-1 book is still the plainest thing this test can draw, and the template is data.
     * `app/build.gradle.kts` puts `site/book/templates` on the androidTest assets for this.
     */
    private fun input(templateId: String = "heirloom"): String = runBlocking {
        val template = InstrumentationRegistry.getInstrumentation().context.assets
            .open("$templateId.json").bufferedReader().use { it.readText() }
            .let { ExportJson.parseToJsonElement(it).jsonObject }
        buildJsonObject {
            put("doc", ExportJson.parseToJsonElement(ExportJson.encodeToString(TreeDocument.serializer(), document())))
            putJsonObject("options") { put("now", "2026-09-15") }
            put("template", template)
            putJsonObject("allowance") {}
        }.toString()
    }

    @Test
    fun makesAnA4PdfThatLooksLikeItsPreview() = runBlocking {
        val book = writeAndCheck("heirloom", "book-test.pdf", "heirloom")
        assertEquals("Cover", book.pages.first().label)
    }

    /**
     * #260: the same structural and preview checks against the template that actually ships.
     *
     * Everything above ran on `heirloom` - one format-1 book of portraits on plain paper - while
     * `diwali` only had to compose. The storybook is the harder case by every measure this test
     * makes: format 2, so the PDF carries symbols and clipped groups rather than flat paths; eleven
     * page archetypes instead of a repeating grid; and far more vector art per page, which is what
     * the 10 MB budget and the preview-parity check are actually for. A release gate that exercises
     * the easy template and composes the hard one is not a gate.
     *
     * Every page is compared with its preview here, not a sample: the storybook's pages are all
     * different from each other, so a sample says nothing about the ones it skipped.
     */
    @Test
    fun theStorybookMakesAnA4PdfThatLooksLikeItsPreviewToo() = runBlocking {
        val book = writeAndCheck("diwali", "storybook-test.pdf", "storybook", everyPage = true)
        assertTrue("the storybook is the featured person's, not a grid", book.pages.size >= 5)
        assertTrue(book.fileName.endsWith("Diwali Book.pdf"))
    }

    /*
     * #315: the chart, which is the one book that is not A4 and the only reason the page size stopped
     * being an assertion (#313).
     *
     * What only a device can show is here: `PdfDocument.PageInfo` takes whole points and this is the
     * first time it is handed something other than 595x842, `PdfRenderer` has to read that page back
     * at the size it was written, and the painter has to agree with it over a page several times the
     * area of a sheet. The composer's own arithmetic is held by `site/book/chart.test.mjs`; none of
     * that would catch a PdfDocument that quietly clamped the page.
     */
    @Test
    fun theChartMakesAPdfTheSizeOfTheFamily() = runBlocking {
        val book = writeAndCheck("chart", "chart-test.pdf", "chart", everyPage = true, photographs = false)
        assertEquals("the chart is one page", 1, book.pages.size)
        assertEquals("The family chart", book.pages.first().label)
        assertTrue("the chart's page is not A4", book.size.w != 595 || book.size.h != 842)
        // Sized to this family rather than to paper: wide enough for its generations, and taller
        // than a sheet is not what it is - what matters is that it is the composer's own number,
        // which writeAndCheck has already held both the PDF and the painter to.
        assertTrue("a page of ${book.size.w}x${book.size.h} is not the size of a family", book.size.w > 595)
        assertTrue(book.fileName.endsWith("Chart.pdf"))
    }

    /**
     * Writes one template's book to a PDF and holds it to the release bar: every page the size the
     * book declares, embedded TrueType rather than Type3 outlines, inside the chat-app budget, and
     * each page matching the preview the app drew from the same Book.
     */
    private suspend fun writeAndCheck(
        templateId: String,
        fileName: String,
        tag: String,
        everyPage: Boolean = false,
        photographs: Boolean = true,
    ): com.vibethroughcode.ftree.book.Book {
        val started = System.nanoTime()
        val book = composer.compose(input(templateId))
        val composed = (System.nanoTime() - started) / 1_000_000
        // The two designed books put a face on nearly every page, and a book that quietly stopped
        // asking for photographs would still pass every other check here. The chart asks for none by
        // design - it draws names and lines and nothing else - so for it the promise is the reverse.
        if (photographs) {
            assertTrue("photographs asked for", book.photos.isNotEmpty())
        } else {
            assertTrue("the chart asks for no photographs", book.photos.isEmpty())
        }

        val photos = book.photos.associate { it.id to portrait() }
        val file = File(app.cacheDir, fileName)
        file.outputStream().use { printer.writePdf(book, photos, it) }
        val written = (System.nanoTime() - started) / 1_000_000
        android.util.Log.i(
            "BookPdfTest",
            "$tag: composed in $composed ms, written in $written ms, ${file.length()} bytes, ${book.pages.size} pages",
        )

        assertTrue("$tag under the chat-app budget: ${file.length()}", file.length() < 10_000_000)

        /*
         * #245: the size the book screen quotes, held against what PdfDocument really wrote.
         *
         * `BookEstimate`'s coefficients are `site/book/compose.js`'s, and those were fitted on
         * Chromium's PDF writer; that they also cover this one was assumed rather than measured,
         * which is what compose.js's comment on ART_PDF means by "#246 and #259 must measure it
         * there too". This is that measurement. The estimate is deliberately built to err toward
         * "too big", so writing MORE than it allowed is the failure, not writing less.
         */
        val estimate = BookEstimate.bytes(book)
        android.util.Log.i("BookPdfTest", "$tag: estimated $estimate bytes, wrote ${file.length()}")
        assertTrue(
            "$tag: PdfDocument wrote ${file.length()} bytes, above the $estimate the estimate allowed - " +
                "raise BookEstimate.ART_PDF and site/book/compose.js's ART_PDF together, so the shells keep agreeing",
            file.length() <= estimate,
        )
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("$tag fonts embedded as TrueType", "/FontFile2" in bytes)
        assertTrue("$tag no Type3 outlines", "/Type3" !in bytes)

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                assertEquals(book.pages.size, pdf.pageCount)
                var worst = 0.0
                for (i in 0 until pdf.pageCount) {
                    pdf.openPage(i).use { page ->
                        // The book's own page, not A4 by assumption (#313). Every book but the chart
                        // is still 595x842, and `book.size` is what says so.
                        assertEquals("$tag page ${i + 1} width", book.size.w, page.width)
                        assertEquals("$tag page ${i + 1} height", book.size.h, page.height)
                        if (everyPage || i == 0 || book.pages[i].label.startsWith("Generations")) {
                            val fromPdf = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            fromPdf.eraseColor(Color.WHITE)
                            page.render(fromPdf, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val fromPainter = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            fromPainter.eraseColor(Color.WHITE)
                            BookPainter(printer.fonts) { photos[it] }.paint(Canvas(fromPainter), book, i)
                            val diff = meanDifference(fromPdf, fromPainter)
                            if (diff > worst) worst = diff
                            save(app, fromPdf, "$tag-page-$i.png")
                            assertTrue(
                                "$tag page ${i + 1} (${book.pages[i].label}) differs from its preview by $diff",
                                diff < 6.0,
                            )
                        }
                    }
                }
                android.util.Log.i("BookPdfTest", "$tag: worst page/preview difference $worst")
            }
        }
        return book
    }

    @Test
    fun theBookFontsCarryDevanagari() {
        // hasGlyph takes one grapheme cluster at a time - consonants, and the conjuncts that make
        // Devanagari names, each of which the font has to shape into a single glyph.
        val clusters = listOf("श", "र", "म", "क", "ष", "त", "ज", "ञ", "क्ष", "त्र", "ज्ञ", "श्र")
        for (key in listOf("book_text", "book_strong", "book_display", "book_hand")) {
            val paint = Paint().apply { typeface = printer.fonts.getValue(key) }
            for (cluster in clusters) assertTrue("$key: $cluster", paint.hasGlyph(cluster))
        }
    }


    @Test
    fun theComposerCanReachNothingButTheAppsAssets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = WebView(app)
            val client = BookWebClient(app) {}
            fun ask(url: String) = client.shouldInterceptRequest(view, request(url))
            assertEquals(404, ask("https://example.com/").statusCode)
            assertEquals(404, ask("http://${BookComposer.HOST}/bookhost/index.html").statusCode)
            assertEquals(404, ask("https://${BookComposer.HOST}/book/policy.json").statusCode)
            assertEquals(404, ask("https://${BookComposer.HOST}/book/site/../../AndroidManifest.xml").statusCode)
            // An asset is served with its content and type; a refusal is an explicit 404.
            val asset = ask("https://${BookComposer.HOST}/book/site/book/compose.js")
            assertTrue(asset.statusCode != 404 && asset.data != null)
            assertEquals("text/javascript", asset.mimeType)
            view.destroy()
        }
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
