package app.hommaterial.quick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import app.hommaterial.R
import app.hommaterial.data.Cache

private const val ACTION_TOGGLE = "app.hommaterial.action.TOGGLE"
// Launchers show four shortcuts at most.
private const val MAX_SHORTCUTS = 4

/** Lists the favorites under the app icon; a tap on one toggles it. */
internal fun publishShortcuts(context: Context) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    val cache = Cache(context)
    val favorites = cache.favorites()
    val hidden = cache.hidden()
    val shortcuts = cache.devices()
        .filter { it.hasPower && it.applianceId in favorites && it.applianceId !in hidden }
        .take(minOf(MAX_SHORTCUTS, manager.maxShortcutCountPerActivity))
        .mapIndexed { rank, device ->
            ShortcutInfo.Builder(context, device.applianceId)
                .setShortLabel(device.name)
                .setLongLabel("Accendi o spegni ${device.name}")
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_power))
                .setRank(rank)
                .setIntent(
                    Intent(context, ToggleActivity::class.java)
                        .setAction(ACTION_TOGGLE)
                        .putExtra(EXTRA_APPLIANCE_ID, device.applianceId),
                )
                .build()
        }
    // The system limits how often an app in the background may publish; the next call catches up.
    runCatching { manager.dynamicShortcuts = shortcuts }
}

/** Shortcuts can only open an activity: this one passes the tap on and never shows itself. */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_APPLIANCE_ID)?.let {
            sendBroadcast(Intent(this, ToggleReceiver::class.java).putExtra(EXTRA_APPLIANCE_ID, it))
        }
        finish()
    }
}
