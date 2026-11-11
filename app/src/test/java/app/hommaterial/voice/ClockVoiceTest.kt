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
    fun alarmSeriesAreRead() {
        val series = { text: String -> (parseClock(listOf(text)) as? ClockCommand.Alarms)?.times }
        assertEquals(
            listOf(6 to 30, 6 to 40, 6 to 50, 7 to 0, 7 to 10, 7 to 20, 7 to 30),
            series("imposta delle sveglie ogni 10 minuti dalle 6:30 alle 7:30"),
        )
        assertEquals(listOf(6 to 0, 6 to 15, 6 to 30), series("sveglie ogni quarto d'ora dalle 6 alle 6 e mezza"))
        assertEquals(listOf(23 to 30, 0 to 0, 0 to 30, 1 to 0), series("sveglie ogni mezz'ora dalle 23 e 30 all'una"))
        assertEquals(listOf(6 to 0, 6 to 5, 6 to 10), series("set alarms every 5 minutes from 6 to 6 10"))
        assertEquals(listOf(7 to 0, 7 to 20), series("dalle 7 alle 7 e 20 sveglie ogni 20 minuti"))
        assertEquals(7 to 0, alarm("sveglia alle 7"))
    }

    @Test
    fun otherSentencesAreLeftAlone() {
        assertNull(parseClock(listOf("spegni la sveglia")))
        assertNull(parseClock(listOf("accendi la luce tra 10 minuti")))
        assertNull(parseClock(listOf("timer")))
        assertNull(parseClock(listOf("cosa c'è nella lista della spesa")))
    }
}
