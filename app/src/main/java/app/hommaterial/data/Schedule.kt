package app.hommaterial.data

import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/** Running a custom device: an action only a schedule has, since a custom device does not run another. */
const val ACTION_MACRO = "macro"

/**
 * An action the phone carries out by itself at a time: once, [at] that moment, or again every one
 * of the [days], which are those of [Calendar], at [hour] and [minute]. [label] says what it does
 * with the names of when it was made, for the list and for the notification.
 */
data class Schedule(
    val id: String,
    val action: MacroAction,
    val label: String,
    val at: Long,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("action", action.toJson())
        .put("label", label)
        .put("at", at)
        .put("hour", hour)
        .put("minute", minute)
        .put("days", JSONArray(days))
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val days = o.optJSONArray("days") ?: JSONArray()
            return Schedule(
                id = o.getString("id"),
                action = MacroAction.fromJson(o.getJSONObject("action")),
                label = o.optString("label"),
                at = o.getLong("at"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = (0 until days.length()).map(days::getInt).toSet(),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

/**
 * The first time it is [hour]:[minute] after [after], on one of the [days] when there are any.
 * [calendar] is the clock to count on, that of the phone unless a test says otherwise.
 */
fun nextTime(
    hour: Int,
    minute: Int,
    days: Set<Int>,
    after: Long,
    calendar: Calendar = Calendar.getInstance(),
): Long {
    calendar.timeInMillis = after
    calendar.set(Calendar.HOUR_OF_DAY, hour)
    calendar.set(Calendar.MINUTE, minute)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    // A week is enough to meet any day; the eighth step only guards against a set of days gone wrong.
    repeat(8) {
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        if (calendar.timeInMillis > after && (days.isEmpty() || day in days)) return calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return calendar.timeInMillis
}
