package app.hommaterial.voice

import app.hommaterial.data.Device
import java.text.Normalizer

/** What a spoken sentence asks for: [devices] switched [on] or off, all those of [room] when it is set. */
data class VoiceCommand(val on: Boolean, val devices: List<Device>, val room: String? = null)

sealed interface VoiceResult {
    /** Understood without doubt. */
    data class Run(val command: VoiceCommand) : VoiceResult

    /** Understood in part: the user picks among [options], best first. */
    data class Ask(val heard: String, val options: List<VoiceCommand>) : VoiceResult

    data class Unknown(val heard: String) : VoiceResult
}

// Italian and English are both understood, whatever the language of the phone.
private val ON_WORDS = setOf("accendi", "accendere", "accendimi", "accenda", "attiva", "attivare", "on")
private val OFF_WORDS = setOf(
    "spegni", "spegnere", "spegnimi", "spenga", "spengi", "disattiva", "disattivare", "off",
)
private val ALL_WORDS = setOf("tutto", "tutta", "tutti", "tutte", "all", "everything", "every")
private val FILLER_WORDS = setOf(
    "il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una",
    "di", "del", "dello", "della", "dei", "degli", "delle", "in", "nel", "nello", "nella", "a", "al", "alla",
    "per", "favore", "piacere", "grazie", "ora", "adesso", "subito", "e",
    "turn", "switch", "the", "an", "of", "at", "to", "my", "please", "now", "thanks",
)

// Below this nothing is offered; from here up to a perfect match the user is asked.
private const val MIN_SCORE = 0.4
private const val MAX_OPTIONS = 3

private fun words(text: String): List<String> =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }

/** Same word, give or take the ending: "luce" and "luci", "presa" and "prese". */
private fun alike(a: String, b: String): Boolean {
    if (a == b) return true
    if (a.length < 4 || b.length < 4 || a.length != b.length) return false
    return a.dropLast(1) == b.dropLast(1)
}

/**
 * How well the [said] words name a device: 1 when they are exactly its name, less when words are
 * missing or extra. The words of its room may be said too and do not count against it.
 */
private fun score(said: List<String>, name: List<String>, room: List<String>): Double {
    if (said.isEmpty() || name.isEmpty()) return 0.0
    val named = name.count { word -> said.any { alike(it, word) } }
    val explained = said.count { word -> (name + room).any { alike(it, word) } }
    return named.toDouble() / name.size * explained / said.size
}

/** What a sentence asks of what Alexa does not know: a custom device to run, a remote or group to switch. */
sealed interface ExtraCommand {
    data class Run(val macroId: String) : ExtraCommand

    data class Remote(val remoteId: String, val on: Boolean) : ExtraCommand

    data class Group(val groupId: String, val on: Boolean) : ExtraCommand
}

private val RUN_WORDS = setOf("esegui", "avvia", "lancia", "fai", "run", "start", "launch")

/**
 * Looks in what was [heard] for a custom device to run, or a remote or group to switch on or off. All are
 * given as id and name, and the name must be said whole: these are tried only when no device of
 * Alexa was meant, and a loose match would steal sentences from them.
 */
fun parseExtras(
    heard: List<String>,
    macros: List<Pair<String, String>>,
    remotes: List<Pair<String, String>>,
    groups: List<Pair<String, String>> = emptyList(),
): ExtraCommand? {
    for (sentence in heard) {
        val said = words(sentence)
        val verb = said.firstOrNull { it in RUN_WORDS || it in ON_WORDS || it in OFF_WORDS } ?: continue
        val rest = said.filter { it != verb && it !in FILLER_WORDS }
        fun named(name: String): Boolean {
            val wanted = words(name).filter { it !in FILLER_WORDS }
            return wanted.isNotEmpty() && wanted.size == rest.size && wanted.all { w -> rest.any { alike(it, w) } }
        }
        // "Accendi" starts a custom device as well as "esegui" does; "spegni" never does.
        if (verb !in OFF_WORDS) macros.firstOrNull { named(it.second) }?.let { return ExtraCommand.Run(it.first) }
        if (verb !in RUN_WORDS) {
            remotes.firstOrNull { named(it.second) }?.let { return ExtraCommand.Remote(it.first, verb in ON_WORDS) }
            groups.firstOrNull { named(it.second) }?.let { return ExtraCommand.Group(it.first, verb in ON_WORDS) }
        }
    }
    return null
}

