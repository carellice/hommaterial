package app.hommaterial.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import app.hommaterial.MainActivity
import app.hommaterial.R
import app.hommaterial.quick.Quick
import app.hommaterial.str
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Where the sync with the server stands, for the settings: the last good one, the last error, what waits to be sent. */
data class SyncStatus(val syncedAt: Long? = null, val error: String? = null, val pending: Int = 0)

/**
 * The app on the home server ("server mode"), off until the user links a server in the settings.
 *
 * The server is the source of truth and the phone keeps a copy: the screens, the widget, the tiles
 * and the shortcuts read the same SharedPreferences as before, which [pull] fills with what the
 * server has. A choice made on the phone is written there as always and sent to the server, the
 * changed names only; without a connection it waits and goes with the next sync, and on a
 * conflict the last change to arrive wins. Commands to Alexa, SwitchBot and the TVs go through
 * the server, and the server alone runs timers, schedules, automations and alerts: the phone shows
 * what happened as its own notifications ([notices]). The favorites and the monitor mode stay on
 * each device, so that every one of them can have its own.
 */
object ServerSync {
    private lateinit var app: Context
    lateinit var store: ServerStore
        private set
    val client = ServerClient()
    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status = _status.asStateFlow()

    /** True while what came from the server is being written, so that it is not sent back. */
    @Volatile
    private var applying = false
    private var pushJob: Job? = null
    private var toldOffline = false

    // Listeners must be kept: SharedPreferences holds them weakly.
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun init(context: Context) {
        app = context.applicationContext
        store = ServerStore(app)
        _status.update { it.copy(pending = store.dirty.size) }
        watch("cache", CACHE_KEYS)
        watch("switchbot", SWITCHBOT_KEYS)
        watch("assistant", ASSISTANT_KEYS)
        watch("notion", NOTION_KEYS)
        watch("settings", SETTINGS_KEYS)
    }

    val active: Boolean get() = ::store.isInitialized && store.config.value != null

    private val config: ServerConfig get() = store.config.value ?: throw IOException(str(R.string.server_off))

    // --- what the server says of the accounts it uses ---------------------------------------------

    val alexaLoggedIn: Boolean get() = store.status.optJSONObject("alexa")?.optBoolean("loggedIn") == true
    val switchBotLinked: Boolean get() = store.status.optBoolean("switchBot")
    val assistantLinked: Boolean get() = store.status.optJSONObject("assistant")?.optBoolean("configured") == true
    val assistantAccount: String get() = store.status.optJSONObject("assistant")?.optString("accountId").orEmpty()
    val assistantModel: String get() = store.status.optJSONObject("assistant")?.optString("model").orEmpty()
    val calendarClient: Boolean get() = store.status.optJSONObject("calendar")?.optBoolean("client") == true
    val calendarLinked: Boolean get() = store.status.optJSONObject("calendar")?.optBoolean("linked") == true
    val calendarAccount: String get() = store.status.optJSONObject("calendar")?.optString("account").orEmpty()
    val notionLinked: Boolean get() = store.status.optJSONObject("notion")?.optBoolean("linked") == true
    val webSearchLinked: Boolean get() = store.status.optBoolean("webSearch")
    val notionWorkspace: String get() = store.status.optJSONObject("notion")?.optString("workspace").orEmpty()

    // --- requests ------------------------------------------------------------------------------------

    /**
     * One request to the server, with its failures as the app knows them: no connection as an
     * [IOException], the Amazon sign-in gone as a [NotLoggedInException], the rest with the words
     * of the server.
     */
    suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = try {
        client.request(config, method, path, body)
    } catch (e: ServerException) {
        throw when {
            e.code == "alexa_login" -> NotLoggedInException(e.message ?: str(R.string.login_expired))
            e.offline -> IOException(e.message)
            else -> Exception(e.message)
        }
    }

