package app.hommaterial.voice

import app.hommaterial.data.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceParserTest {
    private fun device(id: String, name: String, room: String?, hasPower: Boolean = true) =
        Device(id, "e$id", name, "LIGHT", room, hasPower, hasBrightness = false, isSensor = !hasPower)

    private val devices = listOf(
        device("1", "Luce cucina", "Cucina"),
        device("2", "Presa forno", "Cucina"),
        device("3", "Stufa", "Salotto"),
        device("4", "Lampada", "Salotto"),
        device("5", "Termometro", "Salotto", hasPower = false),
        device("6", "Stufa bagno", "Bagno"),
        device("7", "Luce", "Studio"),
    )

    private fun run(vararg heard: String) = parseVoice(heard.toList(), devices)

    private fun ran(result: VoiceResult) = (result as VoiceResult.Run).command

    private fun asked(result: VoiceResult) = (result as VoiceResult.Ask).options

    @Test
    fun exactNameRunsAtOnce() {
        val command = ran(run("accendi la lampada"))
        assertEquals("Lampada", command.devices.single().name)
        assertTrue(command.on)
    }

    @Test
    fun switchesOff() {
        val command = ran(run("Spegni la presa del forno, per favore"))
        assertEquals("Presa forno", command.devices.single().name)
        assertEquals(false, command.on)
    }

    @Test
    fun accentsAndPluralsDoNotMatter() {
        assertEquals("Luce cucina", ran(run("Accèndi le luci della cucina")).devices.single().name)
    }

    @Test
    fun shorterNameWinsWhenSaidAlone() {
        assertEquals("Stufa", ran(run("spegni la stufa")).devices.single().name)
    }

    @Test
    fun roomTellsApartDevicesWithTheSameName() {
        assertEquals("Luce", ran(run("accendi la luce in studio")).devices.single().name)
    }

    @Test
    fun partialNameAsks() {
        val options = asked(run("accendi il forno"))
        assertEquals("Presa forno", options.first().devices.single().name)
    }

    @Test
    fun wholeRoomAlwaysAsks() {
        val options = asked(run("spegni tutto in salotto"))
        assertEquals("Salotto", options.first().room)
        assertEquals(listOf("Stufa", "Lampada"), options.first().devices.map { it.name })
    }

    @Test
    fun sensorsAreNeverSwitched() {
        assertTrue(run("accendi il termometro") is VoiceResult.Unknown)
    }

    @Test
    fun noVerbIsNotACommand() {
        assertTrue(run("la lampada") is VoiceResult.Unknown)
    }

    @Test
    fun unknownDeviceIsNotACommand() {
        assertTrue(run("accendi la lavatrice") is VoiceResult.Unknown)
    }

    @Test
    fun betterGuessOfTheRecognizerWins() {
        assertEquals("Lampada", ran(run("accendi la rampa", "accendi la lampada")).devices.single().name)
    }

    @Test
    fun wakeWordIsFoundAndTakenOff() {
        assertEquals(listOf("accendi la lampada"), wakeCommand(listOf("Ok casa, accendi la lampada"), "ok casa"))
        assertEquals(listOf("spegni tutto"), wakeCommand(listOf("senti ok casa spegni tutto", "che caso"), "Ok Casa"))
        assertEquals(listOf(""), wakeCommand(listOf("ok casa"), "ok casa"))
        assertEquals(null, wakeCommand(listOf("accendi la lampada", "casa"), "ok casa"))
    }

    @Test
    fun englishIsUnderstoodToo() {
        val command = ran(run("turn off the Lampada, please"))
        assertEquals("Lampada", command.devices.single().name)
        assertEquals(false, command.on)
        assertEquals("Salotto", asked(run("turn on everything in the Salotto")).first().room)
    }
}
