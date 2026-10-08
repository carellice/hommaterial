package app.hommaterial.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.MonitorConfig
import app.hommaterial.data.REST_CLOCK
import app.hommaterial.data.REST_DATE
import app.hommaterial.data.REST_POWER
import app.hommaterial.data.REST_SENSORS
import app.hommaterial.data.REST_TIMERS
import app.hommaterial.str

private val REFRESH_CHOICES = listOf(30, 60, 120, 300)
private val REST_CHOICES = listOf(1, 2, 5, 10)
private val REST_LABELS = listOf(
    REST_CLOCK to R.string.rest_item_clock,
    REST_DATE to R.string.rest_item_date,
    REST_SENSORS to R.string.rest_item_sensors,
    REST_POWER to R.string.rest_item_power,
    REST_TIMERS to R.string.rest_item_timers,
)
// In percent; 0 leaves the brightness of the tablet alone.
private val BRIGHTNESS_CHOICES = listOf(
    1 to R.string.brightness_lowest,
    15 to R.string.brightness_low,
    40 to R.string.brightness_medium,
    0 to R.string.brightness_same,
)

/** Everything about the tablet used as the panel of the home: what it shows and how it behaves. */
@Composable
internal fun MonitorSettings(state: UiState, vm: HomeViewModel) {
    val monitor = state.monitor
    val available = availablePages(state)
    var starting by remember { mutableStateOf(false) }
    var choosingPages by remember { mutableStateOf(false) }
    // The page whose content is being chosen.
    var narrowing by remember { mutableStateOf<String?>(null) }
    var choosingSensors by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }

    if (starting) {
        PresetDialog(
            rooms = available.filter { it.startsWith(PAGE_ROOM) && it != PAGE_ROOM },
            onPick = { vm.setMonitor(monitor.copy(enabled = true, pages = it)); starting = false },
            onDismiss = { starting = false },
        )
    }
    if (choosingPages) {
        PicksDialog(
            title = str(R.string.monitor_pages),
            picks = available.map { Pick(it, pageLabel(it)) },
            initial = monitor.pages ?: available,
            limit = null,
            reset = str(R.string.monitor_pages_all),
            onSave = { vm.setMonitor(monitor.copy(pages = it)); choosingPages = false },
            onDismiss = { choosingPages = false },
        )
    }
    narrowing?.let { page ->
        val picks = pagePicks(state, page)
        PicksDialog(
            title = pageLabel(page),
            picks = picks,
            initial = monitor.only[page]?.toList() ?: picks.map { it.key },
            limit = null,
            reset = str(R.string.monitor_show_all),
            onSave = { chosen ->
                // Everything ticked is no choice at all: what is added later shows up by itself.
                val some = chosen?.takeIf { it.size < picks.size }?.toSet()
                val only = if (some == null) monitor.only - page else monitor.only + (page to some)
                vm.setMonitor(monitor.copy(only = only))
                narrowing = null
            },
            onDismiss = { narrowing = null },
        )
    }

    Toggle(R.string.monitor_enable, R.string.monitor_enable_text, monitor.enabled) { on ->
        // The first time it starts from a ready-made panel, to be adjusted afterwards.
        if (on && monitor == MonitorConfig()) starting = true else vm.setMonitor(monitor.copy(enabled = on))
    }
    if (!monitor.enabled) return

    val pages = shownPages(state, wide = true)
    Setting(
        title = str(R.string.monitor_pages),
        text = if (monitor.pages == null) str(R.string.monitor_pages_default) else pages.joinToString { pageLabel(it) },
        onClick = { choosingPages = true },
    )
    Text(
        str(R.string.monitor_pages_order),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    pages.forEachIndexed { index, page ->
        val only = monitor.only[page]
        val total = pagePicks(state, page)
        OrderRow(
            name = pageLabel(page),
            text = if (only == null) {
                str(R.string.monitor_page_all)
            } else {
                str(R.string.monitor_page_some, total.count { it.key in only }, total.size)
            },
            first = index == 0,
            last = index == pages.lastIndex,
            onClick = { narrowing = page },
        ) { by ->
            val moved = pages.toMutableList()
            moved.add(index + by, moved.removeAt(index))
            vm.setMonitor(monitor.copy(pages = moved))
        }
    }
    val sizes = listOf(R.string.widget_compact, R.string.widget_normal, R.string.widget_large)
    Chips(R.string.widget_size, sizes.mapIndexed { i, label -> i to str(label) }, monitor.tileSize) {
        vm.setMonitor(monitor.copy(tileSize = it))
    }
    Toggle(R.string.monitor_clock, R.string.monitor_clock_text, monitor.clock) {
        vm.setMonitor(monitor.copy(clock = it))
    }
    Toggle(R.string.monitor_keep_on, R.string.monitor_keep_on_text, monitor.keepOn) {
        vm.setMonitor(monitor.copy(keepOn = it))
    }
    Toggle(R.string.monitor_fullscreen, R.string.monitor_fullscreen_text, monitor.fullscreen) {
        vm.setMonitor(monitor.copy(fullscreen = it))
    }
    Chips(R.string.monitor_refresh, REFRESH_CHOICES.map { it to duration(it) }, monitor.refreshSeconds) {
        vm.setMonitor(monitor.copy(refreshSeconds = it))
    }

    if (settingPin) {
        PinDialog(
            title = str(R.string.pin_new_title),
            hint = str(R.string.pin_new_hint),
            onSubmit = { vm.setPin(it); settingPin = false; true },
            onDismiss = { settingPin = false },
        )
    }
    Setting(
        title = str(if (state.pin) R.string.monitor_pin_on else R.string.monitor_pin_set),
        text = str(if (state.pin) R.string.monitor_pin_on_text else R.string.monitor_pin_set_text),
        onClick = { settingPin = true },
        trailing = {
            if (state.pin) {
                IconButton(onClick = { vm.setPin(null) }) {
                    Icon(Icons.Outlined.Close, contentDescription = str(R.string.pin_remove))
                }
            }
        },
    )

    Toggle(R.string.monitor_rest, R.string.monitor_rest_text, monitor.rest) { vm.setMonitor(monitor.copy(rest = it)) }
    if (!monitor.rest) return
    Chips(R.string.monitor_rest_after, REST_CHOICES.map { it to duration(it * 60) }, monitor.restMinutes) {
        vm.setMonitor(monitor.copy(restMinutes = it))
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(str(R.string.monitor_rest_items), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((item, label) in REST_LABELS) {
                val shown = item in monitor.restItems
                FilterChip(
                    selected = shown,
                    onClick = {
                        val items = if (shown) monitor.restItems - item else monitor.restItems + item
                        vm.setMonitor(monitor.copy(restItems = items))
                    },
                    label = { Text(str(label)) },
                )
            }
        }
    }
    val sensors = state.placed.filter { it.isSensor && it.applianceId !in state.hidden }
    if (REST_SENSORS in monitor.restItems && sensors.size > 1) {
        val chosen = monitor.restSensors
        Setting(
            title = str(R.string.monitor_rest_sensors),
            text = if (chosen == null) {
                str(R.string.monitor_rest_sensors_all)
            } else {
                sensors.filter { it.applianceId in chosen }.joinToString { it.name }
            },
            onClick = { choosingSensors = true },
        )
    }
    if (choosingSensors) {
        PicksDialog(
            title = str(R.string.monitor_rest_sensors),
            picks = sensors.map { Pick(it.applianceId, it.name, it.room ?: str(R.string.room_none)) },
            initial = monitor.restSensors ?: sensors.map { it.applianceId },
            limit = null,
            reset = str(R.string.monitor_all_sensors),
            onSave = { vm.setMonitor(monitor.copy(restSensors = it)); choosingSensors = false },
            onDismiss = { choosingSensors = false },
        )
    }
    val levels = BRIGHTNESS_CHOICES.map { it.first to str(it.second) }
    Chips(R.string.monitor_rest_brightness, levels, monitor.restBrightness) {
        vm.setMonitor(monitor.copy(restBrightness = it))
    }
    Toggle(R.string.monitor_night, R.string.monitor_night_text, monitor.night) {
        vm.setMonitor(monitor.copy(night = it))
    }
    if (monitor.night) {
        HourRow(R.string.monitor_night_from, monitor.nightFrom) { vm.setMonitor(monitor.copy(nightFrom = it)) }
        HourRow(R.string.monitor_night_to, monitor.nightTo) { vm.setMonitor(monitor.copy(nightTo = it)) }
    }
}

