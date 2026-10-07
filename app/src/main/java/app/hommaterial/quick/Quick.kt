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
internal const val EXTRA_GROUP_ID = "groupId"

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
        return attempt {
            val on = target(device)
            Alexa.get(context).api.setPower(device, on)
            val known = cache.states()
            val state = (known[applianceId] ?: DeviceState()).copy(power = on, reachable = true)
            cache.putStates(known + (applianceId to state))
            refresh(context)
            Outcome(true, str(if (on) R.string.quick_on else R.string.quick_off, device.name))
        }
    }

    /** Switches a whole group: off if any of its devices is on, according to Alexa, on otherwise. */
    suspend fun toggleGroup(context: Context, groupId: String): Outcome {
        val cache = Cache(context)
        val group = cache.groups().find { it.id == groupId }
        val hidden = cache.hidden()
        val devices = cache.devices().filter {
            it.hasPower && it.applianceId in group?.devices.orEmpty() && it.applianceId !in hidden
        }
        if (group == null || devices.isEmpty()) return Outcome(false, str(R.string.quick_group_not_found))
        return attempt {
            val api = Alexa.get(context).api
            val fresh = api.states(devices)
            val on = devices.none { fresh[it.applianceId]?.power == true }
            val switched = HashMap<String, DeviceState>()
            var refused: Exception? = null
            for (device in devices.filter { fresh[it.applianceId]?.power != on }) {
                // One device that does not answer must not keep the others from switching.
                try {
                    api.setPower(device, on)
                    switched[device.applianceId] = (fresh[device.applianceId] ?: DeviceState()).copy(power = on)
                } catch (e: IOException) {
                    throw e
                } catch (e: NotLoggedInException) {
                    throw e
                } catch (e: Exception) {
                    refused = e
                }
            }
            cache.putStates(cache.states() + fresh + switched)
            refresh(context)
            refused?.let { Outcome(false, it.message ?: str(R.string.something_wrong)) }
                ?: Outcome(true, str(if (on) R.string.quick_on else R.string.quick_off, group.name))
        }
    }

    private suspend fun attempt(block: suspend () -> Outcome): Outcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            Outcome(false, str(R.string.login_expired_open_app))
        } catch (e: IOException) {
            Outcome(false, str(R.string.no_connection), noConnection = true)
        } catch (e: Exception) {
            Outcome(false, e.message ?: str(R.string.something_wrong))
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
        Alerts.check(context, fresh)
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
        val device = intent.getStringExtra(EXTRA_APPLIANCE_ID)
        val group = intent.getStringExtra(EXTRA_GROUP_ID)
        if (device == null && group == null) return
        val app = context.applicationContext
        val pending = goAsync()
        Quick.scope.launch {
            try {
                val outcome = if (device != null) Quick.toggle(app, device) else Quick.toggleGroup(app, group!!)
                Toast.makeText(app, outcome.message, Toast.LENGTH_SHORT).show()
            } finally {
                pending.finish()
            }
        }
    }
}
