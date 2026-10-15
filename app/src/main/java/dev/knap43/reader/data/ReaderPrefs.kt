package dev.knap43.reader.data

import android.content.Context
import android.content.pm.ActivityInfo
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

/** Screen orientation for a PDF, remembered per document. */
enum class PdfOrientation(val label: String, val activityValue: Int) {
    AUTO("Auto-rotate", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT("Portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
    LANDSCAPE("Landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

    fun next(): PdfOrientation = entries[(ordinal + 1) % entries.size]
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
    /** Break long words across lines in EPUBs, in the book's own language. */
    val hyphenate: Boolean = false,
) {
    /** Choices that only take effect once the book's own styling is relaxed. */
    val overridesPublisherStyles get() = font != FontChoice.ORIGINAL || spacing != Spacing.ORIGINAL || hyphenate

    fun toEpubPreferences(): EpubPreferences {
        val custom = overridesPublisherStyles
        return EpubPreferences(
            theme = if (theme.isLight) (if (theme == PageTheme.SEPIA) Theme.SEPIA else Theme.LIGHT) else Theme.DARK,
            backgroundColor = ReadiumColor(theme.background.toInt()),
            textColor = ReadiumColor(theme.text.toInt()),
            fontSize = fontScale.toDouble(),
            fontFamily = font.family?.let { FontFamily(it) },
            lineHeight = spacing.lineHeight,
            hyphens = if (hyphenate) true else null,
            // Line height, font and hyphenation overrides only apply once publisher styles are off.
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

    fun pdfOrientation(bookId: String): PdfOrientation =
        enumOr(sp.getString("pdfOrientation:$bookId", null), PdfOrientation.AUTO)

    fun setPdfOrientation(bookId: String, orientation: PdfOrientation) {
        sp.edit().apply {
            if (orientation == PdfOrientation.AUTO) remove("pdfOrientation:$bookId")
            else putString("pdfOrientation:$bookId", orientation.name)
        }.apply()
    }

    /** Drops per-document settings for a book removed from the library. */
    fun forgetBook(bookId: String) {
        sp.edit().remove("pdfOrientation:$bookId").apply()
    }

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
            .putBoolean("hyphenate", next.hyphenate)
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
        hyphenate = sp.getBoolean("hyphenate", false),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
