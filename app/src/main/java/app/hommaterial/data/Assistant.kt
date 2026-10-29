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
val ASSISTANT_MODELS = listOf(
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "@cf/meta/llama-3.1-8b-instruct",
)

/** How many times a key may be pressed for one request, so that a misunderstanding stays small. */
const val ASSISTANT_MAX_TIMES = 20

/** Above this many things switched at once the user is asked before anything happens. */
const val ASSISTANT_CONFIRM_ABOVE = 5

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

    val configured: Boolean get() = prefs.contains("token")

    fun accountId(): String = prefs.getString("accountId", "").orEmpty()

    fun token(): String = prefs.getString("token", "").orEmpty()

    fun model(): String = prefs.getString("model", null)?.takeIf { it in ASSISTANT_MODELS } ?: ASSISTANT_MODELS.first()

    fun connect(accountId: String, token: String) =
        prefs.edit().putString("accountId", accountId).putString("token", token).apply()

    /** Forgets the account; what was taught stays, for when it is linked again. */
    fun disconnect() = prefs.edit().remove("accountId").remove("token").apply()

    fun putModel(model: String) = prefs.edit().putString("model", model).apply()

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

/** One thing the assistant decided to do, already checked against what exists. */
sealed interface AssistantStep {
    data class Power(val device: Device, val on: Boolean) : AssistantStep
    data class Brightness(val device: Device, val percent: Int) : AssistantStep
    data class Color(val device: Device, val choice: ColorChoice) : AssistantStep
    data class GroupPower(val group: Group, val devices: List<Device>, val on: Boolean) : AssistantStep
    data class Run(val macro: Macro) : AssistantStep
    data class Press(val remote: Remote, val key: AssistantKey, val times: Int, val parameter: String = "default") : AssistantStep
    data class Ac(val remote: Remote, val setting: AcSetting, val on: Boolean) : AssistantStep
    data class Wait(val seconds: Int) : AssistantStep
}

/** What came back for a request: the steps, what to tell the user, a question when it was unclear. */
data class AssistantPlan(
    val steps: List<AssistantStep>,
    val reply: String,
    val question: String?,
    /** The actions left out, as the model wrote them, to show what went wrong. */
    val rejected: List<String> = emptyList(),
) {
    val ignored: Int get() = rejected.size

    /** How many things it switches on or off, which decides whether the user is asked first. */
    val switched: Int
        get() = steps.sumOf {
            when (it) {
                is AssistantStep.Power -> 1
                is AssistantStep.GroupPower -> it.devices.size
                is AssistantStep.Run -> 1
                else -> 0
            }
        }
}

/** A question of the assistant about one thing of the home, for the owner to answer in words. */
data class AssistantQuestion(val target: AssistantTarget, val question: String)

private val AC_MODES = setOf(1, 2, 3, 4, 5)
private val AC_FANS = setOf(1, 2, 3, 4)

/**
 * Reads what the model answered to a request into steps. Whatever names an id or a key that does
 * not exist, or a value out of range, is left out and kept in [AssistantPlan.rejected] as it came.
 */
fun parsePlan(answer: JSONObject, catalog: AssistantCatalog, devicesOf: (Group) -> List<Device>): AssistantPlan {
    val steps = mutableListOf<AssistantStep>()
    val rejected = mutableListOf<String>()
    // Some models give a single action, or call the list otherwise.
    val actions = answer.optJSONArray("actions") ?: answer.optJSONArray("steps")
        ?: answer.optJSONObject("action")?.let { JSONArray().put(it) } ?: JSONArray()
    for (i in 0 until actions.length()) {
        val action = actions.optJSONObject(i)
        val step = action?.let { runCatching { step(it, catalog, devicesOf) }.getOrNull() }
        if (step == null) rejected += actions.opt(i).toString().take(300) else steps += step
    }
    val question = answer.optString("question").trim().takeIf { it.isNotEmpty() && it != "null" }
    return AssistantPlan(steps, answer.optString("reply").trim(), question, rejected)
}

// What models write instead of the verbs of the prompt.
private val ON_VERBS = setOf("on", "turn_on", "turnon", "switch_on", "power_on", "accendi", "true")
private val OFF_VERBS = setOf("off", "turn_off", "turnoff", "switch_off", "power_off", "spegni", "false")
private val PRESS_VERBS = setOf("press", "press_key", "key", "send", "button", "push", "command")
private val RUN_VERBS = setOf("run", "start", "execute", "activate", "launch")
private val TARGET_ID = Regex("^\\W*([dgmr]\\d+)\\b", RegexOption.IGNORE_CASE)

/** The first of [names] that this action has, as text. */
private fun JSONObject.text(vararg names: String): String = names.firstNotNullOfOrNull { name ->
    opt(name)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}.orEmpty()

