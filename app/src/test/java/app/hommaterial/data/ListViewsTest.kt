package app.hommaterial.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListViewsTest {
    private val todo = AlexaList("a1", "", ListViews.TO_DO, 0)
    private val shopping = AlexaList("a2", "", ListViews.SHOPPING, 0)
    private val notion = AlexaList("notion:page:1", "Spesa", LIST_NOTION, null)

    @Test
    fun `the lists for what is to do and to buy are those chosen, else those of Alexa`() {
        val lists = listOf(todo, shopping, notion)
        assertEquals(todo, ListViews().defaultOf(lists, ListViews.TO_DO))
        assertEquals(notion, ListViews(shopping = notion.id).defaultOf(lists, ListViews.SHOPPING))
        // A list chosen and gone, with Alexa left out: none.
        assertNull(ListViews(shopping = "gone").defaultOf(listOf(notion), ListViews.SHOPPING))
    }

    @Test
    fun `the choices travel in the json, the lists of Alexa shown when nothing says otherwise`() {
        val views = ListViews(alexa = false, todo = notion.id)
        assertEquals(views, ListViews.fromJson(views.toJson()))
        assertTrue(ListViews.fromJson(JSONObject("""{"names":{},"merged":[]}""")).alexa)
        assertNull(ListViews.fromJson(JSONObject("""{"todo":null}""")).todo)
    }
}
