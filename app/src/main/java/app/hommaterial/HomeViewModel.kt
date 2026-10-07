package app.hommaterial

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hommaterial.data.Alexa
import app.hommaterial.data.Cache
import app.hommaterial.data.ColorChoice
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.History
import app.hommaterial.data.LoginAttempt
import app.hommaterial.data.Marketplace
import app.hommaterial.data.NotLoggedInException
import app.hommaterial.data.Reading
import app.hommaterial.data.Update
import app.hommaterial.data.Updater
import app.hommaterial.quick.PowerTile
import app.hommaterial.quick.Quick
import app.hommaterial.quick.Timers
import app.hommaterial.voice.VoiceCommand
import app.hommaterial.voice.VoiceResult
import app.hommaterial.voice.parseVoice
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
    /** applianceIds repeated in the section at the top of the list. */
    val favorites: Set<String> = emptySet(),
    /** applianceId of the device driven by each quick settings tile, null for the free ones. */
    val tileDevices: List<String?> = emptyList(),
    /** Switch-off time of each device with a timer, in epoch milliseconds. */
    val timers: Map<String, Long> = emptyMap(),
    val refreshing: Boolean = false,
    /** A spoken command understood only in part, waiting for the user to pick what was meant. */
    val voice: VoiceResult.Ask? = null,
    /** When the states were last fetched from Alexa, in epoch milliseconds. */
    val updatedAt: Long? = null,
    /** A newer release found on GitHub, while it is being offered or downloaded. */
    val update: Update? = null,
    /** Download progress from 0 to 1, null when no download is running. */
    val updateProgress: Float? = null,
)

