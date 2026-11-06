package app.hommaterial.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/*
 * The favorite team: what ESPN publishes about Italian football, read into the few things the app
 * shows. The web app reads the same through its server, in pwa/shared/football.js: keep them alike.
 */

/** A league a favorite team can be chosen from, by ESPN's id. */
data class League(val id: String, val name: String)

val LEAGUES = listOf(League("ita.1", "Serie A"), League("ita.2", "Serie B"))

/** The team chosen, kept with its name, logo and color to show them before anything is read. */
data class FavoriteTeam(val league: String, val id: String, val name: String, val logo: String, val color: String?) {
    fun toJson(): JSONObject = JSONObject().put("league", league).put("id", id).put("name", name).put("logo", logo)
        .put("color", color ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = FavoriteTeam(
            o.getString("league"), o.getString("id"), o.optString("name"), o.optString("logo"),
            o.optString("color").takeIf { it.startsWith("#") },
        )
    }
}

data class Side(val id: String, val name: String, val logo: String, val score: String?, val winner: Boolean) {
    companion object
}

data class Match(
    val id: String,
    /** Epoch milliseconds. */
    val at: Long,
    /** False while the hour is not fixed yet: only the day is known. */
    val timeValid: Boolean,
    val competition: String,
    /** "pre", "in" or "post". */
    val state: String,
    val detail: String,
    val venue: String,
    val home: Side,
    val away: Side,
) {
    /** Kept for the widget and the reminder, which show it without reading ESPN again. */
    fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("timeValid", timeValid).put("competition", competition)
        .put("state", state).put("detail", detail).put("venue", venue).put("home", home.toJson()).put("away", away.toJson())

    companion object {
        fun fromJson(o: JSONObject) = Match(
            o.getString("id"), o.getLong("at"), o.optBoolean("timeValid", true), o.optString("competition"), o.optString("state"),
            o.optString("detail"), o.optString("venue"), Side.fromJson(o.getJSONObject("home")), Side.fromJson(o.getJSONObject("away")),
        )
    }
}

private fun Side.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("logo", logo)
    .put("score", score ?: JSONObject.NULL).put("winner", winner)

private fun Side.Companion.fromJson(o: JSONObject) =
    Side(o.optString("id"), o.optString("name"), o.optString("logo"), if (o.isNull("score")) null else o.optString("score"), o.optBoolean("winner"))

data class TableRow(
    val rank: Int,
    val id: String,
    val name: String,
    val logo: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val points: Int,
    /** What the place is worth at the end, with the color ESPN gives it. */
    val zone: String?,
    val zoneColor: String?,
)

data class TeamInfo(
    val table: List<TableRow>,
    val standing: TableRow?,
    /** The match to come, or the one being played. */
    val next: Match?,
    val upcoming: List<Match>,
    /** The last five played, newest first. */
    val recent: List<Match>,
    /** When it was read, in epoch milliseconds. */
    val readAt: Long = System.currentTimeMillis(),
)

private fun logoOf(t: JSONObject?): String =
    t?.optString("logo")?.takeIf { it.isNotEmpty() } ?: t?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

object Football {
    private const val ESPN = "https://site.api.espn.com/apis"

    /** The teams of a league, by name. */
    fun parseTeams(json: JSONObject, league: String): List<FavoriteTeam> =
        json.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams").objects()
            .mapNotNull { it.optJSONObject("team") }
            .map { t ->
                val color = t.optString("color").takeIf { Regex("^[0-9a-fA-F]{6}$").matches(it) }?.let { "#$it" }
                FavoriteTeam(league, t.optString("id"), t.optString("displayName"), logoOf(t), color)
            }
            .sortedBy { it.name }

    private fun side(c: JSONObject): Side {
        val t = c.optJSONObject("team")
        val score = when (val s = c.opt("score")) {
            null, JSONObject.NULL -> null
            is JSONObject -> s.optString("displayValue").ifEmpty { s.opt("value")?.toString() }
            else -> s.toString()
        }
        return Side(
            id = t?.optString("id")?.ifEmpty { null } ?: c.optString("id"),
            name = t?.optString("shortDisplayName")?.ifEmpty { null } ?: t?.optString("displayName").orEmpty(),
            logo = logoOf(t),
            score = score,
            winner = c.optBoolean("winner"),
        )
    }

