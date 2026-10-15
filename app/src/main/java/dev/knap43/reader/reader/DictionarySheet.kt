package dev.knap43.reader.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.knap43.reader.data.Dictionary
import dev.knap43.reader.data.DictionaryEntry
import dev.knap43.reader.readerApp

/** Definitions for [query] from the bundled English dictionary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySheet(query: String, onDismiss: () -> Unit) {
    val dictionary = LocalContext.current.readerApp.dictionary
    val entries by produceState<List<DictionaryEntry>?>(null, query) {
        value = runCatching { dictionary.lookup(query) }.getOrDefault(emptyList())
    }
    val shown = Dictionary.normalize(query) ?: query.trim()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        val list = entries
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }

            list.isEmpty() -> Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(shown, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                Text(
                    "Not in the dictionary.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            else -> LazyColumn(
                Modifier.navigationBarsPadding().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(list) { i, entry ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    Entry(entry)
                }
                item { Box(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

@Composable
private fun Entry(entry: DictionaryEntry) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(entry.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
            Text(
                entry.pos.label,
                style = MaterialTheme.typography.titleSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
            )
        }
        entry.senses.forEachIndexed { n, sense ->
            Row(Modifier.padding(top = 10.dp)) {
                Text(
                    "${n + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(sense.definition, style = MaterialTheme.typography.bodyLarge)
                    sense.examples.take(2).forEach {
                        Text(
                            "“$it”",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (sense.synonyms.isNotEmpty()) {
                        Text(
                            "Also: " + sense.synonyms.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
