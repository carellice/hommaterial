package app.hommaterial.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchTest {
    @Test
    fun tavilyRepliesAreRead() {
        val found = parseTavily(JSONObject("""{"answer":"Roma","results":[
            {"title":"Capitale","url":"https://it.wikipedia.org/wiki/Roma","content":"Roma è la capitale"},
            {"title":"Senza indirizzo","url":"","content":"x"}]}"""))
        assertEquals("Roma", found.answer)
        assertEquals(1, found.results.size)
        assertEquals("https://it.wikipedia.org/wiki/Roma", found.results.single().url)
        assertEquals("", parseTavily(JSONObject("""{"answer":null,"results":[]}""")).answer)
    }

    @Test
    fun newsOfTodayAreAskedForToday() {
        assertEquals(WebScope("news", "day"), webSearchScope("le ultime notizie di oggi"))
        assertEquals(WebScope("news", "week"), webSearchScope("notizie su Sinner"))
        assertEquals(WebScope("general", "week"), webSearchScope("chi ha vinto ieri"))
        assertEquals(WebScope("general", null), webSearchScope("chi ha vinto Sanremo"))
    }
}
