package app.hommaterial.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupSelectionTest {
    private val local = JSONObject("""{"favorites":["local"],"groups":[],"macros":[],"tileSpans":{"local":2},"schedules":[{"id":"local"}],"monitor":{"enabled":false},"switchBot":{"token":"local"}}""")
    private val incoming = JSONObject("""{"favorites":["incoming"],"groups":[{"id":"g"}],"macros":[{"id":"m"}],"tileSpans":{"incoming":2},"schedules":[],"monitor":{"enabled":true},"switchBot":{"token":"incoming"}}""")

    @Test fun importingDevicesLeavesFavoritesAndRuntimeChoicesAlone() {
        val merged = selectBackup(local, incoming, setOf(BackupSection.MACROS, BackupSection.GROUPS))
        assertEquals("m", merged.getJSONArray("macros").getJSONObject(0).getString("id"))
        assertEquals("g", merged.getJSONArray("groups").getJSONObject(0).getString("id"))
        for (field in listOf("favorites", "tileSpans", "schedules", "monitor", "switchBot"))
            assertEquals(local.get(field).toString(), merged.get(field).toString())
        assertEquals(0, local.getJSONArray("groups").length())
    }

    @Test fun cancellationAndOldBackupsKeepLocalChoices() {
        assertEquals(local.toString(), selectBackup(local, incoming, emptySet()).toString())
        assertEquals(local.toString(), selectBackup(local, JSONObject(), BackupSection.entries.toSet()).toString())
    }

    @Test fun explicitlySelectedEmptyCategoryClearsOnlyThatCategory() {
        val merged = selectBackup(local, incoming, setOf(BackupSection.SCHEDULES))
        assertEquals(0, merged.getJSONArray("schedules").length())
        assertEquals("local", merged.getJSONArray("favorites").getString(0))
    }

    @Test fun widthsSurviveThePortableFormatAndStayWithinOneOrTwoColumns() {
        val merged = selectBackup(local, incoming, setOf(BackupSection.TILES))
        assertEquals(mapOf("incoming" to 2), tileSpans(merged.getJSONObject("tileSpans")))
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 1), tileSpans(JSONObject("""{"a":-1,"b":999,"c":"bad"}""")))
        assertTrue(tileSpans(null).isEmpty())
    }
}
