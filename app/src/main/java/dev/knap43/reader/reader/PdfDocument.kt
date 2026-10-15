package dev.knap43.reader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.LruCache
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import io.legere.pdfiumandroid.PdfDocument as PdfiumDocument

/** An entry of the PDF's own outline. */
data class PdfOutlineItem(val title: String, val page: Int, val depth: Int)

/** A search match: a character range on one page, plus context for the result list. */
data class PdfMatch(val page: Int, val start: Int, val count: Int, val before: String, val match: String, val after: String)

/**
 * A page's text with each character's box, normalized to 0..1 of the page (top-left origin).
 * Characters PDFium generates itself (spaces, line breaks) usually have no box.
 */
class TextLayer(val text: String, private val boxes: Array<RectF?>) {

    /** The character under a point, or the nearest one within reach; -1 if none. */
    fun charAt(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        boxes.forEachIndexed { i, b ->
            b ?: return@forEachIndexed
            val dx = if (x < b.left) b.left - x else if (x > b.right) x - b.right else 0f
            val dy = if (y < b.top) b.top - y else if (y > b.bottom) y - b.bottom else 0f
            // Lines are close together; weigh vertical distance more so the right line wins.
            val d = dx + dy * 3f
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return if (bestDistance < 0.03f) best else -1
    }

    /** The word around character [i]. */
    fun wordAt(i: Int): IntRange {
        if (i !in text.indices) return IntRange.EMPTY
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
        if (!isWordChar(text[i])) return i..i
        var s = i
        var e = i
        while (s > 0 && isWordChar(text[s - 1])) s--
        while (e < text.lastIndex && isWordChar(text[e + 1])) e++
        return s..e
    }

    /** Boxes covering characters [start, start + count), merged into one rectangle per line. */
    fun rects(start: Int, count: Int): List<RectF> {
        val out = mutableListOf<RectF>()
        var line: RectF? = null
        for (i in start.coerceAtLeast(0) until (start + count).coerceAtMost(boxes.size)) {
            val b = boxes[i] ?: continue
            val l = line
            if (l != null && sameLine(l, b)) {
                l.union(b)
            } else {
                l?.let(out::add)
                line = RectF(b)
            }
        }
        line?.let(out::add)
        return out
    }

    fun top(start: Int): Float? = (start until (start + 50).coerceAtMost(boxes.size)).firstNotNullOfOrNull { boxes[it] }?.top

    private fun sameLine(a: RectF, b: RectF): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap > 0.5f * minOf(a.height(), b.height()) && abs(b.left - a.right) < 0.2f
    }
}

/**
 * A PDF opened with PDFium: page rendering, text with character positions, search and outline.
 * PDFium isn't thread-safe, so every call is serialized behind a mutex and runs off the main thread.
 */
