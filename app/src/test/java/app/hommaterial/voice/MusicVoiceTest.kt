package app.hommaterial.voice

import app.hommaterial.music.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVoiceTest {
    private fun track(title: String, artist: String, album: String) =
        Track("u:$title", title, artist, album, album, "", 0, 0, 0, 0)

    private val library = listOf(
        track("Questo piccolo grande amore", "Claudio Baglioni", "Questo piccolo grande amore"),
        track("Strada facendo", "Claudio Baglioni", "Strada facendo"),
        track("Bohemian Rhapsody", "Queen", "A Night at the Opera"),
        track("Albachiara", "Vasco Rossi", "Non siamo mica gli americani"),
    )

    private fun parse(vararg heard: String, queued: Boolean = false) = parseMusic(heard.toList(), library, queued)

    @Test
    fun playsEverythingShuffled() {
        val c = parse("riproduci tutta la musica in modo casuale") as MusicCommand.Play
        assertEquals(4, c.tracks.size)
        assertTrue(c.shuffle)
        assertNull(c.name)
    }

    @Test
    fun playsEverythingInOrder() {
        val c = parse("metti un po' di musica") as MusicCommand.Play
        assertEquals(4, c.tracks.size)
        assertEquals(false, c.shuffle)
    }

    @Test
    fun playsAnArtist() {
        val c = parse("riproduci la musica di Claudio Baglioni") as MusicCommand.Play
        assertEquals(listOf("Questo piccolo grande amore", "Strada facendo"), c.tracks.map { it.title })
        assertEquals("Claudio Baglioni", c.name)
        assertEquals(false, c.shuffle)
        assertEquals(2, (parse("riproduci baglioni a caso") as MusicCommand.Play).tracks.size)
    }

    @Test
    fun playsASongOrAlbum() {
        assertEquals("Albachiara", (parse("riproduci albachiara") as MusicCommand.Play).tracks.single().title)
        assertEquals("Bohemian Rhapsody", (parse("play a night at the opera") as MusicCommand.Play).name)
        assertEquals("Strada facendo", (parse("suona strada facendo di baglioni") as MusicCommand.Play).tracks.single().title)
    }

    @Test
    fun leavesOtherSentences() {
        assertNull(parse("riproduci la musica di Lucio Dalla"))
        assertNull(parse("metti la sveglia alle sette"))
        assertNull(parse("accendi la luce"))
        // Without music in the queue, these are not about it.
        assertNull(parse("pausa"))
        assertNull(parse("prossima"))
    }

    @Test
    fun movesTheQueue() {
        assertEquals(MusicCommand.Pause, parse("metti in pausa", queued = true))
        assertEquals(MusicCommand.Pause, parse("ferma la musica", queued = true))
        assertEquals(MusicCommand.Next, parse("canzone successiva", queued = true))
        assertEquals(MusicCommand.Previous, parse("brano precedente", queued = true))
        assertEquals(MusicCommand.Resume, parse("riprendi la musica", queued = true))
        assertEquals(MusicCommand.Resume, parse("riproduci", queued = true))
    }
}
