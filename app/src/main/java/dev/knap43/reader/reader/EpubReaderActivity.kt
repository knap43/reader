package dev.knap43.reader.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.knap43.reader.R
import dev.knap43.reader.data.PageTheme
import dev.knap43.reader.readerApp
import dev.knap43.reader.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions

class EpubReaderActivity : AppCompatActivity() {

    private lateinit var bookId: String
    private lateinit var publication: Publication

    private val chromeVisible = mutableStateOf(false)
    private val locator = MutableStateFlow<Locator?>(null)

    private val navigator: EpubNavigatorFragment
        get() = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = readerApp
        val id = intent.getStringExtra(EXTRA_BOOK_ID)
        val pub = id?.let { app.books.open[it] }
        val book = id?.let { app.library.get(it) }
        if (id == null || pub == null || book == null) {
            // The publication only lives in memory; after process death, return to the library.
            super.onCreate(null)
            finish()
            return
        }
        bookId = id
        publication = pub

        val initialLocator = book.position?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        // The fragment factory must be in place before super.onCreate restores fragments.
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = app.prefs.settings.value.toEpubPreferences(),
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub)

        val container = findViewById<FragmentContainerView>(R.id.navigator_container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.navigator_container, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
                .commitNow()
        }

        // Edge taps turn pages; a tap anywhere else shows or hides the reading chrome.
        navigator.addInputListener(DirectionalNavigationAdapter(navigator))
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                chromeVisible.value = !chromeVisible.value
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { loc ->
                    locator.value = loc
                    app.library.updatePosition(
                        bookId,
                        loc.toJSON().toString(),
                        loc.locations.totalProgression?.toFloat() ?: 0f,
                    )
                }
            }
        }

        lifecycleScope.launch {
            app.prefs.settings.collect { s ->
                navigator.submitPreferences(s.toEpubPreferences())
                applyPageTheme(s.theme)
            }
        }

        findViewById<ComposeView>(R.id.overlay).setContent {
            AppTheme { Chrome() }
        }
    }

    private fun applyPageTheme(theme: PageTheme) {
        findViewById<View>(R.id.root).setBackgroundColor(theme.background.toInt())
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = theme.isLight
            isAppearanceLightNavigationBars = theme.isLight
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::bookId.isInitialized) readerApp.books.close(bookId)
    }

    private fun go(link: Link) {
        lifecycleScope.launch { navigator.go(link) }
    }

    /** Every reading position in the book, computed once on first seek. */
    private var positions: List<Locator>? = null

    private fun goToProgression(progression: Float) {
        lifecycleScope.launch {
            val all = positions ?: publication.positions().also { positions = it }
            if (all.isEmpty()) return@launch
            val index = (progression * (all.size - 1)).toInt().coerceIn(0, all.lastIndex)
            navigator.go(all[index])
        }
    }

    // ---- Compose chrome -----------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Chrome() {
        val app = readerApp
        val visible by chromeVisible
        val loc by locator.collectAsStateWithLifecycle()
        val settings by app.prefs.settings.collectAsStateWithLifecycle()
        var showToc by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        val barColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)

        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    title = {
                        Text(
                            publication.metadata.title ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    actions = {
                        if (publication.tableOfContents.isNotEmpty()) {
                            IconButton(onClick = { showToc = true }) {
                                Icon(Icons.AutoMirrored.Rounded.List, "Contents")
                            }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.TextFields, "Reading settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
                )
            }

            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(color = barColor) {
                    ProgressPanel(
                        chapter = loc?.title,
                        progress = loc?.locations?.totalProgression?.toFloat() ?: 0f,
                        onSeek = ::goToProgression,
                    )
                }
            }
        }

        if (showToc) {
            ModalBottomSheet(
                onDismissRequest = { showToc = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                val entries = remember { flatten(publication.tableOfContents) }
                Text(
                    "Contents",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.navigationBarsPadding()) {
                    items(entries) { (link, depth) ->
                        Text(
                            link.title ?: "Untitled",
                            style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = if (depth == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showToc = false
                                    chromeVisible.value = false
                                    go(link)
                                }
                                .padding(start = (24 + depth * 18).dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                        )
                    }
                }
            }
        }

        if (showSettings) {
            ReadingSettingsSheet(
                settings = settings,
                onChange = { app.prefs.update(it) },
                onDismiss = { showSettings = false },
            )
        }
    }

    private fun flatten(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> =
        links.flatMap { listOf(it to depth) + flatten(it.children, depth + 1) }

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"
        private const val NAVIGATOR_TAG = "navigator"

        fun intent(context: Context, bookId: String) =
            Intent(context, EpubReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}

@Composable
internal fun ProgressPanel(chapter: String?, progress: Float, onSeek: (Float) -> Unit, label: String? = null) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(progress) }
    LaunchedEffect(progress) { if (!dragging) dragValue = progress }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chapter.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                label ?: "${(dragValue * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Slider(
            value = dragValue,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; onSeek(dragValue) },
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}
