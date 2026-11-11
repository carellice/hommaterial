package app.hommaterial.data

import android.content.Context
import androidx.annotation.StringRes
import app.hommaterial.R
import app.hommaterial.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** The models of Workers AI the assistant can use, best first; all of them answer in JSON when asked. */
/** The model that reads pictures, whatever model is chosen for the words (as VISION_MODEL in server/src/assistant.ts). */
const val VISION_MODEL = "@cf/meta/llama-4-scout-17b-16e-instruct"

/**
 * The instructions for a picture the user sent: what it shows, and what can be done with it, an event
 * for the calendar and words to look it up on the web. Kept in the same shape as parseAgent reads.
 */
fun imagePrompt(language: String, now: String): String = """The user sent a picture: a flyer, a poster, a ticket, a screenshot, a photo. Answer in $language. Now it is $now.
Return JSON {"reply": text, "actions": [...], "search": text}.
"reply": in two or three short plain sentences, what the picture shows and what it is about, answering the words of the user when there are some;
then, when there is something to do, ask which the user wants, one or both: put the event in the calendar, look for more about it on the internet.
"actions": when the picture announces something with a date (an event, a concert, a meeting, an appointment), one item
{"do":"event","title":short title with the place,"start":"YYYY-MM-DDTHH:MM","minutes":duration} or {"do":"event","title":text,"date":"YYYY-MM-DD"}
when no time is given; a date without a year is the next one to come. Otherwise [].
"search": a few words to look it up on the web (name, place, year), or "" when there is nothing worth looking up.
The reply is read aloud: no lists, no markdown."""

val ASSISTANT_MODELS = listOf(
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "@cf/meta/llama-3.1-8b-instruct",
)

/** What the owner explained about one thing of the home, [target] being its key in the app. */
data class AssistantNote(val target: String, val question: String, val answer: String) {
    fun toJson(): JSONObject = JSONObject().put("target", target).put("question", question).put("answer", answer)

    companion object {
        fun fromJson(o: JSONObject) = AssistantNote(o.getString("target"), o.optString("question"), o.getString("answer"))
    }
}

class AssistantException(@StringRes val text: Int, private vararg val args: Any) : Exception() {
    override val message: String get() = str(text, *args)
}

/**
 * The account of Cloudflare the assistant runs on, and what it was taught about the home. The
 * keys come from the Cloudflare dashboard and stay on the phone, like those of SwitchBot.
 */
class AssistantStore(context: Context) {
    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    val configured: Boolean get() = if (ServerSync.active) ServerSync.assistantLinked else prefs.contains("token")

    fun accountId(): String = if (ServerSync.active) ServerSync.assistantAccount else prefs.getString("accountId", "").orEmpty()

    fun token(): String = prefs.getString("token", "").orEmpty()

    fun model(): String = (if (ServerSync.active) ServerSync.assistantModel else prefs.getString("model", null))
        ?.takeIf { it in ASSISTANT_MODELS } ?: ASSISTANT_MODELS.first()

    fun connect(accountId: String, token: String) {
        if (ServerSync.active) {
            ServerSync.send("POST", "/api/assistant/connect", JSONObject().put("accountId", accountId).put("token", token))
        } else {
            prefs.edit().putString("accountId", accountId).putString("token", token).apply()
        }
    }

    /** Forgets the account; what was taught stays, for when it is linked again. */
    fun disconnect() {
        if (ServerSync.active) ServerSync.send("POST", "/api/assistant/disconnect") else prefs.edit().remove("accountId").remove("token").apply()
    }

    fun putModel(model: String) {
        if (ServerSync.active) ServerSync.send("POST", "/api/assistant/model", JSONObject().put("model", model))
        else prefs.edit().putString("model", model).apply()
    }

    fun notes(): List<AssistantNote> = runCatching {
        JSONArray(prefs.getString("notes", "[]")).mapObjects(AssistantNote::fromJson)
    }.getOrDefault(emptyList())

    fun putNotes(notes: List<AssistantNote>) =
        prefs.edit().putString("notes", JSONArray(notes.map { it.toJson() }).toString()).apply()

    /** What the owner wrote about the home as a whole: who sleeps where, how things are called. */
    fun house(): String = prefs.getString("house", "").orEmpty()

    fun putHouse(text: String) = prefs.edit().putString("house", text).apply()

    /** The keys of what the assistant has already asked about, so that it asks only about what is new. */
    fun taught(): Set<String> = prefs.getStringSet("taught", emptySet())!!.toSet()

