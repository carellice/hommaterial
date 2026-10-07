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
    val hasColor: Boolean = false,
    /** Shades of white, from warm to cool. */
    val hasColorTemperature: Boolean = false,
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
        .put("hasColor", hasColor)
        .put("hasColorTemperature", hasColorTemperature)

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
            // Missing in what older versions of the app saved.
            hasColor = o.optBoolean("hasColor"),
            hasColorTemperature = o.optBoolean("hasColorTemperature"),
        )
    }
}

data class DeviceState(
    val power: Boolean? = null,
    val brightness: Int? = null,
    val temperature: Double? = null,
    val humidity: Int? = null,
    /** Alexa's name for the current color or shade of white, such as "red" or "cool_white". */
    val colorName: String? = null,
    val reachable: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("power", power ?: JSONObject.NULL)
        .put("brightness", brightness ?: JSONObject.NULL)
        .put("temperature", temperature ?: JSONObject.NULL)
        .put("humidity", humidity ?: JSONObject.NULL)
        .put("colorName", colorName ?: JSONObject.NULL)
        .put("reachable", reachable)

    companion object {
        fun fromJson(o: JSONObject) = DeviceState(
            power = if (o.isNull("power")) null else o.getBoolean("power"),
            brightness = if (o.isNull("brightness")) null else o.getInt("brightness"),
            temperature = if (o.isNull("temperature")) null else o.getDouble("temperature"),
            humidity = if (o.isNull("humidity")) null else o.getInt("humidity"),
            colorName = if (o.isNull("colorName")) null else o.getString("colorName"),
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

/** A color or shade of white a lamp can be set to; [rgb] is how the app paints it. */
data class ColorChoice(val alexaName: String, val label: String, val rgb: Long, val white: Boolean = false)

val WHITE_CHOICES = listOf(
    ColorChoice("warm_white", "Bianco caldo", 0xFFFFC58F, white = true),
    ColorChoice("soft_white", "Bianco tenue", 0xFFFFDDB8, white = true),
    ColorChoice("white", "Bianco", 0xFFFFF1E0, white = true),
    ColorChoice("daylight_white", "Bianco luce diurna", 0xFFF3F6FF, white = true),
    ColorChoice("cool_white", "Bianco freddo", 0xFFDCE8FF, white = true),
)

val COLOR_CHOICES = listOf(
    ColorChoice("red", "Rosso", 0xFFFF0000),
    ColorChoice("orange", "Arancione", 0xFFFF8C00),
    ColorChoice("yellow", "Giallo", 0xFFFFE600),
    ColorChoice("green", "Verde", 0xFF00C853),
    ColorChoice("turquoise", "Turchese", 0xFF40E0D0),
    ColorChoice("cyan", "Ciano", 0xFF00E5FF),
    ColorChoice("sky_blue", "Azzurro", 0xFF87CEEB),
    ColorChoice("blue", "Blu", 0xFF2962FF),
    ColorChoice("purple", "Viola", 0xFF8E24AA),
    ColorChoice("magenta", "Magenta", 0xFFFF00FF),
    ColorChoice("pink", "Rosa", 0xFFFF80AB),
    ColorChoice("lavender", "Lavanda", 0xFFB39DDB),
)
