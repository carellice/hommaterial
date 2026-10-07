package app.hommaterial.quick

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import app.hommaterial.MainActivity
import app.hommaterial.R
import app.hommaterial.data.Cache
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.WidgetConfig
import app.hommaterial.data.statusText
import app.hommaterial.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val APPLIANCE_ID = ActionParameters.Key<String>(EXTRA_APPLIANCE_ID)
private val GROUP_ID = ActionParameters.Key<String>(EXTRA_GROUP_ID)

// Height of a tile and size of its name for each size setting, compact to large.
private val TILE_HEIGHTS = listOf(44.dp, 60.dp, 76.dp)
private val NAME_SIZES = listOf(13.sp, 14.sp, 16.sp)
// A tile without the status line needs less room.
private val STATUS_HEIGHT = 12.dp

/** One tile of the widget: a device, or a group with the devices it switches. */
private class Entry(val key: String, val name: String, val status: String, val on: Boolean, val tap: Action)

/**
 * Home screen widget set up in the app's settings: which devices and groups it shows, and how. A
 * tap toggles a device or a group, or refreshes a sensor.
 */
class HomeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Re-read whenever the app or another surface changes what is remembered.
            val version by Quick.changes.collectAsState()
            val cache = remember(version) { Cache(context) }
            val config = remember(version) { cache.widget() }
            val entries = remember(version) { entries(cache, config) }
            val updatedAt = remember(version) { cache.prefs.getLong("widgetUpdatedAt", 0) }
            GlanceTheme { Content(config, entries, updatedAt) }
        }
    }
}

private fun entries(cache: Cache, config: WidgetConfig): List<Entry> {
    val hidden = cache.hidden()
    val devices = cache.devices().filter { it.applianceId !in hidden }
    val groups = cache.groups()
    val states = cache.states()
    val keys = config.items ?: run {
        val favorites = cache.favorites()
        devices.filter { it.applianceId in favorites }.map { SHORTCUT_DEVICE + it.applianceId }
    }
    return keys.mapNotNull { key ->
        val id = key.substringAfter(':')
        if (key.startsWith(SHORTCUT_GROUP)) {
            val group = groups.find { it.id == id } ?: return@mapNotNull null
            val members = devices.filter { it.hasPower && it.applianceId in group.devices }
            if (members.isEmpty()) return@mapNotNull null
            val lit = members.count { states[it.applianceId]?.power == true }
            val status = when (lit) {
                0 -> str(R.string.off)
                members.size -> str(R.string.on)
                else -> str(R.string.group_some_on, lit, members.size)
            }
            val tap = actionRunCallback<ToggleGroupAction>(actionParametersOf(GROUP_ID to id))
            Entry(key, group.name, status, lit > 0, tap)
        } else {
            val device = devices.find { it.applianceId == id } ?: return@mapNotNull null
            val state = states[id]
            val tap = if (device.hasPower) {
                actionRunCallback<ToggleAction>(actionParametersOf(APPLIANCE_ID to id))
            } else {
                actionRunCallback<RefreshAction>()
            }
            Entry(key, device.name, statusText(device, state), state?.power == true, tap)
        }
    }
}

@Composable
private fun Content(config: WidgetConfig, entries: List<Entry>, updatedAt: Long) {
    val colors = GlanceTheme.colors
    val frame = GlanceModifier.fillMaxSize().cornerRadius(24.dp).padding(8.dp)
        .let { if (config.transparent) it else it.background(colors.widgetBackground) }
    if (entries.isEmpty()) {
        Box(frame.clickable(actionStartActivity<MainActivity>()), contentAlignment = Alignment.Center) {
            Text(
                str(R.string.widget_empty),
                style = TextStyle(color = colors.onSurfaceVariant, textAlign = TextAlign.Center),
            )
        }
        return
    }
    Column(frame) {
        if (config.header) Header(updatedAt)
        val height = TILE_HEIGHTS[config.size] - if (config.status) 0.dp else STATUS_HEIGHT
        LazyVerticalGrid(GridCells.Fixed(config.columns), modifier = GlanceModifier.fillMaxSize()) {
            items(entries, itemId = { it.key.hashCode().toLong() }) { entry ->
                val content = if (entry.on) colors.onPrimaryContainer else colors.onSurface
                // The grid has no spacing of its own: the outer box makes the gap between the tiles.
                Box(GlanceModifier.fillMaxWidth().padding(4.dp)) {
                    Column(
                        GlanceModifier
                            .fillMaxWidth()
                            .height(height)
                            .background(if (entry.on) colors.primaryContainer else colors.surfaceVariant)
                            .cornerRadius(16.dp)
                            .padding(horizontal = 12.dp)
                            .clickable(entry.tap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.name,
                            maxLines = 1,
                            style = TextStyle(
                                color = content,
                                fontSize = NAME_SIZES[config.size],
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        if (config.status) {
                            Text(entry.status, maxLines = 1, style = TextStyle(color = content, fontSize = 12.sp))
                        }
                    }
                }
            }
        }
    }
}

/** The app name, when the states were last fetched, and a button to fetch them again. */
@Composable
private fun Header(updatedAt: Long) {
    val colors = GlanceTheme.colors
    Row(
        GlanceModifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            str(R.string.app_name),
            style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
        )
        if (updatedAt > 0) {
            Text(clock(updatedAt), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp))
        }
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = str(R.string.refresh),
            colorFilter = ColorFilter.tint(colors.onSurfaceVariant),
            modifier = GlanceModifier
                .padding(start = 8.dp)
                .size(28.dp)
                .clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val outcome = Quick.toggle(context, parameters[APPLIANCE_ID] ?: return)
        if (!outcome.ok) {
            withContext(Dispatchers.Main) { Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show() }
        }
    }
}

class ToggleGroupAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val outcome = Quick.toggleGroup(context, parameters[GROUP_ID] ?: return)
        if (!outcome.ok) {
            withContext(Dispatchers.Main) { Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show() }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Quick.fetchFavorites(context)
    }
}

class HomeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = HomeWidget()

    // Runs when the widget is placed and then about every half hour.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val app = context.applicationContext
        Quick.scope.launch { Quick.fetchFavorites(app) }
    }
}
