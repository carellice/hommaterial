package app.hommaterial.data

import android.content.Context
import androidx.annotation.StringRes
import app.hommaterial.R
import app.hommaterial.str
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** The secure port of the webOS remote control; the plain one, 3000, is refused by recent firmware. */
internal const val WEBOS_PORT = 3001
private const val SSDP_TARGET = "urn:lge-com:service:webos-second-screen:1"
private const val REGISTER_ID = "register_0"
// Time for the user to find the TV remote and accept the request on screen.
private const val PAIRING_TIMEOUT_MS = 60_000L
private const val REPLY_TIMEOUT_MS = 10_000L
// How long a connection is kept after the last key, so that the next ones go at once.
private const val IDLE_MS = 30_000L
/** What the id of an LG TV starts with, telling it apart from the remotes of SwitchBot. */
const val LG_PREFIX = "lg:"

/**
 * An LG TV with webOS, paired on the home network. [key] is what the TV gave when the request was
 * accepted on its screen; [pin] the fingerprint of its certificate as first seen, so that another
 * machine answering at the same address is not taken for it; [macs] wake it up from standby.
 * [apps] are the apps and the inputs it has, by id, as last asked.
 */
data class LgTv(
    val id: String,
    val name: String,
    val host: String,
    val key: String,
    val pin: String,
    val macs: List<String> = emptyList(),
    val apps: Map<String, String> = emptyMap(),
) {
    /** The TV among the remotes, next to those of SwitchBot. */
    fun asRemote() = Remote(id, name, LG_TYPE)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("key", key).put("pin", pin)
        .put("macs", JSONArray(macs))
        .put("apps", JSONObject(apps))

    companion object {
        fun fromJson(o: JSONObject) = LgTv(
            o.getString("id"),
            o.getString("name"),
            o.getString("host"),
            o.getString("key"),
            o.getString("pin"),
            o.optJSONArray("macs")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            o.optJSONObject("apps")?.let { a -> a.keys().asSequence().associateWith(a::getString) }.orEmpty(),
        )
    }
}

/** A TV that answered the search on the network, not paired yet; [name] is the one the user gave it, when it tells. */
data class FoundTv(val host: String, val name: String?)

/** A failure of a TV, told with one of the app's texts. */
class LgTvException(@StringRes val text: Int, private vararg val args: Any) : Exception() {
    override val message: String get() = str(text, *args)
}

/** What a command of the app is for webOS: a request with its payload, a key of the remote, or waking it up. */
internal sealed interface WebOsAction {
    data class Call(val uri: String, val payload: JSONObject = JSONObject()) : WebOsAction
    data class Key(val name: String) : WebOsAction
    data object Wake : WebOsAction
}

/**
 * The command as webOS takes it. The names are those of the SwitchBot TV remotes, so that the
 * buttons, the voice and the custom devices work the same with either; the rest are LG's own.
 */
internal fun webOsAction(command: String, parameter: String = "default"): WebOsAction? = when {
    command == "turnOn" -> WebOsAction.Wake
    command == "turnOff" -> WebOsAction.Call("ssap://system/turnOff")
    command == "volumeAdd" -> WebOsAction.Call("ssap://audio/volumeUp")
    command == "volumeSub" -> WebOsAction.Call("ssap://audio/volumeDown")
    command == "channelAdd" -> WebOsAction.Call("ssap://tv/channelUp")
    command == "channelSub" -> WebOsAction.Call("ssap://tv/channelDown")
    // As the key of an infrared remote: it toggles.
    command == "setMute" -> WebOsAction.Key("MUTE")
    command == "SetChannel" && parameter.all { it.isDigit() } && parameter.isNotEmpty() ->
        WebOsAction.Call("ssap://tv/openChannel", JSONObject().put("channelNumber", parameter))
    command == "setVolume" -> parameter.toIntOrNull()?.takeIf { it in 0..100 }
        ?.let { WebOsAction.Call("ssap://audio/setVolume", JSONObject().put("volume", it)) }
    command in setOf("play", "pause", "stop", "rewind", "fastForward") -> WebOsAction.Call("ssap://media.controls/$command")
    command == "toast" -> WebOsAction.Call("ssap://system.notifications/createToast", JSONObject().put("message", parameter))
    command.startsWith("app:") && command.length > 4 ->
        WebOsAction.Call("ssap://system.launcher/launch", JSONObject().put("id", command.substring(4)))
    command.startsWith("input:") && command.length > 6 ->
        WebOsAction.Call("ssap://tv/switchInput", JSONObject().put("inputId", command.substring(6)))
    command.startsWith("key:") && command.substring(4).matches(Regex("[A-Z0-9_]+")) -> WebOsAction.Key(command.substring(4))
    else -> null
}

