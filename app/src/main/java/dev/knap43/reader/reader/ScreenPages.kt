package dev.knap43.reader.reader

import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentContainerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.floor

/**
 * The book's screen pages at one layout: how many screens each reading-order resource fills.
 * Pages are numbered from 1 across the whole book.
 */
internal class PageMap(val counts: List<Int>) {
    private val starts = counts.runningFold(0) { sum, n -> sum + n }

    val total get() = starts.last()

    /** The page showing [progression] (Readium's scroll offset over content width) of resource [index]. */
    fun pageOf(index: Int, progression: Double): Int {
        val n = counts[index]
        // A screen's own locator sits exactly on k/n; the nudge keeps float error from flooring it to k-1.
        val screen = floor(progression * n + 1e-6).toInt().coerceIn(0, n - 1)
        return starts[index] + screen + 1
    }

    /** The resource a page falls in, and the progression Readium turns back into that screen. */
    fun locate(page: Int): Pair<Int, Double> {
        val p = page.coerceIn(1, total) - 1
        val index = (starts.indexOfLast { it <= p }).coerceAtMost(counts.lastIndex)
        return index to (p - starts[index]).toDouble() / counts[index]
    }

    fun withCount(index: Int, count: Int) = PageMap(counts.toMutableList().also { it[index] = count })
}

