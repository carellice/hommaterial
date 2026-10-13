package app.hommaterial.quick

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.hommaterial.R
import app.hommaterial.data.ACTION_MACRO
import app.hommaterial.data.ACTION_POWER
import app.hommaterial.data.ACTION_REMOTE
import app.hommaterial.data.Cache
import app.hommaterial.data.MacroAction
import app.hommaterial.data.Schedule
import app.hommaterial.data.mapObjects
import app.hommaterial.data.nextTime
import app.hommaterial.str
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

private const val ACTION_SCHEDULE = "app.hommaterial.action.SCHEDULE"
private const val RETRY_DELAY_MS = 60_000L
// Past this delay the action would come as a surprise rather than at the time that was asked for.
private const val RETRY_WINDOW_MS = 10 * 60_000L
// A custom device may wait between its actions: it is given longer than a single command.
private const val RUN_TIMEOUT_MS = 5 * 60_000L
// Android wants a broadcast answered within seconds; the work goes on after that.
private const val ANSWER_MS = 8_000L

/**
 * Actions carried out at a set time. As the timers, they are started by the phone: it has to be
 * on and connected when the time comes.
 */
object Schedules {
    fun all(context: Context): List<Schedule> = runCatching {
        JSONArray(Cache(context).prefs.getString("schedules", "[]")).mapObjects(Schedule::fromJson)
    }.getOrDefault(emptyList())

    /** Adds a schedule, or replaces the one with the same id, and sets or clears its alarm. */
    fun put(context: Context, schedule: Schedule) {
        save(context, all(context).filter { it.id != schedule.id } + schedule)
        if (schedule.enabled) arm(context, schedule.id, schedule.at) else disarm(context, schedule.id)
    }

    fun delete(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
        disarm(context, id)
    }

    fun cancelAll(context: Context) = all(context).forEach { delete(context, it.id) }

    /** Alarms do not survive a restart of the phone: this sets them again, firing the overdue ones. */
    fun rearm(context: Context) = all(context).filter { it.enabled }.forEach { arm(context, it.id, it.at) }

    internal fun retry(context: Context, id: String) = arm(context, id, System.currentTimeMillis() + RETRY_DELAY_MS)

    /** Carries out what a schedule is for. */
    internal suspend fun run(context: Context, action: MacroAction): Outcome = when (action.type) {
        ACTION_POWER -> Quick.setPower(context, action.target, action.value == "on")
        ACTION_MACRO -> Quick.runMacro(context, action.target)
        ACTION_REMOTE -> Quick.pressRemote(context, action.target, action.value, action.custom)
        else -> Outcome(false, str(R.string.something_wrong))
    }

    private fun save(context: Context, schedules: List<Schedule>) {
        val json = JSONArray(schedules.sortedBy { it.at }.map { it.toJson() })
        Cache(context).prefs.edit().putString("schedules", json.toString()).apply()
    }

    private fun arm(context: Context, id: String, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, id))
        }
    }

    private fun disarm(context: Context, id: String) =
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))

    // The data makes the intents of different schedules distinct, so that each keeps its own alarm.
    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION_SCHEDULE)
            .setData(Uri.fromParts("hommaterial-schedule", id, null)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Schedules.rearm(app)
            return
        }
        val id = intent.data?.schemeSpecificPart ?: return
        // Deleted or switched off in the meantime, or a leftover from before a sign-out.
        val schedule = Schedules.all(app).find { it.id == id && it.enabled } ?: return
        val pending = goAsync()
        val work = Quick.scope.launch {
            val outcome = withTimeoutOrNull(RUN_TIMEOUT_MS) { Schedules.run(app, schedule.action) }
            val offline = outcome == null || outcome.noConnection
            if (offline && System.currentTimeMillis() - schedule.at < RETRY_WINDOW_MS) {
                Schedules.retry(app, id)
                return@launch
            }
            if (outcome?.ok == true) {
                Alerts.scheduleDone(app, schedule)
            } else {
                Alerts.scheduleFailed(app, schedule, outcome?.message ?: str(R.string.no_connection))
            }
            // One that repeats moves to its next day; one that does not has done its job.
            if (schedule.days.isEmpty()) {
                Schedules.delete(app, id)
            } else {
                val next = nextTime(schedule.hour, schedule.minute, schedule.days, System.currentTimeMillis())
                Schedules.put(app, schedule.copy(at = next))
            }
            Quick.refresh(app)
        }
        Quick.scope.launch {
            withTimeoutOrNull(ANSWER_MS) { work.join() }
            pending.finish()
        }
    }
}
