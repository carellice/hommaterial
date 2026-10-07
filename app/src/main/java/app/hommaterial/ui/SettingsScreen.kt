package app.hommaterial.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.Device
import app.hommaterial.data.Group
import app.hommaterial.plural
import app.hommaterial.quick.Alerts
import app.hommaterial.quick.MAX_SHORTCUTS
import app.hommaterial.quick.SHORTCUT_DEVICE
import app.hommaterial.quick.SHORTCUT_GROUP
import app.hommaterial.quick.clock
import app.hommaterial.quick.degrees
import app.hommaterial.str

private const val SOURCE_URL = "https://github.com/carellice/hommaterial"
private const val BACKUP_FILE = "hommaterial-backup.json"
// File managers disagree on what a .json file is.
private val BACKUP_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/** Everything the user can manage about the app, in one place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: HomeViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Every visit looks for a newer release, whatever the automatic check is set to.
    LaunchedEffect(Unit) { vm.checkForUpdate(manual = true, quiet = true) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRooms by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Device?>(null) }
    // The room or group being edited; an empty name or a null group stand for a new one.
    var editingRoom by remember { mutableStateOf<String?>(null) }
    var editingGroup by remember { mutableStateOf<Group?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var choosingShortcuts by remember { mutableStateOf(false) }
    var choosingWidget by remember { mutableStateOf(false) }
    val placed = state.placed
    val noRoom = str(R.string.room_none)
    val rooms = (placed.mapNotNull { it.room } + state.roomList).distinct().sortedBy { it.lowercase() }
    val switchable = placed.filter { it.hasPower }
    moving?.let { device ->
        RoomPicker(
            device = device,
            current = state.rooms[device.applianceId],
            rooms = rooms,
            onPick = { vm.setRoom(device, it); moving = null },
            onDismiss = { moving = null },
        )
    }
    editingRoom?.let { room ->
        val old = room.ifEmpty { null }
        MembersDialog(
            title = str(if (old == null) R.string.room_new else R.string.room_edit_title),
            hint = str(R.string.room_devices_hint),
            name = room,
            picks = placed.map { Pick(it.applianceId, it.name, it.room ?: noRoom) },
            members = placed.filter { old != null && it.room == old }.map { it.applianceId }.toSet(),
            onSave = { name, members -> vm.saveRoom(old, name, members); editingRoom = null },
            // Only the rooms made here can go: those of Alexa come back as long as devices are in them.
            onDelete = if (room in state.roomList) { { vm.deleteRoom(room); editingRoom = null } } else null,
            onDismiss = { editingRoom = null },
        )
    }
    if (creatingGroup || editingGroup != null) {
        val group = editingGroup
        val close = { creatingGroup = false; editingGroup = null }
        MembersDialog(
            title = str(if (group == null) R.string.group_new else R.string.group_edit_title),
            hint = str(R.string.group_devices_hint),
            name = group?.name.orEmpty(),
            picks = switchable.map { Pick(it.applianceId, it.name, it.room ?: noRoom) },
            members = group?.devices.orEmpty(),
            onSave = { name, members -> vm.saveGroup(group?.id, name, members); close() },
            onDelete = group?.let { { vm.deleteGroup(it.id); close() } },
            onDismiss = close,
        )
    }
    if (choosingShortcuts) {
        PicksDialog(
            title = str(R.string.shortcuts_choose),
            picks = state.groups.map { Pick(SHORTCUT_GROUP + it.id, it.name, str(R.string.group_edit_title)) } +
                switchable.map { Pick(SHORTCUT_DEVICE + it.applianceId, it.name, it.room ?: noRoom) },
            initial = state.shortcuts.orEmpty(),
            limit = MAX_SHORTCUTS,
            onSave = { vm.setShortcuts(it); choosingShortcuts = false },
            onDismiss = { choosingShortcuts = false },
        )
    }
    if (choosingWidget) {
        val shown = placed.filter { it.applianceId !in state.hidden }
        PicksDialog(
            title = str(R.string.widget_items),
            picks = state.groups.map { Pick(SHORTCUT_GROUP + it.id, it.name, str(R.string.group_edit_title)) } +
                shown.map { Pick(SHORTCUT_DEVICE + it.applianceId, it.name, it.room ?: noRoom) },
            initial = state.widget.items.orEmpty(),
            limit = null,
            onSave = { vm.setWidget(state.widget.copy(items = it)); choosingWidget = false },
            onDismiss = { choosingWidget = false },
        )
    }
    if (confirmRooms) {
        ConfirmDialog(
            title = str(R.string.rooms_reset_title),
            text = str(R.string.rooms_reset_text),
            confirm = str(R.string.rooms_reset_confirm),
            onConfirm = vm::resetRooms,
            onDismiss = { confirmRooms = false },
        )
    }
    if (confirmSignOut) SignOutDialog(onConfirm = vm::logout, onDismiss = { confirmSignOut = false })
    if (confirmClear) {
        ConfirmDialog(
            title = str(R.string.history_clear_title),
            text = str(R.string.history_clear_text),
            confirm = str(R.string.history_clear_confirm),
            onConfirm = vm::clearHistory,
            onDismiss = { confirmClear = false },
        )
    }

    // Notifications are switched in the phone settings: read again on the way back from there.
    var returns by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { returns++ }
    val notifications = remember(returns) { Alerts.enabled(context) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) vm.exportBackup(it)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) vm.importBackup(it)
    }
    val names = state.devices.associate { it.applianceId to it.name }
    val now = System.currentTimeMillis()
    val timers = state.timers.filter { it.value > now && it.key in names }
    val alerts = state.alerts.filterKeys { it in names }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val update = state.update
            if (update != null) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = vm::installUpdate,
                        enabled = state.updateProgress == null,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(str(R.string.update_to, update.version))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { Section(R.string.s_notifications) }
            item {
                Setting(
                    title = str(if (notifications) R.string.notifications_on else R.string.notifications_off),
                    text = str(R.string.open_phone_settings),
                    onClick = {
                        context.open(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item { Section(R.string.s_language) }
                item {
                    Setting(
                        title = str(R.string.language_choose),
                        text = str(R.string.language_text),
                        onClick = {
                            context.open(
                                Intent(
                                    Settings.ACTION_APP_LOCALE_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        },
                    )
                }
            }

            item { Section(R.string.s_updates) }
            item {
                Setting(
                    title = str(R.string.auto_update),
                    text = str(R.string.auto_update_text),
                    onClick = { vm.setAutoUpdate(!state.autoUpdate) },
                    trailing = { Switch(checked = state.autoUpdate, onCheckedChange = vm::setAutoUpdate) },
                )
            }
            item {
                Setting(
                    title = str(R.string.check_updates),
                    text = str(R.string.installed_version, vm.installedVersion),
                    onClick = { vm.checkForUpdate(manual = true) },
                )
            }

            if (state.devices.isNotEmpty()) {
                item { Section(R.string.s_rooms, R.string.rooms_text) }
                items(rooms, key = { "room:$it" }) { room ->
                    Setting(
                        title = room,
                        text = plural(R.plurals.devices, placed.count { it.room == room }),
                        onClick = { editingRoom = room },
                    )
                }
                item {
                    Setting(title = str(R.string.room_new), onClick = { editingRoom = "" }, trailing = { AddIcon() })
                }
                if (state.rooms.isNotEmpty() || state.roomList.isNotEmpty()) {
                    item { Setting(title = str(R.string.rooms_reset), onClick = { confirmRooms = true }) }
                }

                item { Section(R.string.s_devices, R.string.devices_text) }
                // Listed in their rooms as shown in the app, but moved as the devices Alexa knows.
                items(state.devices, key = { it.applianceId }) { device ->
                    val room = placed.first { it.applianceId == device.applianceId }.room
                    DeviceRow(device, room, state, vm, onClick = { moving = device })
                }
            }

            if (switchable.isNotEmpty()) {
                item { Section(R.string.s_groups, R.string.groups_text) }
                items(state.groups, key = { "group:${it.id}" }) { group ->
                    Setting(
                        title = group.name,
                        text = plural(R.plurals.devices, switchable.count { it.applianceId in group.devices }),
                        onClick = { editingGroup = group },
                    )
                }
                item {
                    Setting(
                        title = str(R.string.group_new),
                        onClick = { creatingGroup = true },
                        trailing = { AddIcon() },
                    )
                }

                item { Section(R.string.s_shortcuts, R.string.shortcuts_text) }
                item {
                    val names = state.shortcuts?.mapNotNull { key ->
                        val id = key.substringAfter(':')
                        if (key.startsWith(SHORTCUT_GROUP)) {
                            state.groups.find { it.id == id }?.name
                        } else {
                            switchable.find { it.applianceId == id }?.name
                        }
                    }
                    Setting(
                        title = str(R.string.shortcuts_choose),
                        text = when {
                            names == null -> str(R.string.shortcuts_default)
                            names.isEmpty() -> str(R.string.shortcuts_none)
                            else -> names.joinToString(", ")
                        },
                        onClick = { choosingShortcuts = true },
                    )
                }
            }

            if (state.devices.isNotEmpty()) {
                val widget = state.widget
                item { Section(R.string.s_widget, R.string.widget_text) }
                item {
                    val names = widget.items?.mapNotNull { key ->
                        val id = key.substringAfter(':')
                        if (key.startsWith(SHORTCUT_GROUP)) {
                            state.groups.find { it.id == id }?.name
                        } else {
                            placed.find { it.applianceId == id }?.name
                        }
                    }
                    Setting(
                        title = str(R.string.widget_items),
                        text = when {
                            names == null -> str(R.string.widget_items_default)
                            names.isEmpty() -> str(R.string.widget_items_none)
                            else -> names.joinToString(", ")
                        },
                        onClick = { choosingWidget = true },
                    )
                }
                item {
                    Chips(R.string.widget_columns, (1..4).map { it to it.toString() }, widget.columns) {
                        vm.setWidget(widget.copy(columns = it))
                    }
                }
                item {
                    val sizes = listOf(R.string.widget_compact, R.string.widget_normal, R.string.widget_large)
                    Chips(R.string.widget_size, sizes.mapIndexed { i, label -> i to str(label) }, widget.size) {
                        vm.setWidget(widget.copy(size = it))
                    }
                }
                item {
                    Toggle(R.string.widget_status, R.string.widget_status_text, widget.status) {
                        vm.setWidget(widget.copy(status = it))
                    }
                }
                item {
                    Toggle(R.string.widget_header, R.string.widget_header_text, widget.header) {
                        vm.setWidget(widget.copy(header = it))
                    }
                }
                item {
                    Toggle(R.string.widget_transparent, R.string.widget_transparent_text, widget.transparent) {
                        vm.setWidget(widget.copy(transparent = it))
                    }
                }
            }

            item { Section(R.string.s_tiles, R.string.tiles_text) }
            items(state.tileDevices.size) { slot ->
                val name = names[state.tileDevices[slot]]
                Setting(
                    title = str(R.string.tile_slot, slot + 1),
                    text = name ?: str(R.string.tile_free),
                    trailing = {
                        if (name != null) {
                            IconButton(onClick = { vm.clearTile(slot) }) {
                                Icon(Icons.Outlined.Close, contentDescription = str(R.string.tile_clear))
                            }
                        }
                    },
                )
            }

            if (timers.isNotEmpty()) {
                item { Section(R.string.s_timers) }
                items(timers.toList(), key = { "timer:${it.first}" }) { (id, at) ->
                    Setting(
                        title = names.getValue(id),
                        text = str(R.string.turns_off_at, clock(at)),
                        trailing = {
                            IconButton(onClick = { vm.cancelTimer(id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = str(R.string.cancel_timer))
                            }
                        },
                    )
                }
            }

            if (alerts.isNotEmpty()) {
                item { Section(R.string.s_alerts) }
                items(alerts.toList(), key = { "alert:${it.first}" }) { (id, alert) ->
                    val thresholds = listOfNotNull(
                        alert.above?.let { str(R.string.alert_above, degrees(it)) },
                        alert.below?.let { str(R.string.alert_below, degrees(it)) },
                    )
                    Setting(
                        title = names.getValue(id),
                        text = thresholds.joinToString(" · "),
                        trailing = {
                            IconButton(onClick = { vm.clearAlert(id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = str(R.string.alert_remove))
                            }
                        },
                    )
                }
            }

            item { Section(R.string.s_backup, R.string.backup_text) }
            item { Setting(title = str(R.string.backup_export), onClick = { export.launch(BACKUP_FILE) }) }
            item { Setting(title = str(R.string.backup_import), onClick = { import.launch(BACKUP_TYPES) }) }

            item { Section(R.string.s_data) }
            item { Setting(title = str(R.string.history_clear), onClick = { confirmClear = true }) }

            item { Section(R.string.s_account) }
            item {
                Setting(
                    title = str(R.string.sign_out),
                    text = str(R.string.account_marketplace, state.marketplace.domain),
                    onClick = { confirmSignOut = true },
                )
            }

            item { Section(R.string.s_about) }
            item {
                Setting(
                    title = str(R.string.about_source),
                    text = str(R.string.installed_version, vm.installedVersion),
                    onClick = { context.open(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) },
                )
            }
        }
    }
}

/** A setting with a handful of values, all in sight. */
@Composable
private fun Chips(@StringRes title: Int, choices: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(str(title), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in choices) {
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun Toggle(@StringRes title: Int, @StringRes text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Setting(
        title = str(title),
        text = str(text),
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun AddIcon() {
    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(@StringRes title: Int, @StringRes text: Int? = null) {
    Text(
        str(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
    if (text != null) {
        Text(
            str(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun Setting(
    title: String,
    text: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = text?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** A device with its two choices: among the favorites or not, shown in the list or hidden. */
@Composable
private fun DeviceRow(device: Device, room: String?, state: UiState, vm: HomeViewModel, onClick: () -> Unit) {
    val favorite = device.applianceId in state.favorites
    val hidden = device.applianceId in state.hidden
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(room ?: str(R.string.room_none)) },
        modifier = Modifier.clickable(onClick = onClick),
        trailingContent = {
            Row {
                IconButton(onClick = { vm.setFavorite(device, !favorite) }) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = str(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                        tint = if (favorite) colors.primary else colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.setHidden(device, !hidden) }) {
                    Icon(
                        if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = str(if (hidden) R.string.unhide else R.string.hide),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** Opens a screen of the phone, which some versions and brands of Android do not have. */
private fun Context.open(intent: Intent) {
    runCatching { startActivity(intent) }
}
