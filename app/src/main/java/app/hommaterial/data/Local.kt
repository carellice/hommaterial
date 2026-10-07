package app.hommaterial.data

import android.content.Context
import android.content.SharedPreferences
import app.hommaterial.quick.PowerTile
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

    /** applianceId of the device driven by each quick settings tile, null for the free ones. */
    fun tileDevices(): List<String?> = List(PowerTile.SLOTS) { prefs.getString("tileDevice$it", null) }

    fun putTileDevice(slot: Int, applianceId: String?) =
        prefs.edit().putString("tileDevice$slot", applianceId).apply()

    fun putStates(states: Map<String, DeviceState>) {
        val json = JSONObject().also { json -> states.forEach { (id, st) -> json.put(id, st.toJson()) } }
        prefs.edit().putString("states", json.toString()).apply()
    }
}
