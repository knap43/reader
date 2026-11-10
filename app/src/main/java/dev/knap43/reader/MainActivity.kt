package dev.knap43.reader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import dev.knap43.reader.data.Book
import dev.knap43.reader.data.Format
import dev.knap43.reader.reader.EpubReaderActivity
import dev.knap43.reader.reader.PdfReaderActivity
import dev.knap43.reader.ui.LibraryScreen
import dev.knap43.reader.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Shown as a full-screen spinner while a book is being imported or opened. */
    val busy = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LibraryScreen(
                        busy = busy,
                        messages = messages,
                        onChooseFolder = readerApp.folder::choose,
                        onOpen = ::openBook,
                    )
                }
            }
        }
        lifecycleScope.launch { readerApp.folder.messages.collect { messages.tryEmit(it) } }
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            handleViewIntent(intent)
        } else {
            // A cold start or a restore after process death: return to the book that was open.
            readerApp.prefs.resumeBookId?.let { readerApp.library.get(it) }?.let(::openBook)
        }
    }

    override fun onStart() {
        super.onStart()
        // Pick up books added to or removed from the folder while the library was out of sight.
        readerApp.folder.scan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { importAndMaybeOpen(listOf(it), openAfter = true) }
        }
    }

    private fun importAndMaybeOpen(uris: List<Uri>, openAfter: Boolean = false) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            busy.value = if (uris.size == 1) "Adding book…" else "Adding ${uris.size} books…"
            var last: Book? = null
            var failed = 0
            for (uri in uris) {
                runCatching { readerApp.books.import(uri) }
                    .onSuccess { last = it }
                    .onFailure { failed++; messages.tryEmit(it.message ?: "Couldn't add that file") }
            }
            busy.value = null
            if (uris.size > 1 && failed == 0) messages.tryEmit("Added ${uris.size} books")
            val book = last
            if (openAfter && book != null) openBook(book)
        }
    }

    private fun openBook(book: Book) = lifecycleScope.launch {
        val app = readerApp
        if (!app.books.exists(book)) {
            messages.tryEmit("That book's file is missing")
            app.folder.scan()
            return@launch
        }
        app.library.markOpened(book.id)
        app.prefs.resumeBookId = book.id
        when (book.format) {
            Format.PDF -> startActivity(PdfReaderActivity.intent(this@MainActivity, book.id))
            Format.EPUB -> {
                busy.value = "Opening…"
                val result = runCatching { app.books.openEpub(book.uri) }
                busy.value = null
                result
                    .onSuccess { pub ->
                        app.books.close(book.id)
                        app.books.open[book.id] = pub
                        startActivity(EpubReaderActivity.intent(this@MainActivity, book.id))
                    }
                    .onFailure { messages.tryEmit(it.message ?: "Couldn't open the book") }
            }
        }
    }
}
