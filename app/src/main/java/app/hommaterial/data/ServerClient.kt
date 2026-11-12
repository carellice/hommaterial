package app.hommaterial.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.hommaterial.R
import app.hommaterial.str
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The home server (the web version of the app on CasaOS) the app keeps its data on, with the account signed in. */
data class ServerConfig(val url: String, val username: String, val token: String)

/**
 * A failure of the server, told with its own words when it [answered]; [code] is what the server
 * says it is about, such as "alexa_login" or "offline".
 */
class ServerException(
    message: String,
    val status: Int = 0,
    val code: String? = null,
    val answered: Boolean = false,
) : Exception(message) {
    /** The server, or what it talks to, cannot be reached (no connection, Tailscale off, proxy down): worth trying again later. */
    val offline: Boolean get() = code == "offline" || !answered && (status == 0 || status in 502..504)
}

/** Encrypts small secrets with an AES key that never leaves the Android Keystore. */
private object SecretBox {
    private const val ALIAS = "hommaterial_server_secret"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(plain.toByteArray())
        return b64(cipher.iv) + ":" + b64(data)
    }

    /** Null when the value cannot be read back, as when restored on another phone. */
    fun decrypt(value: String): String? = runCatching {
        val (iv, data) = value.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data))
    }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}

/**
 * Where the server is and the session of the account, the token encrypted on the phone. It also
 * keeps what the sync needs between runs: the revision the local copy matches, the choices changed
 * here and not yet sent, the last notice shown, what the server said of its accounts.
 */
class ServerStore(context: Context) {
    val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    /** Null while the app keeps its data only on the phone. */
    val config: StateFlow<ServerConfig?> = _config.asStateFlow()

    val lastUrl: String get() = prefs.getString(KEY_URL, "").orEmpty()
    val lastUsername: String get() = prefs.getString(KEY_USER, "").orEmpty()

    /** Server data revision the local copy matches; -1 for none. */
    var revision: Long
        get() = prefs.getLong(KEY_REVISION, -1)
        set(value) = prefs.edit().putLong(KEY_REVISION, value).apply()

    /** The names of the choices changed on the phone and not yet on the server. */
    var dirty: Set<String>
        get() = prefs.getStringSet(KEY_DIRTY, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_DIRTY, value).apply()

    /** The id of the last notice of the server shown on the phone. */
    var lastNotice: Long
        get() = prefs.getLong(KEY_NOTICE, -1)
        set(value) = prefs.edit().putLong(KEY_NOTICE, value).apply()

    /** What the server said last of the accounts it uses: Amazon, SwitchBot, the assistant. */
    var status: JSONObject
        get() = runCatching { JSONObject(prefs.getString(KEY_STATUS, "{}")!!) }.getOrDefault(JSONObject())
        set(value) = prefs.edit().putString(KEY_STATUS, value.toString()).apply()

    fun save(url: String, username: String, token: String) {
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_USER, username)
            .putString(KEY_TOKEN, SecretBox.encrypt(token))
            .putLong(KEY_REVISION, -1)
            .putLong(KEY_NOTICE, -1)
            .remove(KEY_DIRTY)
            .apply()
        _config.value = load()
    }

    /** Back to data on the phone only; address and username stay to fill the form next time. */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_REVISION).remove(KEY_DIRTY).remove(KEY_NOTICE).remove(KEY_STATUS).apply()
        _config.value = null
    }

    private fun load(): ServerConfig? {
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.let { SecretBox.decrypt(it) } ?: return null
        return ServerConfig(url, prefs.getString(KEY_USER, "").orEmpty(), token)
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "username"
        const val KEY_TOKEN = "token"
        const val KEY_REVISION = "revision"
        const val KEY_DIRTY = "dirty"
        const val KEY_NOTICE = "lastNotice"
        const val KEY_STATUS = "status"
    }
}

/** The same HTTP API the web version uses, with the session cookie of the account. */
class ServerClient {
    /** Signs in (or creates the account, with the family code) and returns the session token. */
    suspend fun login(url: String, username: String, password: String, register: Boolean, invite: String = ""): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("username", username).put("password", password).put("invite", invite)
            val connection = open(url, if (register) "/api/register" else "/api/login", "POST", null)
            try {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                if (code !in 200..299) throw failure(connection, code)
                connection.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten()
                    .firstNotNullOfOrNull { Regex("$SESSION=([^;]+)").find(it)?.groupValues?.get(1) }
                    ?: throw ServerException(str(R.string.server_not_ours))
            } catch (e: IOException) {
                throw unreachable(e)
            } finally {
                connection.disconnect()
            }
        }

    /** Sends a request and returns the answer of the server as JSON. */
    suspend fun request(config: ServerConfig, method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = open(config.url, path, method, config.token)
            try {
                if (body != null) {
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw failure(connection, code)
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                if (text.isBlank() || text.trim() == "null") JSONObject() else JSONObject(text)
            } catch (e: IOException) {
                throw unreachable(e)
            } finally {
                connection.disconnect()
            }
        }

    private fun open(base: String, path: String, method: String, token: String?): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            // A custom device may wait minutes between its actions, a TV a minute for the user.
            readTimeout = 6 * 60_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Cookie", "$SESSION=$token")
        }

    private fun failure(connection: HttpURLConnection, code: Int): ServerException {
        val text = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        val message = json?.optString("error")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("code")?.takeIf { it.isNotBlank() && it != "null" }
        return when {
            code == 401 && message == "Accesso richiesto" -> ServerException(str(R.string.server_expired), code, "session")
            // The answer of a server older than the app, which does not know this call yet.
            code == 404 && message == "Non trovato" -> ServerException(str(R.string.server_outdated), code, answered = true)
            message != null -> ServerException(message, code, reason, answered = true)
            code in 300..399 -> ServerException(str(R.string.server_redirect), code)
            else -> ServerException(str(R.string.server_error, code), code)
        }
    }

    private fun unreachable(e: IOException) = ServerException(
        str(if (e is javax.net.ssl.SSLException) R.string.server_tls else R.string.server_unreachable),
    )

    companion object {
        private const val SESSION = "hommaterial_session"

        /** "casaos.local:8096" becomes "https://casaos.local:8096", without the trailing slash. */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        }
    }
}