    fun putTaught(keys: Set<String>) = prefs.edit().putStringSet("taught", keys).apply()

    /** What travels in a backup: the notes and the house, without the keys of the account. */
    fun toJson(): JSONObject = JSONObject()
        .put("notes", JSONArray(notes().map { it.toJson() }))
        .put("house", house())
        .put("taught", JSONArray(taught()))

    fun restore(o: JSONObject) {
        putNotes(o.optJSONArray("notes")?.mapObjects(AssistantNote::fromJson).orEmpty())
        putHouse(o.optString("house"))
        putTaught(o.optJSONArray("taught")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty().toSet())
    }

    /** The keys of the account, for the backup; null while not linked. */
    fun keys(): Pair<String, String>? = token().takeIf { it.isNotEmpty() }?.let { accountId() to it }
}

/** A key of a remote as the assistant sees it: what is sent, whether the user added it, how it is called. */
data class AssistantKey(val command: String, val custom: Boolean, val label: String)

/** One thing of the home that the assistant can act on, under a short [id] such as "d3". */
sealed interface AssistantTarget {
    val id: String
    /** Its key in the app, which the notes are kept under. */
    val key: String
    val name: String

    data class Dev(override val id: String, val device: Device, val room: String?, val state: DeviceState?) : AssistantTarget {
        override val key get() = device.applianceId
        override val name get() = device.name
    }

    data class Grp(override val id: String, val group: Group, val members: List<String>) : AssistantTarget {
        override val key get() = "group:${group.id}"
        override val name get() = group.name
    }

    data class Mac(override val id: String, val macro: Macro, override val key: String, val room: String?) : AssistantTarget {
        override val name get() = macro.name
    }

    data class Rem(
        override val id: String,
        val remote: Remote,
        override val key: String,
        val room: String?,
        val keys: List<AssistantKey>,
    ) : AssistantTarget {
        override val name get() = remote.name
        val ac: Boolean get() = remote.kind == RemoteKind.AIR_CONDITIONER
    }
}

/**
 * Everything of the home, written for the model. The ids are short to spare tokens, and the app
 * alone knows what they stand for: the model can name nothing that is not in the list.
 */
class AssistantCatalog(val targets: List<AssistantTarget>, private val notes: List<AssistantNote>, private val taught: Set<String>) {
    private val byId = targets.associateBy { it.id }

    operator fun get(id: String): AssistantTarget? = byId[id.trim().lowercase()]

    /** Whether there is anything the assistant was never asked about. */
    val anythingNew: Boolean get() = targets.any { it.key !in taught }

    fun describe(withState: Boolean): String = buildString {
        for (target in targets) {
            append(target.id).append(": ")
            when (target) {
                is AssistantTarget.Dev -> {
                    val d = target.device
                    append("device \"").append(d.name).append('"')
                    target.room?.let { append(" in \"").append(it).append('"') }
                    append(", ").append(d.category.lowercase())
                    val can = buildList {
                        if (d.hasPower) add("on/off")
                        if (d.hasBrightness) add("brightness")
                        if (d.hasColor) add("color")
                        if (d.hasColorTemperature) add("white")
                    }
                    if (can.isNotEmpty()) append("; can ").append(can.joinToString(", "))
                    if (d.isSensor) append("; sensor")
                    val st = target.state
                    if (withState && st != null) {
                        val now = buildList {
                            if (!st.reachable) add("unreachable")
                            st.power?.let { add(if (it) "on" else "off") }
                            if (st.power == true) st.brightness?.let { add("$it%") }
                            st.temperature?.takeIf { !it.isNaN() }?.let { add("%.1f°C".format(it)) }
                            st.humidity?.let { add("humidity $it%") }
                        }
                        if (now.isNotEmpty()) append("; now ").append(now.joinToString(" "))
                    }
                }
                is AssistantTarget.Grp ->
                    append("group \"").append(target.name).append("\" of ").append(target.members.joinToString(", "))
                is AssistantTarget.Mac -> {
                    append("custom device \"").append(target.name).append('"')
                    target.room?.let { append(" in \"").append(it).append('"') }
                    append(", runs a series of actions")
                }
                is AssistantTarget.Rem -> {
                    append("remote \"").append(target.name).append('"')
                    target.room?.let { append(" in \"").append(it).append('"') }
                    append(", ").append(if (target.remote.lg) "LG TV" else target.remote.type)
                    if (target.ac) append("; use \"ac\"")
                    if (target.keys.isNotEmpty()) {
                        append("; keys: ")
                        append(target.keys.joinToString(", ") { if (it.label == it.command) it.command else "${it.command}=${it.label}" })
                    }
                }
            }
            if (target.key !in taught) append(" [NEW]")
            val said = notes.filter { it.target == target.key }
            if (said.isNotEmpty()) {
                append(" — owner's notes: ")
                append(said.joinToString(" ") { if (it.question.isBlank()) it.answer else "${it.question} ${it.answer}" })
            }
            append('\n')
        }
    }