/** The thing an action is about: its id, even with its name after it, or else its very name. */
private fun target(action: JSONObject, catalog: AssistantCatalog): AssistantTarget? {
    val said = action.text("target", "id", "device", "remote", "item", "device_id")
    if (said.isEmpty()) return null
    TARGET_ID.find(said)?.let { m -> catalog[m.groupValues[1]]?.let { return it } }
    val name = said.trim('"', ' ').lowercase()
    return catalog.targets.singleOrNull { it.name.lowercase() == name }
}

/** The key of [remote] the model meant: its code, its code with the name after "=", or its name. */
private fun key(remote: AssistantTarget.Rem, said: String): AssistantKey? {
    if (said.isEmpty()) return null
    val code = said.substringBefore('=').trim()
    return remote.keys.find { it.command == code }
        ?: remote.keys.find { it.command.equals(code, ignoreCase = true) }
        ?: remote.keys.find { it.command.substringAfter(':').equals(code, ignoreCase = true) }
        ?: remote.keys.find { it.label.equals(said.substringAfter('=').trim(), ignoreCase = true) }
}

private fun number(text: String): Double? = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull()

private fun step(action: JSONObject, catalog: AssistantCatalog, devicesOf: (Group) -> List<Device>): AssistantStep? {
    val verb = action.text("do", "action", "type", "verb", "operation").lowercase().replace(' ', '_').replace('-', '_')
    val value = action.text("value", "level", "percent", "color", "channel", "setting")
    if (verb == "wait" || verb == "pause" || verb == "delay") {
        val seconds = number(value.ifEmpty { action.text("seconds", "duration") })?.toInt()
        return if (seconds != null && seconds in 1..60) AssistantStep.Wait(seconds) else null
    }
    val on = verb in ON_VERBS
    val off = verb in OFF_VERBS
    val target = target(action, catalog) ?: return null
    return when (target) {
        is AssistantTarget.Dev -> {
            val d = target.device
            when (verb) {
                in ON_VERBS, in OFF_VERBS -> if (d.hasPower) AssistantStep.Power(d, on) else null
                "brightness", "dim", "set_brightness" -> number(value)?.toInt()
                    ?.takeIf { d.hasBrightness && it in 1..100 }?.let { AssistantStep.Brightness(d, it) }
                "color", "colour", "white", "set_color" -> {
                    val name = value.lowercase().replace(' ', '_')
                    val color = COLOR_CHOICES.find { it.alexaName == name }?.takeIf { d.hasColor }
                        ?: WHITE_CHOICES.find { it.alexaName == name }?.takeIf { d.hasColorTemperature || d.hasColor }
                    color?.let { AssistantStep.Color(d, it) }
                }
                else -> null
            }
        }
        is AssistantTarget.Grp -> if (on || off) {
            devicesOf(target.group).filter { it.hasPower }.takeIf { it.isNotEmpty() }
                ?.let { AssistantStep.GroupPower(target.group, it, on) }
        } else {
            null
        }
        is AssistantTarget.Mac -> if (verb in RUN_VERBS || on) AssistantStep.Run(target.macro) else null
        is AssistantTarget.Rem -> {
            val ac = verb == "ac" || verb == "air_conditioner" || verb == "climate"
            val times = number(action.text("times", "repeat", "count", "presses"))?.toInt()?.let { if (it == 0) 1 else it } ?: 1
            // The key may also come as the verb itself: "do": "volumeAdd".
            val pressed = if (on || off || ac) null else key(target, action.text("command", "key", "button")) ?: key(target, verb)
            when {
                ac -> if (target.ac) acStep(target.remote, value) else null
                pressed != null -> when {
                    times !in 1..ASSISTANT_MAX_TIMES -> null
                    pressed.command == "SetChannel" -> value.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
                        ?.let { AssistantStep.Press(target.remote, pressed, 1, it) }
                    else -> AssistantStep.Press(target.remote, pressed, times)
                }
                on || off -> target.keys.find { it.command == if (on) "turnOn" else "turnOff" }
                    ?.let { AssistantStep.Press(target.remote, it, 1) }
                else -> null
            }
        }
    }
}

/** An air conditioner gets everything at once: "temperature,mode,fan,on|off". */
private fun acStep(remote: Remote, value: String): AssistantStep? {
    val parts = value.split(',').map { it.trim().lowercase() }
    if (parts.size != 4) return null
    val temperature = parts[0].removeSuffix("°").toDoubleOrNull()?.toInt()?.takeIf { it in 16..30 } ?: return null
    val mode = parts[1].toIntOrNull()?.takeIf { it in AC_MODES } ?: return null
    val fan = parts[2].toIntOrNull()?.takeIf { it in AC_FANS } ?: return null
    val on = when (parts[3]) {
        "on" -> true
        "off" -> false
        else -> return null
    }
    return AssistantStep.Ac(remote, AcSetting(temperature, mode, fan), on)
}

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

