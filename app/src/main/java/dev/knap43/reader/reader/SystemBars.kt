package dev.knap43.reader.reader

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides or restores the status and navigation bars. While hidden, a swipe from
 * an edge shows them briefly over the page, without reflowing it.
 */
internal fun Window.setSystemBarsHidden(hidden: Boolean) {
    val controller = WindowCompat.getInsetsController(this, decorView)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
