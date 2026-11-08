package app.hommaterial.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAppTest {
    private val apps = listOf(
        "dialer" to "Telefono",
        "wa" to "WhatsApp",
        "maps" to "Google Maps",
        "settings" to "Impostazioni",
        "cam" to "Fotocamera",
        "gcam" to "Google Camera",
        "yt" to "YouTube",
        "ytm" to "YouTube Music",
    )

    private fun open(vararg heard: String) = parseOpenApp(heard.toList(), apps)

    @Test
    fun opensByExactName() {
        assertEquals("dialer", open("apri telefono"))
        assertEquals("settings", open("Apri le impostazioni"))
        assertEquals("cam", open("apri l'app fotocamera"))
    }

    @Test
    fun wordsRunTogether() {
        assertEquals("wa", open("apri whats app"))
        assertEquals("yt", open("open you tube"))
    }

    @Test
    fun exactBeatsLonger() {
        assertEquals("yt", open("apri youtube"))
        assertEquals("ytm", open("apri youtube music"))
    }

    @Test
    fun partOfOneNameOnly() {
        assertEquals("maps", open("apri maps"))
        // "Google" is in two names: nothing is guessed.
        assertNull(open("apri google"))
    }

    @Test
    fun needsTheVerbAndAKnownApp() {
        assertNull(open("telefono"))
        assertNull(open("apri"))
        assertNull(open("apri la tapparella"))
        assertNull(open("accendi telefono"))
    }

    @Test
    fun laterGuessesAreTried() {
        assertEquals("dialer", open("a prima telefono", "apri telefono"))
    }
}
