package dev.knap43.reader.reader

import android.app.Application
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.knap43.reader.readerApp
import dev.knap43.reader.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewModel(app: Application) : AndroidViewModel(app) {
    val document = MutableStateFlow<PdfDocument?>(null)
    val error = MutableStateFlow<String?>(null)
    private var loadedId: String? = null

    fun load(file: File, id: String) {
        if (loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            runCatching { PdfDocument.open(file) }
                .onSuccess { document.value = it }
                .onFailure { error.value = it.message ?: "Couldn't open this PDF" }
        }
    }

    override fun onCleared() {
        val doc = document.value ?: return
        CoroutineScope(Dispatchers.IO).launch { withContext(NonCancellable) { doc.closeSafely() } }
    }
}

class PdfReaderActivity : ComponentActivity() {

    private val vm: PdfViewModel by viewModels()

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
        vm.load(book.file, id)
        val startPage = book.position?.toIntOrNull() ?: 0

        setContent {
            AppTheme {
                val doc by vm.document.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    val d = doc
                    when {
                        d != null -> PdfReader(d, book.title, id, startPage)
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PdfReader(doc: PdfDocument, title: String, bookId: String, startPage: Int) {
        val app = readerApp
        val settings by app.prefs.settings.collectAsStateWithLifecycle()
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, doc.pageCount - 1))
        val scope = rememberCoroutineScope()
        var chrome by remember { mutableStateOf(false) }
        var askPage by remember { mutableStateOf(false) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(doc) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .debounce(400)
                .collect { page ->
                    val progress = if (doc.pageCount <= 1) 1f else page.toFloat() / (doc.pageCount - 1)
                    app.library.updatePosition(bookId, page.toString(), progress)
                }
        }

        val night = settings.pdfNight
        LaunchedEffect(settings.hideSystemBars) { window.setSystemBarsHidden(settings.hideSystemBars) }
        val pageFilter = remember(night) { if (night) ColorFilter.colorMatrix(NightMatrix) else null }
        val gutter = if (night) Color(0xFF0B0907) else MaterialTheme.colorScheme.surfaceContainerLowest

        Box(
            Modifier
                .fillMaxSize()
                .background(gutter)
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
                        onTap = { chrome = !chrome },
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
                    PdfPage(doc, index, pageFilter)
                }
            }

            AnimatedVisibility(
                visible = chrome,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { askPage = true }) { Icon(Icons.Rounded.FormatListNumbered, "Go to page") }
                        IconButton(onClick = { app.prefs.update { it.copy(hideSystemBars = !it.hideSystemBars) } }) {
                            Icon(
                                if (settings.hideSystemBars) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                if (settings.hideSystemBars) "Show system bars" else "Hide system bars",
                            )
                        }
                        IconButton(onClick = { app.prefs.update { it.copy(pdfNight = !it.pdfNight) } }) {
                            Icon(
                                if (night) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                if (night) "Show original colors" else "Night mode",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
                    ),
                )
            }

            AnimatedVisibility(
                visible = chrome,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)) {
                    val page = listState.firstVisibleItemIndex
                    ProgressPanel(
                        chapter = null,
                        progress = if (doc.pageCount <= 1) 1f else page.toFloat() / (doc.pageCount - 1),
                        label = "Page ${page + 1} of ${doc.pageCount}",
                        onSeek = { p ->
                            scope.launch { listState.scrollToItem((p * (doc.pageCount - 1)).toInt()) }
                        },
                    )
                }
            }
        }

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
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, filter: ColorFilter?) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(doc.aspectRatios[index].coerceIn(0.2f, 5f))
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
            value = doc.render(index, widthPx)?.asImageBitmap()
        }
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