    /**
     * What a spoken command may name, one per line: the things that can be switched or run by voice,
     * with their room and what the owner said of them, then the rooms.
     */
    fun names(): String = buildString {
        val rooms = LinkedHashSet<String>()
        for (target in targets) {
            val (kind, room) = when (target) {
                is AssistantTarget.Dev -> (if (target.device.hasPower) "device" else continue) to target.room
                is AssistantTarget.Mac -> "custom device" to target.room
                is AssistantTarget.Rem -> "remote" to target.room
                is AssistantTarget.Grp -> continue
            }
            room?.let(rooms::add)
            append('"').append(target.name).append("\" (").append(kind)
            room?.let { append(", in \"").append(it).append('"') }
            append(')')
            val said = notes.filter { it.target == target.key }
            if (said.isNotEmpty()) {
                append(" — owner's notes: ")
                append(said.joinToString(" ") { if (it.question.isBlank()) it.answer else "${it.question} ${it.answer}" })
            }
            append('\n')
        }
        if (rooms.isNotEmpty()) append("Rooms: ").append(rooms.joinToString(", ") { "\"$it\"" }).append('\n')
    }

    companion object {
        /**
         * Gives everything an id: devices "d", groups "g", custom devices "m", remotes "r". The
         * lists come already without what the user hid.
         */
        fun build(
            devices: List<Device>,
            states: Map<String, DeviceState>,
            groups: List<Group>,
            macros: List<Pair<Macro, String?>>,
            macroKey: (Macro) -> String,
            remotes: List<Triple<Remote, String?, List<AssistantKey>>>,
            remoteKey: (Remote) -> String,
            notes: List<AssistantNote>,
            taught: Set<String>,
        ): AssistantCatalog {
            val targets = mutableListOf<AssistantTarget>()
            val deviceIds = HashMap<String, String>()
            devices.forEachIndexed { i, d ->
                val id = "d${i + 1}"
                deviceIds[d.applianceId] = id
                targets += AssistantTarget.Dev(id, d, d.room, states[d.applianceId])
            }
            groups.forEachIndexed { i, g ->
                val members = g.devices.mapNotNull { deviceIds[it] }
                if (members.isNotEmpty()) targets += AssistantTarget.Grp("g${i + 1}", g, members)
            }
            macros.forEachIndexed { i, (m, room) -> targets += AssistantTarget.Mac("m${i + 1}", m, macroKey(m), room) }
            remotes.forEachIndexed { i, (r, room, keys) ->
                targets += AssistantTarget.Rem("r${i + 1}", r, remoteKey(r), room, keys)
            }
            return AssistantCatalog(targets, notes, taught)
        }
    }
}

/** A question of the assistant about one thing of the home, for the owner to answer in words. */
data class AssistantQuestion(val target: AssistantTarget, val question: String)

/** Reads the questions of the model, keeping one per thing and only about things that exist. */
fun parseQuestions(answer: JSONObject, catalog: AssistantCatalog, max: Int = 12): List<AssistantQuestion> {
    val list = answer.optJSONArray("questions") ?: return emptyList()
    val questions = mutableListOf<AssistantQuestion>()
    for (i in 0 until list.length()) {
        val o = list.optJSONObject(i) ?: continue
        // A key of a remote may come as "r2/F1": the question is about the remote all the same.
        val target = catalog[o.optString("target").substringBefore('/')] ?: continue
        val question = o.optString("question").trim()
        if (question.isEmpty() || questions.size >= max) continue
        questions += AssistantQuestion(target, question)
    }
    return questions
}

private const val SCHEMA_TEXT = """
{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
"""

private const val SCHEMA_QUESTIONS = """
{"type":"object","properties":{
 "questions":{"type":"array","items":{"type":"object","properties":{
  "target":{"type":"string"},"question":{"type":"string"}},"required":["target","question"]}}},
 "required":["questions"]}
"""

