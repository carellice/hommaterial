package app.hommaterial.voice

import java.text.Normalizer

/**
 * What the chat with the assistant may complete, by name as the user sees it: [power] what can be
 * switched (devices, groups, remotes), [run] the custom devices, [rooms], the to-do [lists], the
 * voice [phrases] of the custom devices and the [verbs] to start a command with.
 */
data class CompletionWords(
    val power: List<String> = emptyList(),
    val run: List<String> = emptyList(),
    val rooms: List<String> = emptyList(),
    val lists: List<String> = emptyList(),
    val phrases: List<String> = emptyList(),
    val verbs: List<String> = emptyList(),
)

/** A suggestion: [label] is shown, [text] is what the field holds once it is picked. */
data class Completion(val label: String, val text: String)

// Kept the same as completeCommand in pwa/web/js/voice.js.
private val LIST_NAMES = setOf("lista", "liste", "list", "lists")
private val LIST_WORDS = LIST_NAMES + setOf("aggiungi", "aggiungere", "aggiungimi", "add")
private const val TAIL_WORDS = 4

private fun plain(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

private fun nameWords(text: String): String = plain(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * Suggestions for what is being [typed] in the chat: the name of a thing of the home, a room or a
 * list that the last words begin (or of which they begin a word), chosen by the verb already typed;
 * after a verb and a space, the things it can act on; while the first word is typed, the verbs and
 * the voice phrases.
 */
fun completeCommand(typed: String, words: CompletionWords, max: Int = 6): List<Completion> {
    if (typed.isBlank()) return emptyList()
    data class Token(val start: Int, val word: String)
    val tokens = Regex("[\\p{L}\\p{N}]+").findAll(typed).map { Token(it.range.first, nameWords(it.value)) }.toList()
    if (tokens.isEmpty()) return emptyList()
    val open = typed.last().isLetterOrDigit()
    val said = tokens.map { it.word }
    val verbAt = said.indexOfFirst { it in ON_WORDS || it in OFF_WORDS || it in RUN_WORDS || it in LIST_WORDS }
    val verb = said.getOrNull(verbAt)
    val toList = said.any { it in LIST_WORDS }
    // A list is named only after "lista": what comes before it is what to add.
    val listAt = said.indexOfLast { it in LIST_NAMES }
    val pool = when {
        toList -> words.lists
        verb in ON_WORDS -> words.power + words.run + words.rooms
        verb in OFF_WORDS -> words.power + words.rooms
        verb in RUN_WORDS -> words.run
        else -> words.power + words.run + words.rooms + words.lists
    }.filter { it.isNotBlank() }.distinctBy { nameWords(it) }

    val found = mutableListOf<Pair<Int, Completion>>()
    val whole = said.joinToString(" ")
    if (open && verbAt < 0) {
        for (phrase in words.phrases) {
            val p = nameWords(phrase)
            if (p.startsWith(whole) && p != whole) found += 100 to Completion(phrase, phrase)
        }
    }
    if (open && tokens.size == 1) {
        for (v in words.verbs) {
            if (nameWords(v).startsWith(said[0]) && nameWords(v) != said[0]) found += 90 to Completion(v, "$v ")
        }
    }
    if (open && (!toList || listAt >= 0)) {
        // The longest run of last words that begins a name, or a word inside it, wins.
        val from = maxOf(if (toList) listAt + 1 else verbAt + 1, tokens.size - TAIL_WORDS)
        for (name in pool) {
            val n = nameWords(name)
            var best: Pair<Int, Int>? = null
            for (first in from until tokens.size) {
                val tail = said.subList(first, tokens.size).joinToString(" ")
                if (n == tail) { best = null; break }
                val score = when {
                    n.startsWith(tail) -> 3 * (tokens.size - first) + 2
                    " $n".contains(" $tail") -> 3 * (tokens.size - first) + 1
                    else -> continue
                }
                if (best == null || score > best.first) best = score to tokens[first].start
            }
            best?.let { (score, start) -> found += score to Completion(name, typed.substring(0, start) + name + " ") }
        }
    } else if (!open && if (toList) said.lastOrNull { it !in FILLER_WORDS } in LIST_NAMES
        else verb != null && said.drop(verbAt + 1).all { it in FILLER_WORDS || it in ALL_WORDS }
    ) {
        // "Accendi " with nothing after it yet: what it can act on.
        for (name in pool) found += 0 to Completion(name, typed + name + " ")
    }
    return found.sortedByDescending { it.first }.map { it.second }
        .filter { it.text.trim() != typed.trim() }
        .distinctBy { it.label.lowercase() }
        .take(max)
}