/**
 * Counts an EPUB's screen pages by laying every chapter out in a second, invisible Readium
 * navigator of the same size and with the same preferences as the one being read, so its
 * columns break exactly where the reader's do.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class PageCounter(
    private val publication: Publication,
    private val fragments: FragmentManager,
    /** Sits under the reader, the same size and padding, and never shows. */
    private val container: FragmentContainerView,
    private val cacheDir: File,
    private val bookId: String,
) {
    private val reflowable = publication.metadata.presentation.layout != EpubLayout.FIXED

    private var navigator: EpubNavigatorFragment? = null

    init {
        container.alpha = 0f
        container.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // After the activity is recreated, the system restores this fragment with the reader's own
        // factory and settings; it's replaced with a fresh one when counting starts.
        fragments.findFragmentByTag(TAG)?.let { fragments.beginTransaction().remove(it).commitNow() }
    }

    /** A counted layout saved earlier, so reopening a book shows its pages at once. */
    fun cached(key: String): PageMap? = runCatching {
        val all = JSONObject(cacheFile.readText())
        val counts = all.getJSONArray(key)
        PageMap(List(counts.length()) { counts.getInt(it) }).takeIf { it.counts.size == publication.readingOrder.size }
    }.getOrNull()

    fun save(key: String, map: PageMap) {
        runCatching {
            val all = runCatching { JSONObject(cacheFile.readText()) }.getOrDefault(JSONObject())
            // Keep the few most recent layouts: a couple of font sizes and both orientations.
            val keys = all.keys().asSequence().filter { it != key }.toList()
            keys.dropLast(MAX_CACHED_LAYOUTS - 1).forEach(all::remove)
            all.put(key, JSONArray(map.counts))
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(all.toString())
        }
    }

    private val cacheFile get() = File(cacheDir, "pages/$bookId.json")

    /**
     * Lays out each chapter in turn at [preferences] and counts its screens.
     * Cancelling the calling coroutine stops the count.
     */
    suspend fun count(preferences: EpubPreferences): PageMap {
        val order = publication.readingOrder
        // Fixed-layout books already are one page per screen.
        if (!reflowable) return PageMap(List(order.size) { 1 })
        val nav = navigatorFor(preferences)
        val positions = publication.positionsByReadingOrder()
        val counts = MutableList(order.size) { 0 }
        for ((i, link) in order.withIndex()) {
            coroutineContext.ensureActive()
            nav.go(link)
            counts[i] = measure(nav, link)
                // A chapter that never settles is estimated from the screens per position counted so far.
                ?: estimate(counts.subList(0, i), positions.take(i).map { it.size }, positions.getOrNull(i)?.size ?: 1)
        }
        return PageMap(counts)
    }

    private fun estimate(counted: List<Int>, sizes: List<Int>, size: Int): Int {
        val screens = counted.sum()
        val positions = sizes.sum()
        return if (positions == 0) size else maxOf(1, Math.round(size.toDouble() * screens / positions).toInt())
    }

    private fun navigatorFor(preferences: EpubPreferences): EpubNavigatorFragment {
        navigator?.let {
            it.submitPreferences(preferences)
            return it
        }
        val nav = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = null,
            initialPreferences = preferences,
        ).instantiate(container.context.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        fragments.beginTransaction().replace(container.id, nav, TAG).commitNow()
        navigator = nav
        return nav
    }

    /**
     * Screens in the chapter the hidden navigator shows, once its layout has stopped changing:
     * the page and its fonts loaded, and the same count read a few times running.
     */
    private suspend fun measure(nav: EpubNavigatorFragment, link: Link): Int? = withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
        var last = -1
        var steady = 0
        while (steady < STEADY_READS) {
            delay(SETTLE_STEP_MS)
            val reading = evaluate(nav)
            // No answer: the chapter's web view was likely swapped out under the call; load it again.
            if (reading == null) nav.go(link)
            val count = reading?.takeIf { it.startsWith("ready:") }?.removePrefix("ready:")?.toIntOrNull() ?: -1
            steady = if (count > 0 && count == last) steady + 1 else 0
            last = count
        }
        last
    }

    /**
     * Readium's script call never returns if the page's web view goes away first, and can't be
     * cancelled; so it runs apart from the count, which gives up on it after a while.
     * Null if it timed out; empty if the chapter has no page to run in yet.
     */
    private suspend fun evaluate(nav: EpubNavigatorFragment): String? {
        val call = scripts.async { nav.evaluateJavascript(MEASURE_SCRIPT) }
        return withTimeoutOrNull(SCRIPT_TIMEOUT_MS) { call.await().orEmpty() }?.trim('"')
    }

    private val scripts = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Drops the hidden navigator; the next count makes a new one. */
    fun release() {
        navigator?.let { if (!fragments.isStateSaved) fragments.beginTransaction().remove(it).commitNowAllowingStateLoss() }
        navigator = null
    }

    companion object {
        private const val TAG = "page_counter"
        private const val MAX_CACHED_LAYOUTS = 4
        private const val MEASURE_TIMEOUT_MS = 10_000L
        private const val SETTLE_STEP_MS = 80L
        private const val SCRIPT_TIMEOUT_MS = 1_500L
        private const val STEADY_READS = 2

        /** The same sum Readium makes for its own page count: content width over the view's width. */
        private const val MEASURE_SCRIPT = """
            (function() {
                var ready = document.readyState === 'complete' && (!document.fonts || document.fonts.status === 'loaded');
                var n = Math.max(1, Math.round(document.scrollingElement.scrollWidth / window.innerWidth));
                return (ready ? 'ready:' : 'busy:') + n;
            })();
        """
    }
}

/**
 * Where a locator sits in the reading order, by resource and progression through it.
 * [positions] (Readium's positions by resource) places locators that carry only a total progression.
 */
internal fun Publication.placeOf(loc: Locator, positions: List<List<Locator>>?): Pair<Int, Double>? {
    val href = loc.href.removeFragment()
    val index = readingOrder.indexOfFirst { it.url().removeFragment().isEquivalent(href) }.takeIf { it >= 0 } ?: return null
    loc.locations.progression?.let { return index to it }
    val total = loc.locations.totalProgression ?: return index to 0.0
    val start = positions?.getOrNull(index)?.firstOrNull()?.locations?.totalProgression ?: return index to 0.0
    val end = positions.getOrNull(index + 1)?.firstOrNull()?.locations?.totalProgression ?: 1.0
    return index to if (end > start) ((total - start) / (end - start)).coerceIn(0.0, 1.0) else 0.0
}

/** A locator Readium opens on the given screen of a resource. */
internal fun Publication.locatorAt(index: Int, progression: Double): Locator {
    val link = readingOrder[index]
    return Locator(
        href = link.url(),
        mediaType = link.mediaType ?: MediaType.XHTML,
        title = link.title,
        locations = Locator.Locations(progression = progression),
    )
}