class PdfDocument private constructor(
    private val doc: PdfiumDocument,
    /** width / height for each page */
    val aspectRatios: FloatArray,
) {
    private val lock = Mutex()
    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val layers = LruCache<Int, TextLayer>(24)
    private val texts = arrayOfNulls<String>(aspectRatios.size)

    val pageCount get() = aspectRatios.size

    @Volatile
    private var closed = false

    /** Returns null once the document has been closed or the page can't be drawn. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$index@$widthPx"
        bitmaps.get(key)?.let { return it }
        return io {
            bitmaps.get(key) ?: runCatching {
                val w = widthPx.coerceIn(1, MAX_WIDTH)
                val h = (w / aspectRatios[index]).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                doc.openPage(index).use { page ->
                    page.renderPageBitmap(
                        bitmap, 0, 0, w, h,
                        renderAnnot = true,
                        canvasColor = Color.WHITE,
                        pageBackgroundColor = Color.WHITE,
                    )
                }
                bitmap
            }.getOrNull()?.also { bitmaps.put(key, it) }
        }
    }

    /** The page's text and character boxes; null if the page has no text layer (e.g. a scan). */
    suspend fun textLayer(index: Int): TextLayer? {
        layers.get(index)?.let { return it }
        return io {
            layers.get(index) ?: runCatching {
                doc.openPage(index).use { page ->
                    page.openTextPage().use { tp ->
                        val count = tp.textPageCountChars()
                        val text = readText(tp, count)
                        val w = UNIT
                        val h = (UNIT / aspectRatios[index]).toInt().coerceAtLeast(1)
                        val boxes = Array(count) { i ->
                            val box = tp.textPageGetCharBox(i)
                            if (box == null || (box.width() == 0f && box.height() == 0f)) null
                            else {
                                val r = page.mapRectToDevice(0, 0, w, h, 0, box)
                                RectF(
                                    minOf(r.left, r.right).toFloat() / w,
                                    minOf(r.top, r.bottom).toFloat() / h,
                                    maxOf(r.left, r.right).toFloat() / w,
                                    maxOf(r.top, r.bottom).toFloat() / h,
                                )
                            }
                        }
                        TextLayer(text, boxes)
                    }
                }
            }.getOrNull()?.also { layers.put(index, it) }
        }
    }

    /** The text layer if it's already loaded, without waiting. */
    fun peekTextLayer(index: Int): TextLayer? = layers.get(index)

    /** Just the text of a page, cached; cheaper than [textLayer] for searching. */
    suspend fun pageText(index: Int): String = texts[index] ?: io {
        texts[index] ?: runCatching {
            doc.openPage(index).use { page -> page.openTextPage().use { tp -> readText(tp, tp.textPageCountChars()) } }
        }.getOrDefault("").also { texts[index] = it }
    } ?: ""

    /**
     * Finds [query] on one page, ignoring case and treating any run of whitespace (including line
     * breaks) as a single space, so phrases match across lines.
     */
    suspend fun search(index: Int, query: String): List<PdfMatch> {
        val raw = pageText(index)
        if (raw.isEmpty()) return emptyList()
        val q = query.trim().replace(Regex("""\s+"""), " ")
        if (q.isEmpty()) return emptyList()
        // Collapse whitespace, remembering where each kept character came from.
        val norm = StringBuilder(raw.length)
        val origin = IntArray(raw.length)
        var lastSpace = true
        raw.forEachIndexed { i, c ->
            if (c.isWhitespace() || c == '\u0000') {
                if (!lastSpace) {
                    origin[norm.length] = i
                    norm.append(' ')
                    lastSpace = true
                }
            } else {
                origin[norm.length] = i
                norm.append(c)
                lastSpace = false
            }
        }
        val text = norm.toString()
        val out = mutableListOf<PdfMatch>()
        var at = text.indexOf(q, 0, ignoreCase = true)
        while (at >= 0) {
            val end = at + q.length
            val start = origin[at]
            val count = origin[end - 1] + 1 - start
            out += PdfMatch(
                page = index,
                start = start,
                count = count,
                before = text.substring((at - 80).coerceAtLeast(0), at),
                match = text.substring(at, end),
                after = text.substring(end, (end + 100).coerceAtMost(text.length)),
            )
            at = text.indexOf(q, end, ignoreCase = true)
        }
        return out
    }

    /** The document's outline (its own table of contents), flattened. */
    suspend fun outline(): List<PdfOutlineItem> = io {
        fun walk(items: List<PdfiumDocument.Bookmark>, depth: Int): List<PdfOutlineItem> = items.flatMap {
            listOf(PdfOutlineItem(it.title?.trim().orEmpty().ifEmpty { "Untitled" }, it.pageIdx.toInt(), depth)) +
                walk(it.children, depth + 1)
        }
        runCatching { walk(doc.getTableOfContents(), 0) }.getOrDefault(emptyList())
    } ?: emptyList()

    /** Waits for any in-flight work, then releases PDFium's document. */
    suspend fun closeSafely() = lock.withLock {
        if (closed) return@withLock
        closed = true
        bitmaps.evictAll()
        layers.evictAll()
        withContext(Dispatchers.IO) { doc.close() }
    }

    private suspend fun <T> io(block: () -> T): T? = lock.withLock {
        withContext(Dispatchers.IO) { if (closed) null else block() }
    }

    private fun readText(tp: io.legere.pdfiumandroid.PdfTextPage, count: Int): String {
        if (count <= 0) return ""
        // PDFium writes a terminating NUL; keep exactly one character per index.
        val t = tp.textPageGetText(0, count + 1).orEmpty()
        return if (t.length >= count) t.substring(0, count) else t.padEnd(count, ' ')
    }

    companion object {
        private const val CACHE_BYTES = 64 * 1024 * 1024
        private const val MAX_WIDTH = 2400
        private const val UNIT = 10_000

        @Volatile
        private var core: PdfiumCore? = null

        private fun core(context: Context) = core ?: synchronized(this) {
            core ?: PdfiumCore(context.applicationContext).also { core = it }
        }

        suspend fun open(context: Context, file: File): PdfDocument = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val doc = try {
                core(context).newDocument(pfd)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            try {
                val ratios = FloatArray(doc.getPageCount()) { i ->
                    doc.openPage(i).use { p ->
                        p.getPageWidthPoint().toFloat() / p.getPageHeightPoint().coerceAtLeast(1)
                    }.takeIf { it > 0f } ?: (1f / 1.414f)
                }
                PdfDocument(doc, ratios)
            } catch (e: Exception) {
                doc.close()
                throw e
            }
        }
    }
}
