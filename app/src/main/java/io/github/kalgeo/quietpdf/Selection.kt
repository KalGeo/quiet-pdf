// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Selected text on one page, with its boxes in PDF points (top-left origin), in reading order. */
class TextSelection(val page: Int, val text: String, val rects: List<RectF>)

private val HandleSize = 22.dp

/**
 * Long press selects a word; two handles stretch the selection; the system's floating toolbar
 * offers Copy. Works on one page at a time, like the search, on Android 15+.
 */
@Stable
class SelectionState(private val doc: PdfDoc, private val zoom: ZoomState) {
    var selection by mutableStateOf<TextSelection?>(null)
        private set

    private var job: Job? = null

    val active: Boolean get() = selection != null

    fun clear() {
        job?.cancel()
        selection = null
    }

    /** Selects the word under [screen]; false if there is no text there. */
    suspend fun selectWord(screen: Offset): Boolean {
        val (page, point) = toPdf(screen) ?: return false
        val found = doc.selectText(page, point, point) ?: return false
        selection = found
        return true
    }

    /** Moves one end of the selection to the text under [screen], keeping the other end. */
    fun moveEnd(scope: CoroutineScope, start: Boolean, screen: Offset) {
        val current = selection ?: return
        val (page, point) = toPdf(screen) ?: return
        if (page != current.page) return
        val first = current.rects.first()
        val last = current.rects.last()
        val keepStart = Point(first.left.roundToInt() + 1, first.centerY().roundToInt())
        val keepStop = Point(last.right.roundToInt() - 1, last.centerY().roundToInt())
        job?.cancel()
        job = scope.launch {
            val found = if (start) doc.selectText(page, point, keepStop) else doc.selectText(page, keepStart, point)
            if (found != null) selection = found
        }
    }

    fun copy(context: Context) {
        val text = selection?.text ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        clear()
    }

    /** The page under a point on screen and that point in the page's PDF points. */
    private fun toPdf(screen: Offset): Pair<Int, Point>? {
        val (page, inPage) = zoom.screenToPage(screen) ?: return null
        val k = doc.pageSize(page).width / zoom.viewport.width.toFloat()
        return page to Point((inPage.x * k).roundToInt(), (inPage.y * k).roundToInt())
    }

    /** Screen pixels per PDF point on the selected page at the current zoom. */
    fun pdfToScreen(): Float {
        val page = selection?.page ?: return 1f
        return zoom.viewport.width / doc.pageSize(page).width.toFloat() * zoom.scale
    }

    /** A point of the selected page, in PDF points, on screen; null when that page is off screen. */
    fun toScreen(x: Float, y: Float): Offset? {
        val page = selection?.page ?: return null
        val k = zoom.viewport.width / doc.pageSize(page).width.toFloat()
        return zoom.pageToScreen(page, x * k, y * k)
    }
}

/** Shades the selected text on page [index]; drawn over the page image. */
@Composable
fun SelectionHighlight(selection: SelectionState, index: Int, pageSize: IntSize) {
    val current = selection.selection?.takeIf { it.page == index } ?: return
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.33f)
    Canvas(Modifier.fillMaxSize()) {
        val k = size.width / pageSize.width
        for (r in current.rects) {
            drawRect(color, Offset(r.left * k, r.top * k), Size(r.width() * k, r.height() * k))
        }
    }
}

/**
 * The two drag handles, and the system's text toolbar (Copy) above the selection.
 * Drawn over the pages in screen coordinates; it follows scrolling and zooming.
 */
@Composable
fun SelectionHandles(selection: SelectionState, scope: CoroutineScope) {
    val current = selection.selection ?: return
    val context = LocalContext.current
    val toolbar = LocalTextToolbar.current
    val first = current.rects.first()
    val last = current.rects.last()
    val startAt = selection.toScreen(first.left, first.bottom)
    val endAt = selection.toScreen(last.right, last.bottom)

    // The toolbar shows once the selection and the page have been still for a moment.
    val latest by rememberUpdatedState(current)
    LaunchedEffect(current) {
        snapshotFlow {
            val top = latest.rects.minOf { it.top }
            val bottom = latest.rects.maxOf { it.bottom }
            val a = selection.toScreen(latest.rects.minOf { it.left }, top)
            val b = selection.toScreen(latest.rects.maxOf { it.right }, bottom)
            if (a != null && b != null) Rect(a, b) else null
        }.collectLatest { rect ->
            toolbar.hide()
            if (rect == null) return@collectLatest
            delay(250)
            toolbar.showMenu(
                rect = rect,
                onCopyRequested = { selection.copy(context) },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (startAt != null) Handle(start = true, at = startAt, lineHeight = first.height(), selection, scope)
        if (endAt != null) Handle(start = false, at = endAt, lineHeight = last.height(), selection, scope)
    }
}

/**
 * A teardrop hanging below the selection's [start] or end, pointing at [at] (screen pixels).
 * Dragging it moves that end to the text under the finger, aimed half a line above the handle.
 */
@Composable
private fun Handle(start: Boolean, at: Offset, lineHeight: Float, selection: SelectionState, scope: CoroutineScope) {
    val sizePx = with(LocalDensity.current) { HandleSize.toPx() }
    val anchor by rememberUpdatedState(at)
    val lift by rememberUpdatedState(lineHeight / 2f * selection.pdfToScreen())
    Box(
        Modifier
            .offset { IntOffset((if (start) at.x - sizePx else at.x).roundToInt(), at.y.roundToInt()) }
            .size(HandleSize)
            .background(
                MaterialTheme.colorScheme.primary,
                if (start) RoundedCornerShape(50, 0, 50, 50) else RoundedCornerShape(0, 50, 50, 50),
            )
            .pointerInput(start) {
                var finger = Offset.Zero
                detectDragGestures(
                    onDragStart = { finger = anchor },
                    onDrag = { change, amount ->
                        change.consume()
                        finger += amount
                        selection.moveEnd(scope, start, finger - Offset(0f, lift))
                    },
                )
            }
    )
}