    /** The matches of a schedule: when, which competition, the two teams and, once played or playing, the score. */
    fun parseMatches(json: JSONObject): List<Match> = json.optJSONArray("events").objects().mapNotNull { ev ->
        val c = ev.optJSONArray("competitions")?.optJSONObject(0) ?: JSONObject()
        val status = c.optJSONObject("status")?.optJSONObject("type") ?: ev.optJSONObject("status")?.optJSONObject("type") ?: JSONObject()
        val teams = c.optJSONArray("competitors").objects()
        val home = teams.find { it.optString("homeAway") == "home" } ?: teams.getOrNull(0)
        val away = teams.find { it.optString("homeAway") == "away" } ?: teams.getOrNull(1)
        val at = runCatching { Instant.parse(ev.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }).toEpochMilli() }.getOrNull()
        if (home == null || away == null || at == null) return@mapNotNull null
        val league = ev.optJSONObject("league")
        Match(
            id = ev.optString("id"),
            at = at,
            timeValid = ev.optBoolean("timeValid", true),
            competition = league?.let { l -> listOf("abbreviation", "shortName", "name").map { l.optString(it) }.firstOrNull { it.isNotEmpty() } }.orEmpty(),
            state = status.optString("state").takeIf { it in setOf("pre", "in", "post") } ?: "pre",
            detail = status.optString("shortDetail"),
            venue = c.optJSONObject("venue")?.optString("fullName").orEmpty(),
            home = side(home),
            away = side(away),
        )
    }

    /** The league table, first place first. */
    fun parseTable(json: JSONObject): List<TableRow> {
        val entries = json.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("standings")?.optJSONArray("entries")
            ?: json.optJSONObject("standings")?.optJSONArray("entries")
        return entries.objects().map { e ->
            val stats = e.optJSONArray("stats").objects().associate { it.optString("name") to it.optDouble("value", 0.0).toInt() }
            val t = e.optJSONObject("team")
            val note = e.optJSONObject("note")
            TableRow(
                rank = stats["rank"] ?: 0,
                id = t?.optString("id").orEmpty(),
                name = t?.optString("shortDisplayName")?.ifEmpty { null } ?: t?.optString("displayName").orEmpty(),
                logo = logoOf(t),
                played = stats["gamesPlayed"] ?: 0,
                wins = stats["wins"] ?: 0,
                draws = stats["ties"] ?: 0,
                losses = stats["losses"] ?: 0,
                goalsFor = stats["pointsFor"] ?: 0,
                goalsAgainst = stats["pointsAgainst"] ?: 0,
                points = stats["points"] ?: 0,
                zone = note?.optString("description")?.ifEmpty { null },
                zoneColor = note?.optString("color")?.ifEmpty { null },
            )
        }.sortedBy { it.rank }
    }

    /** What the app shows of [teamId]: its place, the match to come, the few after it, the last five played. */
    fun summary(teamId: String, table: List<TableRow>, fixtures: List<Match>, results: List<Match>, now: Long = System.currentTimeMillis()): TeamInfo {
        val coming = fixtures.filter { it.state == "in" || (it.state == "pre" && it.at > now - 3 * 3_600_000L) }.sortedBy { it.at }
        val played = results.filter { it.state == "post" }.sortedByDescending { it.at }
        return TeamInfo(table, table.find { it.id == teamId }, coming.firstOrNull(), coming.drop(1).take(3), played.take(5), now)
    }

    /** How a played match went for [teamId]: 'W', 'D' or 'L'; null before the end. */
    fun outcome(match: Match, teamId: String): Char? {
        if (match.state != "post") return null
        val mine = if (match.home.id == teamId) match.home else match.away
        val other = if (mine === match.home) match.away else match.home
        // Penalties leave the score even: the winner says who went through.
        if (mine.winner) return 'W'
        if (other.winner) return 'L'
        val a = mine.score?.toIntOrNull() ?: return null
        val b = other.score?.toIntOrNull() ?: return null
        return if (a > b) 'W' else if (a < b) 'L' else 'D'
    }

    private suspend fun read(http: OkHttpClient, path: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(ESPN + path).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ESPN ${response.code}")
            JSONObject(response.body!!.string())
        }
    }

    suspend fun teams(http: OkHttpClient): List<Pair<League, List<FavoriteTeam>>> = coroutineScope {
        LEAGUES.map { league -> async { league to parseTeams(read(http, "/site/v2/sports/soccer/${league.id}/teams"), league.id) } }
            .map { it.await() }
    }

    suspend fun team(http: OkHttpClient, team: FavoriteTeam): TeamInfo = coroutineScope {
        require(LEAGUES.any { it.id == team.league } && team.id.all(Char::isDigit))
        val table = async { parseTable(read(http, "/v2/sports/soccer/${team.league}/standings")) }
        val fixtures = async { parseMatches(read(http, "/site/v2/sports/soccer/all/teams/${team.id}/schedule?fixture=true")) }
        val results = async { parseMatches(read(http, "/site/v2/sports/soccer/all/teams/${team.id}/schedule")) }
        summary(team.id, table.await(), fixtures.await(), results.await())
    }
}
