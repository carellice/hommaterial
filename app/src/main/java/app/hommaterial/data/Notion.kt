package app.hommaterial.data

import android.content.Context
import app.hommaterial.R
import app.hommaterial.str
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Notion through its public API, with the secret of an internal integration the user makes and
// shares the pages with. Without the home server the phone speaks to Notion itself; with it, the
// server keeps the secret (server/src/notion.ts) and answers in the same shapes.

private const val NOTION_API = "https://api.notion.com/v1"
private const val NOTION_VERSION = "2022-06-28"

/** The id of a list of Notion among those of Alexa: its kind and id after a prefix of its own. */
const val NOTION_LIST = "notion:"

/** The type of such a list, where Alexa says TO_DO or SHOPPING_LIST. */
const val LIST_NOTION = "NOTION"

/** A page or a database of Notion the integration can read, chosen as a to-do list or as notes. */
data class NotionSource(val id: String, val kind: String, val name: String) {
    val isDatabase: Boolean get() = kind == KIND_DATABASE

    /** The id it takes among the lists of Alexa. */
    val listId: String get() = "$NOTION_LIST$kind:$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("name", name)

    companion object {
        const val KIND_DATABASE = "database"
        const val KIND_PAGE = "page"

        fun fromJson(o: JSONObject) = NotionSource(o.getString("id"), o.optString("kind", KIND_PAGE), o.optString("name"))

        /** The source behind the id of a list, or null when the list is of Alexa. */
        fun ofList(listId: String): NotionSource? {
            if (!listId.startsWith(NOTION_LIST)) return null
            val rest = listId.removePrefix(NOTION_LIST)
            return NotionSource(rest.substringAfter(':'), rest.substringBefore(':'), "")
        }
    }
}

/** A note: a page of Notion, with when it was last changed in epoch milliseconds. */
data class NotionNote(val id: String, val title: String, val edited: Long, val url: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("edited", edited).put("url", url)

    companion object {
        fun fromJson(o: JSONObject) = NotionNote(o.getString("id"), o.optString("title"), o.optLong("edited"), o.optString("url"))
    }
}

/**
 * A block of the text of a note, flattened to what the app shows: [kind] is one of h1, h2, h3, p,
 * bullet, number, todo, quote, code, divider; [depth] how far it is nested in a list.
 */
data class NoteBlock(val kind: String, val text: String, val checked: Boolean = false, val depth: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("text", text).put("checked", checked).put("depth", depth)

    companion object {
        fun fromJson(o: JSONObject) = NoteBlock(o.optString("kind", "p"), o.optString("text"), o.optBoolean("checked"), o.optInt("depth"))
    }
}

/** The secret of the integration, without the server, and what this device shows of Notion. */
class NotionStore(context: Context) {
    private val prefs = context.getSharedPreferences("notion", Context.MODE_PRIVATE)

    fun token(): String = prefs.getString("token", "").orEmpty()

    val linked: Boolean get() = if (ServerSync.active) ServerSync.notionLinked else token().isNotEmpty()

    /** The workspace the integration belongs to, as Notion names it. */
    fun workspace(): String = if (ServerSync.active) ServerSync.notionWorkspace else prefs.getString("workspace", "").orEmpty()

    fun connect(token: String, workspace: String) = prefs.edit().putString("token", token.trim()).putString("workspace", workspace).apply()

    fun disconnect() = prefs.edit().remove("token").remove("workspace").remove("lists").remove("notes").apply()

    /** The pages and databases shown as to-do lists, after those of Alexa. */
    fun lists(): List<NotionSource> = read("lists")

    fun putLists(sources: List<NotionSource>) = write("lists", sources)

    /** The pages and databases whose pages are the notes. */
    fun notes(): List<NotionSource> = read("notes")

    fun putNotes(sources: List<NotionSource>) = write("notes", sources)

    private fun read(key: String): List<NotionSource> =
        runCatching { JSONArray(prefs.getString(key, "[]")).mapObjects(NotionSource::fromJson) }.getOrDefault(emptyList())

    private fun write(key: String, sources: List<NotionSource>) =
        prefs.edit().putString(key, JSONArray(sources.map { it.toJson() }).toString()).apply()
}

class Notion(private val http: OkHttpClient, private val store: NotionStore) {

    /** Checks the secret with the smallest request there is; on the home server, keeps it there. */
    suspend fun test(token: String): String {
        if (ServerSync.active) {
            val answer = ServerSync.call("POST", "/api/notion/connect", JSONObject().put("token", token.trim()))
            ServerSync.pull(force = true)
            return answer.optString("workspace")
        }
        val me = call("GET", "/users/me", token = token.trim())
        return me.optJSONObject("bot")?.optString("workspace_name").orEmpty()
    }

