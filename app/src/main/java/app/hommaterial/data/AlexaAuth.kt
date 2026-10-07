package app.hommaterial.data

import android.content.Context
import android.util.Base64
import app.hommaterial.R
import app.hommaterial.str
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Thrown when the stored login is missing or no longer accepted by Amazon. */
class NotLoggedInException(message: String = str(R.string.login_required)) : Exception(message)

data class Session(val cookie: String, val csrf: String)

/** Everything generated for one sign-in attempt; the WebView needs [url] and the two cookies. */
class LoginAttempt(
    val deviceId: String,
    val frc: String,
    val mapMd: String,
    val verifier: String,
    private val locale: String,
) {
    val url: String
        get() {
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            return "https://www.$LOGIN_DOMAIN/ap/signin" +
                "?openid.return_to=https%3A%2F%2Fwww.$LOGIN_DOMAIN%2Fap%2Fmaplanding" +
                "&openid.assoc_handle=amzn_dp_project_dee_ios" +
                "&openid.identity=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
                "&pageId=amzn_dp_project_dee_ios" +
                "&accountStatusPolicy=P1" +
                "&openid.claimed_id=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
                "&openid.mode=checkid_setup" +
                "&openid.ns.oa2=http%3A%2F%2Fwww.$LOGIN_DOMAIN%2Fap%2Fext%2Foauth%2F2" +
                "&openid.oa2.client_id=device%3A$deviceId" +
                "&openid.ns.pape=http%3A%2F%2Fspecs.openid.net%2Fextensions%2Fpape%2F1.0" +
                "&openid.oa2.response_type=code" +
                "&openid.ns=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0" +
                "&openid.pape.max_auth_age=0" +
                "&openid.oa2.scope=device_auth_access" +
                "&openid.oa2.code_challenge_method=S256" +
                "&openid.oa2.code_challenge=$challenge" +
                "&language=${locale.replace('-', '_')}"
        }
}

/** Amazon always signs the Alexa app in on amazon.com, whatever the account's marketplace. */
const val LOGIN_DOMAIN = "amazon.com"

/** The national Amazon site an Alexa account belongs to. */
data class Marketplace(val domain: String, val locale: String) {
    val alexaHost: String get() = "alexa.$domain"
}

val MARKETPLACES = listOf(
    Marketplace("amazon.it", "it-IT"),
    Marketplace("amazon.de", "de-DE"),
    Marketplace("amazon.fr", "fr-FR"),
    Marketplace("amazon.es", "es-ES"),
    Marketplace("amazon.co.uk", "en-GB"),
    Marketplace("amazon.com", "en-US"),
)

// Amazon only issues tokens to clients that identify as its own Alexa app.
private const val APP_VERSION = "2.2.651540.0"
const val USER_AGENT = "AmazonWebView/Amazon Alexa/$APP_VERSION/iOS/18.3.1/iPhone"
private const val APP_NAME = "Hommaterial"
private const val DEVICE_TYPE = "A2IVLV5VM2W81"

