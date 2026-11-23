// SPDX-License-Identifier: GPL-3.0-only

package io.github.kalgeo.quietpdf

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Upper bound for one rendered page bitmap (~48 MB), whatever the zoom. */
private const val MAX_PAGE_PIXELS = 12_000_000f

/** Night mode: inverts the page, softened so white becomes dark grey rather than pure black. */
private val NightFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 235f,
            0f, -0.85f, 0f, 0f, 235f,
            0f, 0f, -0.85f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    reader: Screen.Reader,
    nightMode: Boolean,
    onToggleNightMode: () -> Unit,
    onBack: () -> Unit,
    onPositionChanged: (Position) -> Unit,
) {
    val doc = reader.doc
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = reader.start.page)
    val zoom = remember(listState) { ZoomState(listState) }
    val scope = rememberCoroutineScope()
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    val currentPage by remember(zoom) { derivedStateOf { zoom.currentPage(doc.pageCount) } }

    val search = remember(doc) { SearchState(doc) }
    val reveal: suspend (SearchMatch) -> Unit = { match ->
        val k = zoom.viewport.width / doc.pageSizes[match.page].width.toFloat()
        val r = match.rects.firstOrNull() ?: RectF()
        zoom.reveal(match.page, r.centerX() * k, r.top * k)
    }

    // Where reading stopped is remembered as the exact scroll position, not just a page number.
    val savePosition by rememberUpdatedState(onPositionChanged)
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(zoom) {
        // The list opens on the saved page; move to the saved spot inside it as soon as the
        // page's height on this screen is known.
        val width = snapshotFlow { zoom.viewport.width }.first { it > 0 }
        val start = reader.start
        val size = doc.pageSizes[start.page]
        if (start.fraction > 0f) zoom.restore(start, width.toFloat() * size.height / size.width)
        restored = true
        // While reading, save shortly after each scroll comes to rest.
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest {
                delay(400)
                zoom.position()?.let { savePosition(it) }
            }
    }
    // Save at once when the app goes to the background or the document is closed: there may
    // be no later chance.
    val saveNow = { if (restored) zoom.position()?.let { savePosition(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveNow() }
    DisposableEffect(zoom) { onDispose { saveNow() } }

    BackHandler(onBack = onBack)
    BackHandler(enabled = search.active, onBack = search::close)

    // The bars (ours and the system's) are shown when a document opens, go away as soon as the
    // user starts reading, and come back with a tap. Search keeps them up: it lives in the bar.
    var barsWanted by remember { mutableStateOf(true) }
    val barsVisible = barsWanted || search.active
    ImmersiveMode(enabled = !barsVisible)

    // With the bars hidden, the page number still shows briefly whenever the page changes.
    var pageJustChanged by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage) {
        pageJustChanged = true
        delay(1500)
        pageJustChanged = false
    }

    // The pages fill the whole window and the bars are drawn over them, so showing or hiding
    // the bars never moves or re-renders the page. The list is padded by the bars' full size
    // (whether or not they are showing) so the first page starts below them.
    val systemBars = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()

    Box(
        Modifier
            .fillMaxSize()
            .background(if (nightMode) Color(0xFF0E0E0E) else Color(0xFFE3E3E3))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { zoom.updateViewport(it) }
                .zoomGestures(
                    zoom = zoom,
                    scope = scope,
                    onTap = { if (!search.active) barsWanted = !barsWanted },
                    onMove = { if (!search.active) barsWanted = false },
                )
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false, // all gestures go through zoomGestures
                contentPadding = PaddingValues(
                    top = systemBars.calculateTopPadding() + TopAppBarDefaults.TopAppBarExpandedHeight + 8.dp,
                    bottom = systemBars.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offsetX
                        translationY = zoom.offsetY
                    },
            ) {
                items(doc.pageCount, key = { it }) { index -> PageView(doc, index, zoom, search, nightMode) }
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (search.active) SearchTopBar(
                search = search,
                onSearch = { search.run(scope, currentPage, reveal) },
                onPrevious = { search.step(-1, scope, reveal) },
                onNext = { search.step(1, scope, reveal) },
            ) else TopAppBar(
                title = { Text(reader.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (PdfDoc.canSearch) {
                        IconButton(onClick = search::open) {
                            Icon(Icons.Filled.Search, stringResource(R.string.search))
                        }
                    }
                    IconButton(onClick = onToggleNightMode) {
                        Icon(
                            if (nightMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            stringResource(R.string.night_mode),
                        )
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = barsVisible || pageJustChanged,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Surface(
                onClick = { showGoTo = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    stringResource(R.string.page_indicator, currentPage + 1, doc.pageCount),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = doc.pageCount,
            currentPage = currentPage,
            onDismiss = { showGoTo = false },
            onGo = { page ->
                showGoTo = false
                scope.launch { zoom.jumpTo(page) }
            },
        )
    }
}

/** Hides the status and navigation bars while [enabled]; a swipe from the edge peeks at them. */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val controller = remember(window, view) {
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    LaunchedEffect(controller, enabled) {
        val bars = WindowInsetsCompat.Type.systemBars()
        if (enabled) controller.hide(bars) else controller.show(bars)
    }
    // Leaving the reader always brings the system bars back.
    DisposableEffect(controller) {
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun PageView(doc: PdfDoc, index: Int, zoom: ZoomState, search: SearchState, nightMode: Boolean) {
    val pageSize = doc.pageSizes[index]
    val baseWidth = zoom.viewport.width
    // Render sharper only while zoomed in and only for pages actually on screen.
    val level by remember(index, zoom) {
        derivedStateOf {
            val wanted = zoom.renderLevel
            if (wanted == 1) return@derivedStateOf 1
            val item = zoom.list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (item != null && zoom.isListRangeVisible(item.offset, item.offset + item.size)) wanted else 1
        }
    }
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(index, baseWidth, level) {
        if (baseWidth <= 0) return@LaunchedEffect
        if (level > 1) delay(200) // wait for the pinch to settle before the expensive render
        doc.render(index, renderWidth(baseWidth * level, pageSize))?.let { bitmap = it.asImageBitmap() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(pageSize.width.toFloat() / pageSize.height)
            .background(if (nightMode) Color(0xFF141414) else Color.White)
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                colorFilter = if (nightMode) NightFilter else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        MatchHighlights(search, index, pageSize)
    }
}

private fun renderWidth(wanted: Int, pageSize: IntSize): Int {
    val aspect = pageSize.height.toFloat() / pageSize.width
    val maxWidth = sqrt(MAX_PAGE_PIXELS / aspect).toInt()
    return min(wanted, maxWidth).coerceAtLeast(1)
}

@Composable
private fun GoToPageDialog(pageCount: Int, currentPage: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf((currentPage + 1).toString()) }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_page)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.page_number_hint, pageCount)) },
                    singleLine = true,
                    isError = text.isNotEmpty() && page == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { page?.let { onGo(it - 1) } }),
                )
                if (pageCount > 1) {
                    Slider(
                        value = (page ?: (currentPage + 1)).toFloat(),
                        onValueChange = { text = it.roundToInt().toString() },
                        valueRange = 1f..pageCount.toFloat(),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { page?.let { onGo(it - 1) } }, enabled = page != null) {
                Text(stringResource(R.string.go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