private const val SCHEMA_ACTIONS = """
{"type":"object","properties":{
 "actions":{"type":"array","items":{"type":"object","properties":{
  "target":{"type":"string"},"do":{"type":"string"},"command":{"type":"string"},
  "value":{"type":"string"},"times":{"type":"integer"}},"required":["do"]}},
 "reply":{"type":"string"},"question":{"type":"string"}},
 "required":["actions","reply"]}
"""

private const val SCHEMA_QUESTIONS = """
{"type":"object","properties":{
 "questions":{"type":"array","items":{"type":"object","properties":{
  "target":{"type":"string"},"question":{"type":"string"}},"required":["target","question"]}}},
 "required":["questions"]}
"""

/** The instructions for a request, with the home as it is now. */
fun commandPrompt(catalog: AssistantCatalog, house: String, language: String): String = """
You are the assistant of a smart home app. Turn what the user asks into actions on the things listed below.
Use only ids and key codes from the list, exactly as written. Never invent them.
Each action is an object with "do" and, depending on it, "target", "command", "value", "times":
- "on" / "off": switch a device (d…), a group (g…) or a remote (r…) on or off.
- "brightness": a device that can; "value" is the percent, 1 to 100.
- "color": a device that can; "value" is one of: ${COLOR_CHOICES.joinToString(", ") { it.alexaName }}.
- "white": a device that can; "value" is one of: ${WHITE_CHOICES.joinToString(", ") { it.alexaName }}.
- "run": a custom device (m…).
- "press": a key of a remote; "command" is its key code; "times" is how many presses, 1 to $ASSISTANT_MAX_TIMES.
  For SetChannel put the channel number in "value".
- "ac": an air conditioner remote; "value" is "temperature,mode,fan,on" or "…,off", temperature 16 to 30,
  mode 1 auto, 2 cool, 3 dry, 4 fan, 5 heat; fan 1 auto, 2 low, 3 medium, 4 high.
- "wait": a pause; "value" is the seconds, 1 to 60.
Actions run in order. "Raise the volume by 5" means "press" volumeAdd with "times" 5.
"target" is only the id, such as "r1"; "command" is only the key code, the part before "=".
Example answer: {"actions":[{"target":"r1","do":"press","command":"volumeAdd","times":5}],"reply":"Raising the volume by 5.","question":""}
If the request is unclear, or nothing listed fits, give no actions and ask in "question".
"reply" is one short sentence in $language that says what you are doing; "question" is in $language too.
Questions about the home (what is on, the temperature) are answered in "reply" with no actions.
${if (house.isNotBlank()) "What the owner says about the home:\n${house.trim()}\n" else ""}
The things of the home:
${catalog.describe(withState = true)}
""".trim()

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
 * Workers AI over the REST API of Cloudflare, with the account and the token of the user. Every
 * answer is asked as JSON, read back from what the model returned.
 */
class WorkersAi(private val http: OkHttpClient, private val base: String = "https://api.cloudflare.com/client/v4") {
    /** Checks the account and the token with the smallest request there is. */
    suspend fun test(accountId: String, token: String, model: String) {
        run(accountId, token, model, listOf("user" to "Reply with OK."), schema = null, maxTokens = 4)
    }

    suspend fun command(
        accountId: String,
        token: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>,
    ): JSONObject = json(accountId, token, model, listOf("system" to system) + history, SCHEMA_ACTIONS, 600)

    suspend fun questions(accountId: String, token: String, model: String, system: String): JSONObject =
        json(accountId, token, model, listOf("system" to system, "user" to "Ask your questions."), SCHEMA_QUESTIONS, 1500)

    private suspend fun json(
        accountId: String,
        token: String,
        model: String,
        messages: List<Pair<String, String>>,
        schema: String,
        maxTokens: Int,
    ): JSONObject {
        val answer = try {
            run(accountId, token, model, messages, JSONObject(schema), maxTokens)
        } catch (e: AssistantException) {
            // When the model cannot keep to the schema, it is asked again free: the JSON is then looked for in the text.
            if (e.text != R.string.assistant_json_failed) throw e
            run(accountId, token, model, messages, null, maxTokens)
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
    ): Any = withContext(Dispatchers.IO) {
        if (!accountId.matches(Regex("[0-9a-fA-F]{32}"))) throw AssistantException(R.string.assistant_bad_account)
        val body = JSONObject()
            .put("messages", JSONArray(messages.map { (role, content) -> JSONObject().put("role", role).put("content", content) }))
            .put("max_tokens", maxTokens)
            .put("temperature", 0.1)
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
