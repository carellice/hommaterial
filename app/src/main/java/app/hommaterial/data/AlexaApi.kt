package app.hommaterial.data

import app.hommaterial.App
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

    /** A call to the Alexa web API; [site] is "www" for the lists, which live on the shop's own host. */
    private suspend fun call(method: String, path: String, body: JSONObject? = null, site: String = "alexa"): JSONObject =
        callText(method, path, body, site).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    /** The same call, its answer as it comes: [headers] are added to those of the Alexa app. */
    private suspend fun callText(
        method: String,
        path: String,
        body: JSONObject? = null,
        site: String = "alexa",
        headers: Map<String, String> = emptyMap(),
    ): String =
        withContext(Dispatchers.IO) {
            val market = auth.marketplace
            val host = market.alexaHost
            var session = auth.session()
            repeat(2) { attempt ->
                val request = Request.Builder()
                    .url("https://$site.${market.domain}$path")
                    // The lists answer only to the agent of the Alexa app itself, as alexa-remote sends it.
                    .header("User-Agent", if (site == "www") LISTS_USER_AGENT else USER_AGENT)
                    .header("Accept", "application/json; charset=utf-8")
                    .header("Accept-Language", market.locale)
                    .header("Referer", "https://$host/spa/index.html")
                    .header("Origin", "https://$host")
                    .header("csrf", session.csrf)
                    .header("Cookie", session.cookie)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        // The web session lasts a few days; the first 401 just means it needs renewing.
                        response.code == 401 && attempt == 0 -> session = auth.session(forceRefresh = true)
                        response.code == 401 -> throw NotLoggedInException(str(R.string.login_expired))
                        !response.isSuccessful -> throw Exception(
                            str(R.string.alexa_error, response.code) + amazonReason(text, response.header("x-amzn-ErrorType")),
                        )
                        else -> return@withContext text
                    }
                }
            }
            error("unreachable")
        }

    /** Controllable devices and sensors, each tagged with its Alexa room. */
    suspend fun devices(): List<Device> {
        // On the home server the list comes from there, read anew.
        if (ServerSync.active) {
            ServerSync.call("POST", "/api/alexa/refresh", JSONObject())
            ServerSync.pull()
            return Cache(App.instance).devices()
        }
        return amazonDevices()
    }

    private suspend fun amazonDevices(): List<Device> = coroutineScope {
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
        if (ServerSync.active) {
            val answer = ServerSync.call("POST", "/api/alexa/states", JSONObject().put("ids", JSONArray(devices.map { it.applianceId })))
            val states = answer.optJSONObject("states") ?: JSONObject()
            return states.keys().asSequence().associateWith { DeviceState.fromJson(states.getJSONObject(it)) }
        }
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
        if (ServerSync.active) return ServerSync.call("GET", "/api/alexa/diagnostics/${ServerSync.encode(device.applianceId)}").optString("text")
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
        if (ServerSync.active) server(device, JSONObject().put("action", "power").put("on", on))
        else control(device, JSONObject().put("action", if (on) "turnOn" else "turnOff"))

    suspend fun setBrightness(device: Device, percent: Int) =
        if (ServerSync.active) server(device, JSONObject().put("action", "brightness").put("brightness", percent))
        else control(device, JSONObject().put("action", "setBrightness").put("brightness", percent))

    /** [colorName] is one of the names Alexa gives to colors, such as "red" or "sky_blue". */
    suspend fun setColor(device: Device, colorName: String) =
        if (ServerSync.active) server(device, JSONObject().put("action", "color").put("color", colorName).put("white", false))
        else control(device, JSONObject().put("action", "setColor").put("colorName", colorName))

    /** [whiteName] is one of the names Alexa gives to shades of white, such as "warm_white". */
    suspend fun setColorTemperature(device: Device, whiteName: String) =
        if (ServerSync.active) server(device, JSONObject().put("action", "color").put("color", whiteName).put("white", true))
        else control(device, JSONObject().put("action", "setColorTemperature").put("colorTemperatureName", whiteName))

    /** A command through the home server, which speaks to Alexa with its own sign-in. */
    private suspend fun server(device: Device, body: JSONObject) {
        ServerSync.call("POST", "/api/alexa/control", body.put("applianceId", device.applianceId))
    }

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

    // --- the Echo devices, to say things to Alexa -------------------------------------------------
    // What is said goes to Alexa as text, as if spoken to an Echo, which answers aloud: the command
    // of the routines of the Alexa app ("Custom" / "Tell Alexa"), as alexa-remote sends it. Undocumented.

    private var knownEchos: List<Echo> = emptyList()

    /** The devices Alexa can be spoken to through, those with a speaker of their own. */
    suspend fun echos(): List<Echo> {
        if (ServerSync.active) {
            return (ServerSync.call("GET", "/api/alexa/echos").optJSONArray("echos") ?: JSONArray()).mapObjects(Echo::fromJson)
        }
        val answer = call("GET", "/api/devices-v2/device?cached=true")
        knownEchos = (answer.optJSONArray("devices") ?: JSONArray()).mapObjects { o ->
            val capabilities = o.optJSONArray("capabilities")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
            val serial = o.optString("serialNumber")
            if (serial.isEmpty() || o.optString("deviceFamily") == "WHA" || "VOLUME_SETTING" !in capabilities) return@mapObjects null
            Echo(
                serial = serial,
                name = o.optString("accountName").ifEmpty { serial },
                type = o.optString("deviceType"),
                customer = o.optString("deviceOwnerCustomerId"),
                online = o.optBoolean("online", true),
            )
        }.filterNotNull().sortedBy { it.name.lowercase() }
        return knownEchos
    }

    /** Tells Alexa [text] through the Echo [serial], as if it had been said to it. */
    suspend fun say(serial: String, text: String) {
        if (ServerSync.active) {
            ServerSync.call("POST", "/api/alexa/say", JSONObject().put("echo", serial).put("text", text))
            return
        }
        val echo = knownEchos.find { it.serial == serial } ?: echos().find { it.serial == serial }
            ?: throw Exception(str(R.string.echo_gone))
        val payload = JSONObject()
            .put("deviceType", echo.type)
            .put("deviceSerialNumber", echo.serial)
            .put("locale", auth.marketplace.locale)
            .put("customerId", echo.customer)
            .put("text", text.trim().lowercase())
        val node = JSONObject()
            .put("@type", "com.amazon.alexa.behaviors.model.OpaquePayloadOperationNode")
            .put("type", "Alexa.TextCommand")
            .put("skillId", "amzn1.ask.1p.tellalexa")
            .put("operationPayload", payload)
        val sequence = JSONObject().put("@type", "com.amazon.alexa.behaviors.model.Sequence").put("startNode", node)
        call(
            "POST",
            "/api/behaviors/preview",
            JSONObject().put("behaviorId", "PREVIEW").put("sequenceJson", sequence.toString()).put("status", "ENABLED"),
        )
    }

    // The answer of Alexa is read back from the voice history of the account, as alexa-remote does:
    // the page of the history hands out the token its own calls need. Undocumented too.

    private var historyToken: Pair<String, Long>? = null

    private suspend fun historyToken(): String {
        historyToken?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        val page = callText("GET", HISTORY_PAGE, site = "www", headers = mapOf("Accept" to "text/html"))
        val token = Regex("""meta name="csrf-token" content="([^"]+)"""").find(page)?.groupValues?.get(1)
            ?: throw Exception(str(R.string.alexa_unexpected))
        historyToken = token to System.currentTimeMillis() + 2 * 3600_000L
        return token
    }

    /**
     * What Alexa answered aloud through the Echo [serial] to what was said to it since [since], in
     * epoch milliseconds; null while the history does not have it yet.
     */
    suspend fun reply(serial: String, since: Long): String? {
        if (ServerSync.active) {
            return ServerSync.call("GET", "/api/alexa/reply?echo=${ServerSync.encode(serial)}&since=$since")
                .optString("text").ifEmpty { null }
        }
        val path = "/alexa-privacy/apd/rvh/customer-history-records-v2?startTime=${since - 5_000}&endTime=${System.currentTimeMillis() + 60_000}"
        val headers = mapOf("anti-csrftoken-a2z" to historyToken(), "Referer" to "https://www.${auth.marketplace.domain}$HISTORY_PAGE")
        val answer = JSONObject(callText("POST", path, JSONObject().put("previousRequestToken", JSONObject.NULL), "www", headers).ifBlank { "{}" })
        return replyIn(answer, serial, since)
    }

    // --- lists ---------------------------------------------------------------------------------
    // The to-do and shopping lists of Alexa, through the API its own app uses (as alexa-remote
    // does): undocumented, so the answers are read loosely.

    suspend fun lists(): List<AlexaList> {
        val answer = if (ServerSync.active) {
            ServerSync.call("GET", "/api/lists")
        } else {
            val body = JSONObject()
                .put("listAttributesToAggregate", JSONArray().put(JSONObject().put("type", "totalActiveItemsCount")))
                .put("listOwnershipType", JSONObject.NULL)
            val raw = call("POST", "$LISTS/fetch", body, site = "www")
            JSONObject().put("lists", AlexaList.fromAmazon(raw.optJSONArray("listInfoList") ?: JSONArray()))
        }
        return (answer.optJSONArray("lists") ?: JSONArray()).mapObjects(AlexaList::fromJson)
    }

    suspend fun listItems(listId: String): List<ListItem> {
        if (ServerSync.active) {
            return (ServerSync.call("GET", "/api/lists/${ServerSync.encode(listId)}/items").optJSONArray("items") ?: JSONArray())
                .mapObjects(ListItem::fromJson)
        }
        val body = JSONObject().put("itemAttributesToProject", JSONArray().put("quantity").put("note"))
        val raw = call("POST", "$LISTS/${listId.encoded()}/items/fetch?limit=100", body, site = "www")
        return (raw.optJSONArray("itemInfoList") ?: JSONArray()).mapObjects(ListItem::fromAmazon)
    }

    suspend fun addListItem(listId: String, name: String) {
        if (ServerSync.active) {
            ServerSync.call("POST", "/api/lists/${ServerSync.encode(listId)}/items", JSONObject().put("name", name))
            return
        }
        val item = JSONObject().put("itemType", "KEYWORD").put("itemName", name)
        call("POST", "$LISTS/${listId.encoded()}/items", JSONObject().put("items", JSONArray().put(item)), site = "www")
    }

    /** Ticks an item off, or back on; Amazon wants the version last read. */
    suspend fun setListItemDone(listId: String, item: ListItem, completed: Boolean) {
        if (ServerSync.active) {
            ServerSync.call(
                "PUT",
                "/api/lists/${ServerSync.encode(listId)}/items/${ServerSync.encode(item.id)}",
                JSONObject().put("version", item.version).put("completed", completed),
            )
            return
        }
        val status = JSONObject().put("type", "itemStatus").put("value", if (completed) "COMPLETE" else "ACTIVE")
        val body = JSONObject().put("itemAttributesToUpdate", JSONArray().put(status)).put("itemAttributesToRemove", JSONArray())
        call("PUT", "$LISTS/${listId.encoded()}/items/${item.id.encoded()}?version=${item.version}", body, site = "www")
    }

    suspend fun deleteListItem(listId: String, item: ListItem) {
        if (ServerSync.active) {
            ServerSync.call("DELETE", "/api/lists/${ServerSync.encode(listId)}/items/${ServerSync.encode(item.id)}?version=${item.version}")
            return
        }
        call("DELETE", "$LISTS/${listId.encoded()}/items/${item.id.encoded()}?version=${item.version}", site = "www")
    }

    // The ids go in the path as they are, as alexa-remote sends them: Amazon refuses them encoded
    // (an id ending in "=" sent as "%3D"). Only what would break the path is escaped.
    private fun String.encoded(): String = replace("%", "%25").replace(" ", "%20").replace("?", "%3F").replace("#", "%23")
}

private const val LISTS = "/alexashoppinglists/api/v2/lists"
private const val HISTORY_PAGE = "/alexa-privacy/apd/activity?disableGlobalNav=true&ref=activityHistory"

/** The words of Alexa in the latest record of the history heard by [serial] since [since]. */
internal fun replyIn(history: JSONObject, serial: String, since: Long): String? {
    val records = history.optJSONArray("customerHistoryRecords") ?: return null
    return (0 until records.length()).mapNotNull { records.optJSONObject(it) }
        .filter { it.optString("recordKey").split("#").getOrNull(3) == serial && it.optLong("timestamp") >= since - 2_000 }
        .maxByOrNull { it.optLong("timestamp") }
        ?.let { record ->
            val items = record.optJSONArray("voiceHistoryRecordItems") ?: return@let null
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optString("recordItemType") in setOf("ALEXA_RESPONSE", "TTS_REPLACEMENT_TEXT") }
                .map { it.optString("transcriptText").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" ")
                .ifEmpty { null }
        }
}

