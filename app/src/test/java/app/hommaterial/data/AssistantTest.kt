package app.hommaterial.data

import app.hommaterial.R
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AssistantTest {
    private val lamp = Device("lamp", "e1", "Luce scrivania", "LIGHT", "Camera di Flavio", true, true, false, hasColor = true)
    private val plug = Device("plug", "e2", "Presa 2", "SMARTPLUG", null, true, false, false)
    private val sensor = Device("temp", "e3", "Termometro", "TEMPERATURE_SENSOR", "Soggiorno", false, false, true)
    private val tv = Remote("lg:1", "TV camera", LG_TYPE)
    private val clima = Remote("ac1", "Clima", "Air Conditioner")
    private val fan = Remote("fan1", "Ventola", "DIY Others")
    private val group = Group("g", "Luci", setOf("lamp", "plug"))
    private val macro = Macro("m", "Film")

    private fun catalog(notes: List<AssistantNote> = emptyList(), taught: Set<String> = emptySet()) = AssistantCatalog.build(
        devices = listOf(lamp, plug, sensor),
        states = mapOf("lamp" to DeviceState(power = true, brightness = 40), "temp" to DeviceState(temperature = 21.5)),
        groups = listOf(group),
        macros = listOf(macro to "Soggiorno"),
        macroKey = { "macro:${it.id}" },
        remotes = listOf(
            Triple(tv, "Camera di Flavio", listOf(AssistantKey("turnOn", false, "Accendi"), AssistantKey("volumeAdd", false, "Volume su"), AssistantKey("app:netflix", false, "Netflix"))),
            Triple(clima, null, listOf(AssistantKey("turnOn", false, "Accendi"), AssistantKey("turnOff", false, "Spegni"))),
            Triple(fan, null, listOf(AssistantKey("F1", true, "F1"))),
        ),
        remoteKey = { "remote:${it.id}" },
        notes = notes,
        taught = taught,
    )

    @Test
    fun theHomeIsWrittenWithShortIds() {
        val text = catalog(listOf(AssistantNote("plug", "Cosa c'è attaccato?", "La lampada del comodino")), taught = setOf("lamp")).describe(true)
        assertTrue(text, text.contains("d1: device \"Luce scrivania\" in \"Camera di Flavio\", light; can on/off, brightness, color; now on 40%"))
        assertTrue(text.contains("d2: device \"Presa 2\""))
        assertTrue(text.contains("[NEW] — owner's notes: Cosa c'è attaccato? La lampada del comodino"))
        assertTrue(text.contains("now 21.5°C") || text.contains("now 21,5°C"))
        assertTrue(text.contains("g1: group \"Luci\" of d1, d2"))
        assertTrue(text.contains("m1: custom device \"Film\" in \"Soggiorno\""))
        assertTrue(text.contains("r1: remote \"TV camera\" in \"Camera di Flavio\", LG TV; keys: turnOn=Accendi, volumeAdd=Volume su, app:netflix=Netflix"))
        assertTrue(text.contains("r2: remote \"Clima\", Air Conditioner; use \"ac\""))
        assertTrue(text.contains("keys: F1"))
        assertFalse(text.contains("lg:1") || text.contains("fan1") || text.contains("ac1"))
        assertFalse(catalog().describe(false).contains("now"))
    }

    @Test
    fun questionsAreOnlyAboutWhatExists() {
        val questions = parseQuestions(
            JSONObject("""{"questions":[{"target":"d2","question":"Cosa accende la Presa 2?"},{"target":"r3/F1","question":"Cosa fa F1?"},{"target":"x5","question":"?"},{"target":"d1","question":" "}]}"""),
            catalog(),
        )
        assertEquals(listOf("plug", "remote:fan1"), questions.map { it.target.key })
        assertEquals("Cosa fa F1?", questions[1].question)
    }

    @Test
    fun jsonIsFoundInText() {
        assertEquals(1, extractJson("<think>{no}</think>Ecco: ```json\n{\"a\":1}\n```")!!.getInt("a"))
        assertNull(extractJson("niente"))
    }

    @Test
    fun theNamesToCorrectAreWhatVoiceCanReach() {
        val names = catalog(listOf(AssistantNote("plug", "Cosa c'è attaccato?", "La lampada del comodino"))).names()
        assertTrue(names, names.contains("\"Luce scrivania\" (device, in \"Camera di Flavio\")"))
        assertTrue(names.contains("\"Presa 2\" (device) — owner's notes: Cosa c'è attaccato? La lampada del comodino"))
        assertTrue(names.contains("\"Film\" (custom device, in \"Soggiorno\")"))
        assertTrue(names.contains("\"TV camera\" (remote, in \"Camera di Flavio\")"))
        assertTrue(names.contains("Rooms: \"Camera di Flavio\", \"Soggiorno\""))
        // A sensor is not switched, a group is not reached by voice, and the keys are not names.
        assertFalse(names.contains("Termometro") || names.contains("Luci") || names.contains("Volume su"))
    }

    @Test
    fun promptsCarryTheHome() {
        val prompt = correctionPrompt(catalog(), "Flavio è mio figlio")
        assertTrue(prompt.contains("Flavio è mio figlio"))
        assertTrue(prompt.contains("\"TV camera\" (remote"))
        assertTrue(questionsPrompt(catalog(), "", "Italian").contains("[NEW]"))
    }

    private val server = MockWebServer()
    private val account = "0123456789abcdef0123456789abcdef"

    @After
    fun stop() = server.shutdown()

    private fun client() = WorkersAi(OkHttpClient(), server.url("/client/v4").toString().removeSuffix("/"))

    @Test
    fun answersComeBackAsJson() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"errors":[],"result":{"response":{"text":" accendi TV Flavio "}}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"errors":[],"result":{"response":"Sure! {\"questions\":[]}"}}"""))
        val answer = client().correct(account, "tok", ASSISTANT_MODELS[0], "sys", listOf("accendi televisione flavio", "accendi tv flavia"))
        assertEquals("accendi TV Flavio", answer)
        val request = server.takeRequest()
        assertEquals("/client/v4/accounts/$account/ai/run/${ASSISTANT_MODELS[0]}", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("1. accendi televisione flavio\n2. accendi tv flavia", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(0, client().questions(account, "tok", ASSISTANT_MODELS[0], "sys").getJSONArray("questions").length())
    }

    @Test
    fun aSchemaTheModelCannotKeepIsDroppedOnce() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"errors":[{"code":5006,"message":"JSON Mode couldn't be met"}]}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"result":{"response":"{\"text\":\"ok\"}"}}"""))
        assertEquals("ok", client().correct(account, "tok", ASSISTANT_MODELS[0], "sys", listOf("ok")))
        server.takeRequest()
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("response_format"))
    }

    @Test
    fun failuresAreToldApart() = runBlocking {
        suspend fun fails(code: Int, body: String, account: String = this@AssistantTest.account): Int {
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            try {
                client().test(account, "tok", ASSISTANT_MODELS[0])
                fail()
            } catch (e: AssistantException) {
                return e.text
            }
            return 0
        }
        assertEquals(R.string.assistant_unauthorized, fails(401, """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""))
        assertEquals(R.string.assistant_limit, fails(429, """{"success":false,"errors":[{"code":3036,"message":"daily free allocation of 10,000 neurons"}]}"""))
        assertEquals(R.string.assistant_bad_account, fails(404, """{"success":false,"errors":[{"code":7003,"message":"Could not route"}]}"""))
        assertEquals(R.string.assistant_error, fails(500, "boom"))
        // A wrong account id is not even sent.
        assertEquals(R.string.assistant_bad_account, fails(200, "{}", account = "nope"))
    }
}
