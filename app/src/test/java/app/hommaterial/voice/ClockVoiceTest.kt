package app.hommaterial.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockVoiceTest {
    private fun timer(text: String) = (parseClock(listOf(text)) as? ClockCommand.Timer)?.seconds
    private fun alarm(text: String) = (parseClock(listOf(text)) as? ClockCommand.Alarm)?.let { it.hour to it.minute }

    @Test
    fun timersAreRead() {
        assertEquals(600, timer("imposta un timer di 10 minuti"))
        assertEquals(300, timer("timer 5 minuti"))
        assertEquals(5400, timer("timer di un'ora e mezza"))
        assertEquals(1800, timer("metti un timer di mezz'ora"))
        assertEquals(4800, timer("timer di 1 ora e 20"))
        assertEquals(330, timer("timer di 5 minuti e 30"))
        assertEquals(900, timer("timer di un quarto d'ora"))
        assertEquals(90, timer("timer 90 secondi"))
        assertEquals(3600, timer("set a timer for an hour"))
        assertEquals(1200, timer("svegliami tra 20 minuti"))
    }

    @Test
    fun alarmsAreRead() {
        assertEquals(7 to 0, alarm("sveglia alle 7"))
        assertEquals(7 to 30, alarm("metti la sveglia alle 7:30"))
        assertEquals(6 to 30, alarm("svegliami alle 6 e mezza"))
        assertEquals(19 to 0, alarm("sveglia alle sette di sera"))
        assertEquals(7 to 15, alarm("svegliami per favore alle 7 e 15"))
        assertEquals(18 to 30, alarm("set an alarm at 6 30 pm"))
    }

    @Test
    fun otherSentencesAreLeftAlone() {
        assertNull(parseClock(listOf("spegni la sveglia")))
        assertNull(parseClock(listOf("accendi la luce tra 10 minuti")))
        assertNull(parseClock(listOf("timer")))
        assertNull(parseClock(listOf("cosa c'è nella lista della spesa")))
    }
}
