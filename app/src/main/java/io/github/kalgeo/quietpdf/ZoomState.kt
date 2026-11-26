// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Zoom and pan over a vertical list of pages.
 *
 * The list is drawn scaled by [scale] around its top-left corner and shifted by
 * ([offsetX], [offsetY]) screen pixels. Vertical movement scrolls the list itself;
 * [offsetY] only becomes non-zero at the end of the document, where the list can't scroll
 * further but the scaled-up bottom still needs to be reachable.
 */
@Stable
class ZoomState(val list: LazyListState) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var viewport by mutableStateOf(IntSize.Zero)
        private set

    private var job: Job? = null

    private val minX get() = -(scale - 1f) * viewport.width
    private val minY get() = -(scale - 1f) * viewport.height

    /**
     * The list's top content padding. Item offsets reported by the list start after it, so it
     * has to be added to get an item's position inside the viewport.
     */
    private val padTop get() = list.layoutInfo.beforeContentPadding

    /** How sharp pages should be rendered (1x, 2x or 3x the screen width). */
    val renderLevel: Int
        get() = when {
            scale < 1.3f -> 1
            scale < 2.3f -> 2
            else -> 3
        }

    fun updateViewport(size: IntSize) {
        viewport = size
        clamp()
    }

    /** Moves content horizontally by [dx] screen pixels; returns how much it actually moved. */
    fun moveX(dx: Float): Float {
        val new = (offsetX + dx).coerceIn(minX, 0f)
        val moved = new - offsetX
        offsetX = new
        return moved
    }

    /** Moves content vertically by [dy] screen pixels (positive = down); returns how much it moved. */
    fun moveY(dy: Float): Float {
        var rest = dy
        if (rest < 0f) {
            // Content up: scroll the list first, then reveal the scaled-up bottom.
            rest += list.dispatchRawDelta(-rest / scale) * scale
            val new = (offsetY + rest).coerceIn(minY, 0f)
            rest -= new - offsetY
            offsetY = new
        } else if (rest > 0f) {
            // Content down: undo any bottom offset first, then scroll the list back.
            val new = (offsetY + rest).coerceAtMost(0f)
            rest -= new - offsetY
            offsetY = new
            rest += list.dispatchRawDelta(-rest / scale) * scale
        }
        return dy - rest
    }

    /** Multiplies the scale by [factor], keeping the content under [center] in place. */
    fun zoomBy(factor: Float, center: Offset) {
        val old = scale
        val new = (old * factor).coerceIn(1f, MAX_SCALE)
        if (new == old) return
        scale = new
        val ratio = new / old
        offsetX = (center.x - ratio * (center.x - offsetX)).coerceIn(minX, 0f)
        moveY((1f - ratio) * (center.y - offsetY))
        clamp()
    }

    /** Whether list-space rows [start, end) are on screen. */
    fun isListRangeVisible(start: Int, end: Int): Boolean {
        val top = -offsetY / scale
        val bottom = (viewport.height - offsetY) / scale
        return start + padTop < bottom && end + padTop > top
    }

    /** The page under the middle of the screen (the last one once the end is reached). */
    fun currentPage(pageCount: Int): Int {
        val items = list.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return list.firstVisibleItemIndex
        if (!list.canScrollForward && offsetY <= minY + 1f) return pageCount - 1
        val middle = (viewport.height / 2f - offsetY) / scale
        return items.lastOrNull { it.offset + padTop <= middle }?.index ?: items.first().index
    }

    /** The page under screen point [screen] and that point in the page's list pixels. */
    fun screenToPage(screen: Offset): Pair<Int, Offset>? {
        val x = (screen.x - offsetX) / scale
        val y = (screen.y - offsetY) / scale - padTop
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: return null
        return item.index to Offset(x, y - item.offset)
    }

    /** Point ([x], [y]) of page [page] (list pixels) on screen; null when the page is not laid out. */
    fun pageToScreen(page: Int, x: Float, y: Float): Offset? {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page } ?: return null
        return Offset(x * scale + offsetX, (item.offset + padTop + y) * scale + offsetY)
    }

    /** Where the screen currently starts in the document; null before the first layout. */
    fun position(): Position? {
        val index = list.firstVisibleItemIndex
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        return Position(index, (list.firstVisibleItemScrollOffset.toFloat() / item.size).coerceIn(0f, 1f))
    }

    /** Scrolls back to [position]; [pageHeight] is that page's height in list pixels. */
    suspend fun restore(position: Position, pageHeight: Float) {
        list.scrollToItem(position.page, (position.fraction * pageHeight).roundToInt())
    }

    suspend fun jumpTo(page: Int) {
        stop()
        offsetY = 0f
        list.scrollToItem(page)
    }

    /**
     * Scrolls so the point ([x], [y]) of page [page] (list pixels from the page's top-left) is
     * centred horizontally and a third of the way down the screen, keeping the zoom.
     */
    suspend fun reveal(page: Int, x: Float, y: Float) {
        stop()
        offsetY = 0f
        offsetX = (viewport.width / 2f - x * scale).coerceIn(minX, 0f)
        list.scrollToItem(page, (y + padTop - viewport.height / (3f * scale)).roundToInt().coerceAtLeast(0))
    }

    /** Runs [block] as the single running animation, replacing any previous one. */
    fun launch(scope: CoroutineScope, block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    fun stop() {
        job?.cancel()
    }

    suspend fun animateZoomTo(target: Float, center: Offset) {
        animate(scale, target, animationSpec = tween(250)) { value, _ -> zoomBy(value / scale, center) }
    }

    suspend fun fling(velocity: Velocity, decay: DecayAnimationSpec<Float>) = coroutineScope {
        launch { decayAlong(velocity.x, decay, ::moveX) }
        launch { decayAlong(velocity.y, decay, ::moveY) }
    }

    private suspend fun decayAlong(v: Float, decay: DecayAnimationSpec<Float>, move: (Float) -> Float) {
        if (abs(v) < 50f) return
        var last = 0f
        AnimationState(0f, v).animateDecay(decay) {
            val delta = value - last
            last = value
            if (abs(move(delta) - delta) > 0.5f) cancelAnimation() // hit an edge
        }
    }

    private fun clamp() {
        offsetX = offsetX.coerceIn(minX, 0f)
        if (offsetY < minY) {
            // Zoomed out at the end of the document: give the excess back to the list.
            val excess = minY - offsetY
            offsetY = minY
            list.dispatchRawDelta(excess / scale)
        } else if (offsetY > 0f) {
            offsetY = 0f
        }
    }
}

