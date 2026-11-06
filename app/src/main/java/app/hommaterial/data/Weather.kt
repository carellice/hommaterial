package app.hommaterial.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/*
 * The weather at the top of the home: Open-Meteo, which asks for no key. The web app reads the same
 * in pwa/web/js/store.js (loadWeather): keep them alike.
 */

/** The place of the weather, chosen by name in the settings. */
data class WeatherPlace(val name: String, val lat: Double, val lon: Double, val detail: String = "") {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon)

    companion object {
        fun fromJson(o: JSONObject) = WeatherPlace(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

data class WeatherDay(val date: String, val code: Int, val max: Double, val min: Double, val rain: Int?)

data class WeatherInfo(
    val temperature: Double,
    val feels: Double,
    val humidity: Int,
    val wind: Double,
    val code: Int,
    val day: Boolean,
    /** Today first. */
    val days: List<WeatherDay>,
    val readAt: Long = System.currentTimeMillis(),
)

object Weather {
    private suspend fun read(http: OkHttpClient, url: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Open-Meteo ${response.code}")
            JSONObject(response.body!!.string())
        }
    }

    fun parse(json: JSONObject): WeatherInfo {
        val now = json.getJSONObject("current")
        val d = json.optJSONObject("daily") ?: JSONObject()
        val dates = d.optJSONArray("time")
        val days = (0 until (dates?.length() ?: 0)).map { i ->
            WeatherDay(
                date = dates!!.getString(i),
                code = d.getJSONArray("weather_code").optInt(i),
                max = d.getJSONArray("temperature_2m_max").optDouble(i),
                min = d.getJSONArray("temperature_2m_min").optDouble(i),
                rain = d.optJSONArray("precipitation_probability_max")?.takeUnless { it.isNull(i) }?.optInt(i),
            )
        }
        return WeatherInfo(
            temperature = now.optDouble("temperature_2m"),
            feels = now.optDouble("apparent_temperature"),
            humidity = now.optInt("relative_humidity_2m"),
            wind = now.optDouble("wind_speed_10m"),
            code = now.optInt("weather_code"),
            day = now.optInt("is_day", 1) == 1,
            days = days,
        )
    }

    suspend fun forecast(http: OkHttpClient, place: WeatherPlace): WeatherInfo = parse(
        read(
            http,
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=5",
        ),
    )

    /** The towns called like [query]. */
    suspend fun places(http: OkHttpClient, query: String): List<WeatherPlace> {
        val json = read(
            http,
            "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query, "UTF-8")}&count=8" +
                "&language=${Locale.getDefault().language}&format=json",
        )
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }.map { r ->
            WeatherPlace(
                r.optString("name"), r.optDouble("latitude"), r.optDouble("longitude"),
                listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
            )
        }
    }
}
