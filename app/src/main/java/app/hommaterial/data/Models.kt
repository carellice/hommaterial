package app.hommaterial.data

import androidx.annotation.StringRes
import app.hommaterial.R
import app.hommaterial.str
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

/** Devices of any room that the user switches together; [devices] holds their applianceIds. */
data class Group(val id: String, val name: String, val devices: Set<String>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("devices", JSONArray(devices))

    companion object {
        fun fromJson(o: JSONObject): Group {
            val devices = o.getJSONArray("devices")
            return Group(
                id = o.getString("id"),
                name = o.getString("name"),
                devices = (0 until devices.length()).map { devices.getString(it) }.toSet(),
            )
        }
    }
}

/**
 * How the home screen widget looks. [items] lists what it shows, in order, as "device:" or
 * "group:" followed by an id; null shows the favorites. [size] goes from 0, compact, to 2, large.
 */
data class WidgetConfig(
    val items: List<String>? = null,
    val columns: Int = 2,
    val size: Int = 1,
    /** Whether each tile says the state of its device under the name. */
    val status: Boolean = true,
    /** Whether a first row shows the app name, the time of the last update and a refresh button. */
    val header: Boolean = false,
    val transparent: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("items", items?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("columns", columns)
        .put("size", size)
        .put("status", status)
        .put("header", header)
        .put("transparent", transparent)

    companion object {
        fun fromJson(o: JSONObject): WidgetConfig {
            val items = o.optJSONArray("items")
            return WidgetConfig(
                items = items?.let { a -> (0 until a.length()).map { a.getString(it) } },
                columns = o.optInt("columns", 2).coerceIn(1, 4),
                size = o.optInt("size", 1).coerceIn(0, 2),
                status = o.optBoolean("status", true),
                header = o.optBoolean("header", false),
                transparent = o.optBoolean("transparent", false),
            )
        }
    }
}

/** The line shown under a device name, in the app and in the widget. */
fun statusText(device: Device, state: DeviceState?): String = when {
    state == null -> "…"
    !state.reachable -> str(R.string.unreachable)
    device.isSensor && state.temperature != null && !state.temperature.isNaN() ->
        "%.1f°".format(state.temperature) + (state.humidity?.let { " · $it%" } ?: "")
    state.power == true && device.hasBrightness && state.brightness != null ->
        str(R.string.on_brightness, state.brightness)
    state.power == true -> str(R.string.on)
    state.power == false -> str(R.string.off)
    else -> str(R.string.unknown_state)
}

fun List<Device>.toJsonArray(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }

inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

/** A color or shade of white a lamp can be set to; [rgb] is how the app paints it. */
data class ColorChoice(val alexaName: String, @StringRes val label: Int, val rgb: Long, val white: Boolean = false)

val WHITE_CHOICES = listOf(
    ColorChoice("warm_white", R.string.color_warm_white, 0xFFFFC58F, white = true),
    ColorChoice("soft_white", R.string.color_soft_white, 0xFFFFDDB8, white = true),
    ColorChoice("white", R.string.color_white, 0xFFFFF1E0, white = true),
    ColorChoice("daylight_white", R.string.color_daylight_white, 0xFFF3F6FF, white = true),
    ColorChoice("cool_white", R.string.color_cool_white, 0xFFDCE8FF, white = true),
)

val COLOR_CHOICES = listOf(
    ColorChoice("red", R.string.color_red, 0xFFFF0000),
    ColorChoice("orange", R.string.color_orange, 0xFFFF8C00),
    ColorChoice("yellow", R.string.color_yellow, 0xFFFFE600),
    ColorChoice("green", R.string.color_green, 0xFF00C853),
    ColorChoice("turquoise", R.string.color_turquoise, 0xFF40E0D0),
    ColorChoice("cyan", R.string.color_cyan, 0xFF00E5FF),
    ColorChoice("sky_blue", R.string.color_sky_blue, 0xFF87CEEB),
    ColorChoice("blue", R.string.color_blue, 0xFF2962FF),
    ColorChoice("purple", R.string.color_purple, 0xFF8E24AA),
    ColorChoice("magenta", R.string.color_magenta, 0xFFFF00FF),
    ColorChoice("pink", R.string.color_pink, 0xFFFF80AB),
    ColorChoice("lavender", R.string.color_lavender, 0xFFB39DDB),
)
