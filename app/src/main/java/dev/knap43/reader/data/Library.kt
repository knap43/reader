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

enum class Format { EPUB, PDF }

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val format: Format,
    val path: String,
    val coverPath: String?,
    /** 0f..1f */
    val progress: Float = 0f,
    /** EPUB: serialized Readium locator. PDF: page index. */
    val position: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0L,
) {
    val file get() = File(path)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("format", format.name)
        .put("path", path)
        .put("coverPath", coverPath)
        .put("progress", progress.toDouble())
        .put("position", position)
        .put("addedAt", addedAt)
        .put("lastOpenedAt", lastOpenedAt)

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.getString("title"),
            author = o.optStringOrNull("author"),
            format = Format.valueOf(o.getString("format")),
            path = o.getString("path"),
            coverPath = o.optStringOrNull("coverPath"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            position = o.optStringOrNull("position"),
            addedAt = o.optLong("addedAt"),
            lastOpenedAt = o.optLong("lastOpenedAt"),
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}

/** The bookshelf, persisted as a small JSON file. */
class Library(private val store: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _books = MutableStateFlow(read())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun add(book: Book) = mutate { it + book }

    fun remove(id: String) {
        val book = get(id) ?: return
        mutate { list -> list.filterNot { it.id == id } }
        scope.launch {
            book.file.delete()
            book.coverPath?.let { File(it).delete() }
        }
    }

    fun markOpened(id: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it }
    }

    fun updatePosition(id: String, position: String, progress: Float) = mutate { list ->
        list.map { if (it.id == id) it.copy(position = position, progress = progress.coerceIn(0f, 1f)) else it }
    }

    private fun mutate(change: (List<Book>) -> List<Book>) {
        _books.update(change)
        scope.launch { writeLock.withLock { write(_books.value) } }
    }

    private fun read(): List<Book> = runCatching {
        if (!store.exists()) return emptyList()
        val arr = JSONArray(store.readText())
        List(arr.length()) { Book.fromJson(arr.getJSONObject(it)) }.filter { it.file.exists() }
    }.getOrDefault(emptyList())

    private fun write(books: List<Book>) {
        val arr = JSONArray().apply { books.forEach { put(it.toJson()) } }
        val tmp = File(store.parentFile, store.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(store)
    }
}
