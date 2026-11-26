// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Hands the PDF to Android's print service, which shows its own dialog (printer, pages,
 * copies, "Save as PDF"). The file is passed through unchanged; the print service picks out
 * the pages the user chose. Needs no permission: printers are reached by the system.
 *
 * A PDF that arrived as a stream is printed from the app's own [copy] of it, since the
 * stream cannot be read a second time.
 */
fun printPdf(context: Context, uri: Uri, copy: File?, name: String) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val app = context.applicationContext
    val source = { copy?.inputStream() ?: app.contentResolver.openInputStream(uri) ?: throw IOException("No data") }
    val title = name.ifBlank { "PDF" } // the print framework refuses an empty name
    manager.print(title, PdfPrintAdapter(source, title), null)
}

private class PdfPrintAdapter(
    private val source: () -> InputStream,
    private val name: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        // The document never changes with the paper size: the print service scales it.
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        // Called on the main thread: copy the file on a background one.
        Thread {
            try {
                source().use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal.isCanceled) {
                                callback.onWriteCancelled()
                                return@Thread
                            }
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }
}
