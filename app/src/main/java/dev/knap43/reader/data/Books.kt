package dev.knap43.reader.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.io.IOException
import java.util.UUID

/** Opens EPUBs through Readium and imports new books into the app's private storage. */
class Books(private val context: Context, private val library: Library) {

    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null),
    )

    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    /** Publications currently open in a reader, handed from the library to the reader activity. */
    val open = mutableMapOf<String, Publication>()

    suspend fun openEpub(file: File): Publication {
        val asset = when (val r = assetRetriever.retrieve(file.toUrl())) {
            is Try.Success -> r.value
            is Try.Failure -> throw IOException("Couldn't read the file (${r.value.message})")
        }
        return when (val r = opener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> {
                asset.close()
                throw IOException("Not a readable EPUB (${r.value.message})")
            }
        }
    }

    fun close(id: String) {
        open.remove(id)?.close()
    }

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "Untitled"
        val mime = resolver.getType(uri)
        val format = when {
            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> Format.PDF
            mime == "application/epub+zip" || name.endsWith(".epub", ignoreCase = true) -> Format.EPUB
            else -> throw IOException("Only EPUB and PDF files are supported")
        }
        val id = UUID.randomUUID().toString()
        val file = File(booksDir, "$id.${format.name.lowercase()}")
        (resolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }

        val fallbackTitle = name.substringBeforeLast('.')
        val book = try {
            when (format) {
                Format.EPUB -> {
                    val pub = openEpub(file)
                    try {
                        Book(
                            id = id,
                            title = pub.metadata.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                            author = pub.metadata.authors.joinToString(", ") { it.name }.ifBlank { null },
                            format = format,
                            path = file.absolutePath,
                            coverPath = pub.cover()?.let { saveCover(id, it) },
                        )
                    } finally {
                        pub.close()
                    }
                }
                Format.PDF -> Book(
                    id = id,
                    title = fallbackTitle,
                    author = null,
                    format = format,
                    path = file.absolutePath,
                    coverPath = renderPdfCover(file)?.let { saveCover(id, it) },
                )
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        library.add(book)
        book
    }

    private fun saveCover(id: String, bitmap: Bitmap): String {
        val width = 360
        val scaled = if (bitmap.width > width) {
            Bitmap.createScaledBitmap(bitmap, width, bitmap.height * width / bitmap.width, true)
        } else bitmap
        val out = File(coversDir, "$id.jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return out.absolutePath
    }

    private fun renderPdfCover(file: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val page = renderer.openPage(0)
                try {
                    val w = 360
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } finally {
                    page.close()
                }
            }
        }
    }.getOrNull()

    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }
}
