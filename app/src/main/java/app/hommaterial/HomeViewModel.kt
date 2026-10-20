package app.hommaterial

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hommaterial.R
import app.hommaterial.data.AcSetting
import app.hommaterial.data.Alexa
import app.hommaterial.data.Automation
import app.hommaterial.data.Cache
import app.hommaterial.data.ColorChoice
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.Group
import app.hommaterial.data.History
import app.hommaterial.data.LoginAttempt
import app.hommaterial.data.Macro
import app.hommaterial.data.MacroAction
import app.hommaterial.data.Marketplace
import app.hommaterial.data.MonitorConfig
import app.hommaterial.data.NotLoggedInException
import app.hommaterial.data.Reading
import app.hommaterial.data.Remote
import app.hommaterial.data.RemoteKind
import app.hommaterial.data.Schedule
import app.hommaterial.data.SwitchBot
import app.hommaterial.data.Update
import app.hommaterial.data.Updater
import app.hommaterial.data.WidgetConfig
import app.hommaterial.data.mapObjects
import app.hommaterial.data.nextTime
import app.hommaterial.data.toJsonArray
import app.hommaterial.plural
import app.hommaterial.quick.Alert
import app.hommaterial.quick.Alerts
import app.hommaterial.quick.Automations
import app.hommaterial.quick.PowerTile
import app.hommaterial.quick.Quick
import app.hommaterial.quick.Schedules
import app.hommaterial.quick.Timers
import app.hommaterial.str
import app.hommaterial.ui.HAPTIC_LEVEL_DEFAULT
import app.hommaterial.ui.HAPTIC_LEVEL_MIN
import app.hommaterial.ui.Haptic
import app.hommaterial.voice.ExtraCommand
import app.hommaterial.voice.VoiceCommand
import app.hommaterial.voice.VoiceResult
import app.hommaterial.voice.parseExtras
import app.hommaterial.voice.parseVoice
import java.security.MessageDigest
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    /** The room the user moved each device to: a name, or an empty text for no room. */
    val rooms: Map<String, String> = emptyMap(),
    /** The rooms the user created, which exist even while no device is in them. */
    val roomList: List<String> = emptyList(),
    val groups: List<Group> = emptyList(),
    /** The order the user gave the tiles of each section of the home. See [Cache.order]. */
    val order: Map<String, List<String>> = emptyMap(),
    /** The custom devices: tiles that run a series of actions. */
    val macros: List<Macro> = emptyList(),
    /** What is listed under the app icon, or null for the favorites. See [Cache.shortcuts]. */
    val shortcuts: List<String>? = null,
    val widget: WidgetConfig = WidgetConfig(),
    val showHidden: Boolean = false,
    /** applianceIds repeated in the section at the top of the list. */
    val favorites: Set<String> = emptySet(),
    /** applianceId of the device driven by each quick settings tile, null for the free ones. */
    val tileDevices: List<String?> = emptyList(),
    /** Switch-off time of each device with a timer, in epoch milliseconds. */
    val timers: Map<String, Long> = emptyMap(),
    /** The actions set to be carried out at a time, the next one first. */
    val schedules: List<Schedule> = emptyList(),
    /** What is done by itself when a device gets to a state. */
    val automations: List<Automation> = emptyList(),
    /** Temperature thresholds each sensor notifies about. */
    val alerts: Map<String, Alert> = emptyMap(),
    /** Whether the app looks for a newer release by itself, once a day. */
    val autoUpdate: Boolean = true,
    /** Whether touches and commands answer with a short vibration. */
    val haptics: Boolean = true,
    /** How strong those vibrations are, in percent. */
    val hapticLevel: Int = HAPTIC_LEVEL_DEFAULT,
    /** How the tiles of the home are laid out: [LAYOUT_GRID] or [LAYOUT_LIST]. */
    val layout: Int = LAYOUT_GRID,
    /** 0 follows the phone, 1 is always light, 2 always dark. */
    val theme: Int = 0,
    /** How this tablet behaves as the panel of the home. */
    val monitor: MonitorConfig = MonitorConfig(),
    /** Whether the SwitchBot account is linked, for the infrared [remotes] of its hubs. */
    val switchBot: Boolean = false,
    val remotes: List<Remote> = emptyList(),
    /** The buttons added by hand to each remote, by remote. See [SwitchBot.buttons]. */
    val remoteButtons: Map<String, List<String>> = emptyMap(),
    val acSettings: Map<String, AcSetting> = emptyMap(),
    val hiddenRemotes: Set<String> = emptySet(),
    /** Whether a PIN was chosen for the monitor mode. */
    val pin: Boolean = false,
    /** Whether a backup being imported is waiting to know if its monitor mode comes along. */
    val askMonitorRestore: Boolean = false,
    val refreshing: Boolean = false,
    /** A spoken command understood only in part, waiting for the user to pick what was meant. */
    val voice: VoiceResult.Ask? = null,
    /** When the states were last fetched from Alexa, in epoch milliseconds. */
    val updatedAt: Long? = null,
    /** A newer release found on GitHub, while it is being offered or downloaded. */
    val update: Update? = null,
    /** Whether [update] is being offered in a dialog; the settings offer it without one. */
    val updatePrompt: Boolean = false,
    /** Download progress from 0 to 1, null when no download is running. */
    val updateProgress: Float? = null,
) {
    /** The devices in the rooms the user put them in, which are those of Alexa unless moved. */
    val placed: List<Device>
        get() = devices.map { device ->
            rooms[device.applianceId]?.let { device.copy(room = it.ifEmpty { null }) } ?: device
        }

    /** The room a remote or a custom device was put in, by its key; null when it is in none. */
    fun placeOf(key: String): String? = rooms[key]?.ifEmpty { null }

    /** Every room there is: of the devices, made in the app, or holding only remotes and custom devices. */
    val allRooms: List<String>
        get() = (placed.mapNotNull { it.room } + roomList + rooms.filterKeys { ':' in it }.values)
            .filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

    /** The remotes in the list: all but the hidden ones. */
    val shownRemotes: List<Remote> get() = remotes.filter { it.id !in hiddenRemotes }

    /** Whether the settings and the choices about the devices are behind the PIN of the monitor. */
    val locked: Boolean get() = monitor.enabled && pin
}

