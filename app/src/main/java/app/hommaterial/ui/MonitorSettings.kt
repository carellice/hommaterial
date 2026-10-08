package app.hommaterial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import app.hommaterial.str

private val REFRESH_CHOICES = listOf(30, 60, 120, 300)

/** Everything about the tablet used as the panel of the home: what it shows and how it behaves. */
@Composable
internal fun MonitorSettings(state: UiState, vm: HomeViewModel) {
    val monitor = state.monitor
    val available = availablePages(state)
    var starting by remember { mutableStateOf(false) }
    var choosingPages by remember { mutableStateOf(false) }
    // The page whose content is being chosen.
    var narrowing by remember { mutableStateOf<String?>(null) }

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
