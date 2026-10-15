package dev.knap43.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class PartOfSpeech(val code: String, val label: String) {
    NOUN("n", "noun"), VERB("v", "verb"), ADJECTIVE("a", "adjective"), ADVERB("r", "adverb");

    companion object {
        fun of(code: String) = entries.first { it.code == code }
    }
}

data class Sense(val definition: String, val examples: List<String>, val synonyms: List<String>)

data class DictionaryEntry(val word: String, val pos: PartOfSpeech, val senses: List<Sense>)

/**
 * Offline English dictionary built from WordNet 3.0 (see tools/build_dictionary.py).
 * The database ships in the APK and is copied out on first use, since SQLite can't read from assets.
 */
class Dictionary(private val context: Context) {

    private val dbFile = File(context.noBackupFilesDir, "dictionary-en.db")
    private val lock = Mutex()
    private var db: SQLiteDatabase? = null

    /** Entries for [query], best match first; empty when nothing is found. */
    suspend fun lookup(query: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val word = normalize(query)?.lowercase() ?: return@withContext emptyList()
        val db = open()
        PartOfSpeech.entries
            .flatMap { pos -> baseForms(db, word, pos).map { it to pos } }
            .mapNotNull { (base, pos) -> entry(db, base, pos) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private suspend fun open(): SQLiteDatabase = lock.withLock {
        db?.let { return it }
        val prefs = context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)
        if (!dbFile.exists() || prefs.getInt("version", 0) != DB_VERSION) {
            val tmp = File(dbFile.parentFile, dbFile.name + ".tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dbFile)
            prefs.edit().putInt("version", DB_VERSION).apply()
        }
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    /** The word itself plus its WordNet base forms (irregular forms first, then suffix rules). */
    private fun baseForms(db: SQLiteDatabase, word: String, pos: PartOfSpeech): List<String> {
        val forms = linkedSetOf(word)
        db.rawQuery("SELECT base FROM exc WHERE form = ? AND pos = ?", arrayOf(word, pos.code)).use { c ->
            while (c.moveToNext()) forms += c.getString(0)
        }
        for ((suffix, ending) in RULES[pos].orEmpty()) {
            if (word.endsWith(suffix) && word.length > suffix.length) {
                forms += word.dropLast(suffix.length) + ending
            }
        }
        return forms.toList()
    }

    /** One part-of-speech entry and how commonly that reading occurs, or null if the word lacks it. */
    private fun entry(db: SQLiteDatabase, word: String, pos: PartOfSpeech): Pair<DictionaryEntry, Int>? {
        var tags = 0
        val senses = mutableListOf<Sense>()
        db.rawQuery(
            """SELECT s.tags, y.def, y.ex, y.syn FROM sense s JOIN synset y ON y.id = s.synset
               WHERE s.word = ? AND s.pos = ? ORDER BY s.rank""",
            arrayOf(word, pos.code),
        ).use { c ->
            while (c.moveToNext()) {
                tags = c.getInt(0)
                senses += Sense(
                    definition = c.getString(1),
                    examples = c.getString(2)?.split('\n').orEmpty(),
                    synonyms = c.getString(3).split('\n').filterNot { it.equals(word, ignoreCase = true) },
                )
            }
        }
        if (senses.isEmpty()) return null
        return DictionaryEntry(word, pos, senses) to tags
    }

    companion object {
        private const val ASSET = "dictionary/en.db"
        private const val DB_VERSION = 1

        private val RULES = mapOf(
            PartOfSpeech.NOUN to listOf(
                "s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh",
                "men" to "man", "ies" to "y",
            ),
            PartOfSpeech.VERB to listOf(
                "s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to "",
            ),
            PartOfSpeech.ADJECTIVE to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
        )

        private val EDGES = Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$""")

        /** Trims punctuation, possessives and stray whitespace from a selection; null if it isn't a word. */
        fun normalize(text: String): String? {
            val t = text.replace('’', '\'').replace(Regex("""\s+"""), " ").replace(EDGES, "")
                .removeSuffix("'s").removeSuffix("'")
            return t.takeIf { it.isNotEmpty() && it.length <= 60 && it.any(Char::isLetter) }
        }
    }
}
