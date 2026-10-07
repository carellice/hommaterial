package app.hommaterial.quick

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hommaterial.MainActivity
import app.hommaterial.R
import app.hommaterial.data.Cache
import app.hommaterial.data.DeviceState
import app.hommaterial.str
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val CHANNEL = "alerts"
private const val WORK = "alerts"
// The shortest interval Android grants to periodic work.
private const val CHECK_MINUTES = 15L
// A temperature hovering around the threshold must not notify at every check.
private const val HYSTERESIS = 0.3

/** Temperatures a sensor should notify about when it goes [above] or [below]; null for no alert. */
data class Alert(val above: Double? = null, val below: Double? = null)

/** Notifications: temperature thresholds of the sensors and timers that could not switch off. */
object Alerts {
    fun all(context: Context): Map<String, Alert> = runCatching {
        val json = JSONObject(Cache(context).prefs.getString("alerts", "{}")!!)
        json.keys().asSequence().associateWith { id ->
            val o = json.getJSONObject(id)
            Alert(
                above = if (o.isNull("above")) null else o.getDouble("above"),
                below = if (o.isNull("below")) null else o.getDouble("below"),
            )
        }
    }.getOrDefault(emptyMap())

    /** Sets the alerts of a sensor and starts or stops the periodic check accordingly. */
    fun set(context: Context, applianceId: String, alert: Alert) {
        val prefs = Cache(context).prefs
        val alerts = if (alert == Alert()) all(context) - applianceId else all(context) + (applianceId to alert)
        val json = JSONObject()
        for ((id, a) in alerts) {
            json.put(id, JSONObject().put("above", a.above ?: JSONObject.NULL).put("below", a.below ?: JSONObject.NULL))
        }
        // With a new threshold what was already notified no longer counts.
        val tripped = tripped(context).filterNot { it.startsWith("$applianceId|") }.toSet()
        prefs.edit().putString("alerts", json.toString()).putStringSet("alertsTripped", tripped).apply()
        schedule(context)
    }

    /** Keeps the periodic check running only while there is something to check. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (all(context).isEmpty()) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AlertWorker>(CHECK_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Notifies about the thresholds that freshly fetched [states] have just crossed. */
    fun check(context: Context, states: Map<String, DeviceState>) {
        val alerts = all(context)
        if (alerts.isEmpty()) return
        val cache = Cache(context)
        val names = cache.devices().associate { it.applianceId to it.name }
        val tripped = tripped(context).toMutableSet()
        for ((id, alert) in alerts) {
            val temperature = states[id]?.temperature?.takeIf { !it.isNaN() } ?: continue
            val name = names[id] ?: continue
            val title = "$name: ${degrees(temperature)}"
            alert.above?.let { limit ->
                val key = "$id|above"
                if (temperature > limit) {
                    if (tripped.add(key)) {
                        notify(context, key.hashCode(), title, str(R.string.alert_notification_above, degrees(limit)))
                    }
                } else if (temperature <= limit - HYSTERESIS) {
                    tripped.remove(key)
                }
            }
            alert.below?.let { limit ->
                val key = "$id|below"
                if (temperature < limit) {
                    if (tripped.add(key)) {
                        notify(context, key.hashCode(), title, str(R.string.alert_notification_below, degrees(limit)))
                    }
                } else if (temperature >= limit + HYSTERESIS) {
                    tripped.remove(key)
                }
            }
        }
        cache.prefs.edit().putStringSet("alertsTripped", tripped).apply()
    }

    fun timerFailed(context: Context, applianceId: String, reason: String) {
        val name = Cache(context).devices().find { it.applianceId == applianceId }?.name ?: return
        notify(
            context,
            "$applianceId|timer".hashCode(),
            str(R.string.timer_failed_title, name),
            str(R.string.timer_failed_text, reason),
        )
    }

    fun enabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun tripped(context: Context): Set<String> =
        Cache(context).prefs.getStringSet("alertsTripped", emptySet())!!.toSet()

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, str(R.string.alerts_title), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}

fun degrees(temperature: Double): String = "%.1f°".format(temperature)

/** Fetches the sensors in the background; the thresholds are checked as part of every fetch. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Quick.fetchFavorites(applicationContext)
        return Result.success()
    }
}