/**
 * One-finger pan with fling, two-finger pinch zoom, double tap to zoom in/out.
 * [onTap] is a single tap; [onMove] fires once per gesture when a pan or pinch starts;
 * [onLongPress] gets the pressed point.
 */
fun Modifier.zoomGestures(
    zoom: ZoomState,
    scope: CoroutineScope,
    onTap: () -> Unit,
    onMove: () -> Unit,
    onLongPress: (Offset) -> Unit = {},
): Modifier = this
    .pointerInput(zoom) {
        detectTapGestures(
            onTap = { onTap() },
            onLongPress = { onLongPress(it) },
            onDoubleTap = { position ->
                val target = if (zoom.scale > 1.05f) 1f else DOUBLE_TAP_SCALE
                zoom.launch(scope) { zoom.animateZoomTo(target, position) }
            },
        )
    }
    .pointerInput(zoom) {
        val decay = splineBasedDecay<Float>(this)
        val maxVelocity = viewConfiguration.maximumFlingVelocity
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            zoom.stop()
            val tracker = VelocityTracker()
            var pointers = 0
            var multiTouch = false
            var pastSlop = false
            var panSoFar = Offset.Zero
            var zoomSoFar = 1f
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed > 1) multiTouch = true
                if (pressed != pointers) {
                    // The centroid jumps when fingers are added or lifted: restart velocity tracking.
                    tracker.resetTracking()
                    pointers = pressed
                }
                val zoomChange = event.calculateZoom()
                val pan = event.calculatePan()
                if (!pastSlop) {
                    zoomSoFar *= zoomChange
                    panSoFar += pan
                    val zoomMotion = abs(1f - zoomSoFar) * event.calculateCentroidSize(useCurrent = false)
                    pastSlop = zoomMotion > slop || panSoFar.getDistance() > slop
                    if (pastSlop) onMove()
                }
                if (pastSlop) {
                    if (zoomChange != 1f) zoom.zoomBy(zoomChange, event.calculateCentroid(useCurrent = false))
                    zoom.moveX(pan.x)
                    zoom.moveY(pan.y)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                tracker.addPosition(event.changes.first().uptimeMillis, event.calculateCentroid(useCurrent = true))
            }
            if (pastSlop && !multiTouch) {
                val velocity = tracker.calculateVelocity(Velocity(maxVelocity, maxVelocity))
                zoom.launch(scope) { zoom.fling(velocity, decay) }
            }
        }
    }
