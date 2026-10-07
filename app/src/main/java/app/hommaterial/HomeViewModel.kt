package app.hommaterial

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hommaterial.data.AlexaApi
import app.hommaterial.data.AlexaAuth
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.LoginAttempt
import app.hommaterial.data.Marketplace
import app.hommaterial.data.NotLoggedInException
import app.hommaterial.data.Update
import app.hommaterial.data.Updater
import app.hommaterial.data.mapObjects
import app.hommaterial.data.toJsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UiState(
    /** False until the first-run notice has been accepted. */
    val onboarded: Boolean,
    val loggedIn: Boolean,
    val marketplace: Marketplace,
    val loginMessage: String? = null,
    val signingIn: Boolean = false,
    val devices: List<Device> = emptyList(),
    val states: Map<String, DeviceState> = emptyMap(),
    /** applianceIds with a command in flight. */
    val busy: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val showHidden: Boolean = false,
    val refreshing: Boolean = false,
    /** A newer release found on GitHub, while it is being offered or downloaded. */
    val update: Update? = null,
    /** Download progress from 0 to 1, null when no download is running. */
    val updateProgress: Float? = null,
)

private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val auth = AlexaAuth(app, http)
    private val api = AlexaApi(auth, http)
    private val updater = Updater(app, http)
    private val cache = app.getSharedPreferences("cache", Context.MODE_PRIVATE)
    // Kept apart from the cache so that signing out does not bring the first-run notice back.
    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadCached())
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private var refreshJob: Job? = null
    private var updateJob: Job? = null

    val installedVersion: String get() = updater.installedVersion

    // The last known devices and states are shown instantly while the network catches up.
    private fun loadCached(): UiState {
        val devices = runCatching {
            JSONArray(cache.getString("devices", "[]")).mapObjects(Device::fromJson)
        }.getOrDefault(emptyList())
        val states = runCatching {
            val json = JSONObject(cache.getString("states", "{}")!!)
            json.keys().asSequence().associateWith { DeviceState.fromJson(json.getJSONObject(it)) }
        }.getOrDefault(emptyMap())
        return UiState(
            onboarded = settings.getBoolean("onboarded", false),
            loggedIn = auth.isLoggedIn,
            marketplace = auth.marketplace,
            devices = devices,
            states = states,
            hidden = cache.getStringSet("hidden", emptySet())!!.toSet(),
        )
    }

    private fun saveCache() {
        val s = _state.value
        val states = JSONObject().also { json -> s.states.forEach { (id, st) -> json.put(id, st.toJson()) } }
        cache.edit()
            .putString("devices", s.devices.toJsonArray().toString())
            .putString("states", states.toString())
            .apply()
    }

    fun acceptOnboarding() {
        settings.edit().putBoolean("onboarded", true).apply()
        _state.update { it.copy(onboarded = true) }
    }

    fun newLoginAttempt(): LoginAttempt = auth.newLoginAttempt()

    fun setMarketplace(marketplace: Marketplace) {
        auth.marketplace = marketplace
        _state.update { it.copy(marketplace = marketplace) }
    }

    fun completeLogin(attempt: LoginAttempt, code: String, cookies: String) {
        _state.update { it.copy(signingIn = true, loginMessage = null) }
        viewModelScope.launch {
            try {
                auth.completeLogin(attempt, code, cookies)
                _state.update { it.copy(loggedIn = true, signingIn = false) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(signingIn = false, loginMessage = e.message ?: "Accesso non riuscito") }
            }
        }
    }

    fun logout() {
        refreshJob?.cancel()
        auth.logout()
        cache.edit().clear().apply()
        _state.update { UiState(onboarded = true, loggedIn = false, marketplace = auth.marketplace, update = it.update, updateProgress = it.updateProgress) }
    }

    fun refresh() {
        if (!_state.value.loggedIn || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            guarded {
                coroutineScope {
                    // States of the already known devices are fetched alongside the device list.
                    val known = queryable(_state.value.devices)
                    val early = if (known.isNotEmpty()) async { api.states(known) } else null

                    val devices = api.devices()
                    _state.update { it.copy(devices = devices) }

                    val states = early?.await().orEmpty().toMutableMap()
                    val missing = queryable(devices).filter { it.applianceId !in states }
                    if (missing.isNotEmpty()) states += api.states(missing)
                    // Devices with a command in flight keep their optimistic state.
                    _state.update { s -> s.copy(states = s.states + states.filterKeys { it !in s.busy }) }
                    saveCache()
                }
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private fun queryable(devices: List<Device>): List<Device> {
        val s = _state.value
        return if (s.showHidden) devices else devices.filter { it.applianceId !in s.hidden }
    }

    fun setPower(device: Device, on: Boolean) = command(device, { it.copy(power = on) }) {
        api.setPower(device, on)
    }

    fun setBrightness(device: Device, percent: Int) = command(device, { it.copy(brightness = percent) }) {
        api.setBrightness(device, percent)
    }

    /** Applies [optimistic] right away and rolls it back if Alexa refuses the command. */
    private fun command(device: Device, optimistic: (DeviceState) -> DeviceState, send: suspend () -> Unit) {
        val id = device.applianceId
        val before = _state.value.states[id]
        _state.update {
            it.copy(states = it.states + (id to optimistic(before ?: DeviceState())), busy = it.busy + id)
        }
        viewModelScope.launch {
            val ok = guarded { send() }
            _state.update { s ->
                val states = if (ok) s.states else if (before != null) s.states + (id to before) else s.states - id
                s.copy(states = states, busy = s.busy - id)
            }
            if (ok) saveCache()
        }
    }

    fun setHidden(device: Device, hidden: Boolean) {
        _state.update {
            it.copy(hidden = if (hidden) it.hidden + device.applianceId else it.hidden - device.applianceId)
        }
        cache.edit().putStringSet("hidden", _state.value.hidden).apply()
    }

    fun toggleShowHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        if (_state.value.showHidden) refresh()
    }

    /**
     * Looks for a newer release on GitHub. The automatic check runs at most once a day and stays
     * silent unless it finds something; the [manual] one always reports its outcome.
     */
    fun checkForUpdate(manual: Boolean) {
        if (updateJob?.isActive == true || !_state.value.onboarded) return
        val now = System.currentTimeMillis()
        val last = settings.getLong("updateCheckedAt", 0)
        if (!manual && now - last in 0 until UPDATE_CHECK_INTERVAL_MS) return
        updateJob = viewModelScope.launch {
            try {
                val update = updater.check()
                settings.edit().putLong("updateCheckedAt", now).apply()
                if (update != null) {
                    _state.update { it.copy(update = update) }
                } else if (manual) {
                    _messages.tryEmit("Hai già l'ultima versione (${updater.installedVersion})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                if (manual) _messages.tryEmit("Nessuna connessione")
            } catch (e: Exception) {
                if (manual) _messages.tryEmit(e.message ?: "Controllo degli aggiornamenti non riuscito")
            }
        }
    }

    fun installUpdate() {
        val update = _state.value.update ?: return
        if (updateJob?.isActive == true) return
        _state.update { it.copy(updateProgress = 0f) }
        updateJob = viewModelScope.launch {
            try {
                val apk = updater.download(update) { p -> _state.update { it.copy(updateProgress = p) } }
                updater.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                _messages.tryEmit("Download non riuscito, controlla la connessione")
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Aggiornamento non riuscito")
            }
            _state.update { it.copy(update = null, updateProgress = null) }
        }
    }

    /** Closes the offer, cancelling the download if one is running. */
    fun dismissUpdate() {
        updateJob?.cancel()
        _state.update { it.copy(update = null, updateProgress = null) }
    }

    /** Runs [block], turning failures into a message or a return to the sign-in screen. */
    private suspend fun guarded(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotLoggedInException) {
        _state.update { it.copy(loggedIn = false, loginMessage = e.message) }
        false
    } catch (e: java.io.IOException) {
        _messages.tryEmit("Nessuna connessione")
        false
    } catch (e: Exception) {
        _messages.tryEmit(e.message ?: "Qualcosa è andato storto")
        false
    }
}