/** The request asking the TV to be controlled. Without a [key] the TV asks the user; with one it lets the app in. */
internal fun webOsRegister(key: String?): JSONObject {
    // The permissions of the second screen apps, as the open source clients ask for them.
    val permissions = listOf(
        "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
        "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT",
        "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN",
        "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
        "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS",
        "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS",
        "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO",
        "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
        "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS",
    )
    val manifest = JSONObject()
        .put("appVersion", "1.1")
        .put("manifestVersion", 1)
        .put("permissions", JSONArray(permissions))
    val payload = JSONObject()
        .put("forcePairing", false)
        .put("pairingType", "PROMPT")
        .put("manifest", manifest)
    if (key != null) payload.put("client-key", key)
    return JSONObject().put("type", "register").put("id", REGISTER_ID).put("payload", payload)
}

internal fun webOsRequest(id: String, uri: String, payload: JSONObject = JSONObject()): JSONObject =
    JSONObject().put("type", "request").put("id", id).put("uri", uri).put("payload", payload)

/** A key of the remote, as the pointer socket of the TV wants it. */
internal fun webOsKey(name: String) = "type:button\nname:$name\n\n"

/** The hardware addresses in what the TV tells of its network, wired and wireless. */
internal fun webOsMacs(payload: JSONObject): List<String> =
    listOf("wiredInfo", "wifiInfo")
        .mapNotNull { payload.optJSONObject(it)?.optString("macAddress")?.takeIf(::isMac) }
        .map { it.lowercase() }
        .distinct()