/** The instructions for the first set-up, when the assistant asks about what it does not understand. */
fun questionsPrompt(catalog: AssistantCatalog, house: String, language: String): String = """
You are setting up the assistant of a smart home app, which will later act on these things when asked in words.
Find what you could not use with confidence from its name alone: cryptic or generic names ("Presa 2", "Plug A"),
keys of a remote whose purpose is unclear ("F1", "Tasto 3"), custom devices whose name does not say what they do,
remotes whose appliance is not obvious, rooms named after people or nicknames.
Ask at most 12 short, friendly questions in $language, one per thing, with "target" its id (for a key: "r2/F1").
Ask about things marked [NEW] first. Do not ask about what is clear ("Luce cucina", "TV soggiorno")
or what the owner's notes already explain. If everything is clear, return no questions.
${if (house.isNotBlank()) "What the owner says about the home:\n${house.trim()}\n" else ""}
The things of the home:
${catalog.describe(withState = false)}
""".trim()

/**
 * The instructions for a spoken command. The model only puts right the names the speech recognizer
 * got wrong: the app then reads the sentence as it always does, and acts on nothing it does not know.
 */
fun correctionPrompt(catalog: AssistantCatalog, house: String): String = """
You correct the voice commands of a smart home app. The speech recognizer wrote down what the user said,
often with the names of things wrong: "televisione flavio" for "TV Flavio", "luce cucine" for "Luce cucina".
Rewrite the command so that the thing or room it is about is called exactly by its name in the list below.
Keep the verb as said (accendi, spegni, esegui, turn on, switch off…) and the language of the user.
Keep "tutto", "tutti", "all" before a room. Add nothing, explain nothing, do not translate the names.
Several guesses of the recognizer may be given, best first: use the one that makes sense with the list.
If nothing in the list fits, give back the first guess unchanged.
Example: guesses "accendi televisione flavio" give {"text":"accendi TV Flavio"}
${if (house.isNotBlank()) "What the owner says about the home:\n${house.trim()}\n" else ""}
What the home has:
${catalog.names()}
""".trim()

private const val SCHEMA_PHRASES = """
{"type":"object","properties":{
 "short":{"type":"array","items":{"type":"string"}},
 "medium":{"type":"array","items":{"type":"string"}},
 "long":{"type":"array","items":{"type":"string"}}},
 "required":["short","medium","long"]}
"""

/** Phrases the assistant suggests to run a custom device: short, medium and longer ones. */
data class PhraseSuggestions(val short: List<String>, val medium: List<String>, val long: List<String>) {
    val isEmpty: Boolean get() = short.isEmpty() && medium.isEmpty() && long.isEmpty()
}

/** Reads the phrases of the model, without those already used and each once, a few per length. */
fun parsePhrases(answer: JSONObject, taken: Collection<String>, max: Int = 4): PhraseSuggestions {
    val seen = taken.map { it.trim().lowercase() }.toMutableSet()
    fun read(key: String): List<String> {
        val list = answer.optJSONArray(key) ?: return emptyList()
        return (0 until list.length()).map { list.optString(it).trim().trim('"', '“', '”', '.').trim() }
            .filter { it.isNotEmpty() && it.length <= 80 && seen.add(it.lowercase()) }
            .take(max)
    }
    return PhraseSuggestions(read("short"), read("medium"), read("long"))
}

/**
 * The instructions for suggesting phrases that, said to the microphone of the app, run the custom
 * device [name], which does [does] (or [otherwise] while [condition] is off).
 */
fun phrasesPrompt(
    name: String,
    does: List<String>,
    condition: String?,
    otherwise: List<String>,
    phrases: List<String>,
    taken: List<String>,
    language: String,
): String = """
You suggest voice phrases for a custom device of a smart home app: a button that runs a series of actions.
Saying one of the phrases to the microphone of the app runs it; only the whole phrase counts, so it must be
something a person would naturally say, in $language, to have exactly these actions done. No quotes, no final dot.
Give 4 "short" phrases (1 to 3 words, like "buonanotte"), 4 "medium" ones (4 to 6 words) and 4 "long" ones
(7 to 12 words, natural and specific). Keep them coherent with what the device does, varied, never generic
commands like "accendi" alone. Do not repeat the phrases it already has or those of other devices.
The custom device is called: ${name.trim().ifEmpty { "(no name yet)" }}
What it does, in order:
${bullets(does).ifEmpty { "- (nothing yet)" }}
${if (condition != null) "That is while “$condition” is on; while it is off it does:\n${bullets(otherwise)}\n" else ""}
${if (phrases.isNotEmpty()) "Its phrases already: ${phrases.joinToString("; ")}\n" else ""}
${if (taken.isNotEmpty()) "Phrases of other devices, not to be used: ${taken.joinToString("; ")}\n" else ""}
""".trim()

