package app.hommaterial.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompleteTest {
    private val words = CompletionWords(
        power = listOf("Luce cucina", "Lampada", "TV Flavio", "Luci salotto"),
        run = listOf("Buonanotte", "Modalità cinema"),
        rooms = listOf("Cucina", "Salotto"),
        lists = listOf("Spesa", "Cose da fare"),
        phrases = listOf("Buonanotte a tutti"),
        verbs = listOf("Accendi", "Spegni", "Esegui", "Aggiungi"),
    )

    private fun labels(typed: String) = completeCommand(typed, words).map { it.label }

    @Test
    fun theFirstWordGetsTheVerbs() {
        assertEquals(listOf("Accendi", "Aggiungi"), labels("a").take(2))
        assertEquals("Spegni ", completeCommand("sp", words).first().text)
    }

    @Test
    fun aNameIsCompletedFromItsStartOrFromAWordInside() {
        assertEquals("Luce cucina", labels("accendi luce c").first())
        assertEquals("accendi Luce cucina ", completeCommand("accendi luce c", words).first().text)
        assertEquals("TV Flavio", labels("spegni fla").first())
        assertTrue("Cucina" in labels("accendi cuc"))
    }

    @Test
    fun accentsAndCaseDoNotMatter() {
        assertEquals("Modalità cinema", labels("esegui MODALITA").first())
    }

    @Test
    fun theVerbChoosesWhatIsSuggested() {
        // Custom devices are run, never switched off; lists are only for "aggiungi … alla lista".
        assertEquals(listOf("Buonanotte", "Modalità cinema"), labels("esegui "))
        assertTrue("Buonanotte" !in labels("spegni "))
        assertTrue(labels("spegni ").containsAll(listOf("Luce cucina", "Lampada", "Cucina")))
    }

    @Test
    fun listsAreSuggestedAfterLista() {
        assertEquals(listOf("Spesa", "Cose da fare"), labels("aggiungi latte alla lista "))
        assertEquals("aggiungi latte alla lista Spesa ", completeCommand("aggiungi latte alla lista sp", words).first().text)
        // What to add is free text: no list is pushed on it.
        assertEquals(emptyList<String>(), labels("aggiungi sp"))
    }

    @Test
    fun voicePhrasesAreOfferedWhole() {
        assertEquals("Buonanotte a tutti", labels("buonanotte a").first())
    }

    @Test
    fun nothingWhenTheNameIsAlreadyWhole() {
        assertTrue("Lampada" !in labels("accendi lampada"))
        assertEquals(emptyList<String>(), labels(""))
        assertEquals(emptyList<String>(), labels("accendi xyz"))
    }
}
