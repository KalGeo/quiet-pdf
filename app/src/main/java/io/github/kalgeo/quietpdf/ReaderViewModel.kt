// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Loading : Screen
    class Reader(
        val doc: PdfDoc,
        val uri: Uri,
        val name: String,
        /** Where reading stopped last time. */
        val start: Position,
        /** Opened from another app: Back returns there instead of to our home screen. */
        val external: Boolean,
    ) : Screen
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val resolver get() = getApplication<Application>().contentResolver

    var screen: Screen by mutableStateOf(Screen.Home)
        private set
    var recents by mutableStateOf(store.recents())
        private set
    var nightMode by mutableStateOf(store.nightMode)
        private set
    /** String resource of an error to show once, or null. */
    var error by mutableStateOf<Int?>(null)
        private set

    private var loadJob: Job? = null

    fun open(uri: Uri, external: Boolean = false) {
        if ((screen as? Screen.Reader)?.uri == uri) return
        closeDocument()
        screen = Screen.Loading
        loadJob = viewModelScope.launch {
            // Files picked with the system picker (and some shared from other apps) can be kept
            // readable across restarts; only those go into the recent list.
            val persisted = try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e: SecurityException) {
                false
            }
            try {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                val doc = PdfDoc.open(getApplication(), uri) // last suspension point, so a cancelled load leaks nothing
                if (persisted) {
                    store.addRecent(Recent(uri, name)).forEach { release(it.uri) }
                    recents = store.recents()
                }
                val saved = store.position(doc.fileId) ?: Position(store.takeLegacyPage(uri) ?: 0, 0f)
                val start = saved.copy(page = saved.page.coerceIn(0, doc.pageCount - 1))
                screen = Screen.Reader(doc, uri, name, start, external)
            } catch (e: PdfOpenException) {
                screen = Screen.Home
                error = when (e.reason) {
                    PdfOpenException.Reason.NoAccess -> R.string.error_access
                    PdfOpenException.Reason.Protected -> R.string.error_protected
                    PdfOpenException.Reason.Invalid -> R.string.error_open
                }
            }
        }
    }

    fun closeDocument() {
        loadJob?.cancel()
        (screen as? Screen.Reader)?.doc?.close()
        screen = Screen.Home
    }

    fun savePosition(doc: PdfDoc, position: Position) = store.savePosition(doc.fileId, position)

    fun toggleNightMode() {
        nightMode = !nightMode
        store.nightMode = nightMode
    }

    fun removeRecent(uri: Uri) {
        store.removeRecent(uri)
        recents = store.recents()
        release(uri)
    }

    fun clearError() {
        error = null
    }

    override fun onCleared() {
        (screen as? Screen.Reader)?.doc?.close()
    }

    private fun release(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone.
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (e: Exception) {
            // Fall through to the URI's last segment.
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
    }
}
