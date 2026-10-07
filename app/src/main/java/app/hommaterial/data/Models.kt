package app.hommaterial.data

import org.json.JSONArray
import org.json.JSONObject

data class Device(
    /** Used to query state. */
    val applianceId: String,
    /** Used to send commands. */
    val entityId: String,
    val name: String,
    val category: String,
    val room: String?,
    val hasPower: Boolean,
    val hasBrightness: Boolean,
    val isSensor: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("applianceId", applianceId)
        .put("entityId", entityId)
        .put("name", name)
        .put("category", category)
        .put("room", room ?: JSONObject.NULL)
        .put("hasPower", hasPower)
        .put("hasBrightness", hasBrightness)
        .put("isSensor", isSensor)

    companion object {
        fun fromJson(o: JSONObject) = Device(
            applianceId = o.getString("applianceId"),
            entityId = o.getString("entityId"),
            name = o.getString("name"),
            category = o.getString("category"),
            room = if (o.isNull("room")) null else o.getString("room"),
            hasPower = o.getBoolean("hasPower"),
            hasBrightness = o.getBoolean("hasBrightness"),
            isSensor = o.getBoolean("isSensor"),
        )
    }
}

data class DeviceState(
    val power: Boolean? = null,
    val brightness: Int? = null,
    val temperature: Double? = null,
    val humidity: Int? = null,
    val reachable: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("power", power ?: JSONObject.NULL)
        .put("brightness", brightness ?: JSONObject.NULL)
        .put("temperature", temperature ?: JSONObject.NULL)
        .put("humidity", humidity ?: JSONObject.NULL)
        .put("reachable", reachable)

    companion object {
        fun fromJson(o: JSONObject) = DeviceState(
            power = if (o.isNull("power")) null else o.getBoolean("power"),
            brightness = if (o.isNull("brightness")) null else o.getInt("brightness"),
            temperature = if (o.isNull("temperature")) null else o.getDouble("temperature"),
            humidity = if (o.isNull("humidity")) null else o.getInt("humidity"),
            reachable = o.optBoolean("reachable", true),
        )
    }
}

/** The line shown under a device name, in the app and in the widget. */
fun statusText(device: Device, state: DeviceState?): String = when {
    state == null -> "…"
    !state.reachable -> "Non raggiungibile"
    device.isSensor && state.temperature != null && !state.temperature.isNaN() ->
        "%.1f°".format(state.temperature) + (state.humidity?.let { " · $it%" } ?: "")
    state.power == true && device.hasBrightness && state.brightness != null -> "Acceso · ${state.brightness}%"
    state.power == true -> "Acceso"
    state.power == false -> "Spento"
    else -> "Stato sconosciuto"
}

fun List<Device>.toJsonArray(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }

inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }
