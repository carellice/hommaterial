package app.hommaterial.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FootballTest {
    private fun team(id: String, name: String) =
        JSONObject().put("id", id).put("displayName", name).put("shortDisplayName", name)
            .put("logos", JSONArray().put(JSONObject().put("href", "logo$id")))

    private fun match(id: String, date: String, state: String, home: Pair<String, String>, away: Pair<String, String>,
                      scores: Pair<Int, Int>? = null, homeWins: Boolean = false, timeValid: Boolean = true): JSONObject {
        fun side(where: String, t: Pair<String, String>, score: Int?, wins: Boolean) = JSONObject().put("homeAway", where)
            .put("team", team(t.first, t.second)).put("winner", wins)
            .apply { if (score != null) put("score", JSONObject().put("displayValue", score.toString())) }
        return JSONObject().put("id", id).put("date", date).put("timeValid", timeValid)
            .put("league", JSONObject().put("abbreviation", "Serie A"))
            .put("competitions", JSONArray().put(JSONObject()
                .put("status", JSONObject().put("type", JSONObject().put("state", state)))
                .put("venue", JSONObject().put("fullName", "Stadio"))
                .put("competitors", JSONArray()
                    .put(side("home", home, scores?.first, homeWins))
                    .put(side("away", away, scores?.second, false)))))
    }

    private fun events(vararg e: JSONObject) = JSONObject().put("events", JSONArray(e.toList()))

    @Test
    fun readsTeamsMatchesAndTable() {
        val teams = Football.parseTeams(JSONObject().put("sports", JSONArray().put(JSONObject().put("leagues", JSONArray().put(
            JSONObject().put("teams", JSONArray()
                .put(JSONObject().put("team", team("2", "Roma").put("color", "990a2c")))
                .put(JSONObject().put("team", team("1", "Inter").put("color", "zz")))),
        )))), "ita.1")
        assertEquals(listOf("Inter" to null, "Roma" to "#990a2c"), teams.map { it.name to it.color })

        val m = Football.parseMatches(events(match("9", "2026-11-08T19:45Z", "post", "1" to "Inter", "2" to "Roma", 2 to 1))).single()
        assertEquals(Instant.parse("2026-11-08T19:45:00Z").toEpochMilli(), m.at)
        assertEquals("1", m.away.score)
        assertEquals("Serie A", m.competition)

        fun stats(rank: Int, points: Int) = JSONArray(listOf("rank" to rank, "points" to points, "wins" to 3, "ties" to 1)
            .map { JSONObject().put("name", it.first).put("value", it.second.toDouble()) })
        val table = Football.parseTable(JSONObject().put("children", JSONArray().put(JSONObject().put("standings", JSONObject().put("entries", JSONArray()
            .put(JSONObject().put("team", team("2", "Roma")).put("stats", stats(2, 7)))
            .put(JSONObject().put("team", team("1", "Inter")).put("stats", stats(1, 10))
                .put("note", JSONObject().put("description", "Champions League").put("color", "#81D6AC"))))))))
        assertEquals(listOf("Inter", "Roma"), table.map { it.name })
        assertEquals(10, table[0].points)
        assertEquals("Champions League", table[0].zone)
    }

    @Test
    fun nextMatchLastFiveAndOutcome() {
        val now = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
        val fixtures = Football.parseMatches(events(
            match("a", "2026-11-20T19:45Z", "pre", "1" to "Inter", "3" to "Lazio"),
            match("b", "2026-11-08T19:45Z", "pre", "2" to "Roma", "1" to "Inter", timeValid = false),
            match("old", "2026-10-01T19:45Z", "pre", "2" to "Roma", "1" to "Inter"),
        ))
        val results = Football.parseMatches(events(
            match("r1", "2026-11-01T19:45Z", "post", "1" to "Inter", "2" to "Roma", 2 to 0),
            match("r2", "2026-11-04T19:45Z", "post", "3" to "Lazio", "1" to "Inter", 1 to 1),
            match("r3", "2026-10-20T19:45Z", "post", "3" to "Lazio", "1" to "Inter", 1 to 1, homeWins = true),
        ))
        val info = Football.summary("1", emptyList(), fixtures, results, now)
        assertEquals("b", info.next?.id)
        assertFalse(info.next!!.timeValid)
        assertEquals(listOf("a"), info.upcoming.map { it.id })
        assertEquals(listOf("r2", "r1", "r3"), info.recent.map { it.id })
        assertEquals(listOf('D', 'W', 'L'), info.recent.map { Football.outcome(it, "1") })
        assertNull(Football.outcome(fixtures[0], "1"))
    }

    @Test
    fun settingsFoundByPartTypoOrSynonym() {
        val monitor = "Modalità monitor, tablet, pannello, salvaschermo, luminosità"
        assertTrue(SettingsSearch.matches("", monitor))
        assertTrue(SettingsSearch.matches("lumin", monitor))
        assertTrue(SettingsSearch.matches("luminosita", monitor))
        assertTrue(SettingsSearch.matches("pannelo", monitor))
        assertTrue(SettingsSearch.matches("schermo", monitor))
        assertTrue(SettingsSearch.matches("tablet di casa", "tablet, casa"))
        assertFalse(SettingsSearch.matches("calcio", monitor))
        assertFalse(SettingsSearch.matches("tab calcio", monitor))
        assertTrue(SettingsSearch.matches("squdra", "Squadra del cuore, calcio"))
    }

    @Test
    fun listsRenamedAndJoined() {
        val lists = listOf(AlexaList("a", "Spesa", "SHOPPING_LIST", 2), AlexaList("b", "Casa", "CUSTOM", 1), AlexaList("c", "Lavoro", "CUSTOM", null))
        var v = ListViews().rename("c", "  Ufficio ")
        assertEquals(listOf("Spesa", "Casa", "Ufficio"), v.apply(lists) { it.name }.map { it.name })
        v = v.merge(listOf("b", "a"))
        val shown = v.apply(lists) { it.name }
        assertEquals(listOf("Casa + Spesa" to 3, "Ufficio" to null), shown.map { it.name to it.active })
        assertEquals(listOf("b", "a"), ListViews.members(shown[0].id))
        v = v.rename(shown[0].id, "Tutto")
        assertEquals("Tutto", v.apply(lists) { it.name }[0].name)
        v = v.merge(listOf("a", "c"))
        assertEquals(listOf(listOf("a", "c")), v.merged)
        assertEquals(listOf("b", "c"), v.apply(lists.drop(1)) { it.name }.map { it.id })
        // Saved and read back, as the server and the other devices get it.
        assertEquals(v, ListViews.fromJson(JSONObject(v.toJson().toString())))
        v = v.split(ListViews.idOf(listOf("a", "c")))
        assertTrue(v.merged.isEmpty())
    }

    @Test
    fun restItemsAddedLaterStartShown() {
        // Saved before the team, the lists and the calendar existed: they come in shown.
        val old = MonitorConfig.fromJson(JSONObject().put("restItems", JSONArray(listOf(REST_CLOCK))))
        assertEquals(setOf(REST_CLOCK, REST_TEAM, REST_LISTS, REST_CALENDAR), old.restItems)
        // Saved since: what was turned off stays off.
        val saved = MonitorConfig.fromJson(old.copy(restItems = setOf(REST_CLOCK, REST_LISTS)).toJson())
        assertEquals(setOf(REST_CLOCK, REST_LISTS), saved.restItems)
    }

    @Test
    fun matchKeptForTheWidgetReadsBackTheSame() {
        val match = Match(
            "1", 1_700_000_000_000L, true, "Serie A", "pre", "", "Olimpico",
            Side("10", "Roma", "https://a/logo.png", null, false), Side("11", "Lazio", "", "2", true),
        )
        assertEquals(match, Match.fromJson(JSONObject(match.toJson().toString())))
    }

    @Test
    fun weatherIsReadFromOpenMeteo() {
        val json = JSONObject("""{"current":{"temperature_2m":12.4,"apparent_temperature":10.1,"relative_humidity_2m":80,
            "weather_code":61,"is_day":0,"wind_speed_10m":7.2},"daily":{"time":["2026-11-06","2026-11-07"],"weather_code":[61,3],
            "temperature_2m_max":[14.0,15.5],"temperature_2m_min":[8.0,9.0],"precipitation_probability_max":[90,null]}}""")
        val w = Weather.parse(json)
        assertEquals(61, w.code)
        assertEquals(false, w.day)
        assertEquals(2, w.days.size)
        assertEquals(90, w.days[0].rain)
        assertEquals(null, w.days[1].rain)
    }
}
