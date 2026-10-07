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
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import app.hommaterial.MainActivity
import app.hommaterial.data.Cache
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.statusText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val APPLIANCE_ID = ActionParameters.Key<String>(EXTRA_APPLIANCE_ID)

/** Home screen widget with the favorites: a tap toggles a device, or refreshes a sensor. */
class HomeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Re-read whenever the app or another surface changes what is remembered.
            val version by Quick.changes.collectAsState()
            val cache = remember(version) { Cache(context) }
            val favorites = remember(version) {
                val chosen = cache.favorites() - cache.hidden()
                cache.devices().filter { it.applianceId in chosen }
            }
            val states = remember(version) { cache.states() }
            GlanceTheme { Content(favorites, states) }
        }
    }
}

@Composable
private fun Content(favorites: List<Device>, states: Map<String, DeviceState>) {
    val colors = GlanceTheme.colors
    val frame = GlanceModifier.fillMaxSize().background(colors.widgetBackground).cornerRadius(24.dp).padding(8.dp)
    if (favorites.isEmpty()) {
        Box(frame.clickable(actionStartActivity<MainActivity>()), contentAlignment = Alignment.Center) {
            Text(
                "Aggiungi dei preferiti dall'app",
                style = TextStyle(color = colors.onSurfaceVariant, textAlign = TextAlign.Center),
            )
        }
        return
    }
    LazyVerticalGrid(GridCells.Fixed(2), modifier = frame) {
        items(favorites, itemId = { it.applianceId.hashCode().toLong() }) { device ->
            val state = states[device.applianceId]
            val on = state?.power == true
            val content = if (on) colors.onPrimaryContainer else colors.onSurface
            val tap = if (device.hasPower) {
                actionRunCallback<ToggleAction>(actionParametersOf(APPLIANCE_ID to device.applianceId))
            } else {
                actionRunCallback<RefreshAction>()
            }
            // The grid has no spacing of its own: the outer box makes the gap between the tiles.
            Box(GlanceModifier.fillMaxWidth().padding(4.dp)) {
                Column(
                    GlanceModifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(if (on) colors.primaryContainer else colors.surfaceVariant)
                        .cornerRadius(16.dp)
                        .padding(horizontal = 12.dp)
                        .clickable(tap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        device.name,
                        maxLines = 1,
                        style = TextStyle(color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    )
                    Text(statusText(device, state), maxLines = 1, style = TextStyle(color = content, fontSize = 12.sp))
                }
            }
        }
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
