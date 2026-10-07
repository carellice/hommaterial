package app.hommaterial.data

import android.content.Context

private const val KEEP_MS = 7 * 24 * 60 * 60_000L
// Readings closer than this to the previous one add nothing to a chart that spans days.
private const val MIN_SPACING_MS = 10 * 60_000L

/** One reading of a sensor; [humidity] is missing on the sensors that do not report it. */
data class Reading(val at: Long, val temperature: Double, val humidity: Int?)

/**
 * The readings of the last week for each sensor. They are noted down whenever the phone fetches
 * the states, so they are as frequent as the app is opened and the widget refreshes.
 */
class History(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun readings(applianceId: String): List<Reading> =
        prefs.getString(applianceId, "").orEmpty().split(';').mapNotNull { entry ->
            val parts = entry.split(',')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val temperature = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Reading(at, temperature, parts.getOrNull(2)?.toIntOrNull())
        }

    /** Notes down the sensor readings found in freshly fetched [states]. */
    fun record(states: Map<String, DeviceState>, now: Long = System.currentTimeMillis()) {
        val editor = prefs.edit()
        for ((id, state) in states) {
            val temperature = state.temperature?.takeIf { !it.isNaN() } ?: continue
            val known = readings(id)
            if (known.isNotEmpty() && now - known.last().at < MIN_SPACING_MS) continue
            val kept = known.filter { now - it.at < KEEP_MS } + Reading(now, temperature, state.humidity)
            editor.putString(id, kept.joinToString(";") { "${it.at},${it.temperature},${it.humidity ?: ""}" })
        }
        editor.apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
