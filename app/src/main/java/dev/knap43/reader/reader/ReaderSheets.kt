package dev.knap43.reader.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.knap43.reader.data.Book
import dev.knap43.reader.data.Bookmark
import dev.knap43.reader.data.Highlight
import dev.knap43.reader.data.highlightsMarkdown

/** One line in a table of contents. */
data class TocEntry(val title: String, val depth: Int, val onClick: () -> Unit)

/** One search match with a little surrounding text. */
data class SearchHit(val title: String?, val before: String, val match: String, val after: String)

enum class NavTab(val label: String) { CONTENTS("Contents"), BOOKMARKS("Bookmarks"), HIGHLIGHTS("Highlights") }

/** Contents, bookmarks and highlights in one tabbed sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationSheet(
    book: Book,
    contents: List<TocEntry>,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    onOpenBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onOpenHighlight: (Highlight) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onDismiss: () -> Unit,
    initialTab: NavTab = if (contents.isEmpty()) NavTab.BOOKMARKS else NavTab.CONTENTS,
    /** Where a saved position sits, e.g. "p. 142"; falls back to a percentage. */
    placeOf: (position: String, progress: Float) -> String = { _, p -> "${(p * 100).toInt()}%" },
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.85f).navigationBarsPadding()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                NavTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
            when (tab) {
                NavTab.CONTENTS -> if (contents.isEmpty()) {
                    EmptyNote("This book has no table of contents.")
                } else {
                    LazyColumn {
                        items(contents) { entry ->
                            Text(
                                entry.title,
                                style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                color = if (entry.depth == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = entry.onClick)
                                    .padding(start = (24 + entry.depth * 18).dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                            )
                        }
                    }
                }

                NavTab.BOOKMARKS -> if (bookmarks.isEmpty()) {
                    EmptyNote("No bookmarks yet. Use the bookmark button in the top bar to mark a page.")
                } else {
                    LazyColumn {
                        items(bookmarks, key = { it.id }) { b ->
                            AnnotationRow(
                                title = b.label,
                                meta = placeOf(b.position, b.progress),
                                onClick = { onOpenBookmark(b) },
                                onDelete = { onDeleteBookmark(b) },
                            )
                        }
                    }
                }

                NavTab.HIGHLIGHTS -> if (highlights.isEmpty()) {
                    EmptyNote("No highlights yet. Select text and tap Highlight.")
                } else {
                    Column {
                        ExportRow(book, highlights)
                        LazyColumn {
                            items(highlights, key = { it.id }) { h ->
                                AnnotationRow(
                                    title = "“${h.text.trim()}”",
                                    meta = listOfNotNull(h.chapter, placeOf(h.position, h.progress)).joinToString(" · "),
                                    serif = true,
                                    onClick = { onOpenHighlight(h) },
                                    onDelete = { onDeleteHighlight(h) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportRow(book: Book, highlights: List<Highlight>) {
    val context = LocalContext.current
    val markdown = remember(book, highlights) { highlightsMarkdown(book, highlights) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { saveText(context, it, markdown) }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${highlights.size} highlight" + if (highlights.size == 1) "" else "s",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Highlights from ${book.title}")
                .putExtra(Intent.EXTRA_TEXT, markdown)
            context.startActivity(Intent.createChooser(send, "Share highlights"))
        }) { Icon(Icons.Rounded.IosShare, "Share highlights") }
        IconButton(onClick = { save.launch("${book.title.take(80).replace(Regex("""[\\/:*?"<>|]"""), "_")} – highlights.md") }) {
            Icon(Icons.Rounded.Save, "Save highlights as Markdown")
        }
    }
}

private fun saveText(context: Context, uri: Uri, text: String) {
    val ok = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
    }.getOrDefault(false)
    Toast.makeText(context, if (ok) "Highlights saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
}

@Composable
private fun AnnotationRow(title: String, meta: String, onClick: () -> Unit, onDelete: () -> Unit, serif: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (serif) FontFamily.Serif else null,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Book-wide search. [onSearch] starts a new search; results stream in through [hits] while [searching].
 * The query survives closing the sheet, so reopening it shows the previous results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    hits: List<SearchHit>,
    searching: Boolean,
    searchedFor: String?,
    onSearch: (String) -> Unit,
    onOpen: (Int) -> Unit,
    onDismiss: () -> Unit,
    progress: Float? = null,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (hits.isEmpty()) runCatching { focus.requestFocus() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.85f).navigationBarsPadding().imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text("Search this book") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query.trim()) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (searching) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    when {
                        searchedFor == null -> "Matches in every chapter appear here."
                        searching -> "${hits.size} found so far"
                        hits.isEmpty() -> "No matches for “$searchedFor”."
                        else -> "${hits.size} match" + (if (hits.size == 1) "" else "es") + " for “$searchedFor”"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (searching && progress != null) {
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            }
            val accent = MaterialTheme.colorScheme.primary
            LazyColumn {
                items(hits.size) { i ->
                    val hit = hits[i]
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { onOpen(i) }
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        hit.title?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            buildAnnotatedString {
                                append(hit.before.takeLast(70).trimStart())
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
                                    append(hit.match)
                                }
                                append(hit.after.take(90).trimEnd())
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Shown when a highlight in the text is tapped. */
@Composable
fun HighlightDialog(
    highlight: Highlight,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = if (highlight.chapter != null) {
            { Text(highlight.chapter, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else null,
        text = {
            Text(
                "“${highlight.text.trim()}”",
                fontFamily = FontFamily.Serif,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            Row {
                if (highlight.text.trim().split(Regex("""\s+""")).size <= 3) TextButton(onClick = onDefine) { Text("Define") }
                TextButton(onClick = onCopy) { Text("Copy") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
    )
}
