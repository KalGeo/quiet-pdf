// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.pdf.content.PdfPageTextContent
import android.graphics.pdf.models.selection.SelectionBoundary
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.LruCache
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
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
    val pageCount: Int,
    firstPageSize: IntSize,
    /** Identifies this file by its content, so it is recognised however it was opened. */
    val fileId: String,
) {
    @Volatile private var closed = false

    /*
     * Page sizes. Reading them all means opening every page, which takes seconds in a book of
     * a thousand pages, so a document opens with only the first page's size known. The others
     * start as that size and are read in the background, and at once for any page that is
     * rendered, searched or asked for with [loadPageSize]. Each is Compose state, so a page
     * whose size turns out different is laid out again.
     */
    private val sizes = List(pageCount) { mutableStateOf(firstPageSize) }
    private val known = BooleanArray(pageCount).also { it[0] = true }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            for (i in 0 until pageCount) {
                if (closed) break
                if (!known[i]) openPage(i).close()
                yield() // let renders queued meanwhile go first
            }
        }
    }

    /** Page [index]'s size in PDF points (1/72 inch); an estimate until it has been read. */
    fun pageSize(index: Int): IntSize = sizes[index].value

    /** Reads page [index]'s size now if it is not known yet, and returns it. */
    suspend fun loadPageSize(index: Int): IntSize = withContext(dispatcher) {
        if (!known[index] && !closed) openPage(index).close()
        sizes[index].value
    }

    /** Opens page [index] and records its size. Only on [dispatcher]. */
    private fun openPage(index: Int): PdfRenderer.Page {
        val page = renderer.openPage(index)
        if (!known[index]) {
            known[index] = true
            val size = IntSize(page.width.coerceAtLeast(1), page.height.coerceAtLeast(1))
            if (size != sizes[index].value) sizes[index].value = size
        }
        return page
    }

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    /** Renders page [index] [widthPx] pixels wide (height follows the page's aspect ratio). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        val key = (index.toLong() shl 32) or widthPx.toLong()
        cache.get(key)?.let { return@withContext it }
        if (closed) return@withContext null
        openPage(index).use { page ->
            val size = sizes[index].value
            val heightPx = (widthPx.toFloat() * size.height / size.width).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE) // PDF pages are transparent where nothing is drawn
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(key, bitmap)
            bitmap
        }
    }

    /** Finds [query] on page [index]. Needs Android 15+ ([canSearch]); returns nothing before. */
    suspend fun search(index: Int, query: String): List<SearchMatch> = withContext(dispatcher) {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return@withContext emptyList()
        try {
            openPage(index).use { page ->
                page.searchText(query).map { SearchMatch(index, it.bounds) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Selects the text on page [index] from [start] to [stop] (PDF points, top-left origin).
     * When both are the same point, the word under it is selected. Android 15+ only.
     */
    suspend fun selectText(index: Int, start: Point, stop: Point): TextSelection? = withContext(dispatcher) {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return@withContext null
        try {
            openPage(index).use { page ->
                val selection = page.selectContent(SelectionBoundary(start), SelectionBoundary(stop))
                    ?: return@use null
                textSelection(index, selection.selectedTextContents)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun textSelection(index: Int, contents: List<PdfPageTextContent>): TextSelection? {
        val text = contents.joinToString("\n") { it.text }.trim()
        val rects = contents.flatMap { it.bounds }.filter { it.width() > 0f && it.height() > 0f }
        return if (text.isEmpty() || rects.isEmpty()) null else TextSelection(index, text, rects)
    }

    /** Closes after any render already queued; later renders return null. */
    fun close() {
        closed = true
        scope.cancel()
        dispatcher.executor.execute {
            cache.evictAll()
            renderer.close() // also closes the file descriptor it owns
        }
    }

    companion object {
        /** Text search is built into PdfRenderer from Android 15. */
        val canSearch: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        private const val CACHE_BYTES = 64 * 1024 * 1024

        private const val FINGERPRINT_CHUNK = 16 * 1024

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

            // Before the renderer takes over the descriptor.
            val fileId = fingerprint(pfd) ?: "uri:$uri"

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
                if (renderer.pageCount == 0) throw PdfOpenException(PdfOpenException.Reason.Invalid)
                val first = renderer.openPage(0).use { IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
                PdfDoc(renderer, renderer.pageCount, first, fileId)
            } catch (e: Exception) {
                renderer.close()
                throw e as? PdfOpenException ?: PdfOpenException(PdfOpenException.Reason.Invalid)
            }
        }

        /**
         * A hash of the file's size and its first and last 16 KB. The same file gets the same
         * id whichever app or URI it is opened through, which a content URI does not guarantee.
         * Reads at explicit offsets, leaving the descriptor's own position untouched.
         */
        private fun fingerprint(pfd: ParcelFileDescriptor): String? = try {
            val size = pfd.statSize
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(size.toString().toByteArray())
            val chunk = ByteArray(FINGERPRINT_CHUNK)
            for (start in setOf(0L, (size - chunk.size).coerceAtLeast(0L))) {
                var filled = 0
                while (filled < chunk.size) {
                    val n = Os.pread(pfd.fileDescriptor, chunk, filled, chunk.size - filled, start + filled)
                    if (n <= 0) break
                    filled += n
                }
                digest.update(chunk, 0, filled)
            }
            digest.digest().take(16).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
