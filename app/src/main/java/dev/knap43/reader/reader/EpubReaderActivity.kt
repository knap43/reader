package dev.knap43.reader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.knap43.reader.R
import dev.knap43.reader.data.Book
import dev.knap43.reader.data.Bookmark
import dev.knap43.reader.data.ReadingSettings
import dev.knap43.reader.data.Highlight
import dev.knap43.reader.data.PageTheme
import dev.knap43.reader.readerApp
import dev.knap43.reader.ui.theme.AppTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl

class EpubReaderActivity : AppCompatActivity() {

    private lateinit var bookId: String
    private lateinit var publication: Publication

    private val chromeVisible = mutableStateOf(false)

    /** Turns pages on edge taps and keys, sliding them when that's switched on. */
    private lateinit var pageTurner: PageTurner
    private val locator = MutableStateFlow<Locator?>(null)

    private val navigator: EpubNavigatorFragment
        get() = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment

    /** Where the reader was before following a link forward (to notes, say); offered as a way back. */
    private val returnPoint = MutableStateFlow<Locator?>(null)

    /**
     * The book's screen pages at the current text settings and screen size, so one turn is one
     * page. Null while they are being counted, which takes a while the first time for a long book.
     */
    private val pageMap = MutableStateFlow<PageMap?>(null)
    private lateinit var pageCounter: PageCounter
    private var countJob: Job? = null
    /** The layout [pageMap] was counted for (see [layoutKey]). */
    private var countedLayout: String? = null

    /** Readium's positions by resource; they place search hits, and steer seeking until pages are counted. */
    private var positions: List<List<Locator>>? = null

    /** The 1-based page a locator falls on, or null while pages are still being counted. */
    private fun pageOf(loc: Locator): Int? {
        val map = pageMap.value ?: return null
        val (index, progression) = publication.placeOf(loc, positions) ?: return null
        return map.pageOf(index, progression)
    }

    /** The page a slider value lands on; matches [goToProgression]. */
    private fun pageAt(progression: Float, total: Int) = (progression * (total - 1)).toInt().coerceIn(0, total - 1) + 1

    /** What page breaks depend on: the text settings and the space the pages fill. */
    private fun layoutKey(s: ReadingSettings, page: View): String {
        val w = page.width - page.paddingLeft - page.paddingRight
        val h = page.height - page.paddingTop - page.paddingBottom
        return "${w}x$h ${s.fontScale} ${s.font} ${s.spacing} ${s.hyphenate}"
    }

