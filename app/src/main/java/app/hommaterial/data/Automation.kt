package app.hommaterial.data

import org.json.JSONObject

const val TRIGGER_TEMPERATURE_ABOVE = "temperatureAbove"
const val TRIGGER_TEMPERATURE_BELOW = "temperatureBelow"
const val TRIGGER_HUMIDITY_ABOVE = "humidityAbove"
const val TRIGGER_HUMIDITY_BELOW = "humidityBelow"
const val TRIGGER_ON = "on"
const val TRIGGER_OFF = "off"

// A reading hovering around the threshold must not set the automation off again and again.
private const val TEMPERATURE_MARGIN = 0.3
private const val HUMIDITY_MARGIN = 2.0

/**
 * Something done by itself when a device gets to a state: a sensor past a [threshold], or a device
 * switched on or off. [device] is the applianceId that is watched and [trigger] what is waited
 * for. [title] and [label] say the condition and the action with the names of when it was made.
 */
data class Automation(
    val id: String,
    val device: String,
    val trigger: String,
    val threshold: Double = 0.0,
    val action: MacroAction,
    val title: String,
    val label: String,
    val enabled: Boolean = true,
) {
    /** Whether [state] is what the automation waits for; null when it says nothing about it. */
    fun holds(state: DeviceState): Boolean? = distance(state)?.let { it > 0 }

    /** Whether [state] is far enough from the condition for the automation to be ready again. */
    fun cleared(state: DeviceState): Boolean? = distance(state)?.let { it <= -margin }

    private val margin: Double
        get() = when (trigger) {
            TRIGGER_TEMPERATURE_ABOVE, TRIGGER_TEMPERATURE_BELOW -> TEMPERATURE_MARGIN
            TRIGGER_HUMIDITY_ABOVE, TRIGGER_HUMIDITY_BELOW -> HUMIDITY_MARGIN
            else -> 0.0
        }

    /** How far past the condition the state is: above zero when it holds. */
    private fun distance(state: DeviceState): Double? {
        if (!state.reachable) return null
        val temperature = state.temperature?.takeIf { !it.isNaN() }
        return when (trigger) {
            TRIGGER_TEMPERATURE_ABOVE -> temperature?.let { it - threshold }
            TRIGGER_TEMPERATURE_BELOW -> temperature?.let { threshold - it }
            TRIGGER_HUMIDITY_ABOVE -> state.humidity?.let { it - threshold }
            TRIGGER_HUMIDITY_BELOW -> state.humidity?.let { threshold - it }
            TRIGGER_ON -> state.power?.let { if (it) 1.0 else -1.0 }
            TRIGGER_OFF -> state.power?.let { if (it) -1.0 else 1.0 }
            else -> null
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("device", device)
        .put("trigger", trigger)
        .put("threshold", threshold)
        .put("action", action.toJson())
        .put("title", title)
        .put("label", label)
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = Automation(
            id = o.getString("id"),
            device = o.getString("device"),
            trigger = o.getString("trigger"),
            threshold = o.optDouble("threshold", 0.0),
            action = MacroAction.fromJson(o.getJSONObject("action")),
            title = o.optString("title"),
            label = o.optString("label"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}