private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val alexa = Alexa.get(app)
    private val auth = alexa.auth
    private val api = alexa.api
    private val updater = Updater(app, alexa.http)
    private val local = Cache(app)
    private val cache = local.prefs
    private val history = History(app)
    // Kept apart from the cache so that signing out does not bring the first-run notice back.
    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadCached())
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private var refreshJob: Job? = null
    private var updateJob: Job? = null

    init {
        // Shortcuts and widget may be stale after an update of the app or a change made elsewhere.
        Quick.refresh(app)
        // The tile, the widget, the shortcuts and the timers act while the app is open too.
        viewModelScope.launch {
            Quick.changes.collect {
                _state.update { s ->
                    s.copy(
                        states = s.states + local.states().filterKeys { it !in s.busy },
                        timers = Timers.all(getApplication()),
                    )
                }
            }
        }
    }

    val installedVersion: String get() = updater.installedVersion

    // The last known devices and states are shown instantly while the network catches up.
    private fun loadCached(): UiState {
        return UiState(
            onboarded = settings.getBoolean("onboarded", false),
            loggedIn = auth.isLoggedIn,
            marketplace = auth.marketplace,
            devices = local.devices(),
            states = local.states(),
            updatedAt = cache.getLong("updatedAt", 0).takeIf { it > 0 },
            hidden = local.hidden(),
            favorites = local.favorites(),
            tileDevices = local.tileDevices(),
            timers = Timers.all(getApplication()),
        )
    }

    private fun saveCache() {
        val s = _state.value
        cache.edit().putString("devices", s.devices.toJsonArray().toString()).apply()
        local.putStates(s.states)
        Quick.refresh(getApplication())
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
        Timers.cancelAll(getApplication())
        cache.edit().clear().apply()
        history.clear()
        Quick.refresh(getApplication())
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
                    history.record(states)
                    // Devices with a command in flight keep their optimistic state.
                    val now = System.currentTimeMillis()
                    _state.update { s ->
                        s.copy(states = s.states + states.filterKeys { it !in s.busy }, updatedAt = now)
                    }
                    cache.edit().putLong("updatedAt", now).apply()
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

    fun setPower(device: Device, on: Boolean) {
        // A device switched off by hand no longer needs its timer.
        if (!on) cancelTimer(device)
        command(device, { it.copy(power = on) }) { api.setPower(device, on) }
    }

    /** Sets a color or a shade of white, by the name Alexa gives it. Lamps switch on when asked for one. */
    fun setColor(device: Device, choice: ColorChoice) =
        command(device, { it.copy(colorName = choice.alexaName, power = true) }) {
            if (choice.white) {
                api.setColorTemperature(device, choice.alexaName)
            } else {
                api.setColor(device, choice.alexaName)
            }
        }

    /** Switches [device] off in [minutes] minutes. */
    fun setTimer(device: Device, minutes: Int) {
        Timers.set(getApplication(), device.applianceId, System.currentTimeMillis() + minutes * 60_000L)
        _state.update { it.copy(timers = Timers.all(getApplication())) }
    }

    fun cancelTimer(device: Device) {
        Timers.cancel(getApplication(), device.applianceId)
        _state.update { it.copy(timers = Timers.all(getApplication())) }
    }

    fun setBrightness(device: Device, percent: Int) = command(device, { it.copy(brightness = percent) }) {
        api.setBrightness(device, percent)
    }

    /** Switches every device of a room that is not already in the requested state. */
    fun setRoomPower(devices: List<Device>, on: Boolean) {
        val s = _state.value
        devices
            .filter { it.hasPower && it.applianceId !in s.busy && s.states[it.applianceId]?.power != on }
            .forEach { setPower(it, on) }
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
        Quick.refresh(getApplication())
    }

    fun setFavorite(device: Device, favorite: Boolean) {
        _state.update {
            it.copy(favorites = if (favorite) it.favorites + device.applianceId else it.favorites - device.applianceId)
        }
        cache.edit().putStringSet("favorites", _state.value.favorites).apply()
        Quick.refresh(getApplication())
    }

    /** Gives [device] the first free quick settings tile, or takes its tile away. */
    fun setOnTile(device: Device, onTile: Boolean) {
        val tiles = _state.value.tileDevices
        val slot = tiles.indexOf(if (onTile) null else device.applianceId)
        if (slot < 0 || (onTile && device.applianceId in tiles)) return
        local.putTileDevice(slot, device.applianceId.takeIf { onTile })
        _state.update { it.copy(tileDevices = local.tileDevices()) }
        if (onTile) PowerTile.offer(getApplication(), slot, device.name)
    }

    /** Acts on what the speech recognizer [heard], its guesses best first. */
    fun onSpeech(heard: List<String>) {
        val s = _state.value
        when (val result = parseVoice(heard, s.devices.filter { it.applianceId !in s.hidden })) {
            is VoiceResult.Run -> runVoice(result.command)
            is VoiceResult.Ask -> _state.update { it.copy(voice = result) }
            is VoiceResult.Unknown -> _messages.tryEmit(
                if (result.heard.isBlank()) "Non ho sentito niente" else "Non ho capito «${result.heard}»",
            )
        }
    }

    fun runVoice(command: VoiceCommand) {
        dismissVoice()
        _messages.tryEmit(command.label)
        if (command.room != null) {
            setRoomPower(command.devices, command.on)
        } else {
            setPower(command.devices.single(), command.on)
        }
    }

    fun dismissVoice() = _state.update { it.copy(voice = null) }

    fun speechUnavailable() {
        _messages.tryEmit("Riconoscimento vocale non disponibile su questo telefono")
    }

    /** Copies to the clipboard everything Alexa says about [device], for writing support for it. */
    fun copyDiagnostics(device: Device) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val copied = guarded {
                val text = api.diagnostics(device)
                app.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Hommaterial: ${device.name}", text))
            }
            if (copied) Toast.makeText(app, "Dati tecnici copiati negli appunti", Toast.LENGTH_SHORT).show()
        }
    }

    fun readings(device: Device): List<Reading> = history.readings(device.applianceId)

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
