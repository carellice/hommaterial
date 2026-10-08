package app.hommaterial.data

import org.json.JSONArray
import org.json.JSONObject

/** What the rest screen can show. */
const val REST_CLOCK = "clock"
const val REST_DATE = "date"
const val REST_SENSORS = "sensors"
const val REST_TIMERS = "timers"
const val REST_POWER = "power"
val REST_ITEMS = listOf(REST_CLOCK, REST_DATE, REST_SENSORS, REST_TIMERS, REST_POWER)

/**
 * How the app behaves on a tablet left on a wall as the panel of the home. It belongs to the
 * tablet, not to the account: two tablets signed in to the same account show different panels.
 */
data class MonitorConfig(
    val enabled: Boolean = false,
    /** The pages of the side bar, in order and the first one opening; null lists them all. */
    val pages: List<String>? = null,
    /** What each page is narrowed to, by page: ids of devices, or of groups. The others show all. */
    val only: Map<String, Set<String>> = emptyMap(),
    /** From 0, compact, to 2, large enough to read from across the room. */
    val tileSize: Int = 1,
    val clock: Boolean = true,
    val keepOn: Boolean = true,
    val fullscreen: Boolean = true,
    /** How often the states are fetched while the panel stays open. */
    val refreshSeconds: Int = 60,
    /** Whether a dimmed screen with the clock takes over when nobody touches the panel. */
    val rest: Boolean = true,
    val restMinutes: Int = 2,
    val restItems: Set<String> = REST_ITEMS.toSet(),
    /** applianceIds of the sensors of the rest screen; null shows them all. */
    val restSensors: List<String>? = null,
    /** Brightness of the rest screen in percent; 0 leaves the one of the tablet. */
    val restBrightness: Int = 15,
    /** Whether the rest screen goes as dark as it can between [nightFrom] and [nightTo] o'clock. */
    val night: Boolean = false,
    val nightFrom: Int = 23,
    val nightTo: Int = 7,
) {
    fun toJson(): JSONObject {
        val narrowed = JSONObject()
        for ((page, ids) in only) narrowed.put(page, JSONArray(ids))
        return JSONObject()
            .put("enabled", enabled)
            .put("pages", pages?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("only", narrowed)
            .put("tileSize", tileSize)
            .put("clock", clock)
            .put("keepOn", keepOn)
            .put("fullscreen", fullscreen)
            .put("refreshSeconds", refreshSeconds)
            .put("rest", rest)
            .put("restMinutes", restMinutes)
            .put("restItems", JSONArray(restItems))
            .put("restSensors", restSensors?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("restBrightness", restBrightness)
            .put("night", night)
            .put("nightFrom", nightFrom)
            .put("nightTo", nightTo)
    }

    companion object {
        fun fromJson(o: JSONObject): MonitorConfig {
            fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map(it::getString) }
            val narrowed = o.optJSONObject("only") ?: JSONObject()
            val defaults = MonitorConfig()
            return MonitorConfig(
                enabled = o.optBoolean("enabled"),
                pages = strings(o.optJSONArray("pages")),
                only = narrowed.keys().asSequence()
                    .associateWith { strings(narrowed.optJSONArray(it)).orEmpty().toSet() },
                tileSize = o.optInt("tileSize", defaults.tileSize).coerceIn(0, 2),
                clock = o.optBoolean("clock", defaults.clock),
                keepOn = o.optBoolean("keepOn", defaults.keepOn),
                fullscreen = o.optBoolean("fullscreen", defaults.fullscreen),
                refreshSeconds = o.optInt("refreshSeconds", defaults.refreshSeconds).coerceAtLeast(30),
                rest = o.optBoolean("rest", defaults.rest),
                restMinutes = o.optInt("restMinutes", defaults.restMinutes).coerceAtLeast(1),
                restItems = strings(o.optJSONArray("restItems"))?.toSet() ?: defaults.restItems,
                restSensors = strings(o.optJSONArray("restSensors")),
                restBrightness = o.optInt("restBrightness", defaults.restBrightness).coerceIn(0, 100),
                night = o.optBoolean("night"),
                nightFrom = o.optInt("nightFrom", defaults.nightFrom).coerceIn(0, 23),
                nightTo = o.optInt("nightTo", defaults.nightTo).coerceIn(0, 23),
            )
        }
    }
}
