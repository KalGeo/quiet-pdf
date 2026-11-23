// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Recent(val uri: Uri, val name: String)

/**
 * A place in a document: the page at the top of the screen and how far into it the screen
 * starts, as a fraction of the page's height (so it means the same at any screen width).
 */
data class Position(val page: Int, val fraction: Float)

/** Small persistent settings: night mode, recent files and where reading stopped in each file. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Reading positions by file id ([PdfDoc.fileId]), least recently saved first. */
    private val positions = loadPositions()

    var nightMode: Boolean
        get() = prefs.getBoolean("night", false)
        set(value) = prefs.edit { putBoolean("night", value) }

    fun position(fileId: String): Position? = positions[fileId]

    fun savePosition(fileId: String, position: Position) {
        if (positions[fileId] == position) return
        positions.remove(fileId) // re-insert, so the map stays ordered by last save
        positions[fileId] = position
        while (positions.size > MAX_POSITIONS) positions.remove(positions.keys.first())
        val array = JSONArray()
        for ((id, p) in positions) array.put(JSONArray().put(id).put(p.page).put(p.fraction.toDouble()))
        prefs.edit { putString("positions", array.toString()) }
    }

    /**
     * The page saved for [uri] by the first builds, which remembered a page per URI rather
     * than a position per file. Returned once, then forgotten.
     */
    fun takeLegacyPage(uri: Uri): Int? {
        val key = "page:$uri"
        if (!prefs.contains(key)) return null
        val page = prefs.getInt(key, 0)
        prefs.edit { remove(key) }
        return page
    }

    fun recents(): List<Recent> = try {
        val array = JSONArray(prefs.getString("recents", "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Recent(Uri.parse(o.getString("uri")), o.getString("name"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Moves [recent] to the top of the list; returns the entries that fell off the end. */
    fun addRecent(recent: Recent): List<Recent> {
        val all = listOf(recent) + recents().filter { it.uri != recent.uri }
        write(all.take(MAX_RECENTS))
        return all.drop(MAX_RECENTS)
    }

    fun removeRecent(uri: Uri) = write(recents().filter { it.uri != uri })

    private fun write(list: List<Recent>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)) }
        prefs.edit { putString("recents", array.toString()) }
    }

    private fun loadPositions(): LinkedHashMap<String, Position> {
        val map = LinkedHashMap<String, Position>()
        try {
            val array = JSONArray(prefs.getString("positions", "[]"))
            for (i in 0 until array.length()) {
                val entry = array.getJSONArray(i)
                map[entry.getString(0)] = Position(entry.getInt(1), entry.getDouble(2).toFloat())
            }
        } catch (e: Exception) {
            // Unreadable: start with no saved positions.
        }
        return map
    }

    private companion object {
        const val MAX_RECENTS = 20
        const val MAX_POSITIONS = 500
    }
}
