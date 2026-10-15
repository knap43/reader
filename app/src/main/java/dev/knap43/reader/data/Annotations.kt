package dev.knap43.reader.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A saved place in a book.
 * [position] is a serialized Readium locator for EPUBs and a page index for PDFs.
 */
data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val progress: Float,
    val position: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A highlighted passage.
 * [position] is a serialized Readium locator for EPUBs and "page:start:count" (character range) for PDFs.
 */
data class Highlight(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val chapter: String?,
    val progress: Float,
    val position: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class BookAnnotations(
    val bookmarks: List<Bookmark> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
)

/** PDF character range of a highlight, packed into [Highlight.position]. */
data class PdfRange(val page: Int, val start: Int, val count: Int) {
    override fun toString() = "$page:$start:$count"

    companion object {
        fun parse(s: String): PdfRange? = s.split(':').mapNotNull { it.toIntOrNull() }
            .takeIf { it.size == 3 }?.let { PdfRange(it[0], it[1], it[2]) }
    }
}

/** Bookmarks and highlights, one small JSON file per book. */
class Annotations(private val dir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val loaded = mutableMapOf<String, MutableStateFlow<BookAnnotations>>()

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun of(bookId: String): StateFlow<BookAnnotations> = flow(bookId).asStateFlow()

    fun addBookmark(bookId: String, bookmark: Bookmark) = mutate(bookId) {
        it.copy(bookmarks = (it.bookmarks + bookmark).sortedBy { b -> b.progress })
    }

    fun removeBookmark(bookId: String, id: String) = mutate(bookId) {
        it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == id })
    }

    fun addHighlight(bookId: String, highlight: Highlight) = mutate(bookId) {
        it.copy(highlights = (it.highlights + highlight).sortedBy { h -> h.progress })
    }

    fun removeHighlight(bookId: String, id: String) = mutate(bookId) {
        it.copy(highlights = it.highlights.filterNot { h -> h.id == id })
    }

    /** Drops everything saved for a book removed from the library. */
    @Synchronized
    fun forget(bookId: String) {
        loaded.remove(bookId)
        scope.launch { writeLock.withLock { file(bookId).delete() } }
    }

    @Synchronized
    private fun flow(bookId: String) = loaded.getOrPut(bookId) { MutableStateFlow(read(bookId)) }

    private fun mutate(bookId: String, change: (BookAnnotations) -> BookAnnotations) {
        val state = flow(bookId)
        state.update(change)
        scope.launch { writeLock.withLock { write(bookId, state.value) } }
    }

    private fun file(bookId: String) = File(dir, "$bookId.json")

    private fun read(bookId: String): BookAnnotations = runCatching {
        val f = file(bookId)
        if (!f.exists()) return BookAnnotations()
        val o = JSONObject(f.readText())
        BookAnnotations(
            bookmarks = o.optJSONArray("bookmarks").objects().map {
                Bookmark(
                    id = it.getString("id"),
                    label = it.optString("label"),
                    progress = it.optDouble("progress", 0.0).toFloat(),
                    position = it.getString("position"),
                    createdAt = it.optLong("createdAt"),
                )
            },
            highlights = o.optJSONArray("highlights").objects().map {
                Highlight(
                    id = it.getString("id"),
                    text = it.optString("text"),
                    chapter = if (it.isNull("chapter")) null else it.optString("chapter").ifEmpty { null },
                    progress = it.optDouble("progress", 0.0).toFloat(),
                    position = it.getString("position"),
                    createdAt = it.optLong("createdAt"),
                )
            },
        )
    }.getOrDefault(BookAnnotations())

    private fun write(bookId: String, a: BookAnnotations) {
        val o = JSONObject()
            .put("bookmarks", JSONArray().apply {
                a.bookmarks.forEach {
                    put(
                        JSONObject().put("id", it.id).put("label", it.label).put("progress", it.progress.toDouble())
                            .put("position", it.position).put("createdAt", it.createdAt)
                    )
                }
            })
            .put("highlights", JSONArray().apply {
                a.highlights.forEach {
                    put(
                        JSONObject().put("id", it.id).put("text", it.text).put("chapter", it.chapter)
                            .put("progress", it.progress.toDouble()).put("position", it.position)
                            .put("createdAt", it.createdAt)
                    )
                }
            })
        val tmp = File(dir, "$bookId.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(bookId))
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }
}

/** The book's highlights as Markdown, ready to share or save. */
fun highlightsMarkdown(book: Book, highlights: List<Highlight>): String = buildString {
    append("# ").append(book.title).append('\n')
    book.author?.let { append("\n*").append(it).append("*\n") }
    highlights.forEach { h ->
        append('\n')
        h.text.trim().lines().forEach { line -> append("> ").append(line).append('\n') }
        append("\n— ")
        h.chapter?.let { append(it).append(", ") }
        append((h.progress * 100).toInt()).append("%\n")
    }
}
