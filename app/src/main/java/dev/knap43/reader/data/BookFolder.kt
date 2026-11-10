package dev.knap43.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The folder the library is drawn from. Each scan walks it and its subfolders: new EPUBs and PDFs join
 * the shelf, read in place; books whose files have gone leave it; books removed by hand stay hidden.
 */
class BookFolder(
    private val context: Context,
    private val library: Library,
    private val books: Books,
    private val prefs: ReaderPrefs,
    private val annotations: Annotations,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private val resolver get() = context.contentResolver

    /** Folder files found to be copies of a shelved book, by size, so later scans don't hash them again. */
    private val duplicateFiles = HashMap<String, Long>()

    private val _tree = MutableStateFlow(prefs.bookFolder?.let(Uri::parse))
    /** The chosen folder's tree URI, or null before one is chosen. */
    val tree: StateFlow<Uri?> = _tree.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** What a scan found, or why it failed, for the library to show. */
    val messages: SharedFlow<String> = _messages

    /** The folder's name, for the library to show. */
    val name: String?
        get() = _tree.value?.let { tree ->
            runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull()?.ifBlank { null }
        }

    /** Makes [tree] the book folder, keeping access to it across restarts, and scans it. */
    fun choose(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(tree, flags) }
        _tree.value?.takeIf { it != tree }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, flags) }
            prefs.hiddenDocuments = emptySet()
        }
        prefs.bookFolder = tree.toString()
        _tree.value = tree
        scan()
    }

    /** Scans the folder in the background; a scan already under way covers this request. */
    fun scan() {
        val tree = _tree.value ?: return
        if (scanLock.isLocked) return
        scope.launch {
            scanLock.withLock {
                _scanning.value = true
                try {
                    sync(tree)
                } finally {
                    _scanning.value = false
                }
            }
        }
    }

    /** Takes a book off the shelf. A folder book's file stays in the folder, and later scans skip it. */
    fun remove(book: Book) {
        if (book.isDocument) prefs.hiddenDocuments += book.path
        forget(book)
    }

    private fun forget(book: Book) {
        library.remove(book.id)
        prefs.forgetBook(book.id)
        annotations.forget(book.id)
    }

    private suspend fun sync(tree: Uri) {
        val found = try {
            withContext(Dispatchers.IO) { list(tree) }
        } catch (e: SecurityException) {
            _messages.tryEmit("Reader can no longer open the book folder. Choose it again.")
            return
        } catch (e: Exception) {
            // Never empty the shelf over a folder that couldn't be read.
            _messages.tryEmit("Couldn't scan the book folder")
            return
        }
        val foundPaths = found.mapTo(HashSet()) { it.uri.toString() }
        val hidden = prefs.hiddenDocuments
        // Forget hidden files that have left the folder, so the list doesn't grow forever.
        if (!foundPaths.containsAll(hidden)) prefs.hiddenDocuments = hidden intersect foundPaths

        var added = 0
        var failed = 0
        for (doc in found) {
            val path = doc.uri.toString()
            if (path in hidden || library.books.value.any { it.path == path }) continue
            if (duplicateFiles[path] == doc.size) continue
            val fingerprint = books.fingerprint(doc.uri)
            val same = fingerprint?.let { books.findCopy(it, doc.size) }
                // A book shelved before fingerprints, whose file has since moved within the folder.
                ?: library.books.value.firstOrNull {
                    it.isDocument && it.path !in foundPaths && it.fingerprint == null &&
                        it.fileName == doc.name && it.fileSize == doc.size
                }
            when {
                same == null -> runCatching { books.describeDocument(doc.uri, doc.name, doc.size, doc.format, fingerprint) }
                    .onSuccess { library.add(it); added++ }
                    .onFailure { failed++ }
                // A private copy from before the folder, or a folder book whose file moved: it now reads from here.
                !same.isDocument || same.path !in foundPaths -> adopt(same, doc, fingerprint)
                // A second copy of a file already on the shelf.
                else -> duplicateFiles[path] = doc.size
            }
        }
        val merged = mergeDuplicates(foundPaths)
        // Books whose files have left the folder.
        library.books.value.filter { it.isDocument && it.path !in foundPaths }.forEach(::forget)

        if (merged > 0) _messages.tryEmit("Merged ${plural(merged)} that were on the shelf twice")
        when {
            added > 0 && failed > 0 -> _messages.tryEmit("Added ${plural(added)}; ${plural(failed)} couldn't be read")
            added > 0 -> _messages.tryEmit("Added ${plural(added)}")
            failed > 0 -> _messages.tryEmit("${plural(failed).replaceFirstChar { it.uppercase() }} couldn't be read")
        }
    }

    /** Points [book] at [doc], keeping its reading state; a private copy it replaces is deleted. */
    private fun adopt(book: Book, doc: Found, fingerprint: String?) {
        library.update(book.id) {
            it.copy(path = doc.uri.toString(), fileName = doc.name, fileSize = doc.size, fingerprint = fingerprint ?: it.fingerprint)
        }
        if (!book.isDocument) book.file.delete()
    }

    /**
     * Folds books with the same contents into one: the entry with the most reading done survives,
     * and it reads from the folder when one of them is there. Returns how many entries were folded away.
     */
    private suspend fun mergeDuplicates(present: Set<String>): Int {
        val shelf = library.books.value.filter { !it.isDocument || it.path in present }
        var merged = 0
        // Only books of the same size can match, so only those are hashed.
        for (sameSize in shelf.groupBy { books.sizeOf(it) }.values) {
            if (sameSize.size < 2) continue
            for ((fingerprint, same) in sameSize.groupBy { books.fingerprintOf(it) }) {
                if (fingerprint == null || same.size < 2) continue
                merge(same)
                merged += same.size - 1
            }
        }
        return merged
    }

    private fun merge(same: List<Book>) {
        val keep = same.maxWith(
            compareBy<Book>(
                { annotations.of(it.id).value.let { a -> a.bookmarks.size + a.highlights.size } },
                { it.lastOpenedAt },
                { it.progress },
            )
        )
        val source = same.firstOrNull { it.isDocument } ?: keep
        library.update(keep.id) {
            it.copy(
                path = source.path,
                fileName = source.fileName ?: it.fileName,
                fileSize = source.fileSize,
                finished = same.any { b -> b.finished },
                addedAt = same.minOf { b -> b.addedAt },
            )
        }
        if (!keep.isDocument && source !== keep) keep.file.delete()
        for (other in same) {
            if (other === keep) continue
            if (prefs.resumeBookId == other.id) prefs.resumeBookId = keep.id
            // Removing a folder entry leaves its file alone; removing a private copy deletes it.
            forget(other)
        }
    }

    private class Found(val uri: Uri, val name: String, val size: Long, val format: Format)

    /** Every EPUB and PDF in the folder and its subfolders, skipping hidden ones. */
    private fun list(tree: Uri): List<Found> {
        val out = mutableListOf<Found>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeLast())
            val cursor = resolver.query(children, columns, null, null, null) ?: throw IllegalStateException("No listing")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith('.')) continue
                    val mime = c.getString(2)
                    if (mime == Document.MIME_TYPE_DIR) {
                        pending.add(id)
                        continue
                    }
                    val format = Books.formatOf(name, mime) ?: continue
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size, format)
                }
            }
        }
        return out
    }

    private fun plural(n: Int) = if (n == 1) "1 book" else "$n books"
}
