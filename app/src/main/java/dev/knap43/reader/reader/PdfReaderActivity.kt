package dev.knap43.reader.reader

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.knap43.reader.data.Book
import dev.knap43.reader.data.Bookmark
import dev.knap43.reader.data.Highlight
import dev.knap43.reader.data.PdfOrientation
import dev.knap43.reader.data.PdfRange
import dev.knap43.reader.readerApp
import dev.knap43.reader.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Text picked on a page by long-pressing and dragging; [anchor] is where the drag began. */
data class PdfSelection(val page: Int, val anchor: Int, val start: Int, val end: Int) {
    val range get() = PdfRange(page, start, end - start + 1)
}

class PdfViewModel(app: Application) : AndroidViewModel(app) {
    val document = MutableStateFlow<PdfDocument?>(null)
    val error = MutableStateFlow<String?>(null)
    val outline = MutableStateFlow<List<PdfOutlineItem>>(emptyList())
    private var loadedId: String? = null

    // Book-wide search, kept here so it survives rotation and reopening the sheet.
    val query = MutableStateFlow("")
    val searchedFor = MutableStateFlow<String?>(null)
    val matches = MutableStateFlow<List<PdfMatch>>(emptyList())
    val searching = MutableStateFlow(false)
    val searchProgress = MutableStateFlow(0f)
    /** The match last opened, drawn on its page. */
    val searchMark = MutableStateFlow<PdfRange?>(null)
    private var searchJob: Job? = null

    fun load(file: File, id: String) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            runCatching { PdfDocument.open(getApplication<Application>(), file) }
                .onSuccess { doc ->
                    document.value = doc
                    outline.value = doc.outline()
                }
                .onFailure { error.value = it.message ?: "Couldn't open this PDF" }
        }
    }

    fun search(q: String) {
        val doc = document.value ?: return
        searchJob?.cancel()
        searchMark.value = null
        matches.value = emptyList()
        searchedFor.value = q
        searching.value = true
        searchProgress.value = 0f
        searchJob = viewModelScope.launch {
            try {
                for (page in 0 until doc.pageCount) {
                    if (!isActive) break
                    val found = doc.search(page, q)
                    if (found.isNotEmpty()) matches.value = matches.value + found
                    searchProgress.value = (page + 1f) / doc.pageCount
                }
            } finally {
                searching.value = false
            }
        }
    }

    override fun onCleared() {
        val doc = document.value ?: return
        CoroutineScope(Dispatchers.IO).launch { withContext(NonCancellable) { doc.closeSafely() } }
    }
}

class PdfReaderActivity : ComponentActivity() {