    /** Counts pages anew when the layout changes, or picks up a count saved for it earlier. */
    private fun recountPages(page: View) {
        if (page.width == 0 || page.height == 0) return
        val settings = readerApp.prefs.settings.value
        val key = layoutKey(settings, page)
        if (key == countedLayout) return
        countedLayout = key
        countJob?.cancel()
        // A chapter count from the old layout must not "correct" the new one.
        chapterScreens.value = null
        pageMap.value = pageCounter.cached(key)
        if (pageMap.value != null) {
            pageCounter.release()
            return
        }
        countJob = lifecycleScope.launch {
            // Let a run of changes (stepping through text sizes, say) settle first.
            delay(RECOUNT_DELAY_MS)
            // The hidden navigator is a fragment, so it only counts while the reader is on screen.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (pageMap.value != null) return@repeatOnLifecycle
                val map = pageCounter.count(settings.toEpubPreferences())
                pageCounter.release()
                pageCounter.save(key, map)
                pageMap.value = map
            }
        }
    }

    /**
     * Readium's own screen count for the chapter being read is exact, so it corrects the count
     * where a chapter laid out differently (say, an image that loaded late).
     */
    private fun correctPages(loc: Locator, screens: Int) {
        val map = pageMap.value ?: return
        val (index, _) = publication.placeOf(loc, positions) ?: return
        if (screens <= 0 || map.counts[index] == screens) return
        val fixed = map.withCount(index, screens)
        pageMap.value = fixed
        countedLayout?.let { pageCounter.save(it, fixed) }
    }

    /** The visible chapter's screen count as Readium reports it, with the locator it was reported for. */
    private val chapterScreens = MutableStateFlow<Pair<Locator, Int>?>(null)

    private val paginationListener = object : EpubNavigatorFragment.PaginationListener {
        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
            chapterScreens.value = locator to totalPages
        }
    }

    private val navigatorListener = object : EpubNavigatorFragment.Listener {
        override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
            val here = navigator.currentLocator.value
            // Keep the first point in a chain of note links, so Back returns to the text itself.
            if (returnPoint.value == null && leadsForward(here, link, context)) returnPoint.value = here
            return true
        }

        override fun onExternalLinkActivated(url: AbsoluteUrl) {
            if (!url.isHttp) return
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    /** Footnote links, and links into this chapter or a later one (endnotes, notes sections). */
    private fun leadsForward(here: Locator, link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
        if (context is HyperlinkNavigator.FootnoteContext) return true
        val order = publication.readingOrder
        val from = order.indexOfFirstWithHref(here.href.removeFragment()) ?: return false
        val to = order.indexOfFirstWithHref(link.url().removeFragment()) ?: return false
        return to >= from
    }

    private fun goBackToReturnPoint() {
        val target = returnPoint.value ?: return
        returnPoint.value = null
        lifecycleScope.launch { navigator.go(target) }
    }

    private lateinit var book: Book

    /** Highlight tapped in the text, offered for copying or removal. */
    private val tappedHighlight = mutableStateOf<Highlight?>(null)

    // Book-wide search; results outlive the sheet so it reopens where it was.
    private val searchQuery = mutableStateOf("")
    private val searchedFor = mutableStateOf<String?>(null)
    private val searchResults = MutableStateFlow<List<Locator>>(emptyList())
    private val searching = mutableStateOf(false)
    private var searchJob: Job? = null

    private fun search(query: String) {
        searchJob?.cancel()
        searchResults.value = emptyList()
        searchedFor.value = query
        searching.value = true
        searchJob = lifecycleScope.launch {
            val iterator = publication.search(query)
            try {
                while (iterator != null) {
                    val page = iterator.next().getOrNull() ?: break
                    searchResults.value = searchResults.value + page.locators
                }
            } finally {
                iterator?.close()
                searching.value = false
            }
        }
    }

    /** Jumps to a search match and marks it until the next search or the sheet is used again. */
    private fun openSearchResult(target: Locator) {
        lifecycleScope.launch {
            navigator.applyDecorations(
                listOf(Decoration("search", target, Decoration.Style.Underline(tint = SEARCH_TINT))),
                GROUP_SEARCH,
            )
            navigator.go(target)
        }
    }

    private fun clearSearchMark() {
        lifecycleScope.launch { navigator.applyDecorations(emptyList(), GROUP_SEARCH) }
    }

    /** The table-of-contents entry a position belongs to, falling back to the locator's own title. */
    private fun chapterTitle(loc: Locator): String? {
        val href = loc.href.removeFragment()
        return flatten(publication.tableOfContents).firstOrNull { (link, _) ->
            link.url().removeFragment().isEquivalent(href)
        }?.first?.title ?: loc.title
    }

    private fun goTo(loc: Locator) {
        returnPoint.value = null
        lifecycleScope.launch { navigator.go(loc) }
    }

    private fun parseLocator(json: String) = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    /** The bookmark on the screen being read. */
    private fun bookmarkHere(bookmarks: List<Bookmark>, here: Locator?): Bookmark? {
        here ?: return null
        val page = pageOf(here)
        return bookmarks.firstOrNull { b ->
            val l = parseLocator(b.position) ?: return@firstOrNull false
            l.href.removeFragment().isEquivalent(here.href.removeFragment()) && when {
                page != null -> pageOf(l) == page
                // Until pages are counted, only the very same screen matches.
                else -> kotlin.math.abs((l.locations.progression ?: -1.0) - (here.locations.progression ?: -2.0)) < 1e-4
            }
        }
    }

    private fun toggleBookmark() {
        val here = locator.value ?: return
        val annotations = readerApp.annotations
        val existing = bookmarkHere(annotations.of(bookId).value.bookmarks, here)
        if (existing != null) {
            annotations.removeBookmark(bookId, existing.id)
        } else {
            annotations.addBookmark(
                bookId,
                Bookmark(
                    // No page number here: it moves with the text size; the list shows the current one.
                    label = chapterTitle(here) ?: "Bookmark",
                    progress = (here.locations.totalProgression ?: 0.0).toFloat(),
                    position = here.toJSON().toString(),
                ),
            )
        }
    }

    /** Text picked for a dictionary lookup, shown in a sheet until dismissed. */
    private val lookup = mutableStateOf<String?>(null)

    /** Replaces the system text-selection menu, so it carries Copy alongside Define. */
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_HIGHLIGHT, 0, "Highlight")
            menu.add(Menu.NONE, MENU_DEFINE, 1, "Define")
            menu.add(Menu.NONE, MENU_COPY, 2, android.R.string.copy)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId !in setOf(MENU_DEFINE, MENU_COPY, MENU_HIGHLIGHT)) return false
            lifecycleScope.launch {
                // Read the selection before closing the menu, which can drop it.
                val selected = navigator.currentSelection()?.locator
                val text = selected?.text?.highlight?.trim()
                mode.finish()
                navigator.clearSelection()
                if (selected == null || text.isNullOrEmpty()) return@launch
                when (item.itemId) {
                    MENU_HIGHLIGHT -> readerApp.annotations.addHighlight(
                        bookId,
                        Highlight(
                            text = text,
                            chapter = chapterTitle(selected),
                            progress = (selected.locations.totalProgression ?: locator.value?.locations?.totalProgression ?: 0.0).toFloat(),
                            position = selected.toJSON().toString(),
                        ),
                    )
                    MENU_DEFINE -> lookup.value = text
                    MENU_COPY -> getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(null, text))
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

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
        this.book = book

        val initialLocator = book.position?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        // The fragment factory must be in place before super.onCreate restores fragments.
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = app.prefs.settings.value.toEpubPreferences(),
            listener = navigatorListener,
            paginationListener = paginationListener,
            configuration = EpubNavigatorFragment.Configuration {
                selectionActionModeCallback = selectionCallback
            },
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub)

        val container = findViewById<FragmentContainerView>(R.id.navigator_container)
        val counterContainer = findViewById<FragmentContainerView>(R.id.page_counter_container)
        // Readium already pads the page for the display cutout (top and bottom), so only the part
        // of the system bars beyond the cutout is added here; counting the cutout twice left a gap.
        // The page counter's container gets the same, so its pages break where the reader's do.
        val padForBars = OnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                maxOf(bars.left, cutout.left),
                (bars.top - cutout.top).coerceAtLeast(0),
                maxOf(bars.right, cutout.right),
                (bars.bottom - cutout.bottom).coerceAtLeast(0),
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(container, padForBars)
        ViewCompat.setOnApplyWindowInsetsListener(counterContainer, padForBars)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.navigator_container, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
                .commitNow()
        }

        // Edge taps turn pages; a tap anywhere else shows or hides the reading chrome.
        pageTurner = PageTurner(window, navigator, container, findViewById<ImageView>(R.id.turn_snapshot), lifecycleScope)
        navigator.addInputListener(pageTurner)
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                chromeVisible.value = !chromeVisible.value
                return true
            }
        })

        navigator.addDecorationListener(GROUP_HIGHLIGHTS, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                tappedHighlight.value = app.annotations.of(bookId).value.highlights.firstOrNull { it.id == event.decoration.id }
                return tappedHighlight.value != null
            }
        })
        lifecycleScope.launch {
            app.annotations.of(bookId).collect { a ->
                navigator.applyDecorations(
                    a.highlights.mapNotNull { h ->
                        parseLocator(h.position)?.let { Decoration(h.id, it, Decoration.Style.Highlight(tint = HIGHLIGHT_TINT)) }
                    },
                    GROUP_HIGHLIGHTS,
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { loc ->
                    locator.value = loc
                    // Back on the page we left (e.g. through the note's own return link): nothing to offer.
                    returnPoint.value?.let { back ->
                        val p = back.locations.totalProgression
                        val q = loc.locations.totalProgression
                        if (back.href.isEquivalent(loc.href) && p != null && q != null && kotlin.math.abs(p - q) < 0.002) {
                            returnPoint.value = null
                        }
                    }
                    app.library.updatePosition(
                        bookId,
                        loc.toJSON().toString(),
                        loc.locations.totalProgression?.toFloat() ?: 0f,
                        reading = isReadingStep(lastLocator, loc),
                    )
                    lastLocator = loc
                }
            }
        }

        lifecycleScope.launch { positions = publication.positionsByReadingOrder() }

        pageCounter = PageCounter(publication, supportFragmentManager, counterContainer, cacheDir, bookId)
        container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.post { recountPages(v) } }
        lifecycleScope.launch {
            @OptIn(FlowPreview::class)
            chapterScreens.filterNotNull().debounce(CORRECTION_DELAY_MS).collect { (loc, screens) -> correctPages(loc, screens) }
        }

        lifecycleScope.launch {
            app.prefs.settings.collect { s ->
                navigator.submitPreferences(s.toEpubPreferences())
                applyPageTheme(s.theme)
                window.setSystemBarsHidden(s.hideSystemBars)
                pageTurner.animated = s.animatePageTurns
                recountPages(container)
            }
        }

        findViewById<ComposeView>(R.id.overlay).setContent {
            AppTheme { Chrome() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and sheets can bring the bars back; hide them again on return.
        if (hasFocus && ::bookId.isInitialized) window.setSystemBarsHidden(readerApp.prefs.settings.value.hideSystemBars)
    }

    private fun applyPageTheme(theme: PageTheme) {
        findViewById<View>(R.id.root).setBackgroundColor(theme.background.toInt())
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = theme.isLight
            isAppearanceLightNavigationBars = theme.isLight
        }
    }

    override fun finish() {
        // Closed by the reader, not lost to process death: open the library next time.
        if (::bookId.isInitialized) readerApp.prefs.resumeBookId = null
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::bookId.isInitialized) readerApp.books.close(bookId)
    }

    /** The previous position seen, to tell a page turn from a jump. Null until the book has opened. */
    private var lastLocator: Locator? = null

    /** A short step forward reads like a page turn; anything else (links, contents, search, seeking) is a jump. */
    private fun isReadingStep(from: Locator?, to: Locator): Boolean {
        from ?: return false
        val a = from.locations.position
        val b = to.locations.position
        if (a != null && b != null) return b - a in 0..MAX_READING_STEP
        val p = from.locations.totalProgression ?: return false
        val q = to.locations.totalProgression ?: return false
        return q - p in 0.0..0.01
    }

    private fun go(link: Link) {
        lifecycleScope.launch { navigator.go(link) }
    }

    private fun goToProgression(progression: Float) {
        returnPoint.value = null
        lifecycleScope.launch {
            pageMap.value?.let { map ->
                val (index, at) = map.locate(pageAt(progression, map.total))
                navigator.go(publication.locatorAt(index, at))
                return@launch
            }
            // Pages not counted yet: step by Readium's positions instead.
            val all = publication.positions()
            if (all.isEmpty()) return@launch
            navigator.go(all[pageAt(progression, all.size) - 1])
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
        val annotations by app.annotations.of(bookId).collectAsStateWithLifecycle()
        val map by pageMap.collectAsStateWithLifecycle()
        val total = map?.total ?: 0
        var showNav by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        val marked = remember(annotations.bookmarks, loc, map) { bookmarkHere(annotations.bookmarks, loc) != null }
        val barColor = MaterialTheme.colorScheme.surfaceContainer

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
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Rounded.Search, "Search") }
                        IconButton(onClick = ::toggleBookmark) {
                            Icon(
                                if (marked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                if (marked) "Remove bookmark" else "Bookmark this page",
                                tint = if (marked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = { showNav = true }) {
                            Icon(Icons.AutoMirrored.Rounded.List, "Contents, bookmarks and highlights")
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.TextFields, "Reading settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
                )
            }

            val back by returnPoint.collectAsStateWithLifecycle()
            AnimatedVisibility(
                visible = back != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (visible) 112.dp else 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = ::goBackToReturnPoint,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Back to reading")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = { returnPoint.value = null }) {
                        Icon(Icons.Rounded.Close, "Dismiss")
                    }
                }
            }

            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(color = barColor) {
                    val page = loc?.let(::pageOf)
                    ProgressPanel(
                        chapter = loc?.title,
                        // Once pages are counted, the slider moves a page's width with every turn.
                        progress = if (page != null && total > 1) (page - 1).toFloat() / (total - 1)
                        else loc?.locations?.totalProgression?.toFloat() ?: 0f,
                        onSeek = ::goToProgression,
                        label = { dragged ->
                            val shown = if (dragged != null) pageAt(dragged, total) else page
                            if (total > 0 && shown != null) "Page $shown of $total"
                            else "${(((dragged ?: loc?.locations?.totalProgression?.toFloat()) ?: 0f) * 100).toInt()}%"
                        },
                    )
                }
            }
        }

        if (showNav) {
            val close = { showNav = false; chromeVisible.value = false }
            NavigationSheet(
                book = book,
                contents = remember {
                    flatten(publication.tableOfContents).map { (link, depth) ->
                        TocEntry(link.title ?: "Untitled", depth) { close(); returnPoint.value = null; go(link) }
                    }
                },
                // Older bookmarks carried their percentage in the label; the page now shows beside it.
                bookmarks = annotations.bookmarks.map { b ->
                    b.copy(label = b.label.replace(LEGACY_PERCENT, "").ifEmpty { "Bookmark" })
                },
                highlights = annotations.highlights,
                onOpenBookmark = { b -> close(); parseLocator(b.position)?.let(::goTo) },
                onDeleteBookmark = { b -> app.annotations.removeBookmark(bookId, b.id) },
                onOpenHighlight = { h -> close(); parseLocator(h.position)?.let(::goTo) },
                onDeleteHighlight = { h -> app.annotations.removeHighlight(bookId, h.id) },
                onDismiss = { showNav = false },
                placeOf = { position, progress ->
                    total.takeIf { it > 0 }?.let { parseLocator(position)?.let(::pageOf) }?.let { "p. $it" }
                        ?: "${(progress * 100).toInt()}%"
                },
            )
        }

        if (showSearch) {
            val results by searchResults.collectAsStateWithLifecycle()
            SearchSheet(
                query = searchQuery.value,
                onQueryChange = { searchQuery.value = it },
                hits = remember(results, map) {
                    results.map {
                        val place = listOfNotNull(chapterTitle(it), pageOf(it)?.let { n -> "p. $n" }).joinToString(" · ")
                        SearchHit(place.ifEmpty { null }, it.text.before.orEmpty(), it.text.highlight.orEmpty(), it.text.after.orEmpty())
                    }
                },
                searching = searching.value,
                searchedFor = searchedFor.value,
                onSearch = { clearSearchMark(); search(it) },
                onOpen = { i ->
                    showSearch = false
                    chromeVisible.value = false
                    results.getOrNull(i)?.let(::openSearchResult)
                },
                onDismiss = { showSearch = false },
            )
        }

        tappedHighlight.value?.let { h ->
            HighlightDialog(
                highlight = h,
                onCopy = {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, h.text))
                    tappedHighlight.value = null
                },
                onDefine = { lookup.value = h.text; tappedHighlight.value = null },
                onDelete = { app.annotations.removeHighlight(bookId, h.id); tappedHighlight.value = null },
                onDismiss = { tappedHighlight.value = null },
            )
        }

        lookup.value?.let { text ->
            DictionarySheet(text, onDismiss = { lookup.value = null })
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
        private const val MENU_DEFINE = 1
        private const val MENU_COPY = 2
        private const val MENU_HIGHLIGHT = 3
        private const val GROUP_HIGHLIGHTS = "highlights"
        private const val GROUP_SEARCH = "search"
        /** Readium positions (~1024 characters each) one page turn may cover at small font sizes. */
        private const val MAX_READING_STEP = 3
        private const val RECOUNT_DELAY_MS = 600L
        private const val CORRECTION_DELAY_MS = 800L
        private val LEGACY_PERCENT = Regex("""(^| · )\d+%$""")
        private const val HIGHLIGHT_TINT = 0xFFE1A34F.toInt()
        private const val SEARCH_TINT = 0xFF6FA8DC.toInt()

        fun intent(context: Context, bookId: String) =
            Intent(context, EpubReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}

@Composable
internal fun ProgressPanel(
    chapter: String?,
    progress: Float,
    onSeek: (Float) -> Unit,
    /** The readout beside the chapter; given the slider value while dragging, null at rest. */
    label: (dragged: Float?) -> String = { d -> "${((d ?: progress) * 100).toInt()}%" },
) {
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
                label(if (dragging) dragValue else null),
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
