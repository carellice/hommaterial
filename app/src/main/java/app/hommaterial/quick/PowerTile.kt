package app.hommaterial.quick

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.hommaterial.MainActivity
import app.hommaterial.R
import app.hommaterial.data.Alexa
import app.hommaterial.data.Cache
import app.hommaterial.data.Device
import app.hommaterial.str
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Alexa can take a few seconds to report a state it has just been asked to change.
private const val SETTLE_MS = 5_000L

/**
 * Quick settings tile that toggles the device assigned to its [slot] in the app. Android wants one
 * declared service per tile, hence the fixed number of slots.
 */
abstract class PowerTile(private val slot: Int) : TileService() {
    private var fetch: Job? = null

    private var lastCommandAt: Long
        get() = commandTimes[slot] ?: 0
        set(value) {
            commandTimes[slot] = value
        }

    override fun onStartListening() {
        render()
        val cache = Cache(this)
        val device = cache.devices().find { it.applianceId == cache.tileDevices()[slot] } ?: return
        if (System.currentTimeMillis() - lastCommandAt < SETTLE_MS) return
        // The remembered state may be old: the panel shows it at once and corrects it if needed.
        fetch = Quick.scope.launch { refresh(device) }
    }

    /** Asks Alexa for the state of [device] and shows it; says whether an answer came. */
    private suspend fun refresh(device: Device): Boolean {
        val fresh = runCatching { Alexa.get(applicationContext).api.states(listOf(device)) }.getOrNull()
        if (fresh.isNullOrEmpty()) return false
        if (System.currentTimeMillis() - lastCommandAt >= SETTLE_MS) {
            val cache = Cache(this)
            cache.putStates(cache.states() + fresh)
            render()
        }
        return true
    }

    override fun onStopListening() {
        fetch?.cancel()
    }

    override fun onClick() {
        val cache = Cache(this)
        val id = cache.tileDevices()[slot]
        if (id == null) {
            openApp()
            return
        }
        fetch?.cancel()
        val app = applicationContext
        // There is nothing to switch on a sensor: a tap reads it again.
        val sensor = cache.devices().find { it.applianceId == id }?.takeIf { !it.hasPower }
        if (sensor != null) {
            fetch = Quick.scope.launch {
                if (!refresh(sensor)) Toast.makeText(app, str(R.string.no_connection), Toast.LENGTH_SHORT).show()
            }
            return
        }
        lastCommandAt = System.currentTimeMillis()
        // Shown switched right away; render() puts back the truth once the command has ended.
        qsTile?.let { tile ->
            tile.state = if (tile.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
        Quick.scope.launch {
            val outcome = Quick.toggle(app, id)
            lastCommandAt = System.currentTimeMillis()
            if (!outcome.ok) Toast.makeText(app, outcome.message, Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val cache = Cache(this)
        val device = cache.devices().find { it.applianceId == cache.tileDevices()[slot] }
        val state = device?.let { cache.states()[it.applianceId] }
        // Where the device is tells apart the lamps of two rooms: its room, or else a group it is in.
        val place = device?.let {
            val moved = cache.rooms()[it.applianceId]
            (if (moved != null) moved.ifEmpty { null } else it.room)
                ?: cache.groups().firstOrNull { group -> it.applianceId in group.devices }?.name
        }
        val subtitles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        // A thermostat or a sensor is on a tile to be read: its temperature takes the place of on and off.
        val reading = state?.takeIf { it.reachable && it.temperature?.isNaN() == false }?.let { read ->
            degrees(read.temperature!!) + (read.humidity?.let { " · $it%" } ?: "")
        }
        val named = device?.let { if (place == null) it.name else str(R.string.tile_label, it.name, place) }
        tile.label = when {
            device == null -> "${getString(R.string.app_name)} ${slot + 1}"
            // With nothing to switch, the reading is the tile: it goes on the first line, the largest.
            reading != null && !device.hasPower && subtitles -> reading
            reading != null && !device.hasPower -> str(R.string.tile_label, device.name, reading)
            // Before Android 10 a tile has one line only.
            !subtitles -> named
            else -> device.name
        }
        tile.icon = Icon.createWithResource(
            this,
            if (device != null && !device.hasPower) R.drawable.ic_thermostat else R.drawable.ic_power,
        )
        tile.state = if (state?.power == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (subtitles) {
            tile.subtitle = when {
                device == null -> str(R.string.tile_choose)
                state == null -> str(R.string.unknown_state)
                !state.reachable -> str(R.string.unreachable)
                reading != null -> if (device.hasPower) reading else named
                state.power == null -> str(R.string.unknown_state)
                // The tile itself looks on or off: the second line is free to say where the device is.
                place != null -> place
                state.power -> str(R.string.on)
                else -> str(R.string.off)
            }
        }
        tile.updateTile()
    }

    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val SLOTS = 5

        private val services = listOf(
            PowerTile1::class.java,
            PowerTile2::class.java,
            PowerTile3::class.java,
            PowerTile4::class.java,
            PowerTile5::class.java,
        )

        /** When each slot last sent a command. */
        private val commandTimes = ConcurrentHashMap<Int, Long>()

        /** Asks Android to offer the tile of [slot] to the user; older versions need it added by hand. */
        fun offer(context: Context, slot: Int, label: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(context, services[slot]),
                label,
                Icon.createWithResource(context, R.drawable.ic_power),
                context.mainExecutor,
            ) {}
        }
    }
}

class PowerTile1 : PowerTile(0)

class PowerTile2 : PowerTile(1)

class PowerTile3 : PowerTile(2)

class PowerTile4 : PowerTile(3)

class PowerTile5 : PowerTile(4)