/** An hour of the day, moved one hour at a time and around midnight. */
@Composable
private fun HourRow(@StringRes label: Int, hour: Int, onChange: (Int) -> Unit) {
    Setting(
        title = str(label, "%02d:00".format(hour)),
        trailing = {
            Row {
                IconButton(onClick = { onChange((hour + 23) % 24) }) {
                    Icon(Icons.Outlined.Remove, contentDescription = str(R.string.hour_earlier))
                }
                IconButton(onClick = { onChange((hour + 1) % 24) }) {
                    Icon(Icons.Outlined.Add, contentDescription = str(R.string.hour_later))
                }
            }
        },
    )
}

private fun duration(seconds: Int): String =
    if (seconds < 60) str(R.string.monitor_seconds, seconds) else str(R.string.monitor_minutes, seconds / 60)

/** What a page can be narrowed to: its devices, or the groups for the page that lists them. */
private fun pagePicks(state: UiState, page: String): List<Pick> {
    if (page == PAGE_GROUPS) return state.groups.map { Pick(it.id, it.name) }
    val visible = state.placed.filter { it.applianceId !in state.hidden }
    val room = page.removePrefix(PAGE_ROOM).ifEmpty { null }
    val devices = when (page) {
        PAGE_ALL -> visible
        PAGE_FAVORITES -> visible.filter { it.applianceId in state.favorites }
        else -> visible.filter { it.room == room }
    }
    return devices.map { Pick(it.applianceId, it.name, it.room ?: str(R.string.room_none)) }
}

/** The two ways a panel usually starts: the whole home, or the room the tablet hangs in. */
@Composable
private fun PresetDialog(rooms: List<String>, onPick: (List<String>?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.monitor_preset_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Preset(str(R.string.monitor_preset_all), str(R.string.monitor_preset_all_text)) { onPick(null) }
                for (room in rooms) {
                    Preset(pageLabel(room), str(R.string.monitor_preset_room_text)) { onPick(listOf(room)) }
                }
                Text(
                    str(R.string.monitor_preset_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
private fun Preset(title: String, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
