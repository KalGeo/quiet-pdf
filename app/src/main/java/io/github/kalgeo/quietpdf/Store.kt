// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Recent(val uri: Uri, val name: String)

/** Small persistent settings: night mode, recent files and the last page read in each file. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var nightMode: Boolean
        get() = prefs.getBoolean("night", false)
        set(value) = prefs.edit { putBoolean("night", value) }

    fun lastPage(uri: Uri): Int = prefs.getInt(pageKey(uri), 0)

    fun saveLastPage(uri: Uri, page: Int) = prefs.edit { putInt(pageKey(uri), page) }

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
        val dropped = all.drop(MAX_RECENTS)
        write(all.take(MAX_RECENTS))
        prefs.edit { dropped.forEach { remove(pageKey(it.uri)) } }
        return dropped
    }

    fun removeRecent(uri: Uri) {
        write(recents().filter { it.uri != uri })
        prefs.edit { remove(pageKey(uri)) }
    }

    private fun write(list: List<Recent>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name)) }
        prefs.edit { putString("recents", array.toString()) }
    }

    private fun pageKey(uri: Uri) = "page:$uri"

    private companion object {
        const val MAX_RECENTS = 20
    }
}
