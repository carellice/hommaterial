package app.hommaterial.data

import org.json.JSONArray
import org.json.JSONObject

const val ACTION_POWER = "power"
const val ACTION_REMOTE = "remote"
const val ACTION_WAIT = "wait"

/**
 * One thing a custom device does. Switching a device: [target] is its applianceId and [value] is
 * "on" or "off". Pressing a key of a remote: [target] is the remote and [value] the command, one
 * of the keys the user added when [custom]. Waiting: [value] is the seconds.
 */
data class MacroAction(val type: String, val target: String = "", val value: String = "", val custom: Boolean = false) {
    fun toJson(): JSONObject =
        JSONObject().put("type", type).put("target", target).put("value", value).put("custom", custom)

    companion object {
        fun fromJson(o: JSONObject) =
            MacroAction(o.getString("type"), o.optString("target"), o.optString("value"), o.optBoolean("custom"))
    }
}

/**
 * A custom device: a tile that only runs actions, one after the other. With a [condition], the
 * applianceId of a device, it runs [steps] when that device is on and [otherwise] when it is off;
 * without one it always runs [steps].
 */
data class Macro(
    val id: String,
    val name: String,
    val condition: String? = null,
    val steps: List<MacroAction> = emptyList(),
    val otherwise: List<MacroAction> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("condition", condition ?: JSONObject.NULL)
        .put("steps", JSONArray(steps.map { it.toJson() }))
        .put("otherwise", JSONArray(otherwise.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject) = Macro(
            id = o.getString("id"),
            name = o.getString("name"),
            condition = if (o.isNull("condition")) null else o.getString("condition"),
            steps = o.optJSONArray("steps")?.mapObjects(MacroAction::fromJson).orEmpty(),
            otherwise = o.optJSONArray("otherwise")?.mapObjects(MacroAction::fromJson).orEmpty(),
        )
    }
}
