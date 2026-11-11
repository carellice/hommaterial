package app.hommaterial.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchVoiceTest {
    @Test
    fun searchesAreRead() {
        assertEquals("chi ha vinto Sanremo", parseWebSearch(listOf("Cerca su internet chi ha vinto Sanremo")))
        assertEquals("orari del museo", parseWebSearch(listOf("cercami orari del museo online per favore")))
        assertEquals("the best pizza in Rome", parseWebSearch(listOf("search the web for the best pizza in Rome")))
        assertNull(parseWebSearch(listOf("cerca la lampada")))
        assertNull(parseWebSearch(listOf("accendi la luce")))
    }
}