    private val vm: PdfViewModel by viewModels()
    private var orientation by mutableStateOf(PdfOrientation.AUTO)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_BOOK_ID)
        val book = id?.let { readerApp.library.get(it) }
        if (id == null || book == null) {
            finish()
            return
        }
        orientation = readerApp.prefs.pdfOrientation(id)
        requestedOrientation = orientation.activityValue
        vm.load(book.file, id)
        val startPage = book.position?.toIntOrNull() ?: 0

        setContent {
            AppTheme {
                val doc by vm.document.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val d = doc
                    when {
                        d != null -> PdfReader(d, book, startPage)
                        error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(error.orEmpty()) }
                        else -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }

    override fun finish() {
        readerApp.prefs.resumeBookId = null
        super.finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs can bring the bars back; hide them again on return.
        if (hasFocus) window.setSystemBarsHidden(readerApp.prefs.settings.value.hideSystemBars)
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PdfReader(doc: PdfDocument, book: Book, startPage: Int) {
        val app = readerApp
        val bookId = book.id
        val settings by app.prefs.settings.collectAsStateWithLifecycle()
        val annotations by app.annotations.of(bookId).collectAsStateWithLifecycle()
        val outline by vm.outline.collectAsStateWithLifecycle()
        val searchMark by vm.searchMark.collectAsStateWithLifecycle()
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, doc.pageCount - 1))
        val scope = rememberCoroutineScope()
        // Saveable so the bars stay open when switching orientation recreates the activity.
        var chrome by rememberSaveable { mutableStateOf(false) }
        var askPage by remember { mutableStateOf(false) }
        var showNav by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        var selection by remember { mutableStateOf<PdfSelection?>(null) }
        var tapped by remember { mutableStateOf<Highlight?>(null) }
        var lookup by remember { mutableStateOf<String?>(null) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        // Where each visible page sits, to find the page under a tap.
        val pageCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
        var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        val currentPage = listState.firstVisibleItemIndex
        fun progressOf(page: Int) = if (doc.pageCount <= 1) 1f else page.toFloat() / (doc.pageCount - 1)
        fun sectionOf(page: Int) = outline.lastOrNull { it.page <= page }?.title
        fun placeOf(page: Int) = listOfNotNull(sectionOf(page), "p. ${page + 1}").joinToString(" · ")

        /** Scrolls so a character range sits near the top third of the screen. */
        fun reveal(range: PdfRange) {
            scope.launch {
                val top = doc.textLayer(range.page)?.top(range.start) ?: 0f
                val width = listState.layoutInfo.viewportSize.width.takeIf { it > 0 } ?: return@launch
                val pageHeight = width / doc.aspectRatios[range.page]
                val viewport = listState.layoutInfo.viewportSize.height
                listState.scrollToItem(range.page, (top * pageHeight - viewport / 3f).toInt().coerceAtLeast(0))
            }
        }

        LaunchedEffect(doc) {
            // Scrolling on by a page or two is reading; landing further away is a jump.
            var last: Int? = null
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .debounce(400)
                .collect { page ->
                    val reading = last?.let { page - it in 0..2 } ?: false
                    app.library.updatePosition(bookId, page.toString(), progressOf(page), reading)
                    last = page
                }
        }

        val night = settings.pdfNight
        LaunchedEffect(settings.hideSystemBars) { window.setSystemBarsHidden(settings.hideSystemBars) }
        val pageFilter = remember(night) { if (night) ColorFilter.colorMatrix(NightMatrix) else null }
        val gutter = if (night) Color(0xFF0B0907) else MaterialTheme.colorScheme.surfaceContainerLowest
        val bookmarkHere = annotations.bookmarks.firstOrNull { it.position.toIntOrNull() == currentPage }
        val highlightsByPage = remember(annotations.highlights) {
            annotations.highlights.mapNotNull { h -> PdfRange.parse(h.position)?.let { it to h } }.groupBy { it.first.page }
        }
        // The tap handler below is set up once; read the latest highlights through this.
        val currentHighlights by rememberUpdatedState(highlightsByPage)

        Box(
            Modifier
                .fillMaxSize()
                .background(gutter)
                .onGloballyPositioned { rootCoords = it }
                .pointerInput(Unit) {
                    // Two-finger pinch zooms and pans; single-finger drags are left to the list.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                                val maxOffset = size.width * (newScale - 1f) / 2f
                                offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxOffset, maxOffset)
                                scale = newScale
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            if (selection != null) {
                                selection = null
                                return@detectTapGestures
                            }
                            // A tap on a highlight opens it; anywhere else toggles the controls.
                            val root = rootCoords
                            val hit = root?.let {
                                pageCoords.entries.firstNotNullOfOrNull { (page, coords) ->
                                    if (!coords.isAttached) return@firstNotNullOfOrNull null
                                    val local = coords.localPositionOf(root, pos)
                                    val w = coords.size.width.toFloat()
                                    val h = coords.size.height.toFloat()
                                    if (local.x !in 0f..w || local.y !in 0f..h) return@firstNotNullOfOrNull null
                                    val layer = doc.peekTextLayer(page) ?: return@firstNotNullOfOrNull null
                                    val i = layer.charAt(local.x / w, local.y / h)
                                    currentHighlights[page]?.firstOrNull { (r, _) -> i in r.start until r.start + r.count }?.second
                                }
                            }
                            if (hit != null) tapped = hit else chrome = !chrome
                        },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                            } else {
                                scale = 2f
                            }
                        },
                    )
                },
        ) {
            LazyColumn(
                state = listState,
                contentPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues().let {
                    PaddingValues(top = it.calculateTopPadding() + 8.dp, bottom = it.calculateBottomPadding() + 8.dp)
                },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                    },
            ) {
                items(doc.pageCount, key = { it }) { index ->
                    PdfPage(
                        doc = doc,
                        index = index,
                        filter = pageFilter,
                        highlights = highlightsByPage[index].orEmpty().map { it.first },
                        searchMark = searchMark?.takeIf { it.page == index },
                        selection = selection?.takeIf { it.page == index },
                        onSelect = { selection = it },
                        onPositioned = { pageCoords[index] = it },
                        onDisposed = { pageCoords.remove(index) },
                    )
                }
            }

            AnimatedVisibility(
                visible = chrome && selection == null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Rounded.Search, "Search") }
                        IconButton(onClick = {
                            if (bookmarkHere != null) {
                                app.annotations.removeBookmark(bookId, bookmarkHere.id)
                            } else {
                                app.annotations.addBookmark(
                                    bookId,
                                    Bookmark(label = placeOf(currentPage), progress = progressOf(currentPage), position = currentPage.toString()),
                                )
                            }
                        }) {
                            Icon(
                                if (bookmarkHere != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                if (bookmarkHere != null) "Remove bookmark" else "Bookmark this page",
                                tint = if (bookmarkHere != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = { showNav = true }) {
                            Icon(Icons.AutoMirrored.Rounded.List, "Contents, bookmarks and highlights")
                        }
                        Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Go to page") },
                                leadingIcon = { Icon(Icons.Rounded.FormatListNumbered, null) },
                                onClick = { showMenu = false; askPage = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Orientation: ${orientation.label}") },
                                leadingIcon = {
                                    Icon(
                                        when (orientation) {
                                            PdfOrientation.AUTO -> Icons.Rounded.ScreenRotation
                                            PdfOrientation.PORTRAIT -> Icons.Rounded.StayCurrentPortrait
                                            PdfOrientation.LANDSCAPE -> Icons.Rounded.StayCurrentLandscape
                                        },
                                        null,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val next = orientation.next()
                                    app.prefs.setPdfOrientation(bookId, next)
                                    orientation = next
                                    requestedOrientation = next.activityValue
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (settings.hideSystemBars) "Show system bars" else "Hide system bars") },
                                leadingIcon = {
                                    Icon(if (settings.hideSystemBars) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, null)
                                },
                                onClick = {
                                    showMenu = false
                                    app.prefs.update { it.copy(hideSystemBars = !it.hideSystemBars) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (night) "Original colors" else "Night mode") },
                                leadingIcon = { Icon(if (night) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, null) },
                                onClick = {
                                    showMenu = false
                                    app.prefs.update { it.copy(pdfNight = !it.pdfNight) }
                                },
                            )
                        }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }

            // Actions for the text picked on a page.
            AnimatedVisibility(
                visible = selection != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
            ) {
                val sel = selection
                var text by remember { mutableStateOf("") }
                LaunchedEffect(sel) {
                    text = sel?.let { s -> doc.textLayer(s.page)?.text?.substring(s.start, s.end + 1)?.let(::tidy) }.orEmpty()
                }
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            val s = sel ?: return@TextButton
                            if (text.isNotBlank()) {
                                app.annotations.addHighlight(
                                    bookId,
                                    Highlight(text = text, chapter = placeOf(s.page), progress = progressOf(s.page), position = s.range.toString()),
                                )
                            }
                            selection = null
                        }) { Text("Highlight") }
                        if (text.isNotBlank() && text.split(' ').size <= 3) {
                            TextButton(onClick = { lookup = text; selection = null }) { Text("Define") }
                        }
                        TextButton(onClick = { copy(text); selection = null }) { Text("Copy") }
                        IconButton(onClick = { selection = null }) { Icon(Icons.Rounded.Close, "Cancel") }
                    }
                }
            }

            AnimatedVisibility(
                visible = chrome && selection == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    ProgressPanel(
                        chapter = sectionOf(currentPage),
                        progress = progressOf(currentPage),
                        label = "Page ${currentPage + 1} of ${doc.pageCount}",
                        onSeek = { p ->
                            scope.launch { listState.scrollToItem((p * (doc.pageCount - 1)).toInt()) }
                        },
                    )
                }
            }
        }

        if (showNav) {
            val close = { showNav = false; chrome = false }
            NavigationSheet(
                book = book,
                contents = remember(outline) {
                    outline.map { item ->
                        TocEntry(item.title, item.depth) {
                            close()
                            scope.launch { listState.scrollToItem(item.page.coerceIn(0, doc.pageCount - 1)) }
                        }
                    }
                },
                bookmarks = annotations.bookmarks,
                highlights = annotations.highlights,
                onOpenBookmark = { b ->
                    close()
                    b.position.toIntOrNull()?.let { scope.launch { listState.scrollToItem(it.coerceIn(0, doc.pageCount - 1)) } }
                },
                onDeleteBookmark = { b -> app.annotations.removeBookmark(bookId, b.id) },
                onOpenHighlight = { h -> close(); PdfRange.parse(h.position)?.let(::reveal) },
                onDeleteHighlight = { h -> app.annotations.removeHighlight(bookId, h.id) },
                onDismiss = { showNav = false },
            )
        }

        if (showSearch) {
            val query by vm.query.collectAsStateWithLifecycle()
            val matches by vm.matches.collectAsStateWithLifecycle()
            val searching by vm.searching.collectAsStateWithLifecycle()
            val searchedFor by vm.searchedFor.collectAsStateWithLifecycle()
            val progress by vm.searchProgress.collectAsStateWithLifecycle()
            SearchSheet(
                query = query,
                onQueryChange = { vm.query.value = it },
                hits = remember(matches, outline) {
                    matches.map { SearchHit(placeOf(it.page), it.before, it.match, it.after) }
                },
                searching = searching,
                searchedFor = searchedFor,
                onSearch = vm::search,
                onOpen = { i ->
                    showSearch = false
                    chrome = false
                    matches.getOrNull(i)?.let { m ->
                        val range = PdfRange(m.page, m.start, m.count)
                        vm.searchMark.value = range
                        reveal(range)
                    }
                },
                onDismiss = { showSearch = false },
                progress = progress,
            )
        }

        tapped?.let { h ->
            HighlightDialog(
                highlight = h,
                onCopy = { copy(h.text); tapped = null },
                onDefine = { lookup = h.text; tapped = null },
                onDelete = { app.annotations.removeHighlight(bookId, h.id); tapped = null },
                onDismiss = { tapped = null },
            )
        }

        lookup?.let { DictionarySheet(it, onDismiss = { lookup = null }) }

        if (askPage) {
            GoToPageDialog(
                pageCount = doc.pageCount,
                onDismiss = { askPage = false },
                onGo = { page ->
                    askPage = false
                    chrome = false
                    scope.launch { listState.scrollToItem(page - 1) }
                },
            )
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"

        fun intent(context: Context, bookId: String) =
            Intent(context, PdfReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)

        /** Inverts pages into warm light-on-dark, matching the app's ink background. */
        private val NightMatrix = ColorMatrix(
            floatArrayOf(
                -0.82f, 0f, 0f, 0f, 231f,
                0f, -0.80f, 0f, 0f, 223f,
                0f, 0f, -0.77f, 0f, 211f,
                0f, 0f, 0f, 1f, 0f,
            )
        )

        /** Joins words broken by line ends and collapses whitespace in text taken from a page. */
        fun tidy(raw: String): String = raw
            .replace(Regex("""-\r?\n"""), "")
            .replace(Regex("""[\s\u0000]+"""), " ")
            .trim()
    }
}

