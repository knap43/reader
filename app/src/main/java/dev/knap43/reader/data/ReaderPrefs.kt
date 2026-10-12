package dev.knap43.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.Color as ReadiumColor

enum class PageTheme(val label: String, val background: Long, val text: Long, val isLight: Boolean) {
    DUSK("Dusk", 0xFF16130F, 0xFFE4D9CA, false),
    NIGHT("Night", 0xFF000000, 0xFFC9C4BD, false),
    SEPIA("Sepia", 0xFFF3EAD7, 0xFF5B4636, true),
    PAPER("Paper", 0xFFFBFAF7, 0xFF1C1B19, true),
}

enum class FontChoice(val label: String, val family: String?) {
    ORIGINAL("Original", null),
    SERIF("Serif", "serif"),
    SANS("Sans", "sans-serif"),
}

enum class Spacing(val label: String, val lineHeight: Double?) {
    ORIGINAL("Original", null),
    COMPACT("Compact", 1.3),
    COMFORT("Comfort", 1.55),
    AIRY("Airy", 1.85),
}

data class ReadingSettings(
    val theme: PageTheme = PageTheme.DUSK,
    val fontScale: Float = 1f,
    val font: FontChoice = FontChoice.ORIGINAL,
    val spacing: Spacing = Spacing.ORIGINAL,
    val pdfNight: Boolean = true,
    /** Hide the status and navigation bars while reading. */
    val hideSystemBars: Boolean = false,
    /** Slide EPUB pages when turning them with taps or keys. */
    val animatePageTurns: Boolean = false,
) {
    fun toEpubPreferences(): EpubPreferences {
        val custom = font != FontChoice.ORIGINAL || spacing != Spacing.ORIGINAL
        return EpubPreferences(
            theme = if (theme.isLight) (if (theme == PageTheme.SEPIA) Theme.SEPIA else Theme.LIGHT) else Theme.DARK,
            backgroundColor = ReadiumColor(theme.background.toInt()),
            textColor = ReadiumColor(theme.text.toInt()),
            fontSize = fontScale.toDouble(),
            fontFamily = font.family?.let { FontFamily(it) },
            lineHeight = spacing.lineHeight,
            // Line height and font overrides only apply once publisher styles are off.
            publisherStyles = !custom,
        )
    }
}

class ReaderPrefs(context: Context) {
    private val sp = context.getSharedPreferences("reading", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ReadingSettings> = _settings.asStateFlow()

    /** The book left open in a reader, reopened on the next launch; cleared when the reader is closed. */
    var resumeBookId: String?
        get() = sp.getString("resumeBook", null)
        set(value) = sp.edit().putString("resumeBook", value).apply()

    fun update(change: (ReadingSettings) -> ReadingSettings) {
        val next = change(_settings.value)
        _settings.value = next
        sp.edit()
            .putString("theme", next.theme.name)
            .putFloat("fontScale", next.fontScale)
            .putString("font", next.font.name)
            .putString("spacing", next.spacing.name)
            .putBoolean("pdfNight", next.pdfNight)
            .putBoolean("hideSystemBars", next.hideSystemBars)
            .putBoolean("animatePageTurns", next.animatePageTurns)
            .apply()
    }

    private fun load() = ReadingSettings(
        theme = enumOr(sp.getString("theme", null), PageTheme.DUSK),
        fontScale = sp.getFloat("fontScale", 1f),
        font = enumOr(sp.getString("font", null), FontChoice.ORIGINAL),
        spacing = enumOr(sp.getString("spacing", null), Spacing.ORIGINAL),
        pdfNight = sp.getBoolean("pdfNight", true),
        hideSystemBars = sp.getBoolean("hideSystemBars", false),
        animatePageTurns = sp.getBoolean("animatePageTurns", false),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