    fun encode(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    /** The address of the song at [path] of the music of the account; "" without a server. */
    fun musicUrl(path: String): String = store.config.value?.let { it.url + "/api/music/file?path=" + encode(path) }.orEmpty()

    /** The address of the picture of the song at [path]. */
    fun coverUrl(path: String): String = store.config.value?.let { it.url + "/api/music/cover?path=" + encode(path) }.orEmpty()

    /** The session of the account, for what the player and the downloads ask of the server by themselves. */
    fun sessionToken(): String? = if (::store.isInitialized) store.config.value?.token else null

    /** Whether [url] is an address of the home server, which wants the session. */
    fun isServerUrl(url: String): Boolean = store.config.value?.url?.let { url.startsWith(it) } == true

    /** Sends a change in the background, telling the sync status when it fails. */
    fun send(method: String, path: String, body: JSONObject? = null) {
        if (!active) return
        Quick.scope.launch {
            try {
                call(method, path, body)
                pull()
            } catch (e: Exception) {
                _status.update { it.copy(error = e.message) }
            }
        }
    }

    // --- from the server to the phone ----------------------------------------------------------------

    /** Sends what waits, then takes what changed on the server; [force] takes everything. */
    suspend fun pull(force: Boolean = false) {
        if (!active) return
        mutex.withLock {
            try {
                flushLocked()
                val since = if (force) -1 else store.revision
                val answer = client.request(config, "GET", "/api/state" + if (since >= 0) "?since=$since" else "")
                if (!answer.optBoolean("unchanged")) {
                    store.status = answer.optJSONObject("status") ?: JSONObject()
                    apply(answer.getJSONObject("data"))
                    store.revision = answer.optLong("revision", -1)
                }
                notices()
                history()
                _status.update { it.copy(syncedAt = System.currentTimeMillis(), error = null) }
            } catch (e: ServerException) {
                _status.update { it.copy(error = e.message) }
                throw if (e.offline) IOException(e.message) else e
            }
        }
        Quick.refresh(app)
    }

    /** Writes on the phone what the server has, in the format the app keeps it in. */
    private fun apply(data: JSONObject) {
        applying = true
        try {
            val cache = app.getSharedPreferences("cache", Context.MODE_PRIVATE).edit()
            cache.putString("devices", (data.optJSONArray("devices") ?: JSONArray()).toString())
            cache.putString("states", (data.optJSONObject("states") ?: JSONObject()).toString())
            if (data.isNull("updatedAt")) cache.remove("updatedAt") else cache.putLong("updatedAt", data.optLong("updatedAt"))
            // Unless changed on the phone and not yet sent: the newer choice is the one of the phone.
            val dirty = store.dirty
            fun keep(name: String) = name in dirty
            if (!keep("hidden")) cache.putStringSet("hidden", strings(data.optJSONArray("hidden")))
            for (name in listOf("rooms", "order", "tileSpans")) {
                if (!keep(name)) cache.putString(name, (data.optJSONObject(name) ?: JSONObject()).toString())
            }
            for (name in listOf("roomList", "groups", "macros")) {
                if (!keep(name)) cache.putString(name, (data.optJSONArray(name) ?: JSONArray()).toString())
            }
            if (!keep("shortcuts")) {
                val shortcuts = data.optJSONArray("shortcuts")
                if (shortcuts == null) cache.remove("shortcuts") else cache.putString("shortcuts", shortcuts.toString())
            }
            if (!keep("widget")) {
                val widget = data.optJSONObject("widget")
                if (widget == null) cache.remove("widget") else cache.putString("widget", widget.toString())
            }
            if (!keep("tiles")) {
                val tiles = data.optJSONArray("tiles") ?: JSONArray()
                for (slot in 0 until TILE_SLOTS) {
                    val id = if (tiles.isNull(slot)) null else tiles.optString(slot).takeIf { it.isNotEmpty() }
                    if (id == null) cache.remove("tileDevice$slot") else cache.putString("tileDevice$slot", id)
                }
            }
            // What the server alone changes: it carries them out.
            cache.putString("timers", (data.optJSONObject("timers") ?: JSONObject()).toString())
            cache.putString("schedules", (data.optJSONArray("schedules") ?: JSONArray()).toString())
            cache.putString("automations", (data.optJSONArray("automations") ?: JSONArray()).toString())
            cache.putString("alerts", (data.optJSONObject("alerts") ?: JSONObject()).toString())
            cache.commit()

            val switchBot = app.getSharedPreferences("switchbot", Context.MODE_PRIVATE).edit()
            switchBot.putString("remotes", (data.optJSONArray("remotes") ?: JSONArray()).toString())
            if (!keep("remoteButtons")) switchBot.putString("buttons", (data.optJSONObject("remoteButtons") ?: JSONObject()).toString())
            if (!keep("hiddenRemotes")) switchBot.putStringSet("hidden", strings(data.optJSONArray("hiddenRemotes")))
            if (!keep("acSettings")) switchBot.putString("ac", (data.optJSONObject("acSettings") ?: JSONObject()).toString())
            switchBot.commit()

            // The TVs without their keys, which stay on the server: commands go through it.
            val tvs = JSONArray()
            val list = data.optJSONArray("lgTvs") ?: JSONArray()
            for (i in 0 until list.length()) {
                val tv = list.optJSONObject(i) ?: continue
                tvs.put(JSONObject(tv.toString()).put("key", "").put("pin", ""))
            }
            app.getSharedPreferences("lgtv", Context.MODE_PRIVATE).edit().putString("tvs", tvs.toString()).commit()

            val assistant = app.getSharedPreferences("assistant", Context.MODE_PRIVATE).edit()
            if (!keep("assistantNotes")) assistant.putString("notes", (data.optJSONArray("assistantNotes") ?: JSONArray()).toString())
            if (!keep("assistantHouse")) assistant.putString("house", data.optString("assistantHouse"))
            if (!keep("assistantTaught")) assistant.putStringSet("taught", strings(data.optJSONArray("assistantTaught")))
            assistant.commit()

            // The lists and notes of Notion chosen travel with the account; the first device to sync gives its own.
            val notion = app.getSharedPreferences("notion", Context.MODE_PRIVATE)
            val chosen = notion.edit()
            for ((local, server) in NOTION_KEYS) {
                if (keep(server)) continue
                val fromServer = data.optJSONArray(server)
                if (fromServer != null) {
                    chosen.putString(local, fromServer.toString())
                } else if (notion.getString(local, "[]") != "[]") {
                    store.dirty = store.dirty + server
                }
            }
            chosen.commit()
            val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val shared = settings.edit()
            if (!keep("listViews")) {
                val views = data.optJSONObject("listViews")
                if (views == null) shared.remove("listViews") else shared.putString("listViews", views.toString())
            }
            // The favorite team and the place of the weather: null on the server until a device chooses,
            // false for none; the first device gives its own.
            for (name in listOf("team", "weather")) {
                if (keep(name)) continue
                val value = data.opt(name)
                when {
                    value is JSONObject -> shared.putString(name, value.toString())
                    value == false -> shared.remove(name)
                    settings.contains(name) -> store.dirty = store.dirty + name
                }
            }
            if (!keep("teamReminder")) shared.putInt("teamReminder", data.optInt("teamReminder", 0))
            shared.commit()
        } finally {
            applying = false
        }
    }

    /** The readings of the sensors, as the server noted them, at most every ten minutes. */
    private suspend fun history() {
        val now = System.currentTimeMillis()
        if (now - store.prefs.getLong("historyAt", 0) < HISTORY_EVERY_MS) return
        val sensors = Cache(app).devices().filter { it.isSensor }
        val prefs = app.getSharedPreferences("history", Context.MODE_PRIVATE)
        for (sensor in sensors) {
            val readings = client.request(config, "GET", "/api/history/${encode(sensor.applianceId)}").optJSONArray("readings") ?: continue
            val text = (0 until readings.length()).mapNotNull { readings.optJSONObject(it) }.joinToString(";") {
                val humidity = if (it.isNull("humidity")) "" else it.optInt("humidity").toString()
                "${it.optLong("at")},${it.optDouble("temperature")},$humidity"
            }
            prefs.edit().putString(sensor.applianceId, text).apply()
        }
        store.prefs.edit().putLong("historyAt", now).apply()
    }

    /** Shows as notifications of the phone what the server told about timers, schedules, automations and alerts. */
    private suspend fun notices() {
        val last = store.lastNotice
        val list = client.request(config, "GET", "/api/notices?after=${maxOf(last, 0)}").optJSONArray("notices") ?: return
        var newest = last
        for (i in 0 until list.length()) {
            val notice = list.optJSONObject(i) ?: continue
            val id = notice.optLong("id")
            newest = maxOf(newest, id)
            // Linked just now: what happened before is old news.
            if (last >= 0) notify(notice.optString("tag").hashCode(), notice.optString("title"), notice.optString("body"))
        }
        store.lastNotice = maxOf(newest, 0)
    }

    private fun notify(id: Int, title: String, text: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, str(R.string.alerts_title), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            id,
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    // --- from the phone to the server ----------------------------------------------------------------

    private fun watch(name: String, keys: Map<String, String>) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (applying || !active || key == null) return@OnSharedPreferenceChangeListener
            val server = keys[key] ?: if (name == "cache" && key.startsWith("tileDevice")) "tiles" else null
            if (server != null) changed(server)
        }
        listeners += listener
        app.getSharedPreferences(name, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
    }

    /** Notes a choice changed on the phone and sends it shortly, together with the others changed with it. */
    private fun changed(server: String) {
        store.dirty = store.dirty + server
        _status.update { it.copy(pending = store.dirty.size) }
        pushJob?.cancel()
        pushJob = Quick.scope.launch {
            delay(PUSH_DELAY_MS)
            try {
                mutex.withLock { flushLocked() }
            } catch (_: Exception) {
                // Told in the status; it goes with the next sync.
            }
        }
    }

    /** Sends the choices changed on the phone, if any. */
    private suspend fun flushLocked() {
        val dirty = store.dirty
        if (dirty.isEmpty() || !active) return
        val values = JSONObject()
        // The favorites noted before they stopped going to the server stay on the phone.
        for (name in dirty - "favorites") values.put(name, localValue(name))
        try {
            client.request(config, "POST", "/api/data", JSONObject().put("values", values))
            store.dirty = store.dirty - dirty
            toldOffline = false
            _status.update { it.copy(pending = store.dirty.size, error = null) }
        } catch (e: ServerException) {
            if (!e.offline) {
                // Refused by the server: sending it again would be refused again.
                store.dirty = store.dirty - dirty
                _status.update { it.copy(error = e.message, pending = store.dirty.size) }
                return
            }
            _status.update { it.copy(error = e.message, pending = store.dirty.size) }
            if (!toldOffline) {
                toldOffline = true
                Quick.toast(app, str(R.string.server_saved_offline))
            }
            throw e
        }
    }

    /** A choice as the phone keeps it, in the format of the server. */
    private fun localValue(name: String): Any {
        val cache = Cache(app)
        val prefs = cache.prefs
        val switchBot = app.getSharedPreferences("switchbot", Context.MODE_PRIVATE)
        val assistant = app.getSharedPreferences("assistant", Context.MODE_PRIVATE)
        fun objectOf(p: SharedPreferences, key: String) = runCatching { JSONObject(p.getString(key, "{}")!!) }.getOrDefault(JSONObject())
        fun arrayOf(p: SharedPreferences, key: String) = runCatching { JSONArray(p.getString(key, "[]")!!) }.getOrDefault(JSONArray())
        return when (name) {
            "hidden" -> JSONArray(cache.hidden())
            "rooms", "order", "tileSpans" -> objectOf(prefs, name)
            "roomList", "groups", "macros" -> arrayOf(prefs, name)
            "shortcuts" -> prefs.getString("shortcuts", null)?.let { JSONArray(it) } ?: JSONObject.NULL
            "widget" -> prefs.getString("widget", null)?.let { JSONObject(it) } ?: JSONObject.NULL
            "tiles" -> JSONArray(cache.tileDevices().map { it ?: JSONObject.NULL })
            "remoteButtons" -> objectOf(switchBot, "buttons")
            "hiddenRemotes" -> JSONArray(switchBot.getStringSet("hidden", emptySet()).orEmpty())
            "acSettings" -> objectOf(switchBot, "ac")
            "assistantNotes" -> arrayOf(assistant, "notes")
            "assistantHouse" -> assistant.getString("house", "").orEmpty()
            "assistantTaught" -> JSONArray(assistant.getStringSet("taught", emptySet()).orEmpty())
            "notionLists" -> arrayOf(app.getSharedPreferences("notion", Context.MODE_PRIVATE), "lists")
            "notionNotes" -> arrayOf(app.getSharedPreferences("notion", Context.MODE_PRIVATE), "notes")
            "listViews" -> objectOf(app.getSharedPreferences("settings", Context.MODE_PRIVATE), "listViews")
            "team", "weather" -> app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(name, null)?.let { JSONObject(it) } ?: false
            "teamReminder" -> app.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("teamReminder", 0)
            else -> JSONObject.NULL
        }
    }

    // --- linking and unlinking -------------------------------------------------------------------------

    /** Signs in and keeps the session; nothing changes on the phone until [useServerData] or [usePhoneData]. */
    suspend fun link(url: String, username: String, password: String, register: Boolean, invite: String) {
        val address = ServerClient.normalizeUrl(url)
        val token = client.login(address, username.trim(), password, register, invite.trim())
        store.save(address, username.trim(), token)
    }

    /** Starts from what the server has: the copy on the phone is replaced. */
    suspend fun useServerData() {
        store.dirty = emptySet()
        pull(force = true)
    }

    /**
     * Starts from what the phone has: its choices, as a backup, replace those of the account. The
     * Amazon sign-in of the phone goes along, when the server has none.
     */
    suspend fun usePhoneData(backup: JSONObject, refreshToken: String?, marketplace: String?) {
        store.dirty = emptySet()
        call("POST", "/api/backup/import", backup)
        val status = call("GET", "/api/state").optJSONObject("status")
        val signedIn = status?.optJSONObject("alexa")?.optBoolean("loggedIn") == true
        if (!signedIn && refreshToken != null) {
            runCatching { call("POST", "/api/alexa/token", JSONObject().put("refreshToken", refreshToken).put("marketplace", marketplace)) }
        }
        pull(force = true)
    }

    /** The keys the server keeps for the account, for when the phone goes back to doing everything itself. */
    suspend fun keys(): JSONObject = call("GET", "/api/backup/keys")

    suspend fun unlink() {
        val linked = store.config.value
        if (linked != null) runCatching { client.request(linked, "POST", "/api/logout") }
        store.clear()
        _status.value = SyncStatus()
    }

    private fun strings(array: JSONArray?): Set<String> =
        if (array == null) emptySet() else (0 until array.length()).map { array.optString(it) }.toSet()

    private const val CHANNEL = "alerts"
    private const val TILE_SLOTS = 10
    private const val PUSH_DELAY_MS = 600L
    private const val HISTORY_EVERY_MS = 10 * 60_000L

    // The names the choices have on the phone, and on the server. The favorites and the monitor mode
    // are not among them: each device keeps its own.
    private val CACHE_KEYS = mapOf(
        "hidden" to "hidden", "rooms" to "rooms", "roomList" to "roomList",
        "groups" to "groups", "order" to "order", "tileSpans" to "tileSpans", "macros" to "macros",
        "shortcuts" to "shortcuts", "widget" to "widget",
    )
    private val SWITCHBOT_KEYS = mapOf("buttons" to "remoteButtons", "hidden" to "hiddenRemotes", "ac" to "acSettings")
    private val ASSISTANT_KEYS = mapOf("notes" to "assistantNotes", "house" to "assistantHouse", "taught" to "assistantTaught")
    private val NOTION_KEYS = mapOf("lists" to "notionLists", "notes" to "notionNotes")
    private val SETTINGS_KEYS = mapOf("listViews" to "listViews", "team" to "team", "teamReminder" to "teamReminder", "weather" to "weather")
}
