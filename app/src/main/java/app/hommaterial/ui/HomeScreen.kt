package app.hommaterial.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.COLOR_CHOICES
import app.hommaterial.data.ColorChoice
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.Group
import app.hommaterial.data.WHITE_CHOICES
import app.hommaterial.data.statusText
import app.hommaterial.label
import app.hommaterial.plural
import app.hommaterial.quick.Alert
import app.hommaterial.quick.Alerts
import app.hommaterial.quick.clock
import app.hommaterial.quick.degrees
import app.hommaterial.str
import app.hommaterial.voice.VoiceResult
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// By size of the tiles: compact, normal, large.
private val TILE_MIN_WIDTHS = listOf(132.dp, 148.dp, 216.dp)
private val TILE_HEIGHTS = listOf(92.dp, 116.dp, 156.dp)
private const val WIDE_DP = 600
private const val SIDE_BAR_DP = 840
private val TIMER_CHOICES =
    listOf(R.string.timer_15 to 15, R.string.timer_30 to 30, R.string.timer_60 to 60, R.string.timer_120 to 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: UiState, vm: HomeViewModel, onSettings: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    state.voice?.let { VoiceDialog(it, vm) }

    // A wide screen has room for a side bar that splits the home in pages.
    val width = LocalConfiguration.current.screenWidthDp
    val wide = width >= WIDE_DP
    val monitor = state.monitor
    val pages = shownPages(state, wide)
    // Nothing chosen yet opens the first page, which on a monitor is the one put first.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val current = page.takeIf { it in pages } ?: pages.first()
    val clock = monitor.enabled && monitor.clock

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Title(state.updatedAt) },
                actions = {
                    // Without a side bar to sit in, the clock of the monitor goes up here.
                    if (clock && pages.size == 1) {
                        val now by rememberNow()
                        Text(
                            clock(now),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    VoiceButton(vm)
                    OverflowMenu(state, vm, onSettings)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (pages.size > 1) SideBar(pages, current, expanded = width >= SIDE_BAR_DP, clock) { page = it }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                val look = HomeLook(wide, if (monitor.enabled) monitor.tileSize else 1, state.locked)
                CompositionLocalProvider(LocalHomeLook provides look) {
                    Crossfade(current, label = "page") { PageGrid(it, state, vm) }
                }
            }
        }
    }
}

/** How the tiles look and behave, which depends on the screen the app is on and on the monitor mode. */
private class HomeLook(val wide: Boolean = false, val tileSize: Int = 1, val locked: Boolean = false) {
    val tileMinWidth get() = TILE_MIN_WIDTHS[tileSize]
    val tileHeight get() = TILE_HEIGHTS[tileSize]
}

private val LocalHomeLook = compositionLocalOf { HomeLook() }

