package app.hommaterial.data

import app.hommaterial.R
import app.hommaterial.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val DEVICES_QUERY = """query Endpoints { endpoints { items {
  friendlyName
  displayCategories { primary { value } }
  legacyAppliance { applianceId entityId isEnabled capabilities }
} } }"""

// Asks for more than the app uses, to see what a kind of device not yet supported has to offer.
private const val DIAGNOSTICS_QUERY = """query Endpoints { endpoints { items {
  friendlyName
  displayCategories { primary { value } }
  legacyAppliance { applianceId entityId isEnabled applianceTypes manufacturerName modelName actions capabilities }
} } }"""

// Echo and Fire TV devices also report a power capability but are not home devices.
private const val VOICE_DEVICE_CATEGORY = "ALEXA_VOICE_ENABLED"

class AlexaApi(private val auth: AlexaAuth, private val http: OkHttpClient) {

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val market = auth.marketplace
            val host = market.alexaHost
            var session = auth.session()
            repeat(2) { attempt ->
                val request = Request.Builder()
                    .url("https://$host$path")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json; charset=utf-8")
                    .header("Accept-Language", market.locale)
                    .header("Referer", "https://$host/spa/index.html")
                    .header("Origin", "https://$host")
                    .header("csrf", session.csrf)
                    .header("Cookie", session.cookie)
                    .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        // The web session lasts a few days; the first 401 just means it needs renewing.
                        response.code == 401 && attempt == 0 -> session = auth.session(forceRefresh = true)
                        response.code == 401 -> throw NotLoggedInException(str(R.string.login_expired))
                        !response.isSuccessful -> throw Exception(str(R.string.alexa_error, response.code))
                        else -> return@withContext if (text.isBlank()) JSONObject() else JSONObject(text)
                    }
                }
            }
            error("unreachable")
        }

    /** Controllable devices and sensors, each tagged with its Alexa room. */
    suspend fun devices(): List<Device> = coroutineScope {
        val endpoints = async { call("POST", "/nexus/v1/graphql", JSONObject().put("query", DEVICES_QUERY)) }
        val groups = async { call("GET", "/api/phoenix/group") }

        val roomOf = HashMap<String, String>()
        groups.await().optJSONArray("applianceGroups")?.mapObjects { group ->
            val ids = group.optJSONArray("applianceIds") ?: JSONArray()
            for (i in 0 until ids.length()) roomOf.putIfAbsent(ids.getString(i), group.getString("name"))
        }

        val items = endpoints.await().optJSONObject("data")?.optJSONObject("endpoints")?.optJSONArray("items")
            ?: throw Exception(str(R.string.alexa_unexpected))
        items.mapObjects { item ->
            val appliance = item.optJSONObject("legacyAppliance") ?: return@mapObjects null
            val applianceId = appliance.optString("applianceId")
            if (applianceId.isEmpty() || !appliance.optBoolean("isEnabled", true)) return@mapObjects null

            val interfaces = appliance.optJSONArray("capabilities")
                ?.mapObjects { it.optString("interfaceName") }?.toSet().orEmpty()
            val category = item.optJSONObject("displayCategories")?.optJSONObject("primary")?.optString("value")
                ?.ifEmpty { null } ?: "OTHER"
            val device = Device(
                applianceId = applianceId,
                entityId = appliance.optString("entityId"),
                name = item.optString("friendlyName"),
                category = category,
                room = roomOf[applianceId],
                hasPower = "Alexa.PowerController" in interfaces,
                hasBrightness = "Alexa.BrightnessController" in interfaces,
                isSensor = "Alexa.TemperatureSensor" in interfaces,
                hasColor = "Alexa.ColorController" in interfaces,
                hasColorTemperature = "Alexa.ColorTemperatureController" in interfaces,
            )
            device.takeIf { category != VOICE_DEVICE_CATEGORY && (it.hasPower || it.isSensor) }
        }.filterNotNull().sortedBy { it.name.lowercase() }
    }

    /** Current state of every given device, keyed by applianceId. */
    suspend fun states(devices: List<Device>): Map<String, DeviceState> {
        if (devices.isEmpty()) return emptyMap()
        val requests = JSONArray(
            devices.map { JSONObject().put("entityId", it.applianceId).put("entityType", "APPLIANCE") },
        )
        val response = call("POST", "/api/phoenix/state", JSONObject().put("stateRequests", requests))

        val result = HashMap<String, DeviceState>()
        response.optJSONArray("deviceStates")?.mapObjects { entry ->
            var state = DeviceState()
            val capabilities = entry.optJSONArray("capabilityStates") ?: JSONArray()
            for (i in 0 until capabilities.length()) {
                // Each capability arrives as a JSON document encoded inside a string.
                val cap = JSONObject(capabilities.getString(i))
                state = when (cap.optString("name")) {
                    "powerState" -> state.copy(power = cap.optString("value") == "ON")
                    "brightness" -> state.copy(brightness = cap.optInt("value"))
                    "temperature" -> state.copy(temperature = cap.optJSONObject("value")?.optDouble("value"))
                    "relativeHumidity" -> state.copy(humidity = cap.optInt("value"))
                    "colorProperties" ->
                        state.copy(colorName = cap.optJSONObject("value")?.optString("name")?.ifEmpty { null })
                    else -> state
                }
            }
            result[entry.getJSONObject("entity").getString("entityId")] = state
        }
        response.optJSONArray("errors")?.mapObjects { error ->
            error.optJSONObject("entity")?.optString("entityId")?.let { result[it] = DeviceState(reachable = false) }
        }
        return result
    }

    /**
     * Everything Alexa says about [device], as readable JSON: what it can do and its current state.
     * Meant to be copied out of the app when support for a new kind of device is being written.
     */
    suspend fun diagnostics(device: Device): String {
        fun find(response: JSONObject) =
            response.optJSONObject("data")?.optJSONObject("endpoints")?.optJSONArray("items")
                ?.mapObjects { it }
                ?.find { it.optJSONObject("legacyAppliance")?.optString("applianceId") == device.applianceId }
        // Should Alexa refuse a field of the wider query, the one the app normally uses still answers.
        val wide = try {
            find(call("POST", "/nexus/v1/graphql", JSONObject().put("query", DIAGNOSTICS_QUERY)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            throw e
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val description = wide ?: find(call("POST", "/nexus/v1/graphql", JSONObject().put("query", DEVICES_QUERY)))
        val request = JSONObject().put("entityId", device.applianceId).put("entityType", "APPLIANCE")
        val state = call("POST", "/api/phoenix/state", JSONObject().put("stateRequests", JSONArray().put(request)))
        // Each capability state arrives as JSON inside a string: unpacked, it can be read.
        state.optJSONArray("deviceStates")?.mapObjects { entry ->
            val capabilities = entry.optJSONArray("capabilityStates") ?: return@mapObjects
            entry.put(
                "capabilityStates",
                JSONArray(
                    (0 until capabilities.length()).map { i ->
                        runCatching { JSONObject(capabilities.getString(i)) }.getOrElse { capabilities.get(i) }
                    },
                ),
            )
        }
        return JSONObject().put("device", description ?: JSONObject.NULL).put("state", state).toString(2)
    }

    suspend fun setPower(device: Device, on: Boolean) =
        control(device, JSONObject().put("action", if (on) "turnOn" else "turnOff"))

    suspend fun setBrightness(device: Device, percent: Int) =
        control(device, JSONObject().put("action", "setBrightness").put("brightness", percent))

    /** [colorName] is one of the names Alexa gives to colors, such as "red" or "sky_blue". */
    suspend fun setColor(device: Device, colorName: String) =
        control(device, JSONObject().put("action", "setColor").put("colorName", colorName))

    /** [whiteName] is one of the names Alexa gives to shades of white, such as "warm_white". */
    suspend fun setColorTemperature(device: Device, whiteName: String) =
        control(device, JSONObject().put("action", "setColorTemperature").put("colorTemperatureName", whiteName))

    private suspend fun control(device: Device, parameters: JSONObject) {
        val request = JSONObject()
            .put("entityId", device.entityId)
            .put("entityType", "APPLIANCE")
            .put("parameters", parameters)
        val response = call("PUT", "/api/phoenix/state", JSONObject().put("controlRequests", JSONArray().put(request)))
        val error = response.optJSONArray("errors")?.optJSONObject(0)
        if (error != null) {
            throw Exception(
                when (error.optString("code")) {
                    "ENDPOINT_UNREACHABLE", "NO_SUCH_ENDPOINT" -> str(R.string.device_unreachable, device.name)
                    else -> str(R.string.command_refused, device.name, error.optString("code"))
                },
            )
        }
    }
}