    suspend fun disconnect() {
        if (ServerSync.active) {
            ServerSync.call("POST", "/api/notion/disconnect")
            ServerSync.pull(force = true)
        }
        store.disconnect()
    }

    /** Every page and database shared with the integration, the databases first. */
    suspend fun sources(): List<NotionSource> {
        if (ServerSync.active) {
            return (ServerSync.call("GET", "/api/notion/sources").optJSONArray("sources") ?: JSONArray()).mapObjects(NotionSource::fromJson)
        }
        val found = mutableListOf<NotionSource>()
        var cursor: String? = null
        do {
            val body = JSONObject().put("page_size", 100)
            if (cursor != null) body.put("start_cursor", cursor)
            val answer = call("POST", "/search", body)
            answer.optJSONArray("results")?.mapObjects { o ->
                if (o.optBoolean("archived") || o.optBoolean("in_trash")) return@mapObjects
                when (o.optString("object")) {
                    "database" -> found += NotionSource(o.getString("id"), NotionSource.KIND_DATABASE, plain(o.optJSONArray("title")))
                    "page" -> found += NotionSource(o.getString("id"), NotionSource.KIND_PAGE, pageTitle(o))
                }
            }
            cursor = answer.optString("next_cursor").takeIf { answer.optBoolean("has_more") && it.isNotEmpty() }
        } while (cursor != null && found.size < 500)
        return found.map { it.copy(name = it.name.ifBlank { str(R.string.notion_untitled) }) }
            .sortedWith(compareBy({ !it.isDatabase }, { it.name.lowercase() }))
    }

    // --- to-do lists ------------------------------------------------------------------------------
    // A database is a list of its pages, done by a checkbox or a status; a page is a list of its to-dos.

    suspend fun items(source: NotionSource): List<ListItem> {
        if (ServerSync.active) {
            return (ServerSync.call("GET", "${serverList(source)}/items").optJSONArray("items") ?: JSONArray()).mapObjects(ListItem::fromJson)
        }
        if (!source.isDatabase) {
            return children(source.id).filter { it.optString("type") == "to_do" }.map { block ->
                val todo = block.getJSONObject("to_do")
                ListItem(block.getString("id"), plain(todo.optJSONArray("rich_text")), todo.optBoolean("checked"), 1)
            }
        }
        val schema = schema(source.id)
        return query(source.id).map { page ->
            ListItem(page.getString("id"), pageTitle(page), schema.done(page.optJSONObject("properties") ?: JSONObject()), 1)
        }
    }

    suspend fun add(source: NotionSource, name: String) {
        if (ServerSync.active) {
            ServerSync.call("POST", "${serverList(source)}/items", JSONObject().put("name", name))
            return
        }
        if (!source.isDatabase) {
            val block = JSONObject().put("object", "block").put("type", "to_do")
                .put("to_do", JSONObject().put("rich_text", richText(name)).put("checked", false))
            call("PATCH", "/blocks/${source.id}/children", JSONObject().put("children", JSONArray().put(block)))
            return
        }
        val schema = schema(source.id)
        val properties = JSONObject().put(schema.title, JSONObject().put("title", richText(name)))
        call("POST", "/pages", JSONObject().put("parent", JSONObject().put("database_id", source.id)).put("properties", properties))
    }

    suspend fun setDone(source: NotionSource, item: ListItem, done: Boolean) {
        if (ServerSync.active) {
            ServerSync.call("PUT", "${serverList(source)}/items/${ServerSync.encode(item.id)}", JSONObject().put("completed", done))
            return
        }
        if (!source.isDatabase) {
            call("PATCH", "/blocks/${item.id}", JSONObject().put("to_do", JSONObject().put("checked", done)))
            return
        }
        val change = schema(source.id).change(done) ?: throw Exception(str(R.string.notion_no_checkbox))
        call("PATCH", "/pages/${item.id}", JSONObject().put("properties", change))
    }

    suspend fun rename(source: NotionSource, item: ListItem, name: String) {
        if (ServerSync.active) {
            ServerSync.call("PUT", "${serverList(source)}/items/${ServerSync.encode(item.id)}", JSONObject().put("name", name))
            return
        }
        if (!source.isDatabase) {
            call("PATCH", "/blocks/${item.id}", JSONObject().put("to_do", JSONObject().put("rich_text", richText(name))))
            return
        }
        val properties = JSONObject().put(schema(source.id).title, JSONObject().put("title", richText(name)))
        call("PATCH", "/pages/${item.id}", JSONObject().put("properties", properties))
    }