/** The pages to move between, with their names where the screen has room for them. */
@Composable
private fun SideBar(
    pages: List<String>,
    current: String,
    expanded: Boolean,
    clock: Boolean,
    onSelect: (String) -> Unit,
) {
    val now by rememberNow()
    if (expanded) {
        Column(
            Modifier
                .width(220.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (clock) {
                Column(Modifier.padding(start = 16.dp, bottom = 12.dp)) {
                    Text(clock(now), style = MaterialTheme.typography.displaySmall)
                    Text(
                        dateText(now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for (page in pages) {
                NavigationDrawerItem(
                    label = { Text(pageLabel(page), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(pageIcon(page), contentDescription = null) },
                    selected = page == current,
                    onClick = { onSelect(page) },
                )
            }
        }
    } else {
        NavigationRail(containerColor = Color.Transparent, windowInsets = WindowInsets(0)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (clock) {
                    Text(
                        clock(now),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                for (page in pages) {
                    NavigationRailItem(
                        selected = page == current,
                        onClick = { onSelect(page) },
                        icon = { Icon(pageIcon(page), contentDescription = null) },
                        label = { Text(pageLabel(page), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

/** The tiles of a page: the whole home in sections, or only the favorites, the groups or a room. */
@Composable
private fun PageGrid(page: String, state: UiState, vm: HomeViewModel) {
    val visible = state.placed.filter { state.showHidden || it.applianceId !in state.hidden }
    val room = page.takeIf { it.startsWith(PAGE_ROOM) }?.removePrefix(PAGE_ROOM)?.ifEmpty { null }
    // On a monitor a page may be narrowed to some of its devices, or of its groups.
    val only = state.monitor.takeIf { it.enabled }?.only?.get(page)
    val shown = when {
        page == PAGE_ALL -> visible
        page == PAGE_FAVORITES -> visible.filter { it.applianceId in state.favorites }
        page == PAGE_GROUPS -> emptyList()
        else -> visible.filter { it.room == room }
    }.filter { only == null || it.applianceId in only }
    // Named rooms first, alphabetically; devices without a room close the list.
    val noRoom = str(R.string.no_room)
    val rooms = (if (page == PAGE_FAVORITES) emptyList() else shown).groupBy { it.room ?: noRoom }
        .toSortedMap(compareBy<String> { it == noRoom }.thenBy { it.lowercase() })
    // Hidden devices do not count for a group, as they do not for a room.
    val listed = when (page) {
        PAGE_ALL -> state.groups
        PAGE_GROUPS -> state.groups.filter { only == null || it.id in only }
        else -> emptyList()
    }
    val groups = listed.map { group ->
        group to state.devices.filter {
            it.hasPower && it.applianceId in group.devices && it.applianceId !in state.hidden
        }
    }.filter { it.second.isNotEmpty() }

    if (shown.isEmpty() && groups.isEmpty() && !state.refreshing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(str(R.string.no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(LocalHomeLook.current.tileMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Running timers come first, the one closest to switching off at the top.
        val timers = state.timers.entries.sortedBy { it.value }
            .mapNotNull { (id, at) -> shown.find { it.applianceId == id }?.let { it to at } }
        if (timers.isNotEmpty()) {
            item(key = "timers", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(str(R.string.s_timers))
            }
            items(timers, key = { "timer:${it.first.applianceId}" }, span = { GridItemSpan(maxLineSpan) }) {
                TimerRow(it.first, it.second) { vm.cancelTimer(it.first) }
            }
        }
        // Favorites are repeated at the top and stay in their rooms too.
        val favorites = shown.filter { it.applianceId in state.favorites }.sortedBy { it.name.lowercase() }
        if (favorites.isNotEmpty() && (page == PAGE_ALL || page == PAGE_FAVORITES)) {
            item(key = "favorites", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(str(R.string.favorites))
            }
            items(favorites, key = { "favorite:${it.applianceId}" }) { DeviceTile(it, state, vm) }
        }
        if (groups.isNotEmpty()) {
            item(key = "groups", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(str(R.string.s_groups))
            }
            items(groups, key = { "group:${it.first.id}" }) { (group, devices) ->
                GroupTile(group, devices, state) { vm.toggleGroup(group) }
            }
        }
        for ((name, devices) in rooms) {
            item(key = "room:$name", span = { GridItemSpan(maxLineSpan) }) {
                // The devices without a room are unrelated: switching them together makes no sense.
                // Hidden devices stay out of it even while they are being shown.
                val switchable = if (name == noRoom) emptyList() else {
                    devices.filter { it.hasPower && it.applianceId !in state.hidden }
                }
                if (switchable.size > 1) {
                    SectionHeader(name) { on -> vm.setRoomPower(switchable, on) }
                } else {
                    SectionHeader(name)
                }
            }
            items(devices, key = { it.applianceId }) { DeviceTile(it, state, vm) }
        }
    }
}

@Composable
private fun SectionHeader(name: String, onPower: ((Boolean) -> Unit)? = null) {
    // The fixed height keeps headers aligned whether or not they carry the power button.
    Row(Modifier.padding(top = 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onPower != null) {
            // A menu rather than a toggle: a whole room should not switch on a stray tap.
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.PowerSettingsNew,
                        contentDescription = str(R.string.room_power, name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(str(R.string.all_on)) },
                        onClick = { open = false; onPower(true) },
                    )
                    DropdownMenuItem(
                        text = { Text(str(R.string.all_off)) },
                        onClick = { open = false; onPower(false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceTile(device: Device, state: UiState, vm: HomeViewModel) {
    DeviceTile(
        device = device,
        state = state.states[device.applianceId],
        busy = device.applianceId in state.busy,
        hidden = device.applianceId in state.hidden,
        favorite = device.applianceId in state.favorites,
        tile = state.tileDevices.indexOf(device.applianceId).takeIf { it >= 0 },
        tilesFull = null !in state.tileDevices,
        timer = state.timers[device.applianceId]?.takeIf { it > System.currentTimeMillis() },
        alert = state.alerts[device.applianceId] ?: Alert(),
        vm = vm,
    )
}

/** A group as one tile: lit while any of its devices is on, and a tap switches them all. */
@Composable
private fun GroupTile(group: Group, devices: List<Device>, state: UiState, onToggle: () -> Unit) {
    val lit = devices.count { state.states[it.applianceId]?.power == true }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (lit > 0) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (lit > 0) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalHomeLook.current.tileHeight)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle),
    ) {
        TileBody(
            icon = Icons.Outlined.Layers,
            busy = devices.any { it.applianceId in state.busy },
            name = group.name,
            status = when (lit) {
                0 -> str(R.string.off)
                devices.size -> str(R.string.on)
                else -> str(R.string.group_some_on, lit, devices.size)
            },
        )
    }
}

/** What every tile shows, sized as the screen it is on asks. */
@Composable
private fun TileBody(icon: ImageVector, busy: Boolean, name: String, status: String) {
    val size = LocalHomeLook.current.tileSize
    val type = MaterialTheme.typography
    Column(Modifier.padding(listOf(12.dp, 14.dp, 18.dp)[size])) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(listOf(22.dp, 26.dp, 36.dp)[size]))
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            name,
            style = if (size == 2) type.titleLarge else type.titleSmall,
            fontWeight = FontWeight.SemiBold,
            // A compact tile has no room for a name on two lines.
            maxLines = if (size == 0) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            status,
            style = if (size == 2) type.bodyLarge else type.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.75f),
        )
    }
}

/** A device with a switch-off timer and the time left, counted down second by second. */
@Composable
private fun TimerRow(device: Device, at: Long, onCancel: () -> Unit) {
    val now by produceState(System.currentTimeMillis(), at) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
        Row(
            Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconFor(device), contentDescription = null, modifier = Modifier.size(26.dp))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    str(R.string.turns_off_at, clock(at)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.75f),
                )
            }
            // Digits of equal width keep the countdown from jittering as it ticks.
            Text(
                countdown(at - now),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            )
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = str(R.string.cancel_timer))
            }
        }
    }
}

/** Time left as 1:05:09 or 05:09; a timer that is late, waiting for the network, stays at zero. */
internal fun countdown(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun Title(updatedAt: Long?) {
    Column {
        Text(str(R.string.app_name))
        if (updatedAt != null) {
            // Recomputed every half minute so that the age keeps growing while the screen stays open.
            val now by produceState(System.currentTimeMillis(), updatedAt) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(30_000)
                }
            }
            Text(
                ageText(updatedAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ageText(updatedAt: Long, now: Long): String {
    val minutes = (now - updatedAt) / 60_000
    return when {
        minutes < 1 -> str(R.string.updated_now)
        minutes < 60 -> plural(R.plurals.updated_minutes, minutes.toInt())
        minutes < 24 * 60 -> plural(R.plurals.updated_hours, (minutes / 60).toInt())
        else -> str(R.string.updated_long_ago)
    }
}

/** Asks the phone's speech recognizer for a sentence and passes on what it heard. */
@Composable
private fun VoiceButton(vm: HomeViewModel) {
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.onSpeech(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty())
        }
    }
    IconButton(
        onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, str(R.string.voice_language))
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, str(R.string.voice_prompt))
            try {
                speech.launch(intent)
            } catch (e: ActivityNotFoundException) {
                vm.speechUnavailable()
            }
        },
    ) {
        Icon(Icons.Outlined.Mic, contentDescription = str(R.string.voice_button))
    }
}

@Composable
private fun VoiceDialog(ask: VoiceResult.Ask, vm: HomeViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissVoice,
        title = { Text(str(R.string.voice_ask_title)) },
        text = {
            Column {
                Text(str(R.string.voice_heard, ask.heard), modifier = Modifier.padding(bottom = 8.dp))
                for (option in ask.options) {
                    val label = if (option.room == null) option.label() else {
                        str(R.string.voice_room_option, option.label(), plural(R.plurals.devices, option.devices.size))
                    }
                    TextButton(onClick = { vm.runVoice(option) }) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = vm::dismissVoice) { Text(str(R.string.cancel)) } },
    )
}

@Composable
private fun OverflowMenu(state: UiState, vm: HomeViewModel, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) SignOutDialog(onConfirm = vm::logout, onDismiss = { confirmSignOut = false })
    // What waits for the PIN of the monitor before it happens.
    var behindPin by remember { mutableStateOf<(() -> Unit)?>(null) }
    behindPin?.let { action ->
        PinDialog(
            title = str(R.string.pin_ask_title),
            onSubmit = { pin -> vm.checkPin(pin).also { if (it) { behindPin = null; action() } } },
            onDismiss = { behindPin = null },
        )
    }
    val guarded = { action: () -> Unit -> if (state.locked) behindPin = action else action() }
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = str(R.string.menu)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(str(R.string.refresh)) }, onClick = { open = false; vm.refresh() })
        if (state.hidden.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(str(if (state.showHidden) R.string.hide_hidden else R.string.show_hidden)) },
                onClick = { open = false; vm.toggleShowHidden() },
            )
        }
        DropdownMenuItem(
            text = { Text(str(R.string.check_updates)) },
            onClick = { open = false; vm.checkForUpdate(manual = true) },
        )
        DropdownMenuItem(text = { Text(str(R.string.settings)) }, onClick = { open = false; guarded(onSettings) })
        DropdownMenuItem(
            text = { Text(str(R.string.sign_out)) },
            onClick = { open = false; guarded { confirmSignOut = true } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceTile(
    device: Device,
    state: DeviceState?,
    busy: Boolean,
    hidden: Boolean,
    favorite: Boolean,
    tile: Int?,
    tilesFull: Boolean,
    timer: Long?,
    alert: Alert,
    vm: HomeViewModel,
) {
    val on = state?.power == true
    val powerKnown = state?.power != null
    val colors = MaterialTheme.colorScheme
    var details by remember { mutableStateOf(false) }

    Surface(
        color = if (on) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (on) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalHomeLook.current.tileHeight)
            .alpha(if (hidden) 0.5f else 1f)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = {
                    when {
                        !device.hasPower -> Unit
                        powerKnown -> vm.setPower(device, !on)
                        // Without a reported state (infrared devices, unreachable ones) a toggle would be a guess.
                        else -> details = true
                    }
                },
                onLongClick = { details = true },
            ),
    ) {
        TileBody(
            icon = iconFor(device),
            busy = busy,
            name = device.name,
            status = statusText(device, state).let { status ->
                if (timer == null) status else str(R.string.status_until, status, clock(timer))
            },
        )
    }

    if (details) {
        DeviceSheet(device, state, hidden, favorite, tile, tilesFull, timer, alert, vm, onDismiss = { details = false })
    }
}

/** Everything beyond the tap-to-toggle: explicit on/off, timer, brightness, color, favorites, tile, hiding. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DeviceSheet(
    device: Device,
    state: DeviceState?,
    hidden: Boolean,
    favorite: Boolean,
    tile: Int?,
    tilesFull: Boolean,
    timer: Long?,
    alert: Alert,
    vm: HomeViewModel,
    onDismiss: () -> Unit,
) {
    val askNotifications = rememberNotificationRequest()
    DetailsContainer(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(device), contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    // Hidden on purpose: holding the name copies the technical data of the device.
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.combinedClickable(
                            interactionSource = null,
                            indication = null,
                            onClick = {},
                            onLongClick = { vm.copyDiagnostics(device) },
                        ),
                    )
                    Text(
                        statusText(device, state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (device.hasPower) {
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { vm.setPower(device, true) }, modifier = Modifier.weight(1f)) {
                        Text(str(R.string.turn_on))
                    }
                    FilledTonalButton(onClick = { vm.setPower(device, false) }, modifier = Modifier.weight(1f)) {
                        Text(str(R.string.turn_off))
                    }
                }
            }

            if (device.hasPower) {
                Text(
                    str(R.string.turn_off_in),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp),
                )
                if (timer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.turns_off_at, clock(timer)), modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelTimer(device) }) { Text(str(R.string.cancel_timer)) }
                    }
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((label, minutes) in TIMER_CHOICES) {
                            AssistChip(
                                onClick = { askNotifications(); vm.setTimer(device, minutes) },
                                label = { Text(str(label)) },
                            )
                        }
                    }
                }
            }

            if (device.isSensor) {
                HistoryCharts(remember(device, state) { vm.readings(device) })
                AlertSettings(alert, state?.temperature) { vm.setAlert(device, it) }
            }

            if (device.hasBrightness) {
                Text(
                    str(R.string.brightness),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp),
                )
                BrightnessSlider(state?.brightness ?: 100) { vm.setBrightness(device, it) }
            }

            val choices = (if (device.hasColorTemperature) WHITE_CHOICES else emptyList()) +
                (if (device.hasColor) COLOR_CHOICES else emptyList())
            if (choices.isNotEmpty()) {
                Text(
                    str(R.string.color),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (choice in choices) {
                        ColorSwatch(choice, selected = state?.colorName == choice.alexaName) {
                            vm.setColor(device, choice)
                        }
                    }
                }
            }

            // Behind the PIN of the monitor the panel only controls: how the home is arranged stays put.
            if (LocalHomeLook.current.locked) return@Column
            TextButton(
                onClick = { vm.setFavorite(device, !favorite) },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(str(if (favorite) R.string.favorite_remove else R.string.favorite_add))
            }
            if (device.hasPower) {
                TextButton(onClick = { vm.setOnTile(device, tile == null) }, enabled = tile != null || !tilesFull) {
                    Text(
                        when {
                            tile != null -> str(R.string.tile_remove, tile + 1)
                            tilesFull -> str(R.string.tiles_full)
                            else -> str(R.string.tile_add)
                        },
                    )
                }
            }
            TextButton(onClick = { vm.setHidden(device, !hidden); onDismiss() }) {
                Text(str(if (hidden) R.string.unhide else R.string.hide))
            }
        }
    }
}

/** A sheet from the bottom on a phone, a panel on the side where the screen is wide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsContainer(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (!LocalHomeLook.current.wide) {
        ModalBottomSheet(onDismissRequest = onDismiss) { content() }
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.CenterEnd,
        ) {
            AnimatedVisibility(shown, enter = slideInHorizontally { it } + fadeIn()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    // Taps on the panel must not reach the area around it, which closes it.
                    modifier = Modifier
                        .width(420.dp)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null, onClick = {}),
                ) {
                    Box(Modifier.padding(top = 24.dp)) { content() }
                }
            }
        }
    }
}

/**
 * Returns the action that asks for the permission to notify, where Android wants it asked. Timers
 * and alerts work without it, but then they cannot tell when something needs attention.
 */
@Composable
private fun rememberNotificationRequest(onAnswer: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAnswer() }
    return {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(permission)
        }
    }
}

/** The temperatures above and below which a sensor notifies. */
@Composable
private fun AlertSettings(alert: Alert, temperature: Double?, onChange: (Alert) -> Unit) {
    val context = LocalContext.current
    // Bumped when the permission is answered, to read again whether notifications are allowed.
    var answers by remember { mutableIntStateOf(0) }
    val askNotifications = rememberNotificationRequest { answers++ }
    val current = temperature?.takeIf { !it.isNaN() }?.roundToInt()?.toDouble() ?: 22.0

    Text(
        str(R.string.alerts_title),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 20.dp),
    )
    AlertRow(R.string.alert_above, alert.above, default = current + 2) {
        if (it != null) askNotifications()
        onChange(alert.copy(above = it))
    }
    AlertRow(R.string.alert_below, alert.below, default = current - 2) {
        if (it != null) askNotifications()
        onChange(alert.copy(below = it))
    }
    if (alert != Alert()) {
        val enabled = remember(answers) { Alerts.enabled(context) }
        Text(
            str(if (enabled) R.string.alerts_note else R.string.alerts_disabled),
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AlertRow(@StringRes label: Int, threshold: Double?, default: Double, onChange: (Double?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = threshold != null, onCheckedChange = { onChange(if (it) default else null) })
        Text(str(label, degrees(threshold ?: default)), modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (threshold != null) {
            IconButton(onClick = { onChange(threshold - 0.5) }) {
                Icon(Icons.Outlined.Remove, contentDescription = str(R.string.alert_lower))
            }
            IconButton(onClick = { onChange(threshold + 0.5) }) {
                Icon(Icons.Outlined.Add, contentDescription = str(R.string.alert_raise))
            }
        }
    }
}

@Composable
private fun ColorSwatch(choice: ColorChoice, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = str(choice.label)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            // The ring marks the color the lamp is on; the thin outline keeps pale whites visible.
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, CircleShape)
            .padding(if (selected) 6.dp else 0.dp)
            .background(Color(choice.rgb), CircleShape)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun BrightnessSlider(brightness: Int, onChange: (Int) -> Unit) {
    // Local while dragging; the command goes out once, when the finger lifts.
    var value by remember(brightness) { mutableFloatStateOf(brightness.toFloat()) }
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChange(value.roundToInt().coerceIn(1, 100)) },
        valueRange = 1f..100f,
    )
}

private fun iconFor(device: Device): ImageVector = when (device.category) {
    "LIGHT" -> Icons.Outlined.Lightbulb
    "SMARTPLUG" -> Icons.Outlined.Power
    "SWITCH" -> Icons.Outlined.ToggleOn
    "TV" -> Icons.Outlined.Tv
    "VACUUM_CLEANER" -> Icons.Outlined.CleaningServices
    "TEMPERATURE_SENSOR", "THERMOSTAT" -> Icons.Outlined.Thermostat
    else -> Icons.Outlined.DevicesOther
}
