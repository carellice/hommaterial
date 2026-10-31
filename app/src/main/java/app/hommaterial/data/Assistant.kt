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

/**
 * Workers AI over the REST API of Cloudflare, with the account and the token of the user. Every
 * answer is asked as JSON, read back from what the model returned.
 */
class WorkersAi(private val http: OkHttpClient, private val base: String = "https://api.cloudflare.com/client/v4") {
    /** Checks the account and the token with the smallest request there is. */
    suspend fun test(accountId: String, token: String, model: String) {
        run(accountId, token, model, listOf("user" to "Reply with OK."), schema = null, maxTokens = 4)
    }

    /** What the user meant by the guesses of the speech recognizer [heard], with the names put right. */
    suspend fun correct(accountId: String, token: String, model: String, system: String, heard: List<String>): String {
        val guesses = heard.mapIndexed { i, it -> "${i + 1}. $it" }.joinToString("\n")
        return json(accountId, token, model, listOf("system" to system, "user" to guesses), SCHEMA_TEXT, 120)
            .optString("text").trim()
    }

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