private fun bullets(lines: List<String>) = lines.joinToString("\n") { "- $it" }

// --- the agent: requests in words, acted on by the app (as agentPrompt in server/src/assistant.ts) ---

/**
 * One thing the assistant would do, by the key of the app: [kind] is "power", "run", "later",
 * "automation" (when [sensor] gets to [trigger] past [threshold]), "list" or "event" (at [start],
 * local "YYYY-MM-DDTHH:MM", for [minutes], or the whole day of [date]).
 */
data class AgentAction(
    val kind: String,
    val key: String = "",
    val name: String = "",
    val on: Boolean = true,
    val minutes: Int = 0,
    val sensor: String = "",
    val sensorName: String = "",
    val trigger: String = "",
    val threshold: Double = 0.0,
    val list: String = "",
    val items: List<String> = emptyList(),
    val title: String = "",
    val start: String = "",
    val date: String = "",
)

/** What the assistant answered: the words, whether it is a matter for Alexa, what it would do. */
data class AgentPlan(val reply: String, val alexa: Boolean, val actions: List<AgentAction>) {
    /** Switching or running one thing is done at once; anything more waits for a yes. */
    val needsConfirm: Boolean get() = actions.size > 1 || actions.any { it.kind in setOf("automation", "list", "event") }
}

/** Kept word for word the same as AGENT_RULES in server/src/assistant.ts. */
private const val AGENT_RULES = """You are the assistant of a smart home app. Answer in {language}. Now it is {now}.
Return JSON {"reply": text for the user, "alexa": boolean, "actions": [...]}. Actions, by the ids below:
- {"do":"power","target":id,"on":true|false}: switch a device (d...), a group (g...) or a remote (r...) on or off.
- {"do":"run","target":id}: run a custom device (m...).
- {"do":"later","target":id,"on":true|false,"minutes":n}: the same, in n minutes from now ("tra 20 minuti", "alle 23").
- {"do":"automation","sensor":id of a device,"when":"temperatureAbove"|"temperatureBelow"|"humidityAbove"|"humidityBelow"|"on"|"off","threshold":number,"target":id,"on":true|false}:
  from now on, each time that device gets there, switch or run the target. One condition and one action only:
  for anything more (a time of day, several actions) make the closest one and say in the reply what was left out.
- {"do":"list","list":exact name of a list,"items":[...]}: add items to a list; a dish asked for becomes its ingredients, one per item.
A list may be said loosely (no dashes or spaces, singular for plural, another word for the same thing): it means the closest list below.
- {"do":"event","title":text,"start":"YYYY-MM-DDTHH:MM","minutes":duration} or {"do":"event","title":text,"date":"YYYY-MM-DD"} for a whole day: add to the calendar.
  A time said loosely is still a time, never a whole day: breakfast 8:00, morning 9:00, lunch 13:00, afternoon 16:00,
  dinner ("a cena", "all'ora di cena") 20:00, evening 21:00, night 23:00. A whole day only when nothing hints at a time.
  Several events in one request ("every day this week from 9 to 18 except Friday from 10", "Monday and Thursday at 7"):
  one event action for each, with its own date and times; the reply says how many and on which days.
"Tutto tranne X" means each switchable device (of the room, if one is said) except X, one action each.
Questions about the home (what is on, temperatures, how a sensor went, the agenda, what is in a list) are answered
in the reply from the data below, with no actions. A brief of the day: greet, then what is on, the temperatures,
the events of today and tomorrow, the timers; six sentences at most. A note given below is summed up in a few sentences.
Set "alexa" true, with no actions and an empty reply, only when the request is about nothing of this app
(weather, music, news, general knowledge, Alexa routines): it is then passed to Alexa.
The reply is read aloud: short plain sentences, no lists, no markdown, never the ids. When acting, say briefly what you do.
Use only what is listed; when something asked for is not there, say so and do nothing about it."""

/** The instructions for a request in words, with what the client knows besides the home in [context]. */
fun agentPrompt(catalog: AssistantCatalog, house: String, language: String, now: String, context: String): String = buildString {
    append(AGENT_RULES.replace("{language}", language).replace("{now}", now)).append('\n')
    if (house.isNotBlank()) append("What the owner says about the home:\n").append(house.trim()).append("\n\n")
    append("The things of the home:\n").append(catalog.describe(withState = true))
    if (context.isNotBlank()) append('\n').append(context.trim())
}.trim()

