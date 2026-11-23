// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One occurrence of the search text: its page and its boxes in PDF points (top-left origin). */
class SearchMatch(val page: Int, val rects: List<RectF>)

private val MatchColor = Color(0x66FFD600)
private val CurrentMatchColor = Color(0x88FF6D00)

@Stable
class SearchState(private val doc: PdfDoc) {
    var active by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<SearchMatch>>(emptyList())
        private set
    /** Index into [matches] of the selected match, or -1. */
    var current by mutableIntStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set
    var searched by mutableStateOf(false)
        private set

    /** Matches grouped by page, each with its index in [matches]. */
    val byPage by derivedStateOf { matches.withIndex().groupBy { it.value.page } }

    private var job: Job? = null

    fun open() {
        active = true
    }

    fun close() {
        job?.cancel()
        active = false
        query = ""
        matches = emptyList()
        current = -1
        searching = false
        searched = false
    }

    /**
     * Searches every page in order, publishing matches as they are found. The first match on
     * or after [startPage] is selected and shown as soon as it turns up.
     */
    fun run(scope: CoroutineScope, startPage: Int, reveal: suspend (SearchMatch) -> Unit) {
        job?.cancel()
        matches = emptyList()
        current = -1
        val text = query.trim()
        if (text.isEmpty()) return
        searching = true
        searched = true
        job = scope.launch {
            val found = ArrayList<SearchMatch>()
            for (page in 0 until doc.pageCount) {
                val pageMatches = doc.search(page, text)
                if (pageMatches.isEmpty()) continue
                found += pageMatches
                matches = found.toList()
                if (current == -1 && page >= startPage) {
                    current = found.size - pageMatches.size
                    reveal(found[current])
                }
            }
            if (current == -1 && found.isNotEmpty()) {
                current = 0
                reveal(found[0])
            }
            searching = false
        }
    }

    fun step(delta: Int, scope: CoroutineScope, reveal: suspend (SearchMatch) -> Unit) {
        if (matches.isEmpty()) return
        current = (current + delta).mod(matches.size)
        val match = matches[current]
        scope.launch { reveal(match) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(search: SearchState, onSearch: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = search::close) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_search))
            }
        },
        title = {
            TextField(
                value = search.query,
                onValueChange = { search.query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (search.searching && search.matches.isEmpty()) {
                CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(20.dp), strokeWidth = 2.dp)
            } else if (search.searched) {
                Text(
                    "${search.current + 1}/${search.matches.size}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onPrevious, enabled = search.matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.previous))
            }
            IconButton(onClick = onNext, enabled = search.matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.next))
            }
        },
    )
}

/** Highlights the matches on page [index]; drawn over the page image. */
@Composable
fun MatchHighlights(search: SearchState, index: Int, pageSize: IntSize) {
    val pageMatches = search.byPage[index] ?: return
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / pageSize.width
        for ((i, match) in pageMatches) {
            val color = if (i == search.current) CurrentMatchColor else MatchColor
            for (r in match.rects) {
                drawRect(color, Offset(r.left * k, r.top * k), Size(r.width() * k, r.height() * k))
            }
        }
    }
}
