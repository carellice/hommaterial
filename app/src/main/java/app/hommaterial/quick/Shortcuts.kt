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
import app.hommaterial.str

private const val ACTION_TOGGLE = "app.hommaterial.action.TOGGLE"
// Launchers show four shortcuts at most.
internal const val MAX_SHORTCUTS = 4

internal const val SHORTCUT_DEVICE = "device:"
internal const val SHORTCUT_GROUP = "group:"

/**
 * Lists under the app icon the devices and groups the user chose, or the favorites until a choice
 * is made; a tap on one toggles it.
 */
internal fun publishShortcuts(context: Context) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    val cache = Cache(context)
    val hidden = cache.hidden()
    val devices = cache.devices().filter { it.hasPower && it.applianceId !in hidden }
    val groups = cache.groups()
    val chosen = cache.shortcuts() ?: run {
        val favorites = cache.favorites()
        devices.filter { it.applianceId in favorites }.map { SHORTCUT_DEVICE + it.applianceId }
    }
    val shortcuts = chosen
        .mapNotNull { key ->
            // Devices and groups that are gone leave their place to the next ones.
            val id = key.substringAfter(':')
            val intent = Intent(context, ToggleActivity::class.java).setAction(ACTION_TOGGLE)
            if (key.startsWith(SHORTCUT_GROUP)) {
                groups.find { it.id == id }?.let { Triple(key, it.name, intent.putExtra(EXTRA_GROUP_ID, id)) }
            } else {
                devices.find { it.applianceId == id }
                    ?.let { Triple(key, it.name, intent.putExtra(EXTRA_APPLIANCE_ID, id)) }
            }
        }
        .take(minOf(MAX_SHORTCUTS, manager.maxShortcutCountPerActivity))
        .mapIndexed { rank, (key, name, intent) ->
            ShortcutInfo.Builder(context, key)
                .setShortLabel(name)
                .setLongLabel(str(R.string.shortcut_long, name))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_power))
                .setRank(rank)
                .setIntent(intent)
                .build()
        }
    // The system limits how often an app in the background may publish; the next call catches up.
    runCatching { manager.dynamicShortcuts = shortcuts }
}

/** Shortcuts can only open an activity: this one passes the tap on and never shows itself. */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(
            Intent(this, ToggleReceiver::class.java)
                .putExtra(EXTRA_APPLIANCE_ID, intent.getStringExtra(EXTRA_APPLIANCE_ID))
                .putExtra(EXTRA_GROUP_ID, intent.getStringExtra(EXTRA_GROUP_ID)),
        )
        finish()
    }
}
