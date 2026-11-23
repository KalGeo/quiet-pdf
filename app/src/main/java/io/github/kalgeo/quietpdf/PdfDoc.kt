// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class PdfOpenException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NoAccess, Protected, Invalid }
}

/**
 * An open PDF, rendered with the platform [PdfRenderer].
 *
 * PdfRenderer is not thread-safe and allows only one open page at a time, so every call into it
 * (open, render, close) runs on one shared single-thread dispatcher.
 */
class PdfDoc private constructor(
    private val renderer: PdfRenderer,
    /** Page sizes in PDF points (1/72 inch). */
    val pageSizes: List<IntSize>,
) {
    val pageCount: Int get() = pageSizes.size

    @Volatile private var closed = false

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    /** Renders page [index] [widthPx] pixels wide (height follows the page's aspect ratio). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        val key = (index.toLong() shl 32) or widthPx.toLong()
        cache.get(key)?.let { return@withContext it }
        if (closed) return@withContext null
        val size = pageSizes[index]
        val heightPx = (widthPx.toFloat() * size.height / size.width).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE) // PDF pages are transparent where nothing is drawn
        renderer.openPage(index).use { page ->
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        cache.put(key, bitmap)
        bitmap
    }

    /** Finds [query] on page [index]. Needs Android 15+ ([canSearch]); returns nothing before. */
    suspend fun search(index: Int, query: String): List<SearchMatch> = withContext(dispatcher) {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return@withContext emptyList()
        try {
            renderer.openPage(index).use { page ->
                page.searchText(query).map { SearchMatch(index, it.bounds) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Closes after any render already queued; later renders return null. */
    fun close() {
        closed = true
        dispatcher.executor.execute {
            cache.evictAll()
            renderer.close() // also closes the file descriptor it owns
        }
    }

    companion object {
        /** Text search is built into PdfRenderer from Android 15. */
        val canSearch: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        private const val CACHE_BYTES = 64 * 1024 * 1024

        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        suspend fun open(context: Context, uri: Uri): PdfDoc = withContext(dispatcher) {
            var pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            } ?: throw PdfOpenException(PdfOpenException.Reason.NoAccess)

            // PdfRenderer needs a seekable file. Some apps share PDFs as a stream (a pipe);
            // statSize is -1 then, so copy it to a cache file first.
            if (pfd.statSize < 0) {
                pfd.close()
                val copy = File(context.cacheDir, "shared.pdf")
                try {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        copy.outputStream().use { input.copyTo(it) }
                    }
                } catch (e: Exception) {
                    throw PdfOpenException(PdfOpenException.Reason.NoAccess)
                }
                pfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                pfd.close()
                throw PdfOpenException(PdfOpenException.Reason.Protected)
            } catch (e: Exception) {
                pfd.close()
                throw PdfOpenException(PdfOpenException.Reason.Invalid)
            }

            try {
                val sizes = List(renderer.pageCount) { i ->
                    renderer.openPage(i).use { IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
                }
                if (sizes.isEmpty()) throw PdfOpenException(PdfOpenException.Reason.Invalid)
                PdfDoc(renderer, sizes)
            } catch (e: Exception) {
                renderer.close()
                throw e as? PdfOpenException ?: PdfOpenException(PdfOpenException.Reason.Invalid)
            }
        }
    }
}
