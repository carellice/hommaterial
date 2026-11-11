package app.hommaterial.ui

import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.style.TextAlign
import app.hommaterial.data.SettingsSearch
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Shortcut
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import app.hommaterial.data.BackupSection
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.hommaterial.HomeViewModel
import app.hommaterial.LAYOUT_GRID
import app.hommaterial.LAYOUT_LIST
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.Device
import app.hommaterial.data.Group
import app.hommaterial.data.LgTv
import app.hommaterial.data.Macro
import app.hommaterial.plural
import app.hommaterial.quick.Alerts
import app.hommaterial.quick.MAX_SHORTCUTS
import app.hommaterial.quick.Quick
import app.hommaterial.quick.SHORTCUT_DEVICE
import app.hommaterial.quick.SHORTCUT_GROUP
import app.hommaterial.quick.SHORTCUT_MACRO
import app.hommaterial.quick.clock
import app.hommaterial.quick.degrees
import app.hommaterial.str
import kotlin.math.roundToInt

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
    val rooms = state.allRooms
    // Remotes and custom devices, as what can be put in a room next to the devices.
    val extras = state.macros.map { Pick(Quick.macroKey(it.id), it.name, str(R.string.macro_edit)) } +
        state.remotes.map { Pick(Quick.remoteKey(it.id), it.name, str(R.string.remote_generic)) }
    val switchable = placed.filter { it.hasPower }
    moving?.let { device ->
        RoomPicker(
            name = device.name,
            follow = str(R.string.room_as_alexa, device.room ?: str(R.string.room_none)),
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
            picks = placed.map { Pick(it.applianceId, it.name, it.room ?: noRoom) } + extras,
            members = placed.filter { old != null && it.room == old }.map { it.applianceId }.toSet() +
                extras.filter { old != null && state.placeOf(it.key) == old }.map { it.key },
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
            picks = state.macros.map { Pick(SHORTCUT_MACRO + it.id, it.name, str(R.string.macro_edit)) } +
                state.groups.map { Pick(SHORTCUT_GROUP + it.id, it.name, str(R.string.group_edit_title)) } +
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
            picks = state.macros.map { Pick(SHORTCUT_MACRO + it.id, it.name, str(R.string.macro_edit)) } +
                state.groups.map { Pick(SHORTCUT_GROUP + it.id, it.name, str(R.string.group_edit_title)) } +
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
    if (state.askBackupRestore) {
        var selected by remember { mutableStateOf(BackupSection.entries.filter { it != BackupSection.MONITOR }.toSet()) }
        AlertDialog(
            onDismissRequest = { vm.finishImport(null) },
            title = { Text(str(R.string.backup_choose)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(str(R.string.backup_choose_text))
                    Row {
                        TextButton(onClick = { selected = BackupSection.entries.toSet() }) { Text(str(R.string.backup_all)) }
                        TextButton(onClick = { selected = emptySet() }) { Text(str(R.string.backup_none)) }
                    }
                    for (section in BackupSection.entries) {
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = if (section in selected) selected - section else selected + section
                        }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = section in selected, onCheckedChange = null)
                            Text(str(section.title), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = { vm.finishImport(selected) }) { Text(str(R.string.backup_import)) }
            },
            dismissButton = { TextButton(onClick = { vm.finishImport(null) }) { Text(str(R.string.cancel)) } },
        )
    }
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

    var linkingSwitchBot by remember { mutableStateOf(false) }
    var editingMacro by remember { mutableStateOf<Macro?>(null) }
    var creatingMacro by remember { mutableStateOf(false) }
    if (creatingMacro || editingMacro != null) {
        MacroEditor(editingMacro, state, vm, onDismiss = { creatingMacro = false; editingMacro = null })
    }
    var confirmSwitchBot by remember { mutableStateOf(false) }
    var removingTv by remember { mutableStateOf<LgTv?>(null) }
    var renamingTv by remember { mutableStateOf<LgTv?>(null) }
    renamingTv?.let { tv ->
        LgRenameDialog(tv.name, onRename = { vm.renameLgTv(tv.id, it); renamingTv = null }, onDismiss = { renamingTv = null })
    }
    state.tvSearch?.let { LgTvDialog(it, vm) }
    removingTv?.let { tv ->
        ConfirmDialog(
            title = str(R.string.lgtv_remove_title, tv.name),
            text = str(R.string.lgtv_remove_text),
            confirm = str(R.string.lgtv_remove),
            onConfirm = { vm.forgetLgTv(tv) },
            onDismiss = { removingTv = null },
        )
    }
    if (linkingSwitchBot) {
        SwitchBotDialog(
            onConnect = { token, secret -> vm.connectSwitchBot(token, secret); linkingSwitchBot = false },
            onDismiss = { linkingSwitchBot = false },
        )
    }
    if (confirmSwitchBot) {
        ConfirmDialog(
            title = str(R.string.switchbot_unlink_title),
            text = str(R.string.switchbot_unlink_text),
            confirm = str(R.string.switchbot_unlink),
            onConfirm = vm::disconnectSwitchBot,
            onDismiss = { confirmSwitchBot = false },
        )
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) vm.exportBackup(it)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) vm.importBackup(it)
    }
    val names = state.devices.associate { it.applianceId to it.name }
    // The name behind an entry of the shortcuts or of the widget, null when it no longer exists.
    fun nameOf(key: String): String? {
        val id = key.substringAfter(':')
        return when {
            key.startsWith(SHORTCUT_GROUP) -> state.groups.find { it.id == id }?.name
            key.startsWith(SHORTCUT_MACRO) -> state.macros.find { it.id == id }?.name
            else -> names[id]
        }
    }
    val favoriteKeys = state.devices
        .filter { it.applianceId in state.favorites && it.applianceId !in state.hidden }
        .map { SHORTCUT_DEVICE + it.applianceId } +
        state.groups.filter { "group:${it.id}" in state.favorites }.map { SHORTCUT_GROUP + it.id }
    val now = System.currentTimeMillis()
    val timers = state.timers.filter { it.value > now && it.key in names }
    val alerts = state.alerts.filterKeys { it in names }

    Scaffold(
        modifier = Modifier.screenTexture(),
        containerColor = screenColor(),
        topBar = {
            AppTopBar(
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
                    AppButton(
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
        // The sections that are open, by their title; all closed on arrival.
        var open by rememberSaveable { mutableStateOf(setOf<Int>()) }
        // What is typed in the search: the sections it finds show open, the others hide.
        var query by rememberSaveable { mutableStateOf("") }
        val searching = query.isNotBlank()
        // How many sections the search found, counted as they compose, in order.
        var found = 0

        @Composable
        fun Section(@StringRes title: Int, @StringRes text: Int? = null, content: @Composable () -> Unit) {
            if (searching) {
                val about = listOfNotNull(str(title), text?.let { str(it) }, sectionKeys(title)?.let { str(it) }).joinToString(" ")
                if (!SettingsSearch.matches(query, about)) return
                found++
            }
            Accordion(
                title = title,
                text = text,
                expanded = searching || title in open,
                onToggle = { open = if (title in open) open - title else open + title },
                content = content,
            )
        }

        /** The heading of a few sections that belong together; the search lists what it finds without them. */
        @Composable
        fun Group(@StringRes title: Int) {
            if (searching) return
            // In the skeuomorphic look, the engraved capitals of a grouped table.
            val skeuo = LocalSkeuoMaterials.current
            // In Modern UI, the big light lowercase words of a header in the accent.
            if (LocalMetro.current) {
                Text(
                    str(title).lowercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp),
                )
                return
            }
            Text(
                if (skeuo != null) str(title).uppercase() else str(title),
                style = if (skeuo != null) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, shadow = skeuo.emboss)
                else MaterialTheme.typography.titleSmall,
                color = if (skeuo != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, top = 12.dp),
            )
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // On a wide screen the sections keep a readable width, centered, instead of stretching.
                .wrapContentWidth()
                .widthIn(max = 640.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSearchField(query) { query = it }
            Group(R.string.settings_group_general)
            Section(R.string.s_appearance) {
                val themes = listOf(R.string.theme_auto, R.string.theme_light, R.string.theme_dark)
                Chips(R.string.theme, themes.mapIndexed { i, label -> i to str(label) }, state.theme, vm::setTheme)
                Chips(R.string.look, listOf(LOOK_MATERIAL to str(R.string.look_material), LOOK_SKEUO to str(R.string.look_skeuo), LOOK_METRO to str(R.string.look_metro)), state.look, vm::setLook)
                AccentPicker(state.accent, vm::setAccent)
                val layouts = listOf(LAYOUT_GRID to str(R.string.layout_grid), LAYOUT_LIST to str(R.string.layout_list))
                Chips(R.string.layout, layouts, state.layout, vm::setLayout)
                Toggle(R.string.haptics, R.string.haptics_text, state.haptics, vm::setHaptics)
                if (state.haptics) HapticLevel(state.hapticLevel, vm::setHapticLevel)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

            Section(R.string.s_notifications) {
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

            Section(R.string.s_weather, R.string.weather_text) { WeatherSettings(state, vm) }
            Section(R.string.s_team, R.string.team_text) { TeamSettings(state, vm) }

            Group(R.string.settings_group_services)
            Section(R.string.s_server, R.string.server_text) { ServerSettings(state, vm) }

            Section(R.string.s_switchbot, R.string.switchbot_text) {
                if (state.switchBot) {
                    Setting(
                        title = str(R.string.switchbot_linked),
                        text = plural(R.plurals.remotes_found, state.switchBotRemotes.size),
                    )
                    for (remote in state.switchBotRemotes) {
                        val hidden = remote.id in state.hiddenRemotes
                        Setting(
                            title = remote.name,
                            text = remote.type,
                            trailing = {
                                IconButton(onClick = { vm.setRemoteHidden(remote, !hidden) }) {
                                    Icon(
                                        if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = str(if (hidden) R.string.unhide else R.string.hide),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                    Setting(title = str(R.string.switchbot_unlink), onClick = { confirmSwitchBot = true })
                } else {
                    Setting(
                        title = str(R.string.switchbot_connect),
                        text = str(R.string.switchbot_connect_text),
                        onClick = { linkingSwitchBot = true },
                    )
                }
            }

            Section(R.string.s_lgtv, R.string.lgtv_text) {
                for (tv in state.lgTvs) {
                    Setting(
                        title = tv.name,
                        text = str(R.string.lgtv_test, tv.host),
                        onClick = { vm.testLgTv(tv) },
                        trailing = {
                            Row {
                                IconButton(onClick = { renamingTv = tv }) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = str(R.string.room_rename),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { removingTv = tv }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = str(R.string.lgtv_remove),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }
                Setting(title = str(R.string.lgtv_add), text = str(R.string.lgtv_add_text), onClick = vm::searchLgTvs)
            }

            Section(R.string.s_calendar, R.string.calendar_settings_text) { CalendarSettings(state, vm) }

            Section(R.string.s_notion, R.string.notion_settings_text) { NotionSettings(state, vm) }

            Section(R.string.s_music, R.string.music_settings_text) { MusicSettings() }

            Group(R.string.settings_group_voice)
            Section(R.string.s_voice, R.string.voice_settings_text) {
                var picking by remember { mutableStateOf(false) }
                if (picking) {
                    EchoDialog(
                        title = str(R.string.voice_echo_pick),
                        state = state,
                        vm = vm,
                        none = str(R.string.voice_echo_off),
                        onPick = { vm.setVoiceEcho(it); picking = false },
                        onDismiss = { picking = false },
                    )
                }
                Setting(
                    title = str(R.string.voice_echo),
                    text = state.voiceEcho?.name ?: str(R.string.voice_echo_off),
                    onClick = { picking = true },
                )
                Text(
                    str(R.string.voice_echo_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Toggle(R.string.read_aloud, R.string.read_aloud_text, state.readAloud, vm::setReadAloud)
            }

            Section(R.string.s_assistant, R.string.assistant_text) { AssistantSettings(state, vm) }

            Section(R.string.s_ai_examples, R.string.ai_examples_text) {
                val configured = state.assistant.configured
                if (!configured) {
                    Text(
                        str(R.string.ai_examples_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                for (example in AI_EXAMPLES) {
                    Setting(title = "“${str(example)}”", onClick = if (configured) ({ vm.ask(str(example)) }) else null)
                }
            }

            Group(R.string.settings_group_home)
            if (state.devices.isNotEmpty()) {
                Section(R.string.s_rooms, R.string.rooms_text) {
                    for (room in rooms) {
                        Setting(
                            title = room,
                            text = plural(
                                R.plurals.devices,
                                placed.count { it.room == room } + extras.count { state.placeOf(it.key) == room },
                            ),
                            onClick = { editingRoom = room },
                        )
                    }
                    Setting(title = str(R.string.room_new), onClick = { editingRoom = "" }, trailing = { AddIcon() })
                    if (state.rooms.isNotEmpty() || state.roomList.isNotEmpty()) {
                        Setting(title = str(R.string.rooms_reset), onClick = { confirmRooms = true })
                    }
                }

                Section(R.string.s_devices, R.string.devices_text) {
                    // Listed in their rooms as shown in the app, but moved as the devices Alexa knows.
                    for (device in state.devices) {
                        val room = placed.first { it.applianceId == device.applianceId }.room
                        DeviceRow(device, room, state, vm, onClick = { moving = device })
                    }
                }
            }

            if (switchable.isNotEmpty()) {
                Section(R.string.s_groups, R.string.groups_text) {
                    for (group in state.groups) {
                        Setting(
                            title = group.name,
                            text = plural(R.plurals.devices, switchable.count { it.applianceId in group.devices }),
                            onClick = { editingGroup = group },
                        )
                    }
                    Setting(
                        title = str(R.string.group_new),
                        onClick = { creatingGroup = true },
                        trailing = { AddIcon() },
                    )
                }

                Section(R.string.s_macros_settings, R.string.macros_text) {
                    for (macro in state.macros) {
                        Setting(
                            title = macro.name,
                            text = macroStatus(macro, state),
                            onClick = { editingMacro = macro },
                        )
                    }
                    Setting(
                        title = str(R.string.macro_new),
                        onClick = { creatingMacro = true },
                        trailing = { AddIcon() },
                    )
                }

                Section(R.string.s_shortcuts, R.string.shortcuts_text) {
                    val chosen = state.shortcuts?.mapNotNull(::nameOf)
                    Setting(
                        title = str(R.string.shortcuts_choose),
                        text = when {
                            chosen == null -> str(R.string.shortcuts_default)
                            chosen.isEmpty() -> str(R.string.shortcuts_none)
                            else -> chosen.joinToString(", ")
                        },
                        onClick = { choosingShortcuts = true },
                    )
                }
            }

            if (state.devices.isNotEmpty()) {
                Section(R.string.s_widget, R.string.widget_text) {
                    val widget = state.widget
                    // What the widget shows now, in its order: the choice made, or the favorites.
                    val shown = (widget.items ?: favoriteKeys).filter { nameOf(it) != null }
                    Setting(
                        title = str(R.string.widget_items),
                        text = when {
                            widget.items == null -> str(R.string.widget_items_default)
                            shown.isEmpty() -> str(R.string.widget_items_none)
                            else -> shown.mapNotNull(::nameOf).joinToString(", ")
                        },
                        onClick = { choosingWidget = true },
                    )
                    if (shown.size > 1) {
                        Text(
                            str(R.string.widget_order),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        )
                        shown.forEachIndexed { index, key ->
                            OrderRow(nameOf(key).orEmpty(), first = index == 0, last = index == shown.lastIndex) { by ->
                                val moved = shown.toMutableList()
                                moved.add(index + by, moved.removeAt(index))
                                vm.setWidget(widget.copy(items = moved))
                            }
                        }
                    }
                    Chips(R.string.widget_columns, (1..4).map { it to it.toString() }, widget.columns) {
                        vm.setWidget(widget.copy(columns = it))
                    }
                    val sizes = listOf(R.string.widget_compact, R.string.widget_normal, R.string.widget_large)
                    Chips(R.string.widget_size, sizes.mapIndexed { i, label -> i to str(label) }, widget.size) {
                        vm.setWidget(widget.copy(size = it))
                    }
                    Toggle(R.string.widget_status, R.string.widget_status_text, widget.status) {
                        vm.setWidget(widget.copy(status = it))
                    }
                    Toggle(R.string.widget_header, R.string.widget_header_text, widget.header) {
                        vm.setWidget(widget.copy(header = it))
                    }
                    Toggle(R.string.widget_transparent, R.string.widget_transparent_text, widget.transparent) {
                        vm.setWidget(widget.copy(transparent = it))
                    }
                }
            }

            if (state.devices.isNotEmpty()) {
                Section(R.string.s_monitor, R.string.monitor_text) { MonitorSettings(state, vm) }
            }

            Section(R.string.s_tiles, R.string.tiles_text) {
                state.tileDevices.forEachIndexed { slot, id ->
                    val name = id?.let(::nameOf)
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
            }

            if (timers.isNotEmpty()) {
                Section(R.string.s_timers) {
                    for ((id, at) in timers) {
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
            }

            if (alerts.isNotEmpty()) {
                Section(R.string.s_alerts) {
                    for ((id, alert) in alerts) {
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
            }

            Group(R.string.settings_group_system)
            Section(R.string.s_updates) {
                Toggle(R.string.auto_update, R.string.auto_update_text, state.autoUpdate, vm::setAutoUpdate)
                Setting(
                    title = str(R.string.check_updates),
                    text = str(R.string.installed_version, vm.installedVersion),
                    onClick = { vm.checkForUpdate(manual = true) },
                )
            }

            Section(R.string.s_backup, R.string.backup_text) {
                Setting(title = str(R.string.backup_export), onClick = { export.launch(BACKUP_FILE) })
                Setting(title = str(R.string.backup_import), onClick = { import.launch(BACKUP_TYPES) })
            }

            Section(R.string.s_data) {
                Setting(title = str(R.string.history_clear), onClick = { confirmClear = true })
            }

            Section(R.string.s_account) {
                Setting(
                    title = str(R.string.sign_out),
                    text = str(R.string.account_marketplace, state.marketplace.domain),
                    onClick = { confirmSignOut = true },
                )
            }

            Section(R.string.s_hints, R.string.hints_text) {
                for (hint in TileHint.entries) {
                    ListItem(
                        headlineContent = { Text(str(hint.text)) },
                        leadingContent = { Icon(hint.icon, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            Section(R.string.s_about) {
                Setting(
                    title = str(R.string.about_source),
                    text = str(R.string.installed_version, vm.installedVersion),
                    onClick = { context.open(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) },
                )
            }
            if (searching && found == 0) {
                Text(
                    str(R.string.settings_search_nothing, query.trim()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
            }
        }
    }
}

/** The search at the head of the settings, and of the music, with a button to empty it. */
@Composable
internal fun SettingsSearchField(query: String, hint: String = str(R.string.settings_search_hint), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = str(R.string.search_close))
                }
            }
        },
        singleLine = true,
        shape = corners(28.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The other words a section is looked for by, beyond its title and its text. */
private fun sectionKeys(@StringRes title: Int): Int? = when (title) {
    R.string.s_appearance -> R.string.settings_keys_appearance
    R.string.s_notifications -> R.string.settings_keys_notifications
    R.string.s_team -> R.string.settings_keys_team
    R.string.s_weather -> R.string.settings_keys_weather
    R.string.s_server -> R.string.settings_keys_server
    R.string.s_switchbot -> R.string.settings_keys_switchbot
    R.string.s_lgtv -> R.string.settings_keys_lgtv
    R.string.s_calendar -> R.string.settings_keys_calendar
    R.string.s_notion -> R.string.settings_keys_notion
    R.string.s_music -> R.string.settings_keys_music
    R.string.s_voice -> R.string.settings_keys_voice
    R.string.s_assistant -> R.string.settings_keys_assistant
    R.string.s_ai_examples -> R.string.settings_keys_ai_examples
    R.string.s_updates -> R.string.settings_keys_updates
    R.string.s_rooms -> R.string.settings_keys_rooms
    R.string.s_devices -> R.string.settings_keys_devices
    R.string.s_groups -> R.string.settings_keys_groups
    R.string.s_macros_settings -> R.string.settings_keys_macros
    R.string.s_shortcuts -> R.string.settings_keys_shortcuts
    R.string.s_widget -> R.string.settings_keys_widget
    R.string.s_monitor -> R.string.settings_keys_monitor
    R.string.s_tiles -> R.string.settings_keys_tiles
    R.string.s_timers -> R.string.settings_keys_timers
    R.string.s_alerts -> R.string.settings_keys_alerts
    R.string.s_backup -> R.string.settings_keys_backup
    R.string.s_data -> R.string.settings_keys_data
    R.string.s_account -> R.string.settings_keys_account
    R.string.s_hints -> R.string.settings_keys_hints
    R.string.s_about -> R.string.settings_keys_about
    else -> null
}

/** A section of the settings that opens and closes on a tap of its title. */
@Composable
private fun Accordion(
    @StringRes title: Int,
    @StringRes text: Int?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val skeuo = LocalSkeuo.current
    val metro = LocalMetro.current
    Surface(
        color = if (skeuo) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = if (metro) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(if (skeuo) 12.dp else 20.dp),
        modifier = Modifier.skeuoPanel(corners(12.dp)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sectionIcon(title)?.let { icon ->
                    Box(
                        if (skeuo) Modifier.size(40.dp).skeuoBadge()
                        else if (metro) Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary)
                        else Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (skeuo || metro) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                }
                Text(str(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(turn))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    if (text != null) {
                        Text(
                            str(text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        )
                    }
                    content()
                }
            }
        }
    }
}

/** The icon at the head of each section, so that a long list of them is quick to scan. */
private fun sectionIcon(@StringRes title: Int): ImageVector? = when (title) {
    R.string.s_appearance -> Icons.Outlined.Palette
    R.string.s_notifications -> Icons.Outlined.Notifications
    R.string.s_team -> Icons.Outlined.SportsSoccer
    R.string.s_weather -> Icons.Outlined.WbSunny
    R.string.s_server -> Icons.Outlined.Dns
    R.string.s_switchbot -> Icons.Outlined.SettingsRemote
    R.string.s_lgtv -> Icons.Outlined.Tv
    R.string.s_assistant -> Icons.Outlined.AutoAwesome
    R.string.s_ai_examples -> Icons.Outlined.Lightbulb
    R.string.s_voice -> Icons.Outlined.RecordVoiceOver
    R.string.s_calendar -> Icons.Outlined.CalendarMonth
    R.string.s_notion -> Icons.Outlined.Description
    R.string.s_music -> Icons.Outlined.LibraryMusic
    R.string.s_updates -> Icons.Outlined.SystemUpdate
    R.string.s_rooms -> Icons.Outlined.MeetingRoom
    R.string.s_devices -> Icons.Outlined.DevicesOther
    R.string.s_groups -> Icons.Outlined.Layers
    R.string.s_macros_settings -> Icons.Outlined.Extension
    R.string.s_shortcuts -> Icons.Outlined.Shortcut
    R.string.s_widget -> Icons.Outlined.Widgets
    R.string.s_monitor -> Icons.Outlined.TabletAndroid
    R.string.s_tiles -> Icons.Outlined.ToggleOn
    R.string.s_timers -> Icons.Outlined.Timer
    R.string.s_alerts -> Icons.Outlined.WarningAmber
    R.string.s_backup -> Icons.Outlined.Backup
    R.string.s_data -> Icons.Outlined.Storage
    R.string.s_account -> Icons.Outlined.AccountCircle
    R.string.s_hints -> Icons.Outlined.TipsAndUpdates
    R.string.s_about -> Icons.Outlined.Info
    else -> null
}

/** An entry of an ordered list, with the arrows that move it one place up or down. */
@Composable
internal fun OrderRow(
    name: String,
    first: Boolean,
    last: Boolean,
    text: String? = null,
    onClick: (() -> Unit)? = null,
    onMove: (Int) -> Unit,
) {
    val haptic = LocalHaptics.current
    val move = { by: Int ->
        haptic(Haptic.Tick)
        onMove(by)
    }
    Setting(
        title = name,
        text = text,
        onClick = onClick,
        trailing = {
            Row {
                IconButton(onClick = { move(-1) }, enabled = !first) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = str(R.string.move_up))
                }
                IconButton(onClick = { move(1) }, enabled = !last) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = str(R.string.move_down))
                }
            }
        },
    )
}

/** A setting with a handful of values, all in sight. */
@Composable
internal fun Chips(
    @StringRes title: Int,
    choices: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val haptic = LocalHaptics.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(str(title), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, label) in choices) {
                AppFilterChip(
                    selected = value == selected,
                    onClick = {
                        haptic(Haptic.Tick)
                        onSelect(value)
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}

/**
 * The color of the app: a swatch for each accent, and first the one that follows the wallpaper of
 * the phone. The chosen swatch is ringed and ticked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: String, onSelect: (String) -> Unit) {
    val haptic = LocalHaptics.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(str(R.string.accent), style = MaterialTheme.typography.bodyLarge)
        if (hasWallpaperColors) {
            Text(
                str(R.string.accent_text),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The automatic swatch shows the color the wallpaper gives, whatever the app has now.
            val context = LocalContext.current
            val wallpaper = remember {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context).primary else null
            }
            val choices = buildList {
                // Without the colors of the wallpaper, the automatic accent would be the blue again.
                if (hasWallpaperColors) add(Triple(ACCENT_AUTO, R.string.accent_auto, wallpaper))
                ACCENTS.forEach { add(Triple(it.key, it.label, it.seed)) }
            }
            val shown = selected.takeIf { key -> choices.any { it.first == key } } ?: choices.first().first
            for ((key, label, seed) in choices) {
                Swatch(seed, key == shown, str(label), auto = key == ACCENT_AUTO) {
                    haptic(Haptic.Tick)
                    onSelect(key)
                }
            }
        }
    }
}

@Composable
private fun Swatch(seed: Color?, selected: Boolean, label: String, auto: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
    val tick by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tick",
    )
    // The accents of Windows Phone were squares.
    val shape = if (LocalMetro.current) androidx.compose.ui.graphics.RectangleShape else CircleShape
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .border(ring, colors.onSurface, shape)
            .padding(if (selected) 5.dp else 0.dp)
            .clip(shape)
            .background(seed ?: colors.primaryContainer)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (auto) {
            Icon(
                Icons.Outlined.Wallpaper,
                contentDescription = null,
                tint = if ((seed ?: colors.primaryContainer).luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(22.dp).alpha(1f - tick),
            )
        }
        Icon(
            Icons.Outlined.Check,
            contentDescription = null,
            tint = when {
                seed == null -> colors.onPrimaryContainer
                seed.luminance() > 0.5f -> Color.Black
                else -> Color.White
            },
            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = tick; scaleY = tick; alpha = tick },
        )
    }
}

/** The strength of the vibrations: each step is felt while dragging, at the strength it stands for. */
@Composable
private fun HapticLevel(level: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val vibrations = remember(context) { Vibrations(context) }
    var value by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(str(R.string.haptic_level, value.roundToInt()), style = MaterialTheme.typography.bodyLarge)
        AppSlider(
            value = value,
            onValueChange = {
                if (it.roundToInt() != value.roundToInt()) vibrations.play(Haptic.Tick, it.roundToInt())
                value = it
            },
            onValueChangeFinished = {
                val chosen = value.roundToInt()
                vibrations.play(Haptic.On, chosen)
                onChange(chosen)
            },
            valueRange = HAPTIC_LEVEL_MIN.toFloat()..100f,
            steps = (100 - HAPTIC_LEVEL_MIN) / 10 - 1,
        )
    }
}

/** What can be asked of the assistant, to show it and to try it. */
private val AI_EXAMPLES = listOf(
    R.string.ai_example_1, R.string.ai_example_2, R.string.ai_example_3, R.string.ai_example_4, R.string.ai_example_5,
    R.string.ai_example_6, R.string.ai_example_7, R.string.ai_example_8, R.string.ai_example_9, R.string.ai_example_10,
)

@Composable
internal fun Toggle(@StringRes title: Int, @StringRes text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = LocalHaptics.current
    val change = { on: Boolean ->
        haptic(if (on) Haptic.On else Haptic.Off)
        onChange(on)
    }
    Setting(
        title = str(title),
        text = str(text),
        onClick = { change(!checked) },
        trailing = { AppSwitch(checked = checked, onCheckedChange = change) },
    )
}

@Composable
private fun AddIcon() {
    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Setting(
    title: String,
    text: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = text?.let { { Text(it) } },
        trailingContent = trailing,
        // Transparent, to sit on the card of its section.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
