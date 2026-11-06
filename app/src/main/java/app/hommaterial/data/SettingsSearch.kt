package app.hommaterial.data

import java.text.Normalizer

/**
 * The search of the settings: every word typed must start, or be found in, a word of what a section
 * is about, with one typo forgiven once four letters are typed. The web app does the same in
 * pwa/shared/search.js: keep them alike.
 */
object SettingsSearch {
    /** Lowercase and without accents: "luminosita" finds "luminosità". */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun words(text: String): List<String> = fold(text).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val next = IntArray(b.length + 1)
            next[0] = i
            for (j in 1..b.length) {
                next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            row = next
        }
        return row[b.length]
    }

    private fun near(word: String, key: String): Boolean {
        if (key.startsWith(word) || (word.length >= 3 && word in key)) return true
        if (word.length < 4) return false
        return (word.length - 1..word.length + 1).any { it <= key.length && distance(word, key.take(it)) <= 1 }
    }

    /** Whether [query] finds [text]; an empty query finds everything. */
    fun matches(query: String, text: String): Boolean {
        val all = words(query)
        // Short words such as "di" or "la" only count when nothing else was typed.
        val wanted = all.filter { it.length >= 3 }.ifEmpty { all }
        if (wanted.isEmpty()) return true
        val keys = words(text)
        return wanted.all { word -> keys.any { near(word, it) } }
    }
}