private const val BACKUP_APP = "hommaterial"
const val LAYOUT_GRID = 0
const val LAYOUT_LIST = 1
private const val PIN_KEY = "monitorPin"
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val alexa = Alexa.get(app)
    private val auth = alexa.auth
    private val api = alexa.api
    private val updater = Updater(app, alexa.http)
    private val switchBot = SwitchBot(app, alexa.http)
    private val local = Cache(app)
    private val cache = local.prefs
    private val history = History(app)
    // Kept apart from the cache so that signing out does not bring the first-run notice back.
    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadCached())
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    // What the commands are felt as: at once when asked, and again when one fails or a custom device is done.
    private val _haptics = MutableSharedFlow<Haptic>(extraBufferCapacity = 8)
    val haptics = _haptics.asSharedFlow()

    private var refreshJob: Job? = null
    private var updateJob: Job? = null
    private var pendingBackup: JSONObject? = null

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
                        // One that does not repeat is gone once it has run.
                        schedules = Schedules.all(getApplication()),
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
            rooms = local.rooms(),
            roomList = local.roomList(),
            groups = local.groups(),
            macros = local.macros(),
            order = local.order(),
            shortcuts = local.shortcuts(),
            widget = local.widget(),
            favorites = local.favorites(),
            tileDevices = local.tileDevices(),
            timers = Timers.all(getApplication()),
            schedules = Schedules.all(getApplication()),
            automations = Automations.all(getApplication()),
            alerts = Alerts.all(getApplication()),
            autoUpdate = settings.getBoolean("autoUpdate", true),
            haptics = settings.getBoolean("haptics", true),
            hapticLevel = settings.getInt("hapticLevel", HAPTIC_LEVEL_DEFAULT),
            theme = settings.getInt("theme", 0),
            layout = settings.getInt("layout", LAYOUT_GRID),
            monitor = runCatching {
                MonitorConfig.fromJson(JSONObject(settings.getString("monitor", "{}")!!))
            }.getOrDefault(MonitorConfig()),
            pin = settings.contains(PIN_KEY),
            switchBot = switchBot.connected,
            remotes = switchBot.remotes(),
            remoteButtons = switchBot.buttons(),
            acSettings = switchBot.acSettings(),
            hiddenRemotes = switchBot.hidden(),
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
                _state.update { it.copy(signingIn = false, loginMessage = e.message ?: str(R.string.login_failed)) }
            }
        }
    }

    fun logout() {
        refreshJob?.cancel()
        auth.logout()
        Timers.cancelAll(getApplication())
        Schedules.cancelAll(getApplication())
        // The automations go with the cache cleared here; the check they lean on stops right after.
        cache.edit().clear().apply()
        Alerts.schedule(getApplication())
        history.clear()
        Quick.refresh(getApplication())
        _state.update {
            UiState(
                onboarded = true,
                loggedIn = false,
                marketplace = auth.marketplace,
                autoUpdate = it.autoUpdate,
                theme = it.theme,
                layout = it.layout,
                monitor = it.monitor,
                pin = it.pin,
                // SwitchBot is another account: signing out of Amazon leaves it linked.
                switchBot = it.switchBot,
                remotes = it.remotes,
                remoteButtons = it.remoteButtons,
                acSettings = it.acSettings,
                hiddenRemotes = it.hiddenRemotes,
                update = it.update,
                updatePrompt = it.updatePrompt,
                updateProgress = it.updateProgress,
            )
        }
    }

    fun refresh() {
        if (!_state.value.loggedIn || refreshJob?.isActive == true) return
        // The remotes change rarely and have no state: they are fetched on the side, without a word.
        if (switchBot.connected) {
            viewModelScope.launch {
                guarded(quiet = true) { _state.update { it.copy(remotes = switchBot.fetchRemotes()) } }
            }
        }
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
                    received(states)
                }
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    /**
     * Fetches the states alone and says nothing when it cannot: for a screen that stays open and
     * asks again shortly. The list of devices rarely changes and waits for a real [refresh].
     */
    fun poll() {
        if (!_state.value.loggedIn || refreshJob?.isActive == true) return
        val known = queryable(_state.value.devices)
        if (known.isEmpty()) return refresh()
        refreshJob = viewModelScope.launch { guarded(quiet = true) { received(api.states(known)) } }
    }

    private fun received(states: Map<String, DeviceState>) {
        history.record(states)
        Alerts.check(getApplication(), states)
        Automations.check(getApplication(), states)
        // Devices with a command in flight keep their optimistic state.
        val now = System.currentTimeMillis()
        _state.update { s -> s.copy(states = s.states + states.filterKeys { it !in s.busy }, updatedAt = now) }
        cache.edit().putLong("updatedAt", now).apply()
        saveCache()
    }

    private fun queryable(devices: List<Device>): List<Device> {
        val s = _state.value
        return if (s.showHidden) devices else devices.filter { it.applianceId !in s.hidden }
    }

    fun setPower(device: Device, on: Boolean) {
        _haptics.tryEmit(if (on) Haptic.On else Haptic.Off)
        switch(device, on)
    }

    private fun switch(device: Device, on: Boolean) {
        // A device switched off by hand no longer needs its timer.
        if (!on) cancelTimer(device)
        command(device, { it.copy(power = on) }) { api.setPower(device, on) }
    }

    /** Sets a color or a shade of white, by the name Alexa gives it. Lamps switch on when asked for one. */
    fun setColor(device: Device, choice: ColorChoice) {
        _haptics.tryEmit(Haptic.Tick)
        command(device, { it.copy(colorName = choice.alexaName, power = true) }) {
            if (choice.white) {
                api.setColorTemperature(device, choice.alexaName)
            } else {
                api.setColor(device, choice.alexaName)
            }
        }
    }

    /** Switches [device] off in [minutes] minutes. */
    fun setTimer(device: Device, minutes: Int) {
        _haptics.tryEmit(Haptic.Confirm)
        Timers.set(getApplication(), device.applianceId, System.currentTimeMillis() + minutes * 60_000L)
        _state.update { it.copy(timers = Timers.all(getApplication())) }
    }

    fun cancelTimer(device: Device) = cancelTimer(device.applianceId)

    fun cancelTimer(applianceId: String) {
        Timers.cancel(getApplication(), applianceId)
        _state.update { it.copy(timers = Timers.all(getApplication())) }
    }

    fun setBrightness(device: Device, percent: Int) = command(device, { it.copy(brightness = percent) }) {
        api.setBrightness(device, percent)
    }

    /** Switches every device of a room or group that is not already in the requested state. */
    fun setRoomPower(devices: List<Device>, on: Boolean) {
        _haptics.tryEmit(if (on) Haptic.On else Haptic.Off)
        val s = _state.value
        devices
            .filter { it.hasPower && it.applianceId !in s.busy && s.states[it.applianceId]?.power != on }
            .forEach { switch(it, on) }
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
            if (!ok) _haptics.tryEmit(Haptic.Reject)
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

    fun setFavorite(device: Device, favorite: Boolean) = setFavorite(device.applianceId, favorite)

    /** The same for a remote or a custom device, by its key. */
    fun setFavorite(key: String, favorite: Boolean) {
        _state.update { it.copy(favorites = if (favorite) it.favorites + key else it.favorites - key) }
        cache.edit().putStringSet("favorites", _state.value.favorites).apply()
        Quick.refresh(getApplication())
    }

    /** Gives [device] the first free quick settings tile, or takes its tile away. */
    fun setOnTile(device: Device, onTile: Boolean) = setOnTile(device.applianceId, device.name, onTile)

    /** The same for a custom device, which is on a tile to be run. */
    fun setOnTile(macro: Macro, onTile: Boolean) = setOnTile(Quick.macroKey(macro.id), macro.name, onTile)

    private fun setOnTile(key: String, name: String, onTile: Boolean) {
        val tiles = _state.value.tileDevices
        val slot = tiles.indexOf(if (onTile) null else key)
        if (slot < 0 || (onTile && key in tiles)) return
        local.putTileDevice(slot, key.takeIf { onTile })
        _state.update { it.copy(tileDevices = local.tileDevices()) }
        if (onTile) PowerTile.offer(getApplication(), slot, name)
    }

    /** Acts on what the speech recognizer [heard], its guesses best first. */
    fun onSpeech(heard: List<String>) {
        val s = _state.value
        val result = parseVoice(heard, s.placed.filter { it.applianceId !in s.hidden })
        // What Alexa does not know is tried when no device of hers was clearly meant.
        if (result !is VoiceResult.Run) {
            // A remote learnt key by key has no power key to be told about.
            val remotes = s.shownRemotes.filter { it.kind != RemoteKind.CUSTOM }
            val extra = parseExtras(heard, s.macros.map { it.id to it.name }, remotes.map { it.id to it.name })
            if (extra is ExtraCommand.Run) {
                val macro = s.macros.first { it.id == extra.macroId }
                _messages.tryEmit(str(R.string.schedule_run, macro.name))
                return runMacro(macro)
            }
            if (extra is ExtraCommand.Remote) {
                val remote = remotes.first { it.id == extra.remoteId }
                _messages.tryEmit(str(if (extra.on) R.string.voice_on else R.string.voice_off, remote.name))
                return sendRemote(remote, if (extra.on) "turnOn" else "turnOff")
            }
        }
        when (result) {
            is VoiceResult.Run -> runVoice(result.command)
            is VoiceResult.Ask -> _state.update { it.copy(voice = result) }
            is VoiceResult.Unknown -> {
                _haptics.tryEmit(Haptic.Reject)
                _messages.tryEmit(
                    if (result.heard.isBlank()) {
                        str(R.string.voice_nothing_heard)
                    } else {
                        str(R.string.voice_not_understood, result.heard)
                    },
                )
            }
        }
    }

    fun runVoice(command: VoiceCommand) {
        dismissVoice()
        _messages.tryEmit(command.label())
        if (command.room != null) {
            setRoomPower(command.devices, command.on)
        } else {
            setPower(command.devices.single(), command.on)
        }
    }

    fun dismissVoice() = _state.update { it.copy(voice = null) }

    fun speechUnavailable() {
        _messages.tryEmit(str(R.string.voice_unavailable))
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
            if (copied) Toast.makeText(app, str(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
        }
    }

    fun setAlert(device: Device, alert: Alert) = setAlert(device.applianceId, alert)

    fun clearAlert(applianceId: String) = setAlert(applianceId, Alert())

    private fun setAlert(applianceId: String, alert: Alert) {
        Alerts.set(getApplication(), applianceId, alert)
        _state.update { it.copy(alerts = Alerts.all(getApplication())) }
    }

    /** Moves a device to [room]: a name, an empty text for no room, or null to follow Alexa again. */
    fun setRoom(device: Device, room: String?) {
        val rooms = _state.value.rooms
        saveRooms(if (room == null) rooms - device.applianceId else rooms + (device.applianceId to room))
    }

    /**
     * Creates the room [name] or edits the room [old]: gives it the name [name] and puts in it
     * exactly the devices in [members]. Those taken out go back to their Alexa room.
     */
    fun saveRoom(old: String?, name: String, members: Set<String>) {
        val s = _state.value
        val rooms = s.rooms.toMutableMap()
        for (device in s.devices) {
            val id = device.applianceId
            val wasIn = old != null && (rooms[id] ?: device.room) == old
            when {
                id in members && device.room == name -> rooms.remove(id)
                id in members -> rooms[id] = name
                // Out of its own Alexa room a device can only go to no room at all.
                wasIn && device.room == old -> rooms[id] = ""
                wasIn -> rooms.remove(id)
            }
        }
        // Remotes and custom devices have no room of their own to go back to.
        for (key in s.macros.map { Quick.macroKey(it.id) } + s.remotes.map { Quick.remoteKey(it.id) }) {
            if (key in members) rooms[key] = name else if (old != null && rooms[key] == old) rooms.remove(key)
        }
        val list = (s.roomList.map { if (it == old) name else it } + name).distinct()
        saveRooms(rooms, list)
    }

    /** Puts a remote or a custom device, by its key, in [room]; null or an empty text takes it out. */
    fun setPlace(key: String, room: String?) {
        val rooms = _state.value.rooms
        saveRooms(if (room.isNullOrEmpty()) rooms - key else rooms + (key to room))
    }

    /** Sets [action] to be carried out [at] a moment, and again on each of the [days] if there are any. */
    fun addSchedule(action: MacroAction, label: String, at: Long, days: Set<Int>) {
        _haptics.tryEmit(Haptic.Confirm)
        val time = Calendar.getInstance().apply { timeInMillis = at }
        val schedule = Schedule(
            id = UUID.randomUUID().toString(),
            action = action,
            label = label,
            at = at,
            hour = time.get(Calendar.HOUR_OF_DAY),
            minute = time.get(Calendar.MINUTE),
            days = days,
        )
        Schedules.put(getApplication(), schedule)
        _state.update { it.copy(schedules = Schedules.all(getApplication())) }
    }

    /** Creates an automation: [action] when [device] gets to what [trigger] and [threshold] say. */
    fun addAutomation(
        device: Device,
        trigger: String,
        threshold: Double,
        action: MacroAction,
        title: String,
        label: String,
    ) {
        val id = UUID.randomUUID().toString()
        val automation = Automation(id, device.applianceId, trigger, threshold, action, title, label)
        // Already so: it waits for the next time, instead of going off for what was there before it.
        val holds = _state.value.states[device.applianceId]?.let(automation::holds) == true
        Automations.put(getApplication(), automation, holds)
        _state.update { it.copy(automations = Automations.all(getApplication())) }
    }

    fun setAutomationEnabled(automation: Automation, enabled: Boolean) {
        val holds = _state.value.states[automation.device]?.let(automation::holds) == true
        Automations.put(getApplication(), automation.copy(enabled = enabled), holds)
        _state.update { it.copy(automations = Automations.all(getApplication())) }
    }

    fun deleteAutomation(id: String) {
        Automations.delete(getApplication(), id)
        _state.update { it.copy(automations = Automations.all(getApplication())) }
    }

    /** Sets [action] to happen once, in [minutes] minutes: the timer of what is not a device of Alexa. */
    fun addTimer(action: MacroAction, label: String, minutes: Int) =
        addSchedule(action, label, System.currentTimeMillis() + minutes * 60_000L, emptySet())

    /** Switches a repeating schedule off, or on again from its next time. */
    fun setScheduleEnabled(schedule: Schedule, enabled: Boolean) {
        val now = System.currentTimeMillis()
        val at = if (enabled) nextTime(schedule.hour, schedule.minute, schedule.days, now) else schedule.at
        Schedules.put(getApplication(), schedule.copy(enabled = enabled, at = at))
        _state.update { it.copy(schedules = Schedules.all(getApplication())) }
    }

    fun deleteSchedule(id: String) {
        Schedules.delete(getApplication(), id)
        _state.update { it.copy(schedules = Schedules.all(getApplication())) }
    }

    /** Removes a room created in the app; its devices go back to their Alexa room. */
    fun deleteRoom(room: String) {
        val s = _state.value
        saveRooms(s.rooms.filterValues { it != room }, s.roomList - room)
    }

    fun resetRooms() = saveRooms(emptyMap(), emptyList())

    private fun saveRooms(rooms: Map<String, String>, list: List<String> = _state.value.roomList) {
        local.putRooms(rooms)
        local.putRoomList(list)
        _state.update { it.copy(rooms = rooms, roomList = list) }
    }

    /** Creates a group, when [id] is null, or changes its name and devices. */
    fun saveGroup(id: String?, name: String, members: Set<String>) {
        val groups = _state.value.groups
        val group = Group(id ?: UUID.randomUUID().toString(), name, members)
        saveGroups(if (id == null) groups + group else groups.map { if (it.id == id) group else it })
    }

    fun deleteGroup(id: String) = saveGroups(_state.value.groups.filter { it.id != id })

    private fun saveGroups(groups: List<Group>) {
        local.putGroups(groups)
        _state.update { it.copy(groups = groups) }
        Quick.refresh(getApplication())
    }

    fun setOrder(order: Map<String, List<String>>) {
        local.putOrder(order)
        _state.update { it.copy(order = order) }
    }

    /** Creates a custom device, when its id is empty, or replaces the one with the same id. */
    fun saveMacro(macro: Macro) {
        val macros = _state.value.macros
        saveMacros(
            if (macro.id.isEmpty()) {
                macros + macro.copy(id = UUID.randomUUID().toString())
            } else {
                macros.map { if (it.id == macro.id) macro else it }
            },
        )
    }

    fun deleteMacro(id: String) {
        // Its tile goes back to being free.
        _state.value.macros.find { it.id == id }?.let { setOnTile(it, onTile = false) }
        setPlace(Quick.macroKey(id), null)
        saveMacros(_state.value.macros.filter { it.id != id })
    }

    private fun saveMacros(macros: List<Macro>) {
        local.putMacros(macros)
        _state.update { it.copy(macros = macros) }
        Quick.refresh(getApplication())
    }

    /** Runs a custom device as the widget and the tiles do, and says how it went. */
    fun runMacro(macro: Macro) {
        if (macro.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + macro.id) }
        _haptics.tryEmit(Haptic.Key)
        viewModelScope.launch {
            val outcome = Quick.runMacro(getApplication(), macro.id)
            _haptics.tryEmit(if (outcome.ok) Haptic.Confirm else Haptic.Reject)
            if (outcome.loginExpired) {
                _state.update { it.copy(loggedIn = false, loginMessage = str(R.string.login_expired)) }
            } else {
                _messages.tryEmit(outcome.message)
            }
            _state.update { it.copy(busy = it.busy - macro.id) }
        }
    }

    /** Switches a group off if any of its devices is on, on otherwise. */
    fun toggleGroup(group: Group) {
        val s = _state.value
        val devices = s.devices.filter {
            it.hasPower && it.applianceId in group.devices && it.applianceId !in s.hidden
        }
        setRoomPower(devices, on = devices.none { s.states[it.applianceId]?.power == true })
    }

    fun setWidget(config: WidgetConfig) {
        local.putWidget(config)
        _state.update { it.copy(widget = config) }
        Quick.refresh(getApplication())
    }

    /** Chooses what is listed under the app icon; null goes back to the favorites. */
    fun setShortcuts(shortcuts: List<String>?) {
        local.putShortcuts(shortcuts)
        _state.update { it.copy(shortcuts = shortcuts) }
        Quick.refresh(getApplication())
    }

    fun clearTile(slot: Int) {
        local.putTileDevice(slot, null)
        _state.update { it.copy(tileDevices = local.tileDevices()) }
    }

    fun setAutoUpdate(on: Boolean) {
        settings.edit().putBoolean("autoUpdate", on).apply()
        _state.update { it.copy(autoUpdate = on) }
    }

    fun setHaptics(on: Boolean) {
        settings.edit().putBoolean("haptics", on).apply()
        _state.update { it.copy(haptics = on) }
    }

    fun setHapticLevel(level: Int) {
        settings.edit().putInt("hapticLevel", level).apply()
        _state.update { it.copy(hapticLevel = level) }
    }

    fun setLayout(layout: Int) {
        settings.edit().putInt("layout", layout).apply()
        _state.update { it.copy(layout = layout) }
    }

    fun setTheme(theme: Int) {
        settings.edit().putInt("theme", theme).apply()
        _state.update { it.copy(theme = theme) }
    }

    fun setMonitor(config: MonitorConfig) {
        settings.edit().putString("monitor", config.toJson().toString()).apply()
        _state.update { it.copy(monitor = config) }
    }

    /**
     * Sets the PIN of the monitor mode, or removes it with null. It keeps guests and children out
     * of the settings; it is not meant to resist someone who can read the storage of the tablet.
     */
    fun setPin(pin: String?) {
        if (pin == null) {
            settings.edit().remove(PIN_KEY).apply()
        } else {
            val salt = UUID.randomUUID().toString()
            settings.edit().putString(PIN_KEY, "$salt:${hash(salt, pin)}").apply()
        }
        _state.update { it.copy(pin = pin != null) }
    }

    fun checkPin(pin: String): Boolean {
        val (salt, hashed) = settings.getString(PIN_KEY, null)?.split(':')?.takeIf { it.size == 2 } ?: return true
        return hash(salt, pin) == hashed
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Links the SwitchBot account, keeping the keys only if SwitchBot accepts them. */
    fun connectSwitchBot(token: String, secret: String) {
        switchBot.connect(token.trim(), secret.trim())
        viewModelScope.launch {
            val linked = guarded {
                val remotes = switchBot.fetchRemotes()
                _state.update { it.copy(switchBot = true, remotes = remotes) }
                _messages.tryEmit(plural(R.plurals.remotes_found, remotes.size))
            }
            if (!linked) switchBot.disconnect()
        }
    }

    fun disconnectSwitchBot() {
        switchBot.disconnect()
        _state.update {
            it.copy(
                switchBot = false,
                remotes = emptyList(),
                remoteButtons = emptyMap(),
                acSettings = emptyMap(),
                hiddenRemotes = emptySet(),
            )
        }
    }

    /** Presses a button of [remote]. Nothing comes back from an appliance: only a failure is told. */
    fun sendRemote(remote: Remote, command: String, parameter: String = "default", custom: Boolean = false) {
        val haptic = when (command) {
            "turnOn" -> Haptic.On
            "turnOff" -> Haptic.Off
            else -> Haptic.Key
        }
        send(remote, command, parameter, custom, haptic)
    }

    private fun send(remote: Remote, command: String, parameter: String, custom: Boolean, haptic: Haptic) {
        _state.update { it.copy(busy = it.busy + remote.id) }
        _haptics.tryEmit(haptic)
        viewModelScope.launch {
            if (!guarded { switchBot.send(remote, command, parameter, custom) }) _haptics.tryEmit(Haptic.Reject)
            _state.update { it.copy(busy = it.busy - remote.id) }
        }
    }

    /** Sends an air conditioner everything at once, as its remote would, and remembers it. */
    fun sendAc(remote: Remote, setting: AcSetting, on: Boolean) {
        setAc(remote, setting)
        val power = if (on) "on" else "off"
        send(remote, "setAll", "${setting.temperature},${setting.mode},${setting.fan},$power", false, if (on) Haptic.On else Haptic.Off)
    }

    fun setAc(remote: Remote, setting: AcSetting) {
        switchBot.putAcSetting(remote.id, setting)
        _state.update { it.copy(acSettings = switchBot.acSettings()) }
    }

    fun setRemoteHidden(remote: Remote, hidden: Boolean) {
        val all = _state.value.hiddenRemotes.let { if (hidden) it + remote.id else it - remote.id }
        switchBot.putHidden(all)
        _state.update { it.copy(hiddenRemotes = all) }
    }

    fun setRemoteButtons(remote: Remote, names: List<String>) {
        val buttons = _state.value.remoteButtons + (remote.id to names)
        switchBot.putButtons(buttons)
        _state.update { it.copy(remoteButtons = buttons) }
    }

    fun clearHistory() {
        history.clear()
        _messages.tryEmit(str(R.string.history_cleared))
    }

    /** Writes the choices made in the app to the file the user picked. */
    fun exportBackup(uri: Uri) {
        val s = _state.value
        val alerts = JSONObject()
        for ((id, alert) in s.alerts) {
            alerts.put(
                id,
                JSONObject().put("above", alert.above ?: JSONObject.NULL).put("below", alert.below ?: JSONObject.NULL),
            )
        }
        val backup = JSONObject()
            .put("app", BACKUP_APP)
            .put("version", 1)
            .put("favorites", JSONArray(s.favorites))
            .put("hidden", JSONArray(s.hidden))
            .put("rooms", JSONObject(s.rooms))
            .put("roomList", JSONArray(s.roomList))
            .put("groups", JSONArray(s.groups.map { it.toJson() }))
            .put("macros", JSONArray(s.macros.map { it.toJson() }))
            .put("schedules", JSONArray(s.schedules.map { it.toJson() }))
            .put("automations", JSONArray(s.automations.map { it.toJson() }))
            .put("layout", s.layout)
            .put("order", JSONObject(s.order.mapValues { JSONArray(it.value) }))
            .put("shortcuts", s.shortcuts?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("widget", s.widget.toJson())
            .put("tiles", JSONArray(s.tileDevices.map { it ?: JSONObject.NULL }))
            .put("alerts", alerts)
            .put("autoUpdate", s.autoUpdate)
            .put("haptics", s.haptics)
            .put("hapticLevel", s.hapticLevel)
            .put("theme", s.theme)
            .put("remoteButtons", JSONObject(s.remoteButtons.mapValues { JSONArray(it.value) }))
            .put("hiddenRemotes", JSONArray(s.hiddenRemotes))
            // Without the PIN: a file that travels must not carry it, not even scrambled.
            .put("monitor", s.monitor.toJson())
        // The keys of SwitchBot travel along, so that a new phone links again by itself.
        switchBot.keys()?.let { (token, secret) ->
            backup.put("switchBot", JSONObject().put("token", token).put("secret", secret))
        }
        viewModelScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(backup.toString(2).toByteArray())
                    }
                }
            }.isSuccess
            _messages.tryEmit(str(if (saved) R.string.backup_exported else R.string.backup_failed))
        }
    }

    /** Replaces the choices made in the app with those of a file written by [exportBackup]. */
    fun importBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val backup = runCatching {
                withContext(Dispatchers.IO) {
                    JSONObject(app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
                }
            }.getOrNull()
            if (backup == null || backup.optString("app") != BACKUP_APP) {
                _messages.tryEmit(str(if (backup == null) R.string.backup_failed else R.string.backup_invalid))
                return@launch
            }
            // The panel of a tablet may not suit the device the backup lands on: that is asked.
            if (backup.has("monitor")) {
                pendingBackup = backup
                _state.update { it.copy(askMonitorRestore = true) }
            } else {
                restore(backup, monitor = false)
            }
        }
    }

    /** Applies a backup read by [importBackup]; the monitor mode only when [monitor] says so. */
    private fun restore(backup: JSONObject, monitor: Boolean) {
        val app = getApplication<Application>()
        fun strings(name: String) =
            backup.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
        cache.edit()
            .putStringSet("favorites", strings("favorites"))
            .putStringSet("hidden", strings("hidden"))
            .apply()
        val rooms = backup.optJSONObject("rooms") ?: JSONObject()
        local.putRooms(rooms.keys().asSequence().associateWith { rooms.getString(it) })
        fun list(name: String) =
            backup.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }
        local.putRoomList(list("roomList").orEmpty())
        local.putGroups(backup.optJSONArray("groups")?.mapObjects(Group::fromJson).orEmpty())
        local.putMacros(backup.optJSONArray("macros")?.mapObjects(Macro::fromJson).orEmpty())
        Schedules.cancelAll(app)
        backup.optJSONArray("schedules")?.mapObjects(Schedule::fromJson)?.forEach { Schedules.put(app, it) }
        Automations.all(app).forEach { Automations.delete(app, it.id) }
        backup.optJSONArray("automations")?.mapObjects(Automation::fromJson)?.forEach { automation ->
            val already = automation.holds(local.states()[automation.device] ?: DeviceState()) == true
            Automations.put(app, automation, already)
        }
        val order = backup.optJSONObject("order") ?: JSONObject()
        local.putOrder(
            order.keys().asSequence().associateWith { section ->
                order.getJSONArray(section).let { a -> (0 until a.length()).map(a::getString) }
            },
        )
        local.putShortcuts(list("shortcuts"))
        local.putWidget(backup.optJSONObject("widget")?.let(WidgetConfig::fromJson) ?: WidgetConfig())
        val tiles = backup.optJSONArray("tiles")
        for (slot in 0 until PowerTile.SLOTS) {
            local.putTileDevice(slot, tiles?.optString(slot)?.takeIf { it.isNotEmpty() && !tiles.isNull(slot) })
        }
        val alerts = backup.optJSONObject("alerts") ?: JSONObject()
        for (id in Alerts.all(app).keys) Alerts.set(app, id, Alert())
        for (id in alerts.keys()) {
            val o = alerts.getJSONObject(id)
            Alerts.set(
                app,
                id,
                Alert(
                    above = if (o.isNull("above")) null else o.optDouble("above"),
                    below = if (o.isNull("below")) null else o.optDouble("below"),
                ),
            )
        }
        settings.edit()
            .putBoolean("autoUpdate", backup.optBoolean("autoUpdate", true))
            .putBoolean("haptics", backup.optBoolean("haptics", true))
            .putInt("hapticLevel", backup.optInt("hapticLevel", HAPTIC_LEVEL_DEFAULT).coerceIn(HAPTIC_LEVEL_MIN, 100))
            .putInt("theme", backup.optInt("theme", 0).coerceIn(0, 2))
            .putInt("layout", backup.optInt("layout", 0).coerceIn(0, 1))
            .apply()
        _state.update {
            it.copy(
                favorites = local.favorites(),
                hidden = local.hidden(),
                rooms = local.rooms(),
                roomList = local.roomList(),
                groups = local.groups(),
                macros = local.macros(),
                schedules = Schedules.all(app),
                automations = Automations.all(app),
                layout = settings.getInt("layout", 0),
                order = local.order(),
                shortcuts = local.shortcuts(),
                widget = local.widget(),
                tileDevices = local.tileDevices(),
                alerts = Alerts.all(app),
                autoUpdate = settings.getBoolean("autoUpdate", true),
                haptics = settings.getBoolean("haptics", true),
                hapticLevel = settings.getInt("hapticLevel", HAPTIC_LEVEL_DEFAULT),
                theme = settings.getInt("theme", 0),
            )
        }
        backup.optJSONObject("remoteButtons")?.let { saved ->
            val buttons = saved.keys().asSequence().associateWith { id ->
                saved.getJSONArray(id).let { a -> (0 until a.length()).map(a::getString) }
            }
            switchBot.putButtons(buttons)
            _state.update { it.copy(remoteButtons = buttons) }
        }
        if (backup.has("hiddenRemotes")) {
            switchBot.putHidden(strings("hiddenRemotes"))
            _state.update { it.copy(hiddenRemotes = switchBot.hidden()) }
        }
        if (monitor) backup.optJSONObject("monitor")?.let { setMonitor(MonitorConfig.fromJson(it)) }
        backup.optJSONObject("switchBot")?.let { keys ->
            val token = keys.optString("token")
            if (token.isNotEmpty()) {
                switchBot.connect(token, keys.optString("secret"))
                _state.update { it.copy(switchBot = true) }
                viewModelScope.launch {
                    guarded(quiet = true) { _state.update { it.copy(remotes = switchBot.fetchRemotes()) } }
                }
            }
        }
        Quick.refresh(app)
        _messages.tryEmit(str(R.string.backup_imported))
    }

    /** Answers whether the backup being read brings its monitor mode along; null gives the import up. */
    fun finishImport(monitor: Boolean?) {
        val backup = pendingBackup
        pendingBackup = null
        _state.update { it.copy(askMonitorRestore = false) }
        if (backup != null && monitor != null) restore(backup, monitor)
    }

    fun readings(device: Device): List<Reading> = history.readings(device.applianceId)

    fun toggleShowHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        if (_state.value.showHidden) refresh()
    }

    /**
     * Looks for a newer release on GitHub. The automatic check runs at most once a day and stays
     * silent unless it finds something; the [manual] one always runs and reports its outcome. A
     * [quiet] manual check, the one of the settings, only notes down what it finds.
     */
    fun checkForUpdate(manual: Boolean, quiet: Boolean = false) {
        if (updateJob?.isActive == true || !_state.value.onboarded) return
        val now = System.currentTimeMillis()
        val last = settings.getLong("updateCheckedAt", 0)
        if (!manual && (!_state.value.autoUpdate || now - last in 0 until UPDATE_CHECK_INTERVAL_MS)) return
        updateJob = viewModelScope.launch {
            try {
                val update = updater.check()
                settings.edit().putLong("updateCheckedAt", now).apply()
                if (update != null) {
                    _state.update { it.copy(update = update, updatePrompt = it.updatePrompt || !quiet) }
                } else if (manual && !quiet) {
                    _messages.tryEmit(str(R.string.update_latest, updater.installedVersion))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                if (manual && !quiet) _messages.tryEmit(str(R.string.no_connection))
            } catch (e: Exception) {
                if (manual && !quiet) _messages.tryEmit(e.message ?: str(R.string.update_check_failed))
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
                _messages.tryEmit(str(R.string.update_download_no_connection))
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: str(R.string.update_failed))
            }
            // The update stays known: the installer may be turned down and the settings still offer it.
            _state.update { it.copy(updatePrompt = false, updateProgress = null) }
        }
    }

    /** Closes the offer, cancelling the download if one is running. */
    fun dismissUpdate() {
        updateJob?.cancel()
        _state.update { it.copy(updatePrompt = false, updateProgress = null) }
    }

    /** Runs [block], turning failures into a message, unless [quiet], or a return to the sign-in screen. */
    private suspend fun guarded(quiet: Boolean = false, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotLoggedInException) {
        _state.update { it.copy(loggedIn = false, loginMessage = e.message) }
        false
    } catch (e: java.io.IOException) {
        if (!quiet) _messages.tryEmit(str(R.string.no_connection))
        false
    } catch (e: Exception) {
        if (!quiet) _messages.tryEmit(e.message ?: str(R.string.something_wrong))
        false
    }
}

/** The command as a sentence, to offer it or to say it is being carried out. */
fun VoiceCommand.label(): String = when {
    room != null -> str(if (on) R.string.voice_room_on else R.string.voice_room_off, room)
    else -> str(if (on) R.string.voice_on else R.string.voice_off, devices.single().name)
}
