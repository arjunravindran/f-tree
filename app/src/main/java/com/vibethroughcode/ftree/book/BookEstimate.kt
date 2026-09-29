package com.vibethroughcode.ftree.book

/**
 * How large a book's PDF will be, before anyone waits for it to be written.
 *
 * The twin of `estimateBytes`, `artStats` and `artTerm` in `site/book/compose.js` (#245), and it
 * has to stay one: the book screen quotes this number, the desktop quotes the JavaScript one, and
 * the same book on the two shells saying two different sizes is the kind of disagreement nobody
 * reports and everybody notices. `BookEstimateTest` composes real books and holds the two halves
 * to the same answer, so this file cannot drift quietly.
 *
 * Android's `PdfDocument` keeps photographs losslessly, so this is the JavaScript estimate with
 * `lossless: true` - there is no JPEG branch here because this shell never takes it.
 */
object BookEstimate {
    /** Fonts and the fixed furniture of a PDF. `compose.js`'s BASE_BYTES. */
    const val BASE_BYTES = 420_000L

    /** What a page costs beyond its art. `compose.js`'s PAGE_BYTES. */
    const val PAGE_BYTES = 18_000L

    /** `PdfDocument` writes photographs uncompressed. `compose.js`'s LOSSLESS_BYTES_PER_PIXEL. */
    const val LOSSLESS_BYTES_PER_PIXEL = 1.8

    /** An item's own paint, position and transform, roughly. `compose.js`'s ITEM_BYTES. */
    private const val ITEM_BYTES = 40L

    /**
     * What paper-cut art adds to a PDF, per thing [artStats] counts - `compose.js`'s ART_PDF, and
     * the same numbers, because they were fitted to measurements rather than to a painter. The
     * measurement is in `site/book/qa/pdf-size.json`; `tools/book_pdf_size.mjs` made it by printing
     * pages with and without their drawings and fitting the difference to these five counts, then
     * scaling the fit so it errs toward "too big".
     *
     * Chromium was what it was fitted on. `BookEstimateTest` checks the claim that these also cover
     * `PdfDocument` by printing real books here and failing if Android writes more than the
     * estimate allows - the check `compose.js`'s comment asked for and #246 and #259 did not do.
     */
    val ART_PDF = Coefficients(bytes = 0.75, translucent = 1050.0, layers = 0.0, gradients = 5800.0, clips = 1600.0)

    data class Coefficients(
        val bytes: Double,
        val translucent: Double,
        val layers: Double,
        val gradients: Double,
        val clips: Double,
    )

    /**
     * What a book's art is made of, counted the way a painter writes it into a PDF - every `use`
     * expanded, because a symbol drawn forty times is forty copies of its paths there.
     *
     *  - [bytes]: every path's data, plus [ITEM_BYTES] for each shape, group and use drawn (words
     *    and photographs left out) - what a content stream mostly is;
     *  - [translucent]: shapes with an opacity, a paper shadow among them, each its own graphics
     *    state;
     *  - [layers]: groups and uses with an opacity, each composited once as a transparency group;
     *  - [gradients]: items painted with a gradient, each a shading of its own;
     *  - [clips]: clipped groups.
     */
    data class Stats(
        val bytes: Long = 0,
        val translucent: Long = 0,
        val layers: Long = 0,
        val gradients: Long = 0,
        val clips: Long = 0,
    ) {
        operator fun plus(other: Stats) = Stats(
            bytes + other.bytes,
            translucent + other.translucent,
            layers + other.layers,
            gradients + other.gradients,
            clips + other.clips,
        )
    }

    /** [Stats] priced by a set of coefficients. `compose.js`'s artTerm. */
    fun artTerm(stats: Stats, coefficients: Coefficients = ART_PDF): Double =
        stats.bytes * coefficients.bytes +
            stats.translucent * coefficients.translucent +
            stats.layers * coefficients.layers +
            stats.gradients * coefficients.gradients +
            stats.clips * coefficients.clips

    /**
     * Counts a book's art. Each symbol is counted once and multiplied by its uses, so this stays
     * linear in the book's size however deeply the art nests.
     */
    fun artStats(book: Book): Stats {
        val memo = HashMap<String, Stats>()

        /**
         * One drawn shape. `compose.js` also asks whether the stroke is a gradient; it cannot be,
         * because `format.js` holds `stroke` to a "#rrggbb" string - which is why this model types
         * it as a [Colour] and not a [Fill]. The two halves agree.
         */
        fun shape(total: Stats, d: String?, fill: Fill?, op: Float?) = total.copy(
            bytes = total.bytes + ITEM_BYTES + (d?.length ?: 0),
            gradients = total.gradients + if (fill is Fill.Ref) 1 else 0,
            translucent = total.translucent + if (op != null) 1 else 0,
        )

        fun stats(items: List<Item>): Stats {
            var total = Stats()
            for (item in items) {
                when (item) {
                    // Words and photographs are not art; the base and photo terms already hold them.
                    is Item.Text, is Item.Image -> Unit

                    is Item.Group -> {
                        total += stats(item.items)
                        total = total.copy(
                            bytes = total.bytes + ITEM_BYTES + (item.clip?.length ?: 0),
                            clips = total.clips + if (item.clip != null) 1 else 0,
                            layers = total.layers + if (item.op != null) 1 else 0,
                        )
                    }

                    is Item.Use -> {
                        // A cycle adds nothing; `validateBook` refuses one anyway. Seeding the memo
                        // before recursing is what stops this recursing forever if one ever got in.
                        val symbol = memo.getOrPut(item.ref) {
                            memo[item.ref] = Stats()
                            stats(book.symbols[item.ref]?.items.orEmpty())
                        }
                        total += symbol
                        total = total.copy(
                            bytes = total.bytes + ITEM_BYTES,
                            layers = total.layers + if (item.op != null) 1 else 0,
                        )
                    }

                    is Item.Rect -> total = shape(total, null, item.fill, item.op)
                    is Item.Circle -> total = shape(total, null, item.fill, item.op)
                    is Item.Path -> total = shape(total, item.d, item.fill, item.op)
                }
            }
            return total
        }

        return book.pages.fold(Stats()) { total, page -> total + stats(page.items) }
    }

    /**
     * About how large [book] will be as a PDF written by this app.
     *
     * Format 1 carries no art term: its per-page constant already covers the starfields, and
     * [artStats] would count them a second time.
     */
    fun bytes(book: Book): Long {
        val base = BASE_BYTES + book.pages.size * PAGE_BYTES
        val art = if (book.format >= 2) Math.ceil(artTerm(artStats(book))) else 0.0
        val photos = book.photos.sumOf { it.px.toDouble() * it.px * LOSSLESS_BYTES_PER_PIXEL }
        return Math.round(base + art + photos)
    }
}
