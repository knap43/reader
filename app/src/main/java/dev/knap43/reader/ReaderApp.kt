package dev.knap43.reader

import android.app.Application
import dev.knap43.reader.data.Annotations
import dev.knap43.reader.data.Books
import dev.knap43.reader.data.Dictionary
import dev.knap43.reader.data.Library
import dev.knap43.reader.data.ReaderPrefs
import java.io.File

class ReaderApp : Application() {
    lateinit var library: Library
        private set
    lateinit var books: Books
        private set
    lateinit var prefs: ReaderPrefs
        private set
    lateinit var dictionary: Dictionary
        private set
    lateinit var annotations: Annotations
        private set

    override fun onCreate() {
        super.onCreate()
        library = Library(File(filesDir, "library.json"))
        books = Books(this, library)
        prefs = ReaderPrefs(this)
        dictionary = Dictionary(this)
        annotations = Annotations(File(filesDir, "annotations"))
    }
}

val android.content.Context.readerApp: ReaderApp get() = applicationContext as ReaderApp
