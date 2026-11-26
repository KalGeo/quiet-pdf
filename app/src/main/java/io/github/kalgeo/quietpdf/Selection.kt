// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.pdf.models.selection.SelectionBoundary
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Selected text on one page, with its boxes in PDF points (top-left origin), in reading order,
 * and the points where it starts and stops as PdfRenderer reported them (the edges of the first
 * and last characters; null if it gave none).
 */
class TextSelection(
    val page: Int,
    val text: String,
    val rects: List<RectF>,
    val start: Point?,
    val stop: Point?,
)

private val HandleSize = 22.dp
/** The area that catches a drag: larger than the drawn handle, so it is easy to grab. */
private val HandleTouchSize = 44.dp

/**
 * Long press selects a word; two handles stretch the selection; the system's floating toolbar
 * offers Copy. Works on one page at a time, like the search, on Android 15+.
 *
 * All methods run on the main thread.
 */
@Stable
class SelectionState(private val doc: PdfDoc, private val zoom: ZoomState) {
    var selection by mutableStateOf<TextSelection?>(null)
        private set

    /** The one running lookup (a word, or a handle drag); a new one or [clear] cancels it. */
    private var job: Job? = null

    /** While a handle is dragged: the end that stays put, as PdfRenderer reported it. */
    private var anchor: Point? = null
    /** While a handle is dragged: the latest finger position not yet looked up. */
    private var dragTarget: Offset? = null

    val active: Boolean get() = selection != null

    fun clear() {
        job?.cancel()
        dragTarget = null
        selection = null
    }

    /** Selects the word under [screen] (or clears the selection if there is no text there). */
    fun selectWord(scope: CoroutineScope, screen: Offset, onSelected: () -> Unit) {
        job?.cancel()
        dragTarget = null
        job = scope.launch {
            val (page, point) = toPdf(screen) ?: return@launch
            val here = SelectionBoundary(point)
            val found = doc.selectText(page, here, here)
            selection = found
            if (found != null) onSelected()
        }
    }

    /** A handle drag begins: the other end stays where it is for the whole drag. */
    fun beginDrag(start: Boolean) {
        val current = selection ?: return
        // Fixed for the whole drag: rebuilding it from the shrinking or growing selection on
        // every move would let that end drift by a character at a time.
        anchor = if (start) current.stop else current.start
    }

    /**
     * Moves the dragged end to the text under [screen]. Lookups run one at a time; positions
     * that arrive meanwhile are merged into the latest, so the selection keeps up with the
     * finger instead of waiting for it to stop.
     */
    fun drag(scope: CoroutineScope, screen: Offset) {
        dragTarget = screen
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val target = dragTarget ?: break
                dragTarget = null
                val current = selection ?: break
                val (page, point) = toPdf(target) ?: continue
                if (page != current.page) continue
                val fixed = anchor ?: break
                doc.selectText(page, SelectionBoundary(fixed), SelectionBoundary(point))
                    ?.let { selection = it }
            }
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
    val context = LocalContext.current
    val toolbar = LocalTextToolbar.current
    val handlePx = with(LocalDensity.current) { HandleSize.toPx() }
    val current = selection.selection

    // The toolbar is the system's, outside our layout: hide it whenever the selection goes,
    // and when the reader itself goes.
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }

    // It shows once the selection and the page have been still for a moment.
    LaunchedEffect(current) {
        if (current == null) {
            toolbar.hide()
            return@LaunchedEffect
        }
        snapshotFlow {
            val a = selection.toScreen(current.rects.minOf { it.left }, current.rects.minOf { it.top })
            val b = selection.toScreen(current.rects.maxOf { it.right }, current.rects.maxOf { it.bottom })
            // Down to the bottom of the handles, so a toolbar placed below does not cover them.
            if (a != null && b != null) Rect(a, b.copy(y = b.y + handlePx)) else null
        }.collectLatest { rect ->
            toolbar.hide()
            if (rect == null) {
                // Its page has left the screen (scrolled away, a jump, a search): a selection
                // nobody can see would only swallow the next tap or Back.
                selection.clear()
                return@collectLatest
            }
            delay(250)
            toolbar.showMenu(rect = rect, onCopyRequested = { selection.copy(context) })
        }
    }

    if (current == null) return
    val first = current.rects.first()
    val last = current.rects.last()
    val startAt = selection.toScreen(first.left, first.bottom)
    val endAt = selection.toScreen(last.right, last.bottom)

    // Screen coordinates are left-to-right whatever the language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize()) {
            if (startAt != null) Handle(start = true, at = startAt, lineHeight = first.height(), selection, scope)
            if (endAt != null) Handle(start = false, at = endAt, lineHeight = last.height(), selection, scope)
        }
    }
}

/**
 * A teardrop hanging below the selection's [start] or end, pointing at [at] (screen pixels).
 * Dragging it moves that end to the text under the finger, aimed half a line above the handle.
 */
@Composable
private fun Handle(start: Boolean, at: Offset, lineHeight: Float, selection: SelectionState, scope: CoroutineScope) {
    val touchPx = with(LocalDensity.current) { HandleTouchSize.toPx() }
    val anchor by rememberUpdatedState(at)
    val lift by rememberUpdatedState(lineHeight / 2f * selection.pdfToScreen())
    Box(
        Modifier
            // The touch area extends outwards and down from the point, like the drawn drop.
            .offset { IntOffset((if (start) at.x - touchPx else at.x).roundToInt(), at.y.roundToInt()) }
            .size(HandleTouchSize)
            .pointerInput(start) {
                var finger = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        finger = anchor
                        selection.beginDrag(start)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        finger += amount
                        selection.drag(scope, finger - Offset(0f, lift))
                    },
                )
            }
    ) {
        Box(
            Modifier
                .align(if (start) Alignment.TopEnd else Alignment.TopStart)
                .size(HandleSize)
                .background(
                    MaterialTheme.colorScheme.primary,
                    if (start) RoundedCornerShape(50, 0, 50, 50) else RoundedCornerShape(0, 50, 50, 50),
                )
        )
    }
}
