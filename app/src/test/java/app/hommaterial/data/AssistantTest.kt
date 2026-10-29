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

    private fun plan(json: String) = parsePlan(JSONObject(json), catalog()) { g -> listOf(lamp, plug).filter { it.applianceId in g.devices } }

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
    fun aRequestBecomesSteps() {
        val p = plan(
            """{"actions":[{"target":"r1","do":"press","command":"volumeAdd","times":5},
            {"target":"d1","do":"brightness","value":"30%"},{"target":"d1","do":"color","value":"blue"},
            {"target":"g1","do":"off"},{"target":"m1","do":"run"},{"do":"wait","value":"3"},
            {"target":"r2","do":"ac","value":"22,2,1,on"},{"target":"R3","do":"press","command":"f1"},
            {"target":"r1","do":"on"}],"reply":"Fatto","question":""}""",
        )
        assertEquals(0, p.ignored)
        assertEquals("Fatto", p.reply)
        assertNull(p.question)
        assertEquals(AssistantStep.Press(tv, AssistantKey("volumeAdd", false, "Volume su"), 5), p.steps[0])
        assertEquals(AssistantStep.Brightness(lamp, 30), p.steps[1])
        assertEquals("blue", (p.steps[2] as AssistantStep.Color).choice.alexaName)
        assertEquals(AssistantStep.GroupPower(group, listOf(lamp, plug), false), p.steps[3])
        assertEquals(AssistantStep.Run(macro), p.steps[4])
        assertEquals(AssistantStep.Wait(3), p.steps[5])
        assertEquals(AssistantStep.Ac(clima, AcSetting(22, 2, 1), true), p.steps[6])
        assertEquals(AssistantStep.Press(fan, AssistantKey("F1", true, "F1"), 1), p.steps[7])
        assertEquals(AssistantStep.Press(tv, AssistantKey("turnOn", false, "Accendi"), 1), p.steps[8])
        assertEquals(3, p.switched)
    }

    @Test
    fun whatModelsWriteInsteadIsUnderstood() {
        val p = plan(
            """{"actions":[{"target":"r1 (TV camera)","action":"press","command":"volumeAdd=Volume su","times":"5"},
            {"target":"TV camera","do":"volumeAdd","times":2},{"device":"d1","do":"turn_on"},
            {"target":"r1","do":"press","command":"Netflix"},{"target":"d1","do":"brightness","value":30},
            {"target":"m1","do":"start"},{"do":"pause","seconds":2}],"reply":"ok"}""",
        )
        assertEquals(emptyList<String>(), p.rejected)
        val volume = AssistantKey("volumeAdd", false, "Volume su")
        assertEquals(AssistantStep.Press(tv, volume, 5), p.steps[0])
        assertEquals(AssistantStep.Press(tv, volume, 2), p.steps[1])
        assertEquals(AssistantStep.Power(lamp, true), p.steps[2])
        assertEquals("app:netflix", (p.steps[3] as AssistantStep.Press).key.command)
        assertEquals(AssistantStep.Brightness(lamp, 30), p.steps[4])
        assertEquals(AssistantStep.Run(macro), p.steps[5])
        assertEquals(AssistantStep.Wait(2), p.steps[6])
        assertTrue(plan("""{"actions":[{"target":"d9","do":"on"}]}""").rejected.single().contains("d9"))
    }

    @Test
    fun whatDoesNotExistIsLeftOut() {
        val p = plan(
            """{"actions":[{"target":"d9","do":"on"},{"target":"d2","do":"brightness","value":"50"},
            {"target":"r1","do":"press","command":"volumeAdd","times":500},{"target":"r1","do":"press","command":"HACK"},
            {"target":"d1","do":"color","value":"plaid"},{"target":"r2","do":"ac","value":"40,2,1,on"},
            {"target":"d3","do":"on"},{"do":"wait","value":"600"},{"target":"r3","do":"on"},"x"],
            "reply":"","question":"Quale TV?"}""",
        )
        assertTrue(p.steps.isEmpty())
        assertEquals(10, p.ignored)
        assertEquals("Quale TV?", p.question)
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
    fun promptsCarryTheHome() {
        val prompt = commandPrompt(catalog(), "Flavio è mio figlio", "Italian")
        assertTrue(prompt.contains("Flavio è mio figlio"))
        assertTrue(prompt.contains("d1: device"))
        assertTrue(prompt.contains("in Italian"))
        assertTrue(questionsPrompt(catalog(), "", "Italian").contains("[NEW]"))
    }

    private val server = MockWebServer()
    private val account = "0123456789abcdef0123456789abcdef"

    @After
    fun stop() = server.shutdown()

    private fun client() = WorkersAi(OkHttpClient(), server.url("/client/v4").toString().removeSuffix("/"))

    @Test
    fun answersComeBackAsJson() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"errors":[],"result":{"response":{"actions":[],"reply":"Ciao"}}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"errors":[],"result":{"response":"Sure! {\"questions\":[]}"}}"""))
        val answer = client().command(account, "tok", ASSISTANT_MODELS[0], "sys", listOf("user" to "ciao"))
        assertEquals("Ciao", answer.getString("reply"))
        val request = server.takeRequest()
        assertEquals("/client/v4/accounts/$account/ai/run/${ASSISTANT_MODELS[0]}", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals(0, client().questions(account, "tok", ASSISTANT_MODELS[0], "sys").getJSONArray("questions").length())
    }

    @Test
    fun aSchemaTheModelCannotKeepIsDroppedOnce() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"errors":[{"code":5006,"message":"JSON Mode couldn't be met"}]}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"result":{"response":"{\"actions\":[],\"reply\":\"ok\"}"}}"""))
        assertEquals("ok", client().command(account, "tok", ASSISTANT_MODELS[0], "sys", emptyList()).getString("reply"))
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