private fun base64Url(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

class AlexaAuth(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var session: Session? = prefs.getString("cookie", null)?.let { cookie ->
        prefs.getString("csrf", null)?.let { Session(cookie, it) }
    }

    val isLoggedIn: Boolean get() = prefs.contains("refreshToken")

    /** Defaults to the marketplace of the phone's country until the user picks one. */
    var marketplace: Marketplace
        get() {
            val saved = prefs.getString("marketplace", null)
            val country = Locale.getDefault().country
            return MARKETPLACES.find { it.domain == saved }
                ?: MARKETPLACES.find { it.locale.endsWith("-$country") }
                ?: MARKETPLACES.last()
        }
        set(value) = prefs.edit().putString("marketplace", value.domain).apply()

    fun newLoginAttempt(): LoginAttempt {
        val mapMd = """{"device_user_dictionary":[],"device_registration_data":{"software_version":"1"},""" +
            """"app_identifier":{"app_version":"$APP_VERSION","bundle_id":"com.amazon.echo"}}"""
        return LoginAttempt(
            // Hex of an upper-case hex serial, followed by hex("#" + DEVICE_TYPE).
            deviceId = randomBytes(16).hex().uppercase().toByteArray().hex() + "#$DEVICE_TYPE".toByteArray().hex(),
            frc = Base64.encodeToString(randomBytes(313), Base64.NO_WRAP),
            mapMd = Base64.encodeToString(mapMd.toByteArray(), Base64.NO_WRAP),
            verifier = base64Url(randomBytes(32)),
            locale = marketplace.locale,
        )
    }

    /** Trades the authorization code from the sign-in page for a long-lived refresh token. */
    suspend fun completeLogin(attempt: LoginAttempt, authorizationCode: String, loginCookies: String) =
        withContext(Dispatchers.IO) {
            val cookies = loginCookies.split(";").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
            }.toMap() + mapOf("frc" to attempt.frc, "map-md" to attempt.mapMd)

            val body = JSONObject()
                .put("requested_extensions", JSONArray(listOf("device_info", "customer_info")))
                .put(
                    "cookies",
                    JSONObject()
                        .put("domain", ".$LOGIN_DOMAIN")
                        .put(
                            "website_cookies",
                            JSONArray(cookies.map { JSONObject().put("Name", it.key).put("Value", it.value) }),
                        ),
                )
                .put(
                    "registration_data",
                    JSONObject()
                        .put("domain", "Device")
                        .put("app_version", APP_VERSION)
                        .put("device_type", DEVICE_TYPE)
                        .put("device_name", "%FIRST_NAME%'s%DUPE_STRATEGY_1ST%$APP_NAME")
                        .put("os_version", "18.3.1")
                        .put("device_serial", randomBytes(16).hex())
                        .put("device_model", "iPhone")
                        .put("app_name", APP_NAME)
                        .put("software_version", "1"),
                )
                .put(
                    "auth_data",
                    JSONObject()
                        .put("client_id", attempt.deviceId)
                        .put("authorization_code", authorizationCode)
                        .put("code_verifier", attempt.verifier)
                        .put("code_algorithm", "SHA-256")
                        .put("client_domain", "DeviceLegacy"),
                )
                .put("user_context_map", JSONObject().put("frc", attempt.frc))
                .put("requested_token_type", JSONArray(listOf("bearer", "mac_dms", "website_cookies")))

            val request = Request.Builder()
                .url("https://api.$LOGIN_DOMAIN/auth/register")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", marketplace.locale)
                .header("Accept", "application/json")
                .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .header("x-amzn-identity-auth-domain", "api.$LOGIN_DOMAIN")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val refreshToken = http.newCall(request).execute().use { response ->
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                json?.optJSONObject("response")?.optJSONObject("success")?.optJSONObject("tokens")
                    ?.optJSONObject("bearer")?.optString("refresh_token")?.takeIf { it.isNotEmpty() }
                    ?: throw Exception(str(R.string.registration_refused, response.code))
            }

            prefs.edit().remove("cookie").remove("csrf").putString("refreshToken", refreshToken).apply()
            session = null
            session(forceRefresh = true)
        }

    /** Returns the web session used by the Alexa API, creating it from the refresh token if needed. */
    suspend fun session(forceRefresh: Boolean = false): Session {
        val stale = session
        if (!forceRefresh) stale?.let { return it }
        return mutex.withLock {
            // Another caller may have refreshed while this one waited for the lock.
            session?.takeIf { it !== stale || !forceRefresh }?.let { return it }
            val fresh = withContext(Dispatchers.IO) { exchangeForSession() }
            prefs.edit().putString("cookie", fresh.cookie).putString("csrf", fresh.csrf).apply()
            session = fresh
            fresh
        }
    }

    private fun exchangeForSession(): Session {
        val refreshToken = prefs.getString("refreshToken", null) ?: throw NotLoggedInException()
        val market = marketplace
        val host = market.alexaHost

        val form = FormBody.Builder()
            .add("di.os.name", "iOS")
            .add("app_version", APP_VERSION)
            .add("domain", ".${market.domain}")
            .add("source_token", refreshToken)
            .add("requested_token_type", "auth_cookies")
            .add("source_token_type", "refresh_token")
            .add("di.hw.version", "iPhone")
            .add("di.sdk.version", "6.12.4")
            .add("app_name", APP_NAME)
            .add("di.os.version", "16.6")
            .build()
        val exchange = Request.Builder()
            .url("https://www.${market.domain}/ap/exchangetoken/cookies")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", market.locale)
            .header("Accept", "*/*")
            .header("x-amzn-identity-auth-domain", "api.${market.domain}")
            .post(form)
            .build()

        val jar = LinkedHashMap<String, String>()
        http.newCall(exchange).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            val cookies = json?.optJSONObject("response")?.optJSONObject("tokens")
                ?.optJSONObject("cookies")?.optJSONArray(".${market.domain}")
            if (cookies == null) {
                // The stored token is kept: a later sign-in replaces it, and a transient refusal heals itself.
                if (response.code == 400 || response.code == 401) {
                    throw NotLoggedInException(str(R.string.login_expired))
                }
                throw Exception(str(R.string.amazon_no_answer, response.code))
            }
            cookies.mapObjects { jar[it.getString("Name")] = it.getString("Value") }
        }

        // Any authenticated Alexa page hands out the csrf cookie the API requires.
        val csrfRequest = Request.Builder()
            .url("https://$host/api/language")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Referer", "https://$host/spa/index.html")
            .header("Origin", "https://$host")
            .header("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            .build()
        http.newCall(csrfRequest).execute().use { response ->
            for (header in response.headers("Set-Cookie")) {
                val pair = header.substringBefore(';')
                val i = pair.indexOf('=')
                if (i > 0) jar[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
        }
        val csrf = jar["csrf"] ?: throw Exception(str(R.string.amazon_no_csrf))
        return Session(jar.entries.joinToString("; ") { "${it.key}=${it.value}" }, csrf)
    }

    fun logout() {
        prefs.edit().remove("refreshToken").remove("cookie").remove("csrf").apply()
        session = null
    }
}
