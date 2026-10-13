package app.hommaterial.quick

import android.content.Context
import app.hommaterial.R
import app.hommaterial.data.Automation
import app.hommaterial.data.Cache
import app.hommaterial.data.DeviceState
import app.hommaterial.data.mapObjects
import app.hommaterial.str
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

private const val RUN_TIMEOUT_MS = 5 * 60_000L

/**
 * Automations: an action carried out when a device gets to a state. The phone sees a state only
 * when it fetches it, with the app open, from the widget, or about every quarter of an hour in the
 * background: they follow what happens with that delay, not on the spot.
 */
object Automations {
    fun all(context: Context): List<Automation> = runCatching {
        JSONArray(Cache(context).prefs.getString("automations", "[]")).mapObjects(Automation::fromJson)
    }.getOrDefault(emptyList())

    /**
     * Adds an automation, or replaces the one with the same id. One whose condition [holds] already
     * waits for it to come true again: it is not for what was so before it existed.
     */
    fun put(context: Context, automation: Automation, holds: Boolean) {
        val cache = Cache(context)
        val all = all(context).filter { it.id != automation.id } + automation
        val tripped = if (holds) tripped(cache) + automation.id else tripped(cache) - automation.id
        cache.prefs.edit()
            .putString("automations", JSONArray(all.map { it.toJson() }).toString())
            .putStringSet("automationsTripped", tripped)
            .apply()
        Alerts.schedule(context)
    }

    fun delete(context: Context, id: String) {
        val cache = Cache(context)
        cache.prefs.edit()
            .putString("automations", JSONArray(all(context).filter { it.id != id }.map { it.toJson() }).toString())
            .putStringSet("automationsTripped", tripped(cache) - id)
            .apply()
        Alerts.schedule(context)
    }

    /** Runs the automations whose condition freshly fetched [states] have just met. */
    fun check(context: Context, states: Map<String, DeviceState>) {
        val automations = all(context).filter { it.enabled }
        if (automations.isEmpty()) return
        val cache = Cache(context)
        val tripped = tripped(cache).toMutableSet()
        for (automation in automations) {
            val state = states[automation.device] ?: continue
            if (automation.holds(state) == true) {
                if (tripped.add(automation.id)) run(context.applicationContext, automation)
            } else if (automation.cleared(state) == true) {
                tripped.remove(automation.id)
            }
        }
        cache.prefs.edit().putStringSet("automationsTripped", tripped).apply()
    }

    private fun run(context: Context, automation: Automation) {
        Quick.scope.launch {
            val outcome = withTimeoutOrNull(RUN_TIMEOUT_MS) { Schedules.run(context, automation.action) }
            if (outcome?.ok == true) {
                Alerts.automationDone(context, automation)
            } else {
                Alerts.automationFailed(context, automation, outcome?.message ?: str(R.string.no_connection))
            }
        }
    }

    private fun tripped(cache: Cache): Set<String> =
        cache.prefs.getStringSet("automationsTripped", emptySet())!!.toSet()
}
