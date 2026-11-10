package dev.knap43.reader.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.knap43.reader.data.Book
import dev.knap43.reader.data.Format
import dev.knap43.reader.readerApp
import dev.knap43.reader.ui.theme.AppearanceDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private val CoverShape = RoundedCornerShape(10.dp)

private enum class ShelfFilter(val label: String) { ALL("All"), UNREAD("Unread"), READ("Read") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    busy: StateFlow<String?>,
    messages: SharedFlow<String>,
    onChooseFolder: (Uri) -> Unit,
    onOpen: (Book) -> Unit,
) {
    val app = LocalContext.current.readerApp
    val books by app.library.books.collectAsStateWithLifecycle()
    val busyText by busy.collectAsStateWithLifecycle()
    val folder by app.folder.tree.collectAsStateWithLifecycle()
    val scanning by app.folder.scanning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var actionsFor by remember { mutableStateOf<Book?>(null) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Book?>(null) }
    var filter by rememberSaveable { mutableStateOf(ShelfFilter.ALL) }

    LaunchedEffect(Unit) { messages.collect { snackbar.showSnackbar(it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(onChooseFolder)
    }
    val pick = { picker.launch(folder) }

    val sorted = remember(books) {
        // Finished books sink to the end of the shelf.
        books.sortedWith(compareBy<Book> { it.finished }.thenByDescending { maxOf(it.lastOpenedAt, it.addedAt) })
    }
    val current = if (filter == ShelfFilter.READ) null else sorted.firstOrNull { it.lastOpenedAt > 0 && !it.finished }
    val shelf = sorted.filter {
        it != current && when (filter) {
            ShelfFilter.ALL -> true
            ShelfFilter.UNREAD -> !it.finished
            ShelfFilter.READ -> it.finished
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Library", fontFamily = FontFamily.Serif) },
                actions = {
                    if (folder != null) {
                        IconButton(onClick = pick) { Icon(Icons.Rounded.FolderOpen, "Change book folder") }
                    }
                    IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Rounded.Palette, "Appearance") }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            if (folder == null && books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = pick,
                    icon = { Icon(Icons.Rounded.FolderOpen, null) },
                    text = { Text("Choose folder") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = app.folder::scan,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (books.isEmpty()) {
                EmptyLibrary(folderName = folder?.let { app.folder.name }, hasFolder = folder != null, scanning = scanning, onChoose = pick)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShelfFilter.entries.forEach { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text(f.label) },
                                    leadingIcon = if (filter == f) {
                                        { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else null,
                                )
                            }
                        }
                    }
                    if (current == null && shelf.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "filter-empty") {
                            Text(
                                if (filter == ShelfFilter.READ) "No finished books yet." else "Everything here is read.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            )
                        }
                    }
                    if (current != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                            ContinueCard(current, onClick = { onOpen(current) }, onLongClick = { actionsFor = current })
                        }
                        if (shelf.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "shelf-label") {
                                Text(
                                    "On the shelf",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    items(shelf, key = { it.id }) { book ->
                        BookTile(book, onClick = { onOpen(book) }, onLongClick = { actionsFor = book })
                    }
                }
            }
        }
    }

    if (appearanceOpen) AppearanceDialog(onDismiss = { appearanceOpen = false })

    actionsFor?.let { book ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ListItem(
                    headlineContent = { Text("Read") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { actionsFor = null; onOpen(book) },
                )
                ListItem(
                    headlineContent = { Text(if (book.finished) "Mark as unread" else "Mark as read") },
                    leadingContent = {
                        Icon(if (book.finished) Icons.Rounded.RemoveDone else Icons.Rounded.CheckCircle, null)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { actionsFor = null; app.library.setFinished(book.id, !book.finished) },
                )
                ListItem(
                    headlineContent = { Text("Remove from library", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { actionsFor = null; confirmRemove = book },
                )
            }
        }
    }

    confirmRemove?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove “${book.title}”?") },
            text = {
                Text(
                    if (book.isDocument) "It leaves the shelf, and your position and highlights are forgotten. The file stays in your folder."
                    else "The book and your reading position will be deleted from this app."
                )
            },
            confirmButton = {
                TextButton(onClick = { app.folder.remove(book); confirmRemove = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }

    busyText?.let { text ->
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(text)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(book, Modifier.size(width = 84.dp, height = 126.dp))
            Column(Modifier.weight(1f).height(126.dp)) {
                Text(
                    "Continue reading",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Serif,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${(book.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ProgressLine(book.progress)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.clip(CoverShape).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Cover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        if (book.progress > 0f && !book.finished) {
            Spacer(Modifier.height(8.dp))
            ProgressLine(book.progress)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        book.author?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressLine(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun Cover(book: Book, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, book.coverPath) {
        value = book.coverPath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    Box(modifier.clip(CoverShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.fillMaxSize().alpha(if (book.finished) 0.4f else 1f)) {
            val bmp = bitmap
            if (bmp != null) {
                Image(bmp, contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                GeneratedCover(book)
            }
        }
        if (book.finished) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Read",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(2.dp)
                    .size(14.dp),
            )
        }
        if (book.format == Format.PDF) {
            Text(
                "PDF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun GeneratedCover(book: Book) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(colors.primaryContainer, colors.surfaceContainerHighest))
        ).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            book.title,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Serif,
            color = colors.onPrimaryContainer,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyLibrary(folderName: String?, hasFolder: Boolean, scanning: Boolean, onChoose: () -> Unit) {
    Column(
        // Scrollable so that pull-to-refresh works on an empty shelf too.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(96.dp))
        Icon(
            Icons.Rounded.AutoStories,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (scanning) "Looking for books…" else "Your shelf is empty",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !hasFolder -> "Choose the folder where you keep your EPUB and PDF files. Reader shelves them and keeps up as you add more."
                scanning -> ""
                folderName != null -> "There are no EPUB or PDF files in “$folderName” yet. Add some, then pull down to refresh."
                else -> "There are no EPUB or PDF files in your book folder yet. Add some, then pull down to refresh."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!scanning) {
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onChoose) {
                Icon(Icons.Rounded.FolderOpen, null)
                Spacer(Modifier.size(8.dp))
                Text(if (hasFolder) "Choose another folder" else "Choose folder")
            }
        }
    }
}
