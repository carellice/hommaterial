package app.hommaterial.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import app.hommaterial.R
import app.hommaterial.data.Alexa
import app.hommaterial.data.Cache
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.History
import app.hommaterial.data.NotLoggedInException
import app.hommaterial.str
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val EXTRA_APPLIANCE_ID = "applianceId"

/** How a command sent from outside the app ended, with the sentence to show for it. */
class Outcome(val ok: Boolean, val message: String, val noConnection: Boolean = false)

/** Commands for the surfaces that live outside the app: shortcuts, quick settings tile, widget. */
object Quick {
    /** Outlives the short-lived components that start the work. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _changes = MutableStateFlow(0)

    /** Ticks whenever what is remembered on the phone changes. */
    val changes = _changes.asStateFlow()

    /** Inverts the power of a device, asking Alexa for its real state first. */
    suspend fun toggle(context: Context, applianceId: String): Outcome = command(context, applianceId) { device ->
        val state = Alexa.get(context).api.states(listOf(device))[applianceId]
        if (state == null || !state.reachable) throw Exception(str(R.string.device_unreachable, device.name))
        // Without a reported state a toggle would be a guess.
        val on = state.power ?: throw Exception(str(R.string.quick_unknown_state, device.name))
        !on
    }

    suspend fun setPower(context: Context, applianceId: String, on: Boolean): Outcome =
        command(context, applianceId) { on }

    private suspend fun command(
        context: Context,
        applianceId: String,
        target: suspend (Device) -> Boolean,
    ): Outcome {
        val cache = Cache(context)
        val device = cache.devices().find { it.applianceId == applianceId && it.hasPower }
            ?: return Outcome(false, str(R.string.quick_not_found))
        return try {
            val on = target(device)
            Alexa.get(context).api.setPower(device, on)
            val known = cache.states()
            val state = (known[applianceId] ?: DeviceState()).copy(power = on, reachable = true)
            cache.putStates(known + (applianceId to state))
            refresh(context)
            Outcome(true, str(if (on) R.string.quick_on else R.string.quick_off, device.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            Outcome(false, str(R.string.login_expired_open_app))
        } catch (e: IOException) {
            Outcome(false, str(R.string.no_connection), noConnection = true)
        } catch (e: Exception) {
            Outcome(false, e.message ?: str(R.string.something_wrong))
        }
    }

    /** Fetches the current state of favorites and sensors, for the widget; failures leave things as they are. */
    suspend fun fetchFavorites(context: Context) {
        val cache = Cache(context)
        val hidden = cache.hidden()
        val favorites = cache.favorites()
        // Sensors come along whether favorite or not, to keep their history going.
        val devices = cache.devices().filter {
            it.applianceId !in hidden && (it.applianceId in favorites || it.isSensor)
        }
        if (devices.isEmpty()) return
        val fresh = try {
            Alexa.get(context).api.states(devices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        cache.putStates(cache.states() + fresh)
        History(context).record(fresh)
        refresh(context)
    }

    /** Brings the surfaces outside the app in line with what is remembered on the phone. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        _changes.update { it + 1 }
        publishShortcuts(app)
        scope.launch { runCatching { HomeWidget().updateAll(app) } }
    }
}

/** Target of the taps that toggle a device without opening the app. */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_APPLIANCE_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Quick.scope.launch {
            try {
                Toast.makeText(app, Quick.toggle(app, id).message, Toast.LENGTH_SHORT).show()
            } finally {
                pending.finish()
            }
        }
    }
}
