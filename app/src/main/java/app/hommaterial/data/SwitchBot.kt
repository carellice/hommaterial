package app.hommaterial.data

import android.content.Context
import app.hommaterial.R
import app.hommaterial.str
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val HOST = "https://api.switch-bot.com/v1.1"
private const val SUCCESS = 100

/** The signature SwitchBot wants on a request: the token, the time and a one-off value, keyed with the secret. */
internal fun switchBotSign(token: String, secret: String, time: String, nonce: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
    return Base64.getEncoder().encodeToString(mac.doFinal("$token$time$nonce".toByteArray())).uppercase()
}

/** What a remote can do, which decides the buttons the app gives it. */
enum class RemoteKind { TV, AIR_CONDITIONER, CUSTOM, OTHER }

/** The type of the paired LG TVs among the remotes. */
const val LG_TYPE = "LG webOS"

/**
 * An infrared remote set up on a SwitchBot hub, [type] being the name SwitchBot gives its kind;
 * or an LG TV, driven on the network instead, as [lg] tells.
 */
data class Remote(val id: String, val name: String, val type: String) {
    val lg: Boolean get() = id.startsWith(LG_PREFIX)

    val kind: RemoteKind
        // The remotes learnt button by button are named as the ready-made ones, after "DIY".
        get() = when (type.removePrefix("DIY ").lowercase()) {
            "tv", "iptv", "streamer", "set top box", "lg webos" -> RemoteKind.TV
            "air conditioner" -> RemoteKind.AIR_CONDITIONER
            "others" -> RemoteKind.CUSTOM
            else -> RemoteKind.OTHER
        }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type)

    companion object {
        fun fromJson(o: JSONObject) = Remote(o.getString("id"), o.getString("name"), o.getString("type"))
    }
}

/**
 * What an air conditioner was last told. Infrared goes one way: the app cannot ask the appliance,
 * and every command must carry all of this at once. [mode] and [fan] are the numbers SwitchBot uses.
 */
data class AcSetting(val temperature: Int = 24, val mode: Int = 2, val fan: Int = 1) {
    fun toJson(): JSONObject = JSONObject().put("temperature", temperature).put("mode", mode).put("fan", fan)

    companion object {
        fun fromJson(o: JSONObject) = AcSetting(o.optInt("temperature", 24), o.optInt("mode", 2), o.optInt("fan", 1))
    }
}

/**
 * The official SwitchBot API, used only for the infrared remotes, which Alexa barely knows. The
 * token and the secret come from the SwitchBot app and stay on the phone.
 */
class SwitchBot(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("switchbot", Context.MODE_PRIVATE)

    val connected: Boolean get() = prefs.contains("token")

    fun connect(token: String, secret: String) =
        prefs.edit().putString("token", token).putString("secret", secret).apply()

    fun disconnect() = prefs.edit().clear().apply()

    /** The token and the secret, for the backup; null while not linked. */
    fun keys(): Pair<String, String>? =
        prefs.getString("token", null)?.let { it to prefs.getString("secret", "").orEmpty() }

    /** The remotes as last fetched. */
    fun remotes(): List<Remote> = runCatching {
        JSONArray(prefs.getString("remotes", "[]")).mapObjects(Remote::fromJson)
    }.getOrDefault(emptyList())

    suspend fun fetchRemotes(): List<Remote> {
        val list = call("GET", "/devices").optJSONArray("infraredRemoteList") ?: JSONArray()
        val remotes = list.mapObjects {
            Remote(it.getString("deviceId"), it.optString("deviceName"), it.optString("remoteType"))
        }
        prefs.edit().putString("remotes", JSONArray(remotes.map { it.toJson() }).toString()).apply()
        return remotes
    }

    /** Presses a button: one of those SwitchBot knows for the kind of remote, or a [custom] one by its name. */
    suspend fun send(remote: Remote, command: String, parameter: String = "default", custom: Boolean = false) {
        val body = JSONObject()
            .put("command", command)
            .put("parameter", parameter)
            .put("commandType", if (custom) "customize" else "command")
        call("POST", "/devices/${remote.id}/commands", body)
    }

    /**
     * The names of the buttons the user added to each remote, by remote. SwitchBot does not list
     * the buttons learnt by hand, so they are typed in the app as they are named there.
     */
    fun buttons(): Map<String, List<String>> = runCatching {
        val json = JSONObject(prefs.getString("buttons", "{}")!!)
        json.keys().asSequence().associateWith { id ->
            json.getJSONArray(id).let { a -> (0 until a.length()).map(a::getString) }
        }
    }.getOrDefault(emptyMap())

    fun putButtons(buttons: Map<String, List<String>>) {
        val json = JSONObject()
        for ((id, names) in buttons) json.put(id, JSONArray(names))
        prefs.edit().putString("buttons", json.toString()).apply()
    }

    /** The ids of the remotes the user does not want in the list. */
    fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet())!!.toSet()

    fun putHidden(hidden: Set<String>) = prefs.edit().putStringSet("hidden", hidden).apply()

    fun acSettings(): Map<String, AcSetting> = runCatching {
        val json = JSONObject(prefs.getString("ac", "{}")!!)
        json.keys().asSequence().associateWith { AcSetting.fromJson(json.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    fun putAcSetting(id: String, setting: AcSetting) {
        val json = JSONObject()
        for ((key, value) in acSettings() + (id to setting)) json.put(key, value.toJson())
        prefs.edit().putString("ac", json.toString()).apply()
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val token = prefs.getString("token", null) ?: throw Exception(str(R.string.switchbot_not_connected))
            val secret = prefs.getString("secret", "").orEmpty()
            // Every request is signed with the secret over the token, the time and a one-off value.
            val time = System.currentTimeMillis().toString()
            val nonce = UUID.randomUUID().toString()
            val sign = switchBotSign(token, secret, time, nonce)
            val request = Request.Builder()
                .url(HOST + path)
                .header("Authorization", token)
                .header("sign", sign)
                .header("t", time)
                .header("nonce", nonce)
                .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                // A wrong token, a wrong secret and the daily limit all come back as unauthorized.
                if (response.code == 401) throw Exception(str(R.string.switchbot_unauthorized))
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw Exception(str(R.string.switchbot_error, response.code))
                val status = json.optInt("statusCode", response.code)
                if (status != SUCCESS) {
                    throw Exception(str(R.string.switchbot_refused, json.optString("message"), status))
                }
                json.optJSONObject("body") ?: JSONObject()
            }
        }
}
