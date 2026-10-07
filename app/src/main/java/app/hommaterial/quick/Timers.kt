package app.hommaterial.quick

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.hommaterial.data.Cache
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val ACTION_TIMER = "app.hommaterial.action.TIMER"
private const val RETRY_DELAY_MS = 60_000L
// Past this delay a switch-off would come as a surprise rather than as the timer that was asked for.
private const val RETRY_WINDOW_MS = 10 * 60_000L
private const val COMMAND_TIMEOUT_MS = 45_000L

/**
 * Switch-off timers. Alexa is asked to switch the device off by the phone when the time comes,
 * so the phone has to be on and connected at that moment.
 */
object Timers {
    /** Switch-off time of each device with a timer, in epoch milliseconds. */
    fun all(context: Context): Map<String, Long> = runCatching {
        val json = JSONObject(Cache(context).prefs.getString("timers", "{}")!!)
        json.keys().asSequence().associateWith { json.getLong(it) }
    }.getOrDefault(emptyMap())

    fun set(context: Context, applianceId: String, at: Long) {
        save(context, all(context) + (applianceId to at))
        arm(context, applianceId, at)
    }

    fun cancel(context: Context, applianceId: String) {
        if (applianceId !in all(context)) return
        save(context, all(context) - applianceId)
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, applianceId))
    }

    fun cancelAll(context: Context) = all(context).keys.forEach { cancel(context, it) }

    /** Alarms do not survive a restart of the phone: this sets them again, firing the overdue ones. */
    fun rearm(context: Context) = all(context).forEach { (id, at) -> arm(context, id, at) }

    internal fun retry(context: Context, applianceId: String) =
        arm(context, applianceId, System.currentTimeMillis() + RETRY_DELAY_MS)

    private fun save(context: Context, timers: Map<String, Long>) {
        val json = JSONObject().also { json -> timers.forEach { (id, at) -> json.put(id, at) } }
        Cache(context).prefs.edit().putString("timers", json.toString()).apply()
    }

    private fun arm(context: Context, applianceId: String, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = pending(context, applianceId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    // The data makes the intents of different devices distinct, so that each keeps its own alarm.
    private fun pending(context: Context, applianceId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimerReceiver::class.java)
            .setAction(ACTION_TIMER)
            .setData(Uri.fromParts("hommaterial-timer", applianceId, null)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timers.rearm(app)
            return
        }
        val id = intent.data?.schemeSpecificPart ?: return
        // Cancelled in the meantime, or a leftover from before a sign-out.
        val due = Timers.all(app)[id] ?: return
        val pending = goAsync()
        Quick.scope.launch {
            try {
                val outcome = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { Quick.setPower(app, id, false) }
                val offline = outcome == null || outcome.noConnection
                if (offline && System.currentTimeMillis() - due < RETRY_WINDOW_MS) {
                    Timers.retry(app, id)
                } else {
                    Timers.cancel(app, id)
                    Quick.refresh(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
