package app.hommaterial.data

import app.hommaterial.R
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LgTvTest {
    @Test
    fun commandsAreThoseOfTheSwitchBotRemotes() {
        assertEquals(WebOsAction.Wake, webOsAction("turnOn"))
        assertEquals("ssap://system/turnOff", (webOsAction("turnOff") as WebOsAction.Call).uri)
        assertEquals("ssap://audio/volumeUp", (webOsAction("volumeAdd") as WebOsAction.Call).uri)
        assertEquals("ssap://tv/channelDown", (webOsAction("channelSub") as WebOsAction.Call).uri)
        assertEquals(WebOsAction.Key("MUTE"), webOsAction("setMute"))
        val channel = webOsAction("SetChannel", "105") as WebOsAction.Call
        assertEquals("ssap://tv/openChannel", channel.uri)
        assertEquals("105", channel.payload.getString("channelNumber"))
        assertNull(webOsAction("SetChannel", "uno"))
    }

    @Test
    fun commandsOfLgAreReadFromTheirName() {
        val app = webOsAction("app:netflix") as WebOsAction.Call
        assertEquals("ssap://system.launcher/launch", app.uri)
        assertEquals("netflix", app.payload.getString("id"))
        assertEquals("HDMI_2", (webOsAction("input:HDMI_2") as WebOsAction.Call).payload.getString("inputId"))
        assertEquals(WebOsAction.Key("UP"), webOsAction("key:UP"))
        assertNull(webOsAction("key:up\nname:POWER"))
        assertNull(webOsAction("setVolume", "120"))
        assertEquals(30, (webOsAction("setVolume", "30") as WebOsAction.Call).payload.getInt("volume"))
        assertNull(webOsAction("selfDestruct"))
    }

    @Test
    fun theRegistrationCarriesTheKeyOnlyOnceGiven() {
        val first = webOsRegister(null)
        assertEquals("register", first.getString("type"))
        assertEquals("PROMPT", first.getJSONObject("payload").getString("pairingType"))
        assertFalse(first.getJSONObject("payload").has("client-key"))
        val permissions = first.getJSONObject("payload").getJSONObject("manifest").getJSONArray("permissions").toString()
        assertTrue("CONTROL_POWER" in permissions)
        assertEquals("abc", webOsRegister("abc").getJSONObject("payload").getString("client-key"))
    }

    @Test
    fun theMagicPacketRepeatsTheAddressSixteenTimes() {
        val packet = wakeOnLanPacket("A8:23:FE:01:02:0c")
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        val mac = byteArrayOf(0xA8.toByte(), 0x23, 0xFE.toByte(), 0x01, 0x02, 0x0C)
        for (i in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
        assertArrayEquals(packet, wakeOnLanPacket("a8-23-fe-01-02-0C"))
        assertThrows { wakeOnLanPacket("a8:23:fe") }
    }

    @Test
    fun theSearchFindsOnlyLgTvs() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.20:1810/\r\n" +
            "SERVER: WebOS/4.1.0 UPnP/1.0\r\nST: urn:lge-com:service:webos-second-screen:1\r\n" +
            "DLNADeviceName.lge.com: %5BLG%5D%20Sala%20OLED\r\n\r\n"
        assertEquals(FoundTv("192.168.1.20", "[LG] Sala OLED"), parseSsdp(answer, "192.168.1.20"))
        val unnamed = answer.replace(Regex("DLNADeviceName[^\r]*\r\n"), "")
        assertEquals(FoundTv("192.168.1.20", null), parseSsdp(unnamed, "192.168.1.20"))
        assertNull(parseSsdp(answer.replace("webos-second-screen", "dial"), "192.168.1.20"))
        assertNull(parseSsdp("NOTIFY * HTTP/1.1\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n", "x"))
    }

    @Test
    fun theAddressesComeFromBothNetworks() {
        val info = JSONObject()
            .put("wiredInfo", JSONObject().put("macAddress", "A8:23:FE:01:02:03"))
            .put("wifiInfo", JSONObject().put("macAddress", "a8:23:fe:01:02:04"))
        assertEquals(listOf("a8:23:fe:01:02:03", "a8:23:fe:01:02:04"), webOsMacs(info))
        assertEquals(emptyList<String>(), webOsMacs(JSONObject().put("wifiInfo", JSONObject().put("macAddress", "none"))))
    }

    // A TV on a local socket, answering as webOS does, with a certificate of its own making.

    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().build()

    @Before
    fun start() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
    }

    @After
    fun stop() {
        // The connections kept for the next keys would hold the fake TV open.
        clients.forEach(LgTvClient::close)
        server.shutdown()
    }

    private val clients = mutableListOf<LgTvClient>()

    private fun lgClient() = LgTvClient(client, server.port).also { clients += it }

    private fun paired(pin: String = certificatePin(certificate.certificate)) =
        LgTv("lg:1", "Sala", server.hostName, "abc", pin, listOf("a8:23:fe:01:02:03"))

    private fun answer(type: String, id: String, payload: JSONObject = JSONObject().put("returnValue", true)) =
        JSONObject().put("type", type).put("id", id).put("payload", payload)

    /** Closes when asked, as a TV does. */
    private open class Closing : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private inner class FakeTv(private val deny: Boolean = false) : Closing() {
        val received = LinkedBlockingQueue<JSONObject>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received.add(message)
            val id = message.optString("id")
            val replies = when (message.optString("type")) {
                "hello" -> listOf(answer("hello", id, JSONObject()))
                "register" -> if (message.getJSONObject("payload").has("client-key")) {
                    listOf(answer("registered", id, JSONObject().put("client-key", "abc")))
                } else {
                    listOf(
                        answer("response", id, JSONObject().put("pairingType", "PROMPT").put("returnValue", true)),
                        if (deny) JSONObject().put("type", "error").put("id", id).put("error", "403 User denied access")
                        else answer("registered", id, JSONObject().put("client-key", "abc")),
                    )
                }
                else -> listOf(
                    when (message.optString("uri")) {
                        "ssap://system/getSystemInfo" -> answer("response", id, JSONObject().put("modelName", "OLED55C1"))
                        "ssap://com.webos.service.connectionmanager/getinfo" -> answer(
                            "response", id,
                            JSONObject().put("returnValue", true)
                                .put("wiredInfo", JSONObject().put("macAddress", "A8:23:FE:01:02:03")),
                        )
                        "ssap://com.webos.service.networkinput/getPointerInputSocket" -> answer(
                            "response", id,
                            JSONObject().put("returnValue", true)
                                .put("socketPath", "wss://${server.hostName}:${server.port}/pointer"),
                        )
                        "ssap://tv/channelUp" -> JSONObject().put("type", "error").put("id", id).put("error", "500 Application error")
                        else -> answer("response", id)
                    },
                )
            }
            for (reply in replies) webSocket.send(reply.toString())
        }
    }

    private fun tv(fake: FakeTv) = server.enqueue(MockResponse().withWebSocketUpgrade(fake))

    @Test
    fun pairingKeepsTheKeyTheCertificateAndTheAddresses() = runBlocking {
        val fake = FakeTv().also(::tv)
        var asked = false
        val tv = lgClient().pair(server.hostName, "Ciao") { asked = true }
        assertTrue(asked)
        assertEquals("abc", tv.key)
        assertEquals("LG OLED55C1", tv.name)
        assertEquals(certificatePin(certificate.certificate), tv.pin)
        assertEquals(listOf("a8:23:fe:01:02:03"), tv.macs)
        assertTrue(tv.id.startsWith("lg:"))
        val uris = fake.received.map { it.optString("uri") }
        assertTrue("ssap://system.notifications/createToast" in uris)
    }

    @Test
    fun aRefusalOnTheTvIsTold() = runBlocking {
        tv(FakeTv(deny = true))
        val error = catching { lgClient().pair(server.hostName, "Ciao") }
        assertEquals(R.string.lgtv_denied, error.text)
    }

    @Test
    fun aCommandGoesWithTheKey() = runBlocking {
        val fake = FakeTv().also(::tv)
        lgClient().send(paired(), "volumeAdd")
        val register = fake.received.poll(5, TimeUnit.SECONDS)!!
        assertEquals("abc", register.getJSONObject("payload").getString("client-key"))
        assertEquals("ssap://audio/volumeUp", fake.received.poll(5, TimeUnit.SECONDS)!!.getString("uri"))
    }

    @Test
    fun keysInARowGoOnOneConnection() = runBlocking {
        val fake = FakeTv().also(::tv)
        val keys = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : Closing() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                keys.add(text)
            }
        }))
        val lg = lgClient()
        lg.send(paired(), "volumeAdd")
        lg.send(paired(), "key:UP")
        lg.send(paired(), "key:DOWN")
        assertEquals("type:button\nname:UP\n\n", keys.poll(5, TimeUnit.SECONDS))
        assertEquals("type:button\nname:DOWN\n\n", keys.poll(5, TimeUnit.SECONDS))
        // One registration and one socket for the keys, whatever the number of presses.
        assertEquals(2, server.requestCount)
        assertEquals(1, fake.received.count { it.optString("type") == "register" })
    }

    @Test
    fun theAppsOfTheTvAreAsked() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : Closing() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                val id = message.optString("id")
                val reply = if (message.optString("type") == "register") {
                    answer("registered", id, JSONObject().put("client-key", "abc"))
                } else {
                    val points = org.json.JSONArray()
                        .put(JSONObject().put("id", "netflix").put("title", "Netflix"))
                        .put(JSONObject().put("id", "com.webos.app.hdmi1").put("title", "HDMI 1"))
                    answer("response", id, JSONObject().put("returnValue", true).put("launchPoints", points))
                }
                webSocket.send(reply.toString())
            }
        }))
        assertEquals(mapOf("netflix" to "Netflix", "com.webos.app.hdmi1" to "HDMI 1"), lgClient().apps(paired()))
    }

    @Test
    fun theSourcesComeWithTheNamesTheTvShows() {
        val payload = JSONObject().put(
            "devices",
            org.json.JSONArray()
                .put(JSONObject().put("id", "HDMI_1").put("label", "PlayStation"))
                .put(JSONObject().put("id", "HDMI_2").put("label", ""))
                .put(JSONObject().put("id", "").put("label", "Nothing")),
        )
        assertEquals(mapOf("HDMI_1" to "PlayStation", "HDMI_2" to "HDMI_2"), webOsInputs(payload))
        val switch = webOsAction("input:HDMI_1") as WebOsAction.Call
        assertEquals("ssap://tv/switchInput", switch.uri)
        assertEquals("HDMI_1", switch.payload.getString("inputId"))
    }

    @Test
    fun aTvIsKeptWithItsAppsAndIsATvAmongTheRemotes() {
        val tv = paired().copy(apps = mapOf("netflix" to "Netflix"), inputs = mapOf("HDMI_1" to "HDMI 1"))
        assertEquals(tv, LgTv.fromJson(JSONObject(tv.toJson().toString())))
        // Saved before the apps and the sources were kept, a TV is read without them.
        val older = JSONObject(tv.toJson().toString()).apply { remove("apps"); remove("inputs") }
        assertEquals(tv, LgTv.fromJson(older).copy(apps = tv.apps, inputs = tv.inputs))
        val remote = tv.asRemote()
        assertTrue(remote.lg)
        assertEquals(RemoteKind.TV, remote.kind)
        assertFalse(Remote("abc", "Sala", "TV").lg)
    }

    @Test
    fun aCommandTheTvRefusesIsTold() = runBlocking {
        tv(FakeTv())
        assertEquals(R.string.lgtv_refused, catching { lgClient().send(paired(), "channelAdd") }.text)
    }

    @Test
    fun keysGoOnTheirOwnSocket() = runBlocking {
        tv(FakeTv())
        val keys = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : Closing() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                keys.add(text)
            }
        }))
        lgClient().send(paired(), "key:UP")
        assertEquals("type:button\nname:UP\n\n", keys.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun anotherCertificateAtTheSameAddressIsRefused() = runBlocking {
        tv(FakeTv())
        assertEquals(R.string.lgtv_changed, catching { lgClient().send(paired(pin = "00".repeat(32)), "volumeAdd") }.text)
    }

    @Test
    fun aTvThatIsOffIsToldAsUnreachable() = runBlocking {
        val port = server.port
        server.shutdown()
        val error = catching { LgTvClient(client, port).send(paired(), "volumeAdd") }
        assertEquals(R.string.lgtv_unreachable, error.text)
    }

    @Test
    fun unknownCommandsAreRefusedBeforeConnecting() = runBlocking {
        assertEquals(R.string.lgtv_unknown_command, catching { lgClient().send(paired(), "selfDestruct") }.text)
        assertEquals(0, server.requestCount)
    }

    private suspend fun catching(block: suspend () -> Unit): LgTvException {
        try {
            block()
        } catch (e: LgTvException) {
            return e
        }
        fail("Expected an LgTvException")
        throw AssertionError()
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected an IllegalArgumentException")
    }
}
