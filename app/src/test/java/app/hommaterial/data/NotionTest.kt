package app.hommaterial.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionTest {
    @Test
    fun `a list of Notion is told from those of Alexa by its id`() {
        val source = NotionSource("1a2b-3c", NotionSource.KIND_DATABASE, "Spesa")
        assertEquals(source.copy(name = ""), NotionSource.ofList(source.listId))
        assertNull(NotionSource.ofList("amzn1.list.TO_DO"))
    }

    @Test
    fun `a database is done by its checkbox, or by a status in the complete group`() {
        val checkbox = NotionSchema.of(
            JSONObject("""{"properties":{"Nome":{"type":"title"},"Fatto":{"type":"checkbox"}}}"""),
        )
        assertEquals("Nome", checkbox.title)
        assertTrue(checkbox.done(JSONObject("""{"Fatto":{"checkbox":true}}""")))
        assertEquals("""{"Fatto":{"checkbox":false}}""", checkbox.change(false).toString())

        val status = NotionSchema.of(
            JSONObject(
                """{"properties":{"Name":{"type":"title"},"Stato":{"type":"status","status":{
                "options":[{"id":"a","name":"Da fare"},{"id":"b","name":"In corso"},{"id":"c","name":"Fatto"}],
                "groups":[{"name":"To-do","option_ids":["a"]},{"name":"In progress","option_ids":["b"]},{"name":"Complete","option_ids":["c"]}]}}}}""",
            ),
        )
        assertTrue(status.done(JSONObject("""{"Stato":{"status":{"name":"Fatto"}}}""")))
        assertFalse(status.done(JSONObject("""{"Stato":{"status":{"name":"In corso"}}}""")))
        assertEquals("Fatto", status.change(true)!!.getJSONObject("Stato").getJSONObject("status").getString("name"))
        assertEquals("Da fare", status.change(false)!!.getJSONObject("Stato").getJSONObject("status").getString("name"))
        assertNull(NotionSchema.of(JSONObject("""{"properties":{"Name":{"type":"title"}}}""")).change(true))
    }

    @Test
    fun `the blocks of a note keep their text and their kind`() {
        val todo = JSONObject("""{"type":"to_do","to_do":{"checked":true,"rich_text":[{"plain_text":"Latte"}]}}""")
        assertEquals(NoteBlock("todo", "Latte", checked = true, depth = 1), noteBlock(todo, 1))
        assertNull(noteBlock(JSONObject("""{"type":"image","image":{}}"""), 0))
    }

    @Test
    fun `the answer of Alexa is the latest record of that Echo since it was spoken to`() {
        val history = JSONObject(
            """{"customerHistoryRecords":[
            {"recordKey":"a#b#TYPE#ECHO1","timestamp":1000,"voiceHistoryRecordItems":[{"recordItemType":"ALEXA_RESPONSE","transcriptText":"Vecchia"}]},
            {"recordKey":"a#b#TYPE#ECHO2","timestamp":5000,"voiceHistoryRecordItems":[{"recordItemType":"ALEXA_RESPONSE","transcriptText":"Altro Echo"}]},
            {"recordKey":"a#b#TYPE#ECHO1","timestamp":5000,"voiceHistoryRecordItems":[
              {"recordItemType":"CUSTOMER_TRANSCRIPT","transcriptText":"che tempo fa"},
              {"recordItemType":"ALEXA_RESPONSE","transcriptText":"Oggi c'è il sole."}]},
            {"recordKey":"a#b#TYPE#ECHO3","timestamp":5000,"voiceHistoryRecordItems":[{"recordItemType":"ALEXA_RESPONSE","transcriptText":"null"}]}]}""",
        )
        assertEquals("Oggi c'è il sole.", replyIn(history, "ECHO1", 4000))
        // Only a chime: nothing to show.
        assertNull(replyIn(history, "ECHO3", 4000))
        assertNull(replyIn(history, "ECHO1", 9000))
    }

    @Test
    fun `the suggested phrases leave out those already taken`() {
        val answer = JSONObject("""{"short":["Buonanotte","“Film”."," "],"medium":["Accendi la luce del film"],"long":[]}""")
        val found = parsePhrases(answer, listOf("buonanotte"))
        assertEquals(listOf("Film"), found.short)
        assertEquals(listOf("Accendi la luce del film"), found.medium)
        assertTrue(found.long.isEmpty())
    }
}
