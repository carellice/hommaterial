package app.hommaterial.data

import android.content.Context
import android.content.SharedPreferences
import app.hommaterial.quick.PowerTile
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** One client for the whole process, so that the app and what runs outside it share the session. */
class Alexa private constructor(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val auth = AlexaAuth(context, http)
    val api = AlexaApi(auth, http)

    companion object {
        @Volatile
        private var instance: Alexa? = null

        fun get(context: Context): Alexa = instance ?: synchronized(this) {
            instance ?: Alexa(context.applicationContext).also { instance = it }
        }
    }
}

/** What is remembered on the phone about the account: devices, last known states, choices. */
class Cache(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun devices(): List<Device> = runCatching {
        JSONArray(prefs.getString("devices", "[]")).mapObjects(Device::fromJson)
    }.getOrDefault(emptyList())

    fun states(): Map<String, DeviceState> = runCatching {
        val json = JSONObject(prefs.getString("states", "{}")!!)
        json.keys().asSequence().associateWith { DeviceState.fromJson(json.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet())!!.toSet()

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()

    /**
     * The room the user moved each device to, by applianceId: a name, or an empty text for no room.
     * Devices that are not here stay in their Alexa room.
     */
    fun rooms(): Map<String, String> = runCatching {
        val json = JSONObject(prefs.getString("rooms", "{}")!!)
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    fun putRooms(rooms: Map<String, String>) = prefs.edit().putString("rooms", JSONObject(rooms).toString()).apply()

    /** The rooms the user created, which exist even while no device is in them. */
    fun roomList(): List<String> = strings("roomList")

    fun putRoomList(rooms: List<String>) = prefs.edit().putString("roomList", JSONArray(rooms).toString()).apply()

    fun groups(): List<Group> = runCatching {
        JSONArray(prefs.getString("groups", "[]")).mapObjects(Group::fromJson)
    }.getOrDefault(emptyList())

    fun putGroups(groups: List<Group>) =
        prefs.edit().putString("groups", JSONArray(groups.map { it.toJson() }).toString()).apply()

    /**
     * What is listed under the app icon, in order: "device:" or "group:" followed by an id. Null
     * until the user chooses, and the favorites are listed instead.
     */
    fun shortcuts(): List<String>? = if (prefs.contains("shortcuts")) strings("shortcuts") else null

    fun putShortcuts(shortcuts: List<String>?) {
        val editor = prefs.edit()
        if (shortcuts == null) {
            editor.remove("shortcuts")
        } else {
            editor.putString("shortcuts", JSONArray(shortcuts).toString())
        }
        editor.apply()
    }

    fun widget(): WidgetConfig = runCatching {
        WidgetConfig.fromJson(JSONObject(prefs.getString("widget", "{}")!!))
    }.getOrDefault(WidgetConfig())

    fun putWidget(config: WidgetConfig) = prefs.edit().putString("widget", config.toJson().toString()).apply()

    private fun strings(key: String): List<String> = runCatching {
        val json = JSONArray(prefs.getString(key, "[]"))
        (0 until json.length()).map { json.getString(it) }
    }.getOrDefault(emptyList())

    /** applianceId of the device driven by each quick settings tile, null for the free ones. */
    fun tileDevices(): List<String?> = List(PowerTile.SLOTS) { prefs.getString("tileDevice$it", null) }

    fun putTileDevice(slot: Int, applianceId: String?) =
        prefs.edit().putString("tileDevice$slot", applianceId).apply()

    fun putStates(states: Map<String, DeviceState>) {
        val json = JSONObject().also { json -> states.forEach { (id, st) -> json.put(id, st.toJson()) } }
        prefs.edit().putString("states", json.toString()).apply()
    }
}
