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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Alexa can take a few seconds to report a state it has just been asked to change.
private const val SETTLE_MS = 5_000L

/** Quick settings tile that toggles the device chosen in the app. */
class PowerTileService : TileService() {
    private var fetch: Job? = null

    override fun onStartListening() {
        render()
        val cache = Cache(this)
        val device = cache.devices().find { it.applianceId == cache.tileDevice() } ?: return
        if (System.currentTimeMillis() - lastCommandAt < SETTLE_MS) return
        // The remembered state may be old: the panel shows it at once and corrects it if needed.
        fetch = Quick.scope.launch {
            val fresh = runCatching { Alexa.get(applicationContext).api.states(listOf(device)) }.getOrNull()
            if (!fresh.isNullOrEmpty() && System.currentTimeMillis() - lastCommandAt >= SETTLE_MS) {
                cache.putStates(cache.states() + fresh)
                render()
            }
        }
    }

    override fun onStopListening() {
        fetch?.cancel()
    }

    override fun onClick() {
        val id = Cache(this).tileDevice()
        if (id == null) {
            openApp()
            return
        }
        fetch?.cancel()
        lastCommandAt = System.currentTimeMillis()
        // Shown switched right away; render() puts back the truth once the command has ended.
        qsTile?.let { tile ->
            tile.state = if (tile.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
        val app = applicationContext
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
        val device = cache.devices().find { it.applianceId == cache.tileDevice() }
        val state = device?.let { cache.states()[it.applianceId] }
        tile.label = device?.name ?: getString(R.string.app_name)
        tile.state = if (state?.power == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                device == null -> "Scegli un dispositivo"
                state == null || state.power == null -> "Stato sconosciuto"
                !state.reachable -> "Non raggiungibile"
                state.power -> "Acceso"
                else -> "Spento"
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
        @Volatile
        private var lastCommandAt = 0L

        /** Asks Android to offer the tile to the user; older versions need it added by hand. */
        fun offer(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                ComponentName(context, PowerTileService::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_power),
                context.mainExecutor,
            ) {}
        }
    }
}