private fun isMac(text: String) = text.matches(Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"))

/** The magic packet that wakes up the machine with [mac]: six bytes 0xFF, then the address sixteen times. */
internal fun wakeOnLanPacket(mac: String): ByteArray {
    require(isMac(mac)) { "Not a MAC address: $mac" }
    val address = mac.split(':', '-').map { it.toInt(16).toByte() }
    return ByteArray(6) { 0xFF.toByte() } + List(16) { address }.flatten()
}

/** The fingerprint of a certificate, SHA-256 in hex. */
internal fun certificatePin(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

/** A TV in an answer to the search, null when the answer came from something else. */
internal fun parseSsdp(text: String, host: String): FoundTv? {
    val lines = text.split("\r\n", "\n")
    if (lines.firstOrNull()?.startsWith("HTTP/1.1 200") != true) return null
    val headers = lines.drop(1).mapNotNull { line ->
        line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
    }.toMap()
    if (headers["st"] != SSDP_TARGET) return null
    // LG TVs put the name the user gave them in a header of their own, encoded as in a URL.
    val name = headers["dlnadevicename.lge.com"]
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
    return FoundTv(host, name)
}

/** The apps and the inputs in the answer of the TV, by id, in its order. */
internal fun webOsApps(payload: JSONObject): Map<String, String> =
    (payload.optJSONArray("launchPoints") ?: JSONArray())
        .mapObjects { it.optString("id") to it.optString("title") }
        .filter { (id, title) -> id.isNotEmpty() && title.isNotBlank() }
        .toMap()

internal fun ssdpSearch(): String =
    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SSDP_TARGET\r\n\r\n"

/**
 * The remote control of webOS, over a WebSocket on the home network: no account and no cloud.
 * The TV has a certificate of its own making, so it is trusted as first seen and then by [LgTv.pin].
 */
class LgTvClient(private val http: OkHttpClient, private val port: Int = WEBOS_PORT) {
    // The connections kept open for a while, by TV, and what closes them when no longer used.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val kept = mutableMapOf<String, Kept>()

    private class Kept(val main: Session) {
        var keys: Session? = null
        var idle: Job? = null

        fun close() {
            idle?.cancel()
            keys?.close()
            main.close()
        }
    }

    /** Looks for TVs on the network for [millis], with the search of the second screen apps. */
    suspend fun discover(millis: Long = 3_000): List<FoundTv> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, FoundTv>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val search = ssdpSearch().toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            // Twice, as datagrams may get lost.
            repeat(2) { socket.send(DatagramPacket(search, search.size, group, 1900)) }
            val buffer = ByteArray(2048)
            val end = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val host = packet.address.hostAddress ?: continue
                parseSsdp(String(packet.data, 0, packet.length), host)?.let { found.putIfAbsent(host, it) }
            }
        }
        found.values.toList()
    }

    /**
     * Asks the TV at [host] to be controlled, which it does by asking the user on screen, and
     * returns it paired. [onAsking] is called while the TV waits for the user; [greeting] is
     * shown on screen once it accepted.
     */
    suspend fun pair(host: String, greeting: String, onAsking: () -> Unit = {}): LgTv {
        var seen: String? = null
        val session = open("wss://$host:$port", host, trusting(null) { seen = it })
        try {
            session.send(JSONObject().put("id", "hello").put("type", "hello").put("payload", JSONObject()))
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { session.reply("hello") }
            // Recent webOS tells about itself only before the registration.
            val model = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                session.send(webOsRequest("info", "ssap://system/getSystemInfo"))
                session.reply("info").optJSONObject("payload")?.optString("modelName")
            }?.takeIf { it.isNotBlank() }
            val key = register(session, null, onAsking)
            val macs = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                session.send(webOsRequest("network", "ssap://com.webos.service.connectionmanager/getinfo"))
                session.reply("network").optJSONObject("payload")?.let(::webOsMacs)
            }.orEmpty()
            runCatching { session.call(WebOsAction.Call("ssap://system.notifications/createToast", JSONObject().put("message", greeting))) }
            return LgTv(
                id = LG_PREFIX + UUID.randomUUID(),
                name = model?.let { "LG $it" } ?: "LG TV",
                host = host,
                key = key,
                pin = seen ?: throw LgTvException(R.string.lgtv_unreachable, host),
                macs = macs,
            )
        } finally {
            session.close()
        }
    }

    /** Carries out a [command] of the app on [tv]; see [webOsAction]. */
    suspend fun send(tv: LgTv, command: String, parameter: String = "default") {
        when (val action = webOsAction(command, parameter) ?: throw LgTvException(R.string.lgtv_unknown_command, command)) {
            WebOsAction.Wake -> wake(tv)
            is WebOsAction.Call -> session(tv) { it.main.call(action) }
            is WebOsAction.Key -> session(tv) { connection ->
                // The keys go on a socket of their own, which the TV opens on request.
                val keys = connection.keys?.takeIf { it.open } ?: run {
                    val path = connection.main
                        .call(WebOsAction.Call("ssap://com.webos.service.networkinput/getPointerInputSocket"))
                        .optString("socketPath").takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
                        ?: throw LgTvException(R.string.lgtv_refused, "socketPath")
                    open(path, tv.host, trusting(tv.pin)).also { connection.keys = it }
                }
                keys.socket.send(webOsKey(action.name))
            }
        }
    }

    /** The apps and the inputs of [tv], by id. */
    suspend fun apps(tv: LgTv): Map<String, String> = session(tv) {
        webOsApps(it.main.call(WebOsAction.Call("ssap://com.webos.applicationManager/listLaunchPoints")))
    }

    /** Closes the connections kept open. */
    fun close() {
        val all = synchronized(kept) { kept.values.toList().also { kept.clear() } }
        all.forEach(Kept::close)
    }

    /** Wakes up [tv] from standby, which works only when its network stays on. */
    suspend fun wake(tv: LgTv) = withContext(Dispatchers.IO) {
        if (tv.macs.isEmpty()) throw LgTvException(R.string.lgtv_no_mac)
        val targets = buildList {
            add(InetAddress.getByName("255.255.255.255"))
            // The broadcast of the TV's own network as well, which some routers prefer.
            val address = runCatching { InetAddress.getByName(tv.host) }.getOrNull()
            if (address is Inet4Address) {
                val bytes = address.address.copyOf().also { it[3] = 0xFF.toByte() }
                add(InetAddress.getByAddress(bytes))
            }
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (mac in tv.macs) {
                val packet = wakeOnLanPacket(mac)
                for (target in targets) runCatching { socket.send(DatagramPacket(packet, packet.size, target, 9)) }
            }
        }
    }

    /**
     * Runs [block] on the connection to [tv], kept from before when still open. One request at a
     * time: the answers come on the same socket, and keys are pressed one after the other anyway.
     */
    private suspend fun <T> session(tv: LgTv, block: suspend (Kept) -> T): T = lock.withLock {
        val before = synchronized(kept) { kept[tv.id] }?.takeIf { it.main.open }
        before?.idle?.cancel()
        val connection = before ?: connect(tv)
        try {
            block(connection).also { keep(tv, connection) }
        } catch (e: LgTvException) {
            forget(tv, connection)
            if (before == null || e.text != R.string.lgtv_closed) throw e
            // The TV closed it in the meantime, as it does going to standby: once more on a new one.
            val fresh = connect(tv)
            try {
                block(fresh).also { keep(tv, fresh) }
            } catch (again: Exception) {
                fresh.close()
                throw again
            }
        } catch (e: Exception) {
            forget(tv, connection)
            throw e
        }
    }

    private suspend fun connect(tv: LgTv): Kept {
        val session = open("wss://${tv.host}:$port", tv.host, trusting(tv.pin))
        try {
            register(session, tv.key) { throw LgTvException(R.string.lgtv_pair_again, tv.name) }
        } catch (e: Exception) {
            session.close()
            throw e
        }
        return Kept(session)
    }

    private fun keep(tv: LgTv, connection: Kept) {
        synchronized(kept) { kept[tv.id] = connection }
        connection.idle = scope.launch {
            delay(IDLE_MS)
            lock.withLock { forget(tv, connection) }
        }
    }

    private fun forget(tv: LgTv, connection: Kept) {
        synchronized(kept) { if (kept[tv.id] === connection) kept.remove(tv.id) }
        connection.close()
    }

    /** Registers on [session], returning the key; [onAsking] when the TV asks the user. */
    private suspend fun register(session: Session, key: String?, onAsking: () -> Unit): String {
        session.send(webOsRegister(key))
        val answer = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
            var message = session.reply(REGISTER_ID)
            // A plain response means the TV is asking the user; the outcome comes after.
            while (message.optString("type") == "response") {
                if (message.optJSONObject("payload")?.optString("pairingType") == "PROMPT") onAsking()
                message = session.reply(REGISTER_ID)
            }
            message
        } ?: throw LgTvException(R.string.lgtv_no_answer)
        if (answer.optString("type") != "registered") throw LgTvException(R.string.lgtv_denied)
        return answer.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() }
            ?: key ?: throw LgTvException(R.string.lgtv_denied)
    }

    /** A client for the TV that trusts the certificate with [pin], or any certificate when null, telling it to [seen]. */
    private fun trusting(pin: String?, seen: (String) -> Unit = {}): Trust {
        val trust = Trust(pin, seen)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        trust.client = http.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            // The certificate is not made out to the address of the TV: the pin stands for the name.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        return trust
    }

    private class Trust(private val pin: String?, private val seen: (String) -> Unit) : X509TrustManager {
        lateinit var client: OkHttpClient
        /** Whether the TV showed a certificate other than the one it was paired with. */
        @Volatile var changed = false

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException()

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val got = certificatePin(chain.firstOrNull() ?: throw CertificateException("No certificate"))
            if (pin != null && got != pin) {
                changed = true
                throw CertificateException("The certificate of the TV changed")
            }
            seen(got)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Opens the WebSocket at [url] of the TV at [host]. */
    private suspend fun open(url: String, host: String, trust: Trust): Session = try {
        withTimeout(REPLY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val incoming = Channel<JSONObject>(Channel.UNLIMITED)
                val alive = AtomicBoolean(true)
                val socket = trust.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        continuation.resume(Session(webSocket, incoming, alive))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching { JSONObject(text) }.onSuccess { incoming.trySend(it) }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        alive.set(false)
                        webSocket.close(1000, null)
                        incoming.close()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        alive.set(false)
                        if (continuation.isActive) continuation.resumeWithException(t) else incoming.close(t)
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        throw LgTvException(if (trust.changed) R.string.lgtv_changed else R.string.lgtv_unreachable, host)
    }

    private class Session(val socket: WebSocket, private val incoming: Channel<JSONObject>, private val alive: AtomicBoolean) {
        private var count = 0

        /** Whether neither side closed it. */
        val open: Boolean get() = alive.get()

        fun send(message: JSONObject) {
            socket.send(message.toString())
        }

        /** The next message about the request [id]; those about others are let go. */
        suspend fun reply(id: String): JSONObject {
            while (true) {
                val message = try {
                    incoming.receive()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw LgTvException(R.string.lgtv_closed)
                }
                if (message.optString("id") == id) return message
            }
        }

        /** Sends a request and returns the payload of its answer, if the TV did it. */
        suspend fun call(action: WebOsAction.Call): JSONObject {
            val id = "call_${count++}"
            send(webOsRequest(id, action.uri, action.payload))
            val answer = withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply(id) } ?: throw LgTvException(R.string.lgtv_no_answer)
            val payload = answer.optJSONObject("payload") ?: JSONObject()
            if (answer.optString("type") == "error" || !payload.optBoolean("returnValue", true)) {
                throw LgTvException(R.string.lgtv_refused, answer.optString("error").ifEmpty { payload.optString("errorText") })
            }
            return payload
        }

        fun close() {
            alive.set(false)
            socket.close(1000, null)
        }
    }
}

/** The TVs paired on this phone. Their keys stay here, as those of SwitchBot. */
class LgTvs(context: Context) {
    private val prefs = context.getSharedPreferences("lgtv", Context.MODE_PRIVATE)

    fun all(): List<LgTv> = runCatching {
        JSONArray(prefs.getString("tvs", "[]")).mapObjects(LgTv::fromJson)
    }.getOrDefault(emptyList())

    /** Keeps [tv], in its place when known, else in the place of one paired before at the same address. */
    fun put(tv: LgTv): List<LgTv> {
        val all = all()
        if (all.any { it.id == tv.id }) return save(all.map { if (it.id == tv.id) tv else it })
        return save(all.filter { it.host != tv.host } + tv)
    }

    fun remove(id: String): List<LgTv> = save(all().filter { it.id != id })

    /** Keeps only [tvs], as those of a backup. */
    fun replaceAll(tvs: List<LgTv>): List<LgTv> = save(tvs.distinctBy { it.id })

    private fun save(tvs: List<LgTv>): List<LgTv> {
        prefs.edit().putString("tvs", JSONArray(tvs.map { it.toJson() }).toString()).apply()
        return tvs
    }
}