private const val LISTS_USER_AGENT = "AppleWebKit PitanguiBridge/2.2.595606.0-[HARDWARE=iPhone14_7][SOFTWARE=17.4.1][DEVICE=iPhone]"

/** Why Amazon refused a call, in its own words when it gives them, to show after the code. */
internal fun amazonReason(body: String, errorType: String?): String {
    val said = runCatching {
        val o = JSONObject(body)
        listOf("message", "Message", "errorMessage", "error", "code", "type").firstNotNullOfOrNull { key ->
            o.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }
    }.getOrNull() ?: errorType?.substringBefore(':')?.takeIf { it.isNotBlank() }
    return said?.let { ": " + it.take(120) }.orEmpty()
}

/** A list of Alexa: [type] is TO_DO, SHOPPING_LIST or one the user made; [active] how many items are still to do. */
data class AlexaList(val id: String, val name: String, val type: String, val active: Int?) {
    companion object {
        fun fromJson(o: JSONObject) = AlexaList(
            o.getString("id"),
            o.optString("name"),
            o.optString("type", "CUSTOM"),
            if (o.isNull("active")) null else o.optInt("active"),
        )

        /** The lists as Amazon answers, in the format of the home server. */
        fun fromAmazon(list: JSONArray): JSONArray = JSONArray().also { out ->
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                if (it.optBoolean("archived")) continue
                val active = runCatching { JSONObject(it.optString("totalActiveItemsCount")).optInt("count") }.getOrNull()
                out.put(
                    JSONObject()
                        .put("id", it.optString("listId"))
                        .put("name", it.optString("listName"))
                        .put("type", it.optString("listType", "CUSTOM"))
                        .put("active", active ?: JSONObject.NULL),
                )
            }
        }
    }
}

/** An Echo, or another device with Alexa and a speaker: what is said to Alexa goes through one. */
data class Echo(val serial: String, val name: String, val type: String, val customer: String, val online: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("serial", serial).put("name", name).put("type", type)
        .put("customer", customer).put("online", online)

    companion object {
        fun fromJson(o: JSONObject) = Echo(
            o.getString("serial"),
            o.optString("name"),
            o.optString("type"),
            o.optString("customer"),
            o.optBoolean("online", true),
        )
    }
}

/** An item of a list; [version] goes with every change, as Amazon wants it. [waiting] while a change made without internet is not yet sent. */
data class ListItem(val id: String, val name: String, val completed: Boolean, val version: Long, val waiting: Boolean = false) {
    companion object {
        fun fromJson(o: JSONObject) = ListItem(o.getString("id"), o.optString("name"), o.optBoolean("completed"), o.optLong("version", 1))

        fun fromAmazon(o: JSONObject) = ListItem(
            o.optString("itemId"),
            o.optString("itemName"),
            o.optString("itemStatus") == "COMPLETE",
            o.optLong("version", 1),
        )
    }
}