/** The lists for the assistant, each with what is still to do in it; null items when they could not be read. */
fun listsContext(lists: List<Pair<String, List<String>?>>): String =
    "Lists, with what is still to do in each:\n" + lists.joinToString("\n") { (name, items) ->
        "- \"$name\": " + when {
            items == null -> "(could not be read)"
            items.isEmpty() -> "(empty)"
            else -> items.take(50).joinToString("; ") + if (items.size > 50) "; and ${items.size - 50} more" else ""
        }
    }

/**
 * The list meant by [said]: the same name, else the same once case, accents, spaces and dashes
 * are left out, else the only one whose name holds the other ("todo" for "To-do list").
 */
fun <T> closestList(lists: List<T>, said: String, name: (T) -> String): T? {
    fun key(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("""[^\p{L}\p{N}]"""), "").lowercase()
    val wanted = key(said)
    if (wanted.isEmpty()) return null
    return lists.find { name(it).trim().equals(said.trim(), ignoreCase = true) }
        ?: lists.find { key(name(it)) == wanted }
        ?: lists.filter { key(name(it)).let { n -> n.isNotEmpty() && (wanted in n || n in wanted) } }.singleOrNull()
}

/** The readings of a sensor of the last week, a line a day: what the model needs to tell how it went. */
fun historyLine(name: String, readings: List<Reading>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val format = java.time.format.DateTimeFormatter.ofPattern("MM-dd")
    fun range(values: List<Double>, unit: String) = "%.1f-%.1f%s".format(java.util.Locale.ROOT, values.min(), values.max(), unit)
    val days = readings.groupBy { java.time.Instant.ofEpochMilli(it.at).atZone(zone).format(format) }
    val parts = days.map { (day, list) ->
        val humidity = list.mapNotNull { it.humidity?.toDouble() }
        "$day ${range(list.map { it.temperature }, "°C")}" + if (humidity.isNotEmpty()) " ${range(humidity, "%")}" else ""
    }
    return "\"$name\" by day (MM-DD min-max): ${parts.joinToString("; ")}"
}

private val AUTOMATION_TRIGGERS = setOf("temperatureAbove", "temperatureBelow", "humidityAbove", "humidityBelow", "on", "off")