private val HighlightColor = Color(0x66E1A34F)
private val SearchColor = Color(0x666FA8DC)
private val SelectionColor = Color(0x553D8BFD)

@Composable
private fun PdfPage(
    doc: PdfDocument,
    index: Int,
    filter: ColorFilter?,
    highlights: List<PdfRange>,
    searchMark: PdfRange?,
    selection: PdfSelection?,
    onSelect: (PdfSelection) -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit,
    onDisposed: () -> Unit,
) {
    DisposableEffect(index) { onDispose(onDisposed) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(doc.aspectRatios[index].coerceIn(0.2f, 5f))
            .onGloballyPositioned(onPositioned)
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
            value = doc.render(index, widthPx)?.asImageBitmap()
        }
        val layer by produceState<TextLayer?>(null, index) { value = doc.textLayer(index) }
        val currentLayer by rememberUpdatedState(layer)
        val currentSelect by rememberUpdatedState(onSelect)
        val currentSelection by rememberUpdatedState(selection)

        val bmp = bitmap
        if (bmp != null) {
            Image(
                bmp,
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = filter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(if (filter != null) Color(0xFF16130F) else Color.White))
        }

        // Long-press picks a word; keep dragging to extend the selection.
        Canvas(
            Modifier.fillMaxSize().pointerInput(index) {
                fun charAt(o: Offset) = currentLayer?.charAt(o.x / size.width, o.y / size.height) ?: -1
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        val l = currentLayer ?: return@detectDragGesturesAfterLongPress
                        val i = charAt(o)
                        if (i >= 0) {
                            val word = l.wordAt(i)
                            if (!word.isEmpty()) currentSelect(PdfSelection(index, word.first, word.first, word.last))
                        }
                    },
                    onDrag = { change, _ ->
                        val sel = currentSelection ?: return@detectDragGesturesAfterLongPress
                        val i = charAt(change.position)
                        if (i >= 0) {
                            currentSelect(sel.copy(start = minOf(sel.anchor, i), end = maxOf(sel.anchor, i)))
                        }
                        change.consume()
                    },
                )
            }
        ) {
            val l = layer ?: return@Canvas
            fun paint(range: PdfRange, color: Color) {
                l.rects(range.start, range.count).forEach { r ->
                    drawRect(
                        color,
                        topLeft = Offset(r.left * size.width, r.top * size.height),
                        size = Size(r.width() * size.width, r.height() * size.height),
                    )
                }
            }
            highlights.forEach { paint(it, HighlightColor) }
            searchMark?.let { paint(it, SearchColor) }
            selection?.let { paint(it.range, SelectionColor) }
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text("1 – $pageCount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { page?.let(onGo) }, enabled = page != null) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