    suspend fun delete(source: NotionSource, item: ListItem) {
        if (ServerSync.active) {
            ServerSync.call("DELETE", "${serverList(source)}/items/${ServerSync.encode(item.id)}")
            return
        }
        if (source.isDatabase) call("PATCH", "/pages/${item.id}", JSONObject().put("archived", true))
        else call("DELETE", "/blocks/${item.id}")
    }

    private fun serverList(source: NotionSource) = "/api/notion/lists/${source.kind}/${ServerSync.encode(source.id)}"

    // --- notes ------------------------------------------------------------------------------------

    /** The notes of [sources], the latest changed first: the pages of a database, or a page itself. */
    suspend fun notes(sources: List<NotionSource>): List<NotionNote> {
        if (sources.isEmpty()) return emptyList()
        if (ServerSync.active) {
            val query = ServerSync.encode(sources.joinToString(",") { "${it.kind}:${it.id}" })
            return (ServerSync.call("GET", "/api/notion/notes?sources=$query").optJSONArray("notes") ?: JSONArray())
                .mapObjects(NotionNote::fromJson)
        }
        return sources.flatMap { source ->
            if (source.isDatabase) query(source.id).map(::note) else listOf(note(call("GET", "/pages/${source.id}")))
        }.distinctBy { it.id }.sortedByDescending { it.edited }
    }

    /** The text of the note [id], block by block, with the lists nested up to a few levels. */
    suspend fun note(id: String): List<NoteBlock> {
        if (ServerSync.active) {
            return (ServerSync.call("GET", "/api/notion/notes/${ServerSync.encode(id)}").optJSONArray("blocks") ?: JSONArray())
                .mapObjects(NoteBlock::fromJson)
        }
        val blocks = mutableListOf<NoteBlock>()
        suspend fun read(parent: String, depth: Int) {
            for (block in children(parent)) {
                if (blocks.size >= 400) return
                noteBlock(block, depth)?.let { blocks += it }
                if (block.optBoolean("has_children") && depth < 2 && block.optString("type") != "child_page") read(block.getString("id"), depth + 1)
            }
        }
        read(id, 0)
        return blocks
    }

    // --- the API ----------------------------------------------------------------------------------

    private suspend fun children(id: String): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val answer = call("GET", "/blocks/$id/children?page_size=100" + (cursor?.let { "&start_cursor=$it" } ?: ""))
            answer.optJSONArray("results")?.mapObjects { all += it }
            cursor = answer.optString("next_cursor").takeIf { answer.optBoolean("has_more") && it.isNotEmpty() }
        } while (cursor != null && all.size < 400)
        return all
    }

    private suspend fun query(database: String): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val body = JSONObject().put("page_size", 100)
                .put("sorts", JSONArray().put(JSONObject().put("timestamp", "created_time").put("direction", "ascending")))
            if (cursor != null) body.put("start_cursor", cursor)
            val answer = call("POST", "/databases/$database/query", body)
            answer.optJSONArray("results")?.mapObjects { all += it }
            cursor = answer.optString("next_cursor").takeIf { answer.optBoolean("has_more") && it.isNotEmpty() }
        } while (cursor != null && all.size < 400)
        return all
    }

    private val schemas = HashMap<String, NotionSchema>()

    private suspend fun schema(database: String): NotionSchema =
        schemas[database] ?: NotionSchema.of(call("GET", "/databases/$database")).also { schemas[database] = it }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, token: String = store.token()): JSONObject =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) throw Exception(str(R.string.notion_not_linked))
            val request = Request.Builder()
                .url(NOTION_API + path)
                .header("Authorization", "Bearer $token")
                .header("Notion-Version", NOTION_VERSION)
                .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                when {
                    response.code == 401 -> throw Exception(str(R.string.notion_unauthorized))
                    response.code == 404 -> throw Exception(str(R.string.notion_not_shared))
                    response.code == 429 -> throw Exception(str(R.string.notion_limit))
                    !response.isSuccessful || json == null ->
                        throw Exception(str(R.string.notion_error, json?.optString("message")?.ifEmpty { null } ?: response.code.toString()))
                }
                json!!
            }
        }
}