/** Reads what the model would do, keeping only what names things of the [catalog], each by its key in the app. */
fun parseAgent(answer: JSONObject, catalog: AssistantCatalog): AgentPlan {
    val actions = mutableListOf<AgentAction>()
    val list = answer.optJSONArray("actions") ?: JSONArray()
    for (i in 0 until list.length()) {
        val a = list.optJSONObject(i) ?: continue
        val target = catalog[a.optString("target")]
        val switchable = when (target) {
            is AssistantTarget.Dev -> target.device.hasPower
            is AssistantTarget.Grp, is AssistantTarget.Rem -> true
            else -> false
        }
        val runnable = target is AssistantTarget.Mac
        val on = a.optBoolean("on", true)
        when (a.optString("do")) {
            "power" -> if (switchable) actions += AgentAction("power", target!!.key, target.name, on)
            "run" -> if (runnable) actions += AgentAction("run", target!!.key, target.name)
            "later" -> {
                val minutes = a.optDouble("minutes", 0.0).let { if (it.isNaN()) 0 else Math.round(it).toInt() }
                if ((switchable || runnable) && minutes in 1..7 * 24 * 60) {
                    actions += AgentAction("later", target!!.key, target.name, on, minutes = minutes)
                }
            }
            "automation" -> {
                val sensor = catalog[a.optString("sensor")] as? AssistantTarget.Dev
                val threshold = a.optDouble("threshold", 0.0)
                val trigger = a.optString("when")
                if (sensor != null && target !is AssistantTarget.Grp && (switchable || runnable) &&
                    trigger in AUTOMATION_TRIGGERS && !threshold.isNaN()
                ) {
                    actions += AgentAction(
                        "automation", target!!.key, target.name, on,
                        sensor = sensor.key, sensorName = sensor.name, trigger = trigger, threshold = threshold,
                    )
                }
            }
            "list" -> {
                val items = a.optJSONArray("items")?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() } }
                    .orEmpty().filter { it.isNotEmpty() && it != "null" }.take(40)
                val name = a.optString("list").trim()
                if (name.isNotEmpty() && items.isNotEmpty()) actions += AgentAction("list", list = name, items = items)
            }
            "event" -> {
                val title = a.optString("title").trim()
                val start = a.optString("start")
                val date = a.optString("date")
                val minutes = a.optDouble("minutes", 60.0).let { if (it.isNaN() || it < 1) 60 else Math.round(it).toInt() }
                if (title.isNotEmpty() && Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*""").matches(start)) {
                    actions += AgentAction("event", title = title, start = start.take(16), minutes = minutes.coerceIn(5, 24 * 60))
                } else if (title.isNotEmpty() && Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) {
                    actions += AgentAction("event", title = title, date = date)
                }
            }
        }
    }
    val reply = if (answer.isNull("reply")) "" else answer.optString("reply").trim()
    return AgentPlan(reply, answer.optBoolean("alexa") && actions.isEmpty(), actions)
}

private const val SCHEMA_AGENT = """
{"type":"object","properties":{
 "reply":{"type":"string"},"alexa":{"type":"boolean"},
 "actions":{"type":"array","items":{"type":"object","properties":{
  "do":{"type":"string"},"target":{"type":"string"},"on":{"type":"boolean"},"minutes":{"type":"number"},
  "sensor":{"type":"string"},"when":{"type":"string"},"threshold":{"type":"number"},
  "list":{"type":"string"},"items":{"type":"array","items":{"type":"string"}},
  "title":{"type":"string"},"start":{"type":"string"},"date":{"type":"string"}},"required":["do"]}}},
 "required":["reply","actions"]}
"""

/**
 * Workers AI over the REST API of Cloudflare, with the account and the token of the user. Every
 * answer is asked as JSON, read back from what the model returned.
 */
class WorkersAi(private val http: OkHttpClient, private val base: String = "https://api.cloudflare.com/client/v4") {
    /** Checks the account and the token with the smallest request there is; on the home server, links them there. */
    suspend fun test(accountId: String, token: String, model: String) {
        if (ServerSync.active) {
            ServerSync.call("POST", "/api/assistant/connect", JSONObject().put("accountId", accountId).put("token", token))
            ServerSync.pull()
            return
        }
        run(accountId, token, model, listOf("user" to "Reply with OK."), schema = null, maxTokens = 4)
    }

    /** What the user meant by the guesses of the speech recognizer [heard], with the names put right. */
    suspend fun correct(accountId: String, token: String, model: String, system: String, heard: List<String>): String {
        if (ServerSync.active) {
            val body = JSONObject().put("kind", "correct").put("system", system).put("heard", JSONArray(heard))
            return ServerSync.call("POST", "/api/assistant/ask", body).optString("text").trim()
        }
        val guesses = heard.mapIndexed { i, it -> "${i + 1}. $it" }.joinToString("\n")
        return json(accountId, token, model, listOf("system" to system, "user" to guesses), SCHEMA_TEXT, 120)
            .optString("text").trim()
    }

    /** Phrases to run a custom device, as the model suggests them, a bit freely to have them varied. */
    suspend fun phrases(accountId: String, token: String, model: String, system: String): JSONObject =
        if (ServerSync.active) {
            ServerSync.call("POST", "/api/assistant/ask", JSONObject().put("kind", "phrases").put("system", system))
                .optJSONObject("answer") ?: JSONObject()
        } else json(accountId, token, model, listOf("system" to system, "user" to "Suggest the phrases."), SCHEMA_PHRASES, 800, 0.7)

    suspend fun questions(accountId: String, token: String, model: String, system: String): JSONObject =
        if (ServerSync.active) {
            ServerSync.call("POST", "/api/assistant/ask", JSONObject().put("kind", "questions").put("system", system))
                .optJSONObject("answer") ?: JSONObject()
        } else json(accountId, token, model, listOf("system" to system, "user" to "Ask your questions."), SCHEMA_QUESTIONS, 1500)

    /** What the assistant answers to [request] and would do, under the instructions [system]. */
    suspend fun agent(accountId: String, token: String, model: String, system: String, request: String): JSONObject =
        if (ServerSync.active) {
            val body = JSONObject().put("kind", "agent").put("system", system).put("heard", JSONArray().put(request))
            ServerSync.call("POST", "/api/assistant/ask", body).optJSONObject("answer") ?: JSONObject()
        } else json(accountId, token, model, listOf("system" to system, "user" to request), SCHEMA_AGENT, 1200, 0.2)

    /**
     * What the vision model makes of [jpeg], a picture in base64, with the words of [request], as the JSON
     * [system] asks for; on the home server, the server asks. Through the endpoint like OpenAI's, which takes pictures.
     */
    suspend fun image(accountId: String, token: String, system: String, request: String, jpeg: String): JSONObject {
        if (ServerSync.active) {
            val body = JSONObject().put("system", system).put("request", request).put("image", jpeg)
            return ServerSync.call("POST", "/api/assistant/image", body).optJSONObject("answer") ?: throw AssistantException(R.string.assistant_unreadable)
        }
        if (!accountId.matches(Regex("[0-9a-fA-F]{32}"))) throw AssistantException(R.string.assistant_bad_account)
        val user = JSONArray()
            .put(JSONObject().put("type", "text").put("text", request.ifBlank { "." }))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpeg")))
        val body = JSONObject()
            .put("model", VISION_MODEL)
            .put("max_tokens", 700)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        return withContext(Dispatchers.IO) {
            val call = Request.Builder()
                .url("$base/accounts/$accountId/ai/v1/chat/completions")
                .header("Authorization", "Bearer ${token.trim()}")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(call).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val message = json?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                    ?: json?.optJSONObject("error")?.optString("message").orEmpty()
                when {
                    response.code == 401 || response.code == 403 -> throw AssistantException(R.string.assistant_unauthorized)
                    response.code == 429 || message.contains("neurons", ignoreCase = true) -> throw AssistantException(R.string.assistant_limit)
                    !response.isSuccessful -> throw AssistantException(R.string.assistant_error, message.ifEmpty { response.code.toString() })
                }
                val content = json?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                extractJson(content) ?: throw AssistantException(R.string.assistant_unreadable)
            }
        }
    }

    private suspend fun json(
        accountId: String,
        token: String,
        model: String,
        messages: List<Pair<String, String>>,
        schema: String,
        maxTokens: Int,
        temperature: Double = 0.1,
    ): JSONObject {
        val answer = try {
            run(accountId, token, model, messages, JSONObject(schema), maxTokens, temperature)
        } catch (e: AssistantException) {
            // When the model cannot keep to the schema, it is asked again free: the JSON is then looked for in the text.
            if (e.text != R.string.assistant_json_failed) throw e
            run(accountId, token, model, messages, null, maxTokens, temperature)
        }
        return when (answer) {
            is JSONObject -> answer
            else -> extractJson(answer.toString()) ?: throw AssistantException(R.string.assistant_unreadable)
        }
    }

    private suspend fun run(
        accountId: String,
        token: String,
        model: String,
        messages: List<Pair<String, String>>,
        schema: JSONObject?,
        maxTokens: Int,
        temperature: Double = 0.1,
    ): Any = withContext(Dispatchers.IO) {
        if (!accountId.matches(Regex("[0-9a-fA-F]{32}"))) throw AssistantException(R.string.assistant_bad_account)
        val body = JSONObject()
            .put("messages", JSONArray(messages.map { (role, content) -> JSONObject().put("role", role).put("content", content) }))
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
        if (schema != null) body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema", schema))
        val request = Request.Builder()
            .url("$base/accounts/$accountId/ai/run/$model")
            .header("Authorization", "Bearer ${token.trim()}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val error = json?.optJSONArray("errors")?.optJSONObject(0)
            val message = error?.optString("message").orEmpty()
            when {
                response.code == 401 || response.code == 403 -> throw AssistantException(R.string.assistant_unauthorized)
                response.code == 429 || message.contains("neurons", ignoreCase = true) ->
                    throw AssistantException(R.string.assistant_limit)
                message.contains("JSON Mode", ignoreCase = true) -> throw AssistantException(R.string.assistant_json_failed)
                response.code == 404 || error?.optInt("code") == 7003 -> throw AssistantException(R.string.assistant_bad_account)
                json == null || !response.isSuccessful || !json.optBoolean("success", true) ->
                    throw AssistantException(R.string.assistant_error, message.ifEmpty { response.code.toString() })
            }
            val result = json!!.optJSONObject("result") ?: throw AssistantException(R.string.assistant_unreadable)
            result.opt("response") ?: throw AssistantException(R.string.assistant_unreadable)
        }
    }
}

/** The first JSON object in [text], which some models wrap in words or in a code block. */
internal fun extractJson(text: String): JSONObject? {
    // Reasoning models think aloud first, inside these tags.
    val clean = text.replace(Regex("(?s)<think>.*?</think>"), "")
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull()
}