/**
 * Looks in what was [heard] for one of the voice phrases of the custom devices, given as the id of
 * the device and its phrases. A phrase must be said whole and nothing else, give or take the small
 * words and "Alexa": "buonanotte" is not "buonanotte a tutti".
 */
fun parsePhrase(heard: List<String>, phrases: List<Pair<String, List<String>>>): String? {
    fun meaningful(text: String) = words(text).filter { it !in FILLER_WORDS && it != "alexa" }
    for (sentence in heard) {
        val said = meaningful(sentence)
        if (said.isEmpty()) continue
        for ((id, list) in phrases) {
            if (list.any { phrase ->
                    val wanted = meaningful(phrase)
                    wanted.size == said.size && wanted.zip(said).all { (w, it) -> alike(it, w) }
                }
            ) {
                return id
            }
        }
    }
    return null
}

/**
 * Turns what the speech recognizer [heard], its guesses best first, into a command for one of the
 * [devices] or for a whole room. Only switching on and off is understood.
 */
fun parseVoice(heard: List<String>, devices: List<Device>): VoiceResult {
    val switchable = devices.filter { it.hasPower }
    val readings = heard.map { interpret(it, switchable) }
    // An earlier guess wins over a later one that is equally convincing.
    val best = readings.maxByOrNull { it.first } ?: return VoiceResult.Unknown("")
    return best.second
}

private fun interpret(sentence: String, devices: List<Device>): Pair<Double, VoiceResult> {
    val all = words(sentence)
    val on = when {
        all.any { it in ON_WORDS } -> true
        all.any { it in OFF_WORDS } -> false
        else -> return 0.0 to VoiceResult.Unknown(sentence)
    }
    val wholeRoom = all.any { it in ALL_WORDS }
    val said = all.filter { it !in ON_WORDS && it !in OFF_WORDS && it !in ALL_WORDS && it !in FILLER_WORDS }
    if (said.isEmpty()) return 0.0 to VoiceResult.Unknown(sentence)

    val rooms = devices.filter { it.room != null }.groupBy { it.room!! }
        .map { (room, members) -> VoiceCommand(on, members, room) to score(said, words(room), emptyList()) }
        .filter { it.second >= MIN_SCORE }
    val singles = devices
        .map { VoiceCommand(on, listOf(it)) to score(said, words(it.name), words(it.room.orEmpty())) }
        .filter { it.second >= MIN_SCORE }

    // "Tutto in salotto" means the room; "salotto" alone means it only if no device goes by that name.
    val ranked = (if (wholeRoom) rooms + singles else singles + rooms).sortedByDescending { it.second }
    val top = ranked.firstOrNull() ?: return 0.0 to VoiceResult.Unknown(sentence)
    val certain = top.second == 1.0 && ranked.count { it.second == 1.0 } == 1 && top.first.room == null
    val result = if (certain) {
        VoiceResult.Run(top.first)
    } else {
        // A whole room is always confirmed: it is a lot to switch on a misheard word.
        VoiceResult.Ask(sentence, ranked.take(MAX_OPTIONS).map { it.first })
    }
    return top.second to result
}

private val OPEN_WORDS = setOf("apri", "aprire", "aprimi", "apra", "open")
private val APP_WORDS = setOf("app", "applicazione", "applicazioni", "application")

/**
 * Looks in what was [heard] for "apri" and the name of one of the [apps] of the phone, given as id and
 * name ("apri telefono", "open the camera app"). The name must be said whole, words may be run
 * together ("whats app" is "WhatsApp"); when no name is exact, the only app whose name holds all the
 * words said is taken ("apri maps" is "Google Maps"). Null when no app is meant without doubt.
 */
fun parseOpenApp(heard: List<String>, apps: List<Pair<String, String>>): String? {
    val names = apps.map { (id, name) -> Triple(id, words(name), words(name).joinToString("")) }
    for (sentence in heard) {
        val said = words(sentence)
        val verb = said.firstOrNull { it in OPEN_WORDS } ?: continue
        val whole = said.filter { it != verb && it !in FILLER_WORDS }
        val rest = whole.filter { it !in APP_WORDS }
        if (rest.isEmpty()) continue
        // "App" may be part of the name ("whats app") or just a word around it ("l'app fotocamera").
        for (joined in listOf(whole, rest).map { it.joinToString("") }.distinct()) {
            names.filter { it.third == joined }.let { if (it.size == 1) return it.single().first }
        }
        val holding = names.filter { (_, name, _) -> rest.all { w -> name.any { alike(it, w) } } }
        if (holding.size == 1) return holding.single().first
    }
    return null
}