/** Where a database keeps the name of its pages and whether they are done. */
internal class NotionSchema(
    val title: String,
    private val checkbox: String?,
    private val status: String?,
    private val doneOptions: Set<String>,
    private val doneOption: String?,
    private val todoOption: String?,
) {
    fun done(properties: JSONObject): Boolean {
        if (checkbox != null) return properties.optJSONObject(checkbox)?.optBoolean("checkbox") == true
        if (status != null) return properties.optJSONObject(status)?.optJSONObject("status")?.optString("name") in doneOptions
        return false
    }

    /** The properties that mark a page done, or not; null when the database has nothing to mark it with. */
    fun change(done: Boolean): JSONObject? = when {
        checkbox != null -> JSONObject().put(checkbox, JSONObject().put("checkbox", done))
        status != null -> (if (done) doneOption else todoOption)?.let { JSONObject().put(status, JSONObject().put("status", JSONObject().put("name", it))) }
        else -> null
    }

    companion object {
        fun of(database: JSONObject): NotionSchema {
            val properties = database.optJSONObject("properties") ?: JSONObject()
            var title = "Name"
            var checkbox: String? = null
            var status: String? = null
            var statusOf: JSONObject? = null
            for (name in properties.keys()) {
                val property = properties.getJSONObject(name)
                when (property.optString("type")) {
                    "title" -> title = name
                    "checkbox" -> if (checkbox == null) checkbox = name
                    "status" -> if (status == null) { status = name; statusOf = property.optJSONObject("status") }
                }
            }
            // The options of a status are done when they are in the group Notion calls "Complete".
            val options = statusOf?.optJSONArray("options")?.mapObjects { it.optString("id") to it.optString("name") }.orEmpty().toMap()
            fun group(name: String) = statusOf?.optJSONArray("groups")?.mapObjects { it }
                ?.find { it.optString("name").equals(name, ignoreCase = true) }
                ?.optJSONArray("option_ids")?.let { ids -> (0 until ids.length()).mapNotNull { options[ids.optString(it)] } }.orEmpty()
            val complete = group("Complete")
            val todo = group("To-do").ifEmpty { options.values.filter { it !in complete } }
            return NotionSchema(title, checkbox, status, complete.toSet(), complete.firstOrNull(), todo.firstOrNull())
        }
    }
}

/** Notion takes at most 2000 characters in a piece of rich text and 100 pieces: a long text goes in several. */
private fun richText(text: String): JSONArray = JSONArray().apply {
    for (piece in text.chunked(2000).ifEmpty { listOf("") }.take(100)) {
        put(JSONObject().put("type", "text").put("text", JSONObject().put("content", piece)))
    }
}

/** The text of rich text, without its formatting. */
internal fun plain(rich: JSONArray?): String =
    rich?.let { (0 until it.length()).joinToString("") { i -> it.optJSONObject(i)?.optString("plain_text").orEmpty() } }.orEmpty()

/** The title of a page, wherever the database keeps it. */
internal fun pageTitle(page: JSONObject): String {
    val properties = page.optJSONObject("properties") ?: return ""
    for (name in properties.keys()) {
        val property = properties.optJSONObject(name) ?: continue
        if (property.optString("type") == "title") return plain(property.optJSONArray("title"))
    }
    return ""
}

private fun note(page: JSONObject) = NotionNote(
    id = page.getString("id"),
    title = pageTitle(page).ifBlank { str(R.string.notion_untitled) },
    edited = runCatching { OffsetDateTime.parse(page.optString("last_edited_time")).toInstant().toEpochMilli() }.getOrDefault(0L),
    url = page.optString("url"),
)

/** One block of Notion as the app shows it; null for those it does not (images, embeds, tables). */
internal fun noteBlock(block: JSONObject, depth: Int): NoteBlock? {
    val type = block.optString("type")
    val content = block.optJSONObject(type)
    val text = plain(content?.optJSONArray("rich_text"))
    return when (type) {
        "heading_1" -> NoteBlock("h1", text, depth = depth)
        "heading_2" -> NoteBlock("h2", text, depth = depth)
        "heading_3" -> NoteBlock("h3", text, depth = depth)
        "paragraph", "toggle", "callout" -> NoteBlock("p", text, depth = depth)
        "bulleted_list_item" -> NoteBlock("bullet", text, depth = depth)
        "numbered_list_item" -> NoteBlock("number", text, depth = depth)
        "to_do" -> NoteBlock("todo", text, content?.optBoolean("checked") == true, depth)
        "quote" -> NoteBlock("quote", text, depth = depth)
        "code" -> NoteBlock("code", text, depth = depth)
        "divider" -> NoteBlock("divider", "", depth = depth)
        "child_page" -> NoteBlock("p", content?.optString("title").orEmpty(), depth = depth)
        else -> null
    }
}
