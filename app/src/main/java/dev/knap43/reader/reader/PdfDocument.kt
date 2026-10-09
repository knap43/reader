package dev.knap43.reader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper around [PdfRenderer]. The renderer allows one open page at a time,
 * so every call is serialized behind a mutex and runs off the main thread.
 */
class PdfDocument private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** width / height for each page */
    val aspectRatios: FloatArray,
) : Closeable {

    private val lock = Mutex()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val pageCount get() = aspectRatios.size

    @Volatile
    private var closed = false

    /** Returns null once the document has been closed or the page can't be drawn. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return lock.withLock {
            withContext(Dispatchers.IO) {
                if (closed) return@withContext null
                cache.get(key) ?: runCatching { renderUncached(index, widthPx) }.getOrNull()?.also { cache.put(key, it) }
            }
        }
    }

    private fun renderUncached(index: Int, widthPx: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val w = widthPx.coerceIn(1, MAX_WIDTH)
            val h = (w / aspectRatios[index]).toInt().coerceAtLeast(1)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    /** Waits for any in-flight render, then releases the renderer. */
    suspend fun closeSafely() = lock.withLock {
        if (closed) return@withLock
        closed = true
        cache.evictAll()
        renderer.close()
        pfd.close()
    }

    override fun close() {
        // Prefer closeSafely(); this exists for Closeable callers that hold no other references.
        if (closed) return
        closed = true
        renderer.close()
        pfd.close()
    }

    companion object {
        private const val CACHE_BYTES = 64 * 1024 * 1024
        private const val MAX_WIDTH = 2400

        suspend fun open(file: File): PdfDocument = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(pfd)
                val ratios = FloatArray(renderer.pageCount) { i ->
                    val p = renderer.openPage(i)
                    try {
                        p.width.toFloat() / p.height.coerceAtLeast(1)
                    } finally {
                        p.close()
                    }
                }
                PdfDocument(pfd, renderer, ratios)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }
    }
}
