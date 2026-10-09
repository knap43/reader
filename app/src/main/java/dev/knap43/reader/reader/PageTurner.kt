package dev.knap43.reader.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.Key
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Turns pages on edge taps and arrow/space keys, optionally with a slide.
 *
 * Readium's own `animated` flag only animates turns that cross into another
 * chapter: within a chapter, a tap moves the page instantly (only swipes glide).
 * So the slide is drawn here instead: a snapshot of the current page is laid
 * over the reader, the page is turned at once underneath, and then the snapshot
 * slides out while the new page slides in. Every turn looks the same, in or
 * across chapters.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class PageTurner(
    private val window: Window,
    private val navigator: EpubNavigatorFragment,
    /** The view that moves: the reader's container. */
    private val page: View,
    /** An ImageView above [page] and below the reading chrome, normally hidden. */
    private val snapshot: ImageView,
    private val scope: CoroutineScope,
) : InputListener {

    /** Slide pages when turning them; off turns them at once. */
    var animated = false

    private var running: Animator? = null
    private var busy = false

    override fun onTap(event: TapEvent): Boolean {
        if (navigator.overflow.value.scroll) return false
        val width = navigator.publicationView.width.toFloat()
        val edge = maxOf(EDGE_MIN_PX, EDGE_FRACTION * width)
        return when {
            event.point.x >= width - edge -> turn(right = true)
            event.point.x <= edge -> turn(right = false)
            else -> false
        }
    }

    override fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEvent.Type.Down || event.modifiers.isNotEmpty()) return false
        val ltr = navigator.overflow.value.readingProgression == ReadingProgression.LTR
        return when (event.key) {
            Key.ArrowRight -> turn(right = true)
            Key.ArrowLeft -> turn(right = false)
            Key.ArrowDown, Key.Space -> turn(right = ltr)
            Key.ArrowUp -> turn(right = !ltr)
            else -> false
        }
    }

    /** Turns toward the right or left edge of the screen; RTL books turn backward on the right. */
    private fun turn(right: Boolean): Boolean {
        val forward = right == (navigator.overflow.value.readingProgression == ReadingProgression.LTR)
        // Finish a slide still in progress, so rapid taps never queue up.
        running?.end()
        // Taps landing while a turn is being prepared are absorbed rather than turning pages unseen.
        if (busy) return true
        if (!animated || page.width == 0 || page.height == 0) {
            return go(forward)
        }
        busy = true
        scope.launch {
            try {
                val shot = capture()
                if (shot == null) {
                    go(forward)
                    return@launch
                }
                snapshot.setImageBitmap(shot)
                snapshot.translationX = 0f
                snapshot.visibility = View.VISIBLE
                val before = navigator.currentLocator.value
                if (!go(forward)) {
                    hideSnapshot() // first or last page: nothing to turn to
                    return@launch
                }
                // Wait until the new page is in place, then give the web view a frame to draw it.
                withTimeoutOrNull(SETTLE_TIMEOUT_MS) { navigator.currentLocator.first { it != before } }
                nextFrame()
                nextFrame()
                slide(right)
            } finally {
                busy = false
            }
        }
        return true
    }

    private fun go(forward: Boolean): Boolean =
        if (forward) navigator.goForward(animated = false) else navigator.goBackward(animated = false)

    private fun slide(right: Boolean) {
        val w = page.width.toFloat()
        val sign = if (right) -1f else 1f
        page.translationX = -sign * w
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(snapshot, View.TRANSLATION_X, 0f, sign * w),
                ObjectAnimator.ofFloat(page, View.TRANSLATION_X, -sign * w, 0f),
            )
            duration = SLIDE_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    page.translationX = 0f
                    hideSnapshot()
                    if (running === animation) running = null
                }
            })
        }
        running = set
        set.start()
    }

    private fun hideSnapshot() {
        snapshot.visibility = View.GONE
        snapshot.translationX = 0f
        snapshot.setImageDrawable(null)
    }

    /** Copies what the reader shows right now, straight from the window. */
    private suspend fun capture(): Bitmap? = suspendCoroutine { cont ->
        val loc = IntArray(2)
        page.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + page.width, loc[1] + page.height)
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, rect, bitmap, { result ->
                cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            cont.resume(null) // window not drawn yet
        }
    }

    private suspend fun nextFrame() = suspendCoroutine { cont -> page.postOnAnimation { cont.resume(Unit) } }

    private companion object {
        const val EDGE_FRACTION = 0.3f
        const val EDGE_MIN_PX = 80f
        const val SETTLE_TIMEOUT_MS = 400L
        const val SLIDE_MS = 280L
    }
}
