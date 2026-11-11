package app.hommaterial.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.hommaterial.MainActivity
import app.hommaterial.R
import app.hommaterial.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the downloads of the songs of the home server going with the app closed, and shows how far
 * they have gone in a notification, with a button to stop them. It ends by itself when nothing is left.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) MusicDownloads.stop(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, str(R.string.music_download_channel), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, ID, notification(MusicDownloads.waiting.value, percent(MusicDownloads.progress.value)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!watching) {
            watching = true
            scope.launch {
                MusicDownloads.waiting.combine(MusicDownloads.progress) { waiting, progress -> waiting to percent(progress) }
                    .distinctUntilChanged()
                    .collect { (waiting, percent) ->
                        if (waiting == 0) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            manager.notify(ID, notification(waiting, percent))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun percent(progress: Float) = Math.round(progress * 100)

    private fun notification(waiting: Int, percent: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(str(R.string.music_download_notification))
        .setContentText(str(R.string.music_download_progress, percent, waiting))
        .setProgress(100, percent, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .addAction(
            0, str(R.string.music_download_stop),
            PendingIntent.getService(
                this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val CHANNEL = "music_downloads"
        private const val ID = 4207
        private const val ACTION_STOP = "app.hommaterial.music.STOP_DOWNLOADS"

        /** Starts the notification of the downloads; asked from the app, which is in front, so Android allows it. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
