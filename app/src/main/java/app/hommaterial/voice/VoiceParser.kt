package app.hommaterial.voice

import app.hommaterial.data.Device
import app.hommaterial.music.Track
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
internal val ON_WORDS = setOf("accendi", "accendere", "accendimi", "accenda", "attiva", "attivare", "on")
internal val OFF_WORDS = setOf(
    "spegni", "spegnere", "spegnimi", "spenga", "spengi", "disattiva", "disattivare", "off",
)
internal val ALL_WORDS = setOf("tutto", "tutta", "tutti", "tutte", "all", "everything", "every")
internal val FILLER_WORDS = setOf(
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

internal val RUN_WORDS = setOf("esegui", "avvia", "lancia", "fai", "run", "start", "launch")

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

/** What a sentence asks of the music of the phone. */
sealed interface MusicCommand {
    /** Plays [tracks], in their order or mixed; [name] is what was asked for, null for all the music. */
    data class Play(val tracks: List<Track>, val shuffle: Boolean, val name: String?) : MusicCommand

    data object Pause : MusicCommand

    data object Resume : MusicCommand

    data object Next : MusicCommand

    data object Previous : MusicCommand
}

// "Riproduci" is about music by itself; "metti" only with a word such as "musica" ("metti la musica di…").
private val PLAY_WORDS = setOf(
    "riproduci", "riprodurre", "riproducimi", "suona", "suonami", "ascolta", "ascoltare", "play",
)
private val SOFT_PLAY_WORDS = setOf("metti", "mettimi", "mettere", "fammi", "fai", "sentire", "partire", "put")
private val MUSIC_WORDS = setOf(
    "musica", "canzone", "canzoni", "brano", "brani", "pezzo", "pezzi", "album", "artista", "cantante",
    "libreria", "music", "song", "songs", "track", "tracks", "library", "artist",
)
private val SHUFFLE_WORDS = setOf(
    "casuale", "casuali", "casualmente", "caso", "random", "shuffle", "mescola", "mescolata", "mescolato",
    "mescolando", "aleatoria", "aleatorio", "randomly", "shuffled",
)
private val SHUFFLE_FILLER = setOf("modo", "ordine", "maniera", "mode", "order", "po", "mia", "mio", "miei", "mie", "by", "from", "da", "dal", "dalla", "dai")
private val PAUSE_WORDS = setOf("pausa", "pause", "ferma", "fermare", "stop", "interrompi")
private val RESUME_WORDS = setOf("riprendi", "riprendere", "continua", "resume")
private val NEXT_WORDS = setOf("successiva", "successivo", "prossima", "prossimo", "avanti", "next", "skip", "salta")
private val PREVIOUS_WORDS = setOf("precedente", "indietro", "previous", "back")

/**
 * Looks in what was [heard] for music to play from [tracks]: "riproduci tutta la musica in modo casuale",
 * "riproduci la musica di Claudio Baglioni", "metti l'album…". Every word named must be found in the
 * title, artist or album of a track, and all the tracks that have them are played. Pausing, going on
 * and moving to the next or previous track are understood only while there is music [queued].
 */
fun parseMusic(heard: List<String>, tracks: List<Track>, queued: Boolean): MusicCommand? {
    val fields = tracks.map { words("${it.title} ${it.artist} ${it.album}") }
    for (sentence in heard) {
        val said = words(sentence)
        if (said.isEmpty()) continue
        val music = said.any { it in MUSIC_WORDS }
        val rest = said.filter {
            it !in FILLER_WORDS && it !in MUSIC_WORDS && it !in ALL_WORDS && it !in SHUFFLE_WORDS && it !in SHUFFLE_FILLER
        }
        if (queued) {
            // A word that moves the music, and nothing else of meaning: "metti in pausa", "canzone successiva".
            fun only(set: Set<String>) = rest.any { it in set } && rest.all { it in set || it in PLAY_WORDS || it in SOFT_PLAY_WORDS }
            when {
                only(PAUSE_WORDS) -> return MusicCommand.Pause
                only(NEXT_WORDS) -> return MusicCommand.Next
                only(PREVIOUS_WORDS) -> return MusicCommand.Previous
                only(RESUME_WORDS) -> return MusicCommand.Resume
            }
        }
        val verb = said.firstOrNull { it in PLAY_WORDS } ?: said.firstOrNull { it in SOFT_PLAY_WORDS }?.takeIf { music } ?: continue
        val shuffle = said.any { it in SHUFFLE_WORDS }
        val named = rest.filter { it != verb && it !in PLAY_WORDS && it !in SOFT_PLAY_WORDS }
        if (named.isEmpty()) {
            if (music || shuffle) return MusicCommand.Play(tracks, shuffle, null).takeIf { tracks.isNotEmpty() }
            if (queued) return MusicCommand.Resume
            continue
        }
        val found = tracks.filterIndexed { i, _ -> named.all { w -> fields[i].any { alike(it, w) } } }
        if (found.isEmpty()) continue
        // One song goes by its title; more by the artist or album they share.
        val name = (if (found.size == 1) listOf(Track::title) else listOf(Track::artist, Track::album, Track::title))
            .firstNotNullOfOrNull { field -> found.map(field).distinct().singleOrNull()?.takeIf { it.isNotBlank() } }
        return MusicCommand.Play(found, shuffle, name ?: named.joinToString(" "))
    }
    return null
}

/** A timer or an alarm for the clock app of the phone. */
sealed interface ClockCommand {
    data class Timer(val seconds: Int) : ClockCommand

    data class Alarm(val hour: Int, val minute: Int) : ClockCommand

    /** Several alarms at once, as hour to minute, in the order they ring. */
    data class Alarms(val times: List<Pair<Int, Int>>) : ClockCommand
}

private val TIMER_WORDS = setOf("timer", "countdown")
private val ALARM_WORDS = setOf("sveglia", "sveglie", "svegliami", "svegliarmi", "alarm", "alarms", "wake")
private val EVERY_WORDS = setOf("ogni", "every")
private val FROM_WORDS = setOf("dalle", "dall", "da", "from")
private val UNTIL_WORDS = setOf("alle", "all", "a", "to", "until")
// More than this is surely a misunderstanding, and too many alarms to set in the clock app one by one.
private const val MAX_ALARMS = 30
private val NUMBER_WORDS = mapOf(
    "un" to 1, "uno" to 1, "una" to 1, "a" to 1, "an" to 1, "one" to 1, "due" to 2, "two" to 2, "tre" to 3, "three" to 3,
    "quattro" to 4, "four" to 4, "cinque" to 5, "five" to 5, "sei" to 6, "six" to 6, "sette" to 7, "seven" to 7,
    "otto" to 8, "eight" to 8, "nove" to 9, "nine" to 9, "dieci" to 10, "ten" to 10, "undici" to 11, "eleven" to 11,
    "dodici" to 12, "twelve" to 12, "quindici" to 15, "fifteen" to 15, "venti" to 20, "twenty" to 20,
    "trenta" to 30, "thirty" to 30, "quaranta" to 40, "forty" to 40, "cinquanta" to 50, "fifty" to 50,
)
private val HOUR_UNITS = setOf("ora", "ore", "hour", "hours", "h")
private val MINUTE_UNITS = setOf("minuto", "minuti", "minute", "minutes", "min")
private val SECOND_UNITS = setOf("secondo", "secondi", "second", "seconds", "sec", "s")
private val EVENING_WORDS = setOf("sera", "pomeriggio", "pm", "evening", "afternoon")

private fun number(word: String): Int? = word.toIntOrNull() ?: NUMBER_WORDS[word]

/**
 * "10 minuti", "un'ora e mezza", "1 ora e 20", "mezz'ora", "90 secondi": the seconds said in [words],
 * null when none. A number with no unit after another one is of the next smaller unit.
 */
private fun duration(words: List<String>): Int? {
    var total = 0
    var found = false
    var last = 0 // the unit of the last amount, in seconds
    var i = 0
    while (i < words.size) {
        val w = words[i]
        val next = words.getOrNull(i + 1)
        when {
            (w == "mezz" || w == "mezzo" || w == "mezza") && next in HOUR_UNITS -> { total += 1800; found = true; last = 3600; i++ }
            (w == "mezza" || w == "mezzo" || w == "half") && last > 0 -> { total += last / 2; last = 0 }
            w == "quarto" && next == "d" -> { total += 900; found = true; last = 0; i += 2 }
            number(w) != null -> {
                val n = number(w)!!
                val unit = when (next) {
                    in HOUR_UNITS -> 3600
                    in MINUTE_UNITS -> 60
                    in SECOND_UNITS -> 1
                    else -> if (last == 3600) 60 else if (last == 60 && words.getOrNull(i - 1) == "e") 1 else 0
                }
                if (unit > 0) {
                    total += n * unit
                    found = true
                    last = unit
                    if (next in HOUR_UNITS || next in MINUTE_UNITS || next in SECOND_UNITS) i++
                }
            }
        }
        i++
    }
    return total.takeIf { found && it > 0 }
}

/** "alle 7", "alle 7:30", "alle 7 e mezza", "alle sette di sera", "at 6 30 pm": hour and minute, null when none. */
private fun clockTime(words: List<String>): Pair<Int, Int>? {
    for ((at, w) in words.withIndex()) {
        if (w != "alle" && w != "all" && w != "at" && w != "per") continue
        clockTime(words, words.drop(at + 1).filter { it != "le" && it != "ore" })?.let { return it }
    }
    return null
}

private fun clockTime(words: List<String>, rest: List<String>): Pair<Int, Int>? {
    var hour = rest.firstOrNull()?.let(::number) ?: return null
    val after = rest.drop(1).dropWhile { it == "e" }
    val minute = when {
        after.firstOrNull() == "mezza" || after.firstOrNull() == "mezzo" -> 30
        after.firstOrNull() == "un" && after.getOrNull(1) == "quarto" -> 15
        else -> after.firstOrNull()?.let(::number)?.takeIf { it in 0..59 } ?: 0
    }
    if (hour < 12 && words.any { it in EVENING_WORDS }) hour += 12
    if (hour !in 0..23) return null
    return hour to minute
}

/** "ogni 10 minuti dalle 6:30 alle 7:30": the alarms from one time to the other, both included; null when not said so. */
private fun alarmSeries(words: List<String>): List<Pair<Int, Int>>? {
    val every = words.indexOfFirst { it in EVERY_WORDS }.takeIf { it >= 0 } ?: return null
    val from = words.indexOfFirst { it in FROM_WORDS }.takeIf { it >= 0 } ?: return null
    // Only the words of the step: the hours after it would add up to it.
    val step = duration(words.drop(every + 1).takeWhile { it !in FROM_WORDS })?.div(60)?.takeIf { it > 0 } ?: return null
    val until = (from + 2 until words.size).firstOrNull { words[it] in UNTIL_WORDS } ?: return null
    val clean = { list: List<String> -> list.filter { it != "le" && it != "ore" } }
    val (h1, m1) = clockTime(words, clean(words.subList(from + 1, until))) ?: return null
    val (h2, m2) = clockTime(words, clean(words.drop(until + 1))) ?: return null
    val start = h1 * 60 + m1
    // Past midnight the end is on the next day: "dalle 23 all'1".
    val end = (h2 * 60 + m2).let { if (it < start) it + 24 * 60 else it }
    return (start..end step step).take(MAX_ALARMS).map { (it / 60 % 24) to (it % 60) }
}

/**
 * Looks in what was [heard] for a timer ("imposta un timer di 10 minuti", "timer 5 minuti") or an
 * alarm ("sveglia alle 7", "svegliami alle 6 e mezza"; "svegliami tra 20 minuti" is a timer), or a
 * series of alarms ("imposta delle sveglie ogni 10 minuti dalle 6:30 alle 7:30"). A
 * sentence that switches something on or off is left to the devices: "spegni la sveglia" is not one.
 */
fun parseClock(heard: List<String>): ClockCommand? {
    for (sentence in heard) {
        val said = words(sentence)
        if (said.any { it in ON_WORDS || it in OFF_WORDS }) continue
        val timer = said.any { it in TIMER_WORDS }
        val alarm = said.any { it in ALARM_WORDS }
        if (!timer && !alarm) continue
        if (alarm) alarmSeries(said)?.let { return ClockCommand.Alarms(it) }
        if (alarm) clockTime(said)?.let { (h, m) -> return ClockCommand.Alarm(h, m) }
        duration(said)?.let { return ClockCommand.Timer(it.coerceAtMost(24 * 3600)) }
    }
    return null
}
