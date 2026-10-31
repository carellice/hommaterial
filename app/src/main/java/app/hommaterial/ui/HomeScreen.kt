package app.hommaterial.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.LAYOUT_LIST
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.ACTION_POWER
import app.hommaterial.data.COLOR_CHOICES
import app.hommaterial.data.ColorChoice
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.Group
import app.hommaterial.data.Macro
import app.hommaterial.data.MacroAction
import app.hommaterial.data.Remote
import app.hommaterial.data.Schedule
import app.hommaterial.data.WHITE_CHOICES
import app.hommaterial.data.statusText
import app.hommaterial.label
import app.hommaterial.plural
import app.hommaterial.quick.Alert
import app.hommaterial.quick.Alerts
import app.hommaterial.quick.NFC_DEVICE
import app.hommaterial.quick.NFC_GROUP
import app.hommaterial.quick.Quick
import app.hommaterial.quick.clock
import app.hommaterial.quick.degrees
import app.hommaterial.str
import app.hommaterial.voice.VoiceResult
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// By size of the tiles: compact, normal, large.
private val LIST_MIN_WIDTH = 320.dp
private val ROW_HEIGHTS = listOf(56.dp, 64.dp, 84.dp)
private val TILE_MIN_WIDTHS = listOf(132.dp, 148.dp, 216.dp)
private val TILE_HEIGHTS = listOf(92.dp, 116.dp, 156.dp)
private const val WIDE_DP = 600
private const val SIDE_BAR_DP = 840
internal val TIMER_CHOICES =
    listOf(R.string.timer_15 to 15, R.string.timer_30 to 30, R.string.timer_60 to 60, R.string.timer_120 to 120)

// A timer that switches on is a scheduled action, since the timers of the devices only switch off.
private fun switchOn(device: Device) = MacroAction(ACTION_POWER, device.applianceId, "on")

private fun switchingOn(device: Device, state: UiState): List<Schedule> = state.schedules.filter {
    it.days.isEmpty() && it.enabled && it.action.type == ACTION_POWER &&
        it.action.target == device.applianceId && it.action.value == "on"
}

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
    var arranging by rememberSaveable { mutableStateOf(false) }
    if (arranging) ArrangeDialog(state, vm, onDismiss = { arranging = false })
    // The search of the top bar: null while it is closed.
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = query != null) { query = null }
    // The tile asked to open its panel, by its id, until it does.
    val opening = remember { mutableStateOf<String?>(null) }
    val found = query?.let { search(it, homeSections(PAGE_ALL, state)) }.orEmpty()

    /** What a touch on the tile of [entry] does: the search runs it for the one it found first. */
    fun activate(entry: Entry) {
        val keepOpen = when (entry) {
            is Entry.Dev -> {
                val power = state.states[entry.device.applianceId]?.power?.takeIf { entry.device.hasPower }
                if (power != null) vm.setPower(entry.device, !power) else opening.value = entry.id
                power != null
            }
            is Entry.Grp -> vm.toggleGroup(entry.group).let { true }
            is Entry.Mac -> vm.runMacro(entry.macro).let { true }
            is Entry.Rem -> {
                opening.value = entry.id
                false
            }
        }
        // What switched or ran is done with; a panel opens over the results, which must stay.
        if (keepOpen) query = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val typed = query
                    if (typed == null) {
                        Title(state.updatedAt)
                    } else {
                        SearchField(typed, onChange = { query = it }) { found.firstOrNull()?.let(::activate) }
                    }
                },
                actions = {
                    if (query != null) {
                        IconButton(onClick = { query = null }) {
                            Icon(Icons.Outlined.Close, contentDescription = str(R.string.search_close))
                        }
                        return@TopAppBar
                    }
                    // Without a side bar to sit in, the clock of the monitor goes up here.
                    if (clock && pages.size == 1) {
                        val now by rememberNow()
                        Text(
                            clock(now),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Search, contentDescription = str(R.string.search))
                    }
                    VoiceButton(vm, correcting = state.correcting)
                    OverflowMenu(state, vm, onSettings, onArrange = { arranging = true })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (pages.size > 1 && query == null) {
                SideBar(pages, current, expanded = width >= SIDE_BAR_DP, clock) { page = it }
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                val look = HomeLook(
                    wide = wide,
                    tileSize = if (monitor.enabled) monitor.tileSize else 1,
                    locked = state.locked,
                    list = state.layout == LAYOUT_LIST,
                )
                CompositionLocalProvider(LocalHomeLook provides look, LocalOpening provides opening) {
                    val typed = query
                    if (typed != null) {
                        SearchResults(typed, found, state, vm)
                    } else {
                        Crossfade(current, label = "page") { PageGrid(it, state, vm) }
                    }
                }
            }
        }
    }
}

/** The search of the top bar: typing finds, the Enter key acts on what was found first. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onEnter: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(str(R.string.search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onEnter() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/**
 * What [query] matches among the tiles of the [sections], best first: names that start with it,
 * then names with a word that does, then names that only contain it. Each tile comes once.
 */
private fun search(query: String, sections: List<Section>): List<Entry> {
    val wanted = query.trim().lowercase()
    if (wanted.isEmpty()) return emptyList()
    fun rank(name: String): Int? {
        val lower = name.lowercase()
        return when {
            lower == wanted -> 0
            lower.startsWith(wanted) -> 1
            lower.split(' ').any { it.startsWith(wanted) } -> 2
            wanted in lower -> 3
            else -> null
        }
    }
    return sections.flatMap { it.entries }.distinctBy { it.id }
        .mapNotNull { entry -> rank(entry.name)?.let { entry to it } }
        .sortedWith(compareBy({ it.second }, { it.first.name.length }))
        .map { it.first }
}

/** What the search found, the first one marked: it is the one the Enter key acts on. */
@Composable
private fun SearchResults(query: String, found: List<Entry>, state: UiState, vm: HomeViewModel) {
    if (query.isBlank()) return
    if (found.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(str(R.string.search_nothing, query), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val first = found.first()
    val list = LocalHomeLook.current.list
    LazyVerticalGrid(
        columns = GridCells.Adaptive(LocalHomeLook.current.tileMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "enter", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(str(R.string.search_enter, enterText(first, state), first.name))
        }
        items(found, key = { "found|${it.id}" },
            span = { GridItemSpan((if (list) 1 else state.tileSpans[it.id] ?: 1).coerceIn(1, maxLineSpan)) }) { entry ->
            val marked = entry === first
            Box(
                if (marked) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(23.dp)).padding(3.dp)
                } else {
                    Modifier
                },
            ) {
                entry.Tile(state, vm)
            }
        }
    }
}

/** What the Enter key will do with [entry], as a verb. */
private fun enterText(entry: Entry, state: UiState): String = when (entry) {
    is Entry.Dev -> {
        val power = state.states[entry.device.applianceId]?.power
        when {
            !entry.device.hasPower || power == null -> str(R.string.search_open)
            power -> str(R.string.turn_off)
            else -> str(R.string.turn_on)
        }
    }
    is Entry.Grp -> str(R.string.search_switch)
    is Entry.Mac -> str(R.string.search_run)
    is Entry.Rem -> str(R.string.search_open)
}

/** How the tiles look and behave, which depends on the screen the app is on and on the monitor mode. */
internal class HomeLook(
    val wide: Boolean = false,
    val tileSize: Int = 1,
    val locked: Boolean = false,
    /** Whether the tiles are rows of a list rather than squares of a grid. */
    val list: Boolean = false,
) {
    // A list fills the width, or takes columns of a comfortable width on a wide screen.
    val tileMinWidth get() = if (list) LIST_MIN_WIDTH else TILE_MIN_WIDTHS[tileSize]
    val tileHeight get() = (if (list) ROW_HEIGHTS else TILE_HEIGHTS)[tileSize]
    val gap get() = if (list) 8.dp else 12.dp
}

/** The tile asked to open its panel, by its id: the search opens what cannot be switched. */
internal val LocalOpening = compositionLocalOf { mutableStateOf<String?>(null) }

internal val LocalHomeLook = compositionLocalOf { HomeLook() }

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

/** A section of the home: its tiles in the order the user gave them, and the room switch if it has one. */
private class Section(val key: String, val title: String, val entries: List<Entry>, val switchable: List<Device>)

/**
 * The sections of a [page], each with its tiles. The order of each section is the one the user
 * gave it; what was never moved keeps its usual place, after what was.
 */
private fun homeSections(page: String, state: UiState): List<Section> {
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
    // Hidden devices do not count for a group, as they do not for a room.
    val groups = when (page) {
        PAGE_ALL, PAGE_FAVORITES -> state.groups
        PAGE_GROUPS -> state.groups.filter { only == null || it.id in only }
        else -> emptyList()
    }.map { group ->
        val members = state.devices.filter { it.applianceId in group.devices && it.applianceId !in state.hidden }
        Entry.Grp(group, members.filter { it.hasPower })
    }.filter { it.devices.isNotEmpty() }

    // Remotes and custom devices put in a room show there; the others keep their own sections.
    val inList = if (state.showHidden) state.remotes else state.shownRemotes
    val roomRemotes = inList.groupBy { state.placeOf(Quick.remoteKey(it.id)) }
    val roomMacros = state.macros.groupBy { state.placeOf(Quick.macroKey(it.id)) }
    val remotes = when (page) {
        PAGE_ALL -> roomRemotes[null].orEmpty()
        PAGE_REMOTES -> inList.filter { only == null || it.id in only }
        else -> emptyList()
    }
    val macros = when (page) {
        PAGE_ALL -> roomMacros[null].orEmpty()
        PAGE_MACROS -> state.macros.filter { only == null || it.id in only }
        else -> emptyList()
    }
    // A room may hold nothing but them.
    val extraRooms = (roomRemotes.keys + roomMacros.keys).filterNotNull().filter { page == PAGE_ALL || it == room }
    val names = (rooms.keys + extraRooms).toSortedSet(compareBy<String> { it == noRoom }.thenBy { it.lowercase() })
    // On a monitor a room narrowed to some of its things leaves the others out, whatever they are.
    val narrowed = only != null && page != PAGE_ALL

    // Favorites are repeated at the top and stay in their rooms too; remotes and custom devices as the devices.
    val starred = page == PAGE_ALL || page == PAGE_FAVORITES
    val favorites = if (!starred) emptyList() else {
        shown.filter { it.applianceId in state.favorites }.sortedBy { it.name.lowercase() }.map(Entry::Dev) +
            state.macros.filter { Quick.macroKey(it.id) in state.favorites }.map(Entry::Mac) +
            inList.filter { Quick.remoteKey(it.id) in state.favorites }.map(Entry::Rem) +
            groups.filter { it.id in state.favorites && (only == null || it.group.id in only) }
    }

    fun section(key: String, title: String, entries: List<Entry>, switchable: List<Device> = emptyList()): Section {
        val saved = state.order[key].orEmpty().withIndex().associate { it.value to it.index }
        return Section(key, title, entries.sortedBy { saved[it.id] ?: Int.MAX_VALUE }, switchable)
    }
    return buildList {
        add(section("favorites", str(R.string.favorites), favorites))
        if (page != PAGE_FAVORITES) add(section("groups", str(R.string.s_groups), groups))
        add(section("macros", str(R.string.s_macros), macros.map(Entry::Mac)))
        add(section("remotes", str(R.string.s_remotes), remotes.map(Entry::Rem)))
        for (name in names) {
            val devices = rooms[name].orEmpty()
            // The devices without a room are unrelated: switching them together makes no sense.
            // Hidden devices stay out of it even while they are being shown.
            val switchable = if (name == noRoom) emptyList() else {
                devices.filter { it.hasPower && it.applianceId !in state.hidden }
            }
            val entries = devices.map(Entry::Dev) +
                roomMacros[name].orEmpty().filter { !narrowed || it.id in only.orEmpty() }.map(Entry::Mac) +
                roomRemotes[name].orEmpty().filter { !narrowed || it.id in only.orEmpty() }.map(Entry::Rem)
            add(section("room:$name", name, entries, switchable.takeIf { it.size > 1 }.orEmpty()))
        }
    }.filter { it.entries.isNotEmpty() }
}

/** The tiles of a page: the whole home in sections, or only the favorites, the groups or a room. */
@Composable
private fun PageGrid(page: String, state: UiState, vm: HomeViewModel) {
    val sections = homeSections(page, state)
    val list = LocalHomeLook.current.list
    val shown = sections.flatMap { it.entries }.filterIsInstance<Entry.Dev>().map { it.device }
    // Running timers come first, the one closest to switching off at the top.
    val timers = state.timers.entries.sortedBy { it.value }
        .mapNotNull { (id, at) -> shown.find { it.applianceId == id }?.let { it to at } }
    // With them, what was set to happen once: the timers of remotes and custom devices among it.
    val due = if (page == PAGE_ALL) state.schedules.filter { it.days.isEmpty() && it.enabled } else emptyList()
    val repeating = if (page == PAGE_ALL) state.schedules.filter { it.days.isNotEmpty() && it.enabled } else emptyList()
    val automations = if (page == PAGE_ALL) state.automations.filter { it.enabled } else emptyList()

    val running = timers.isNotEmpty() || due.isNotEmpty() || repeating.isNotEmpty() || automations.isNotEmpty()
    if (sections.isEmpty() && !running && !state.refreshing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(str(R.string.no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(LocalHomeLook.current.tileMinWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(LocalHomeLook.current.gap),
    ) {
        if (timers.isNotEmpty() || due.isNotEmpty()) {
            item(key = "timers", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(str(R.string.s_timers))
            }
            items(timers, key = { "timer:${it.first.applianceId}" }, span = { GridItemSpan(maxLineSpan) }) {
                val text = str(R.string.turns_off_at, clock(it.second))
                TimerRow(iconFor(it.first), it.first.name, text, it.second) { vm.cancelTimer(it.first) }
            }
            items(due, key = { "schedule:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) {
                val text = str(R.string.schedule_due, clock(it.at))
                TimerRow(Icons.Outlined.Schedule, it.label, text, it.at) { vm.deleteSchedule(it.id) }
            }
        }
        // Then what repeats and what waits for a device, as running as a timer: paused from here,
        // they stay in their lists of the menu, to be switched on again.
        if (repeating.isNotEmpty()) {
            item(key = "repeating", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(str(R.string.s_schedules))
            }
            items(repeating, key = { "repeating:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) {
                TimerRow(
                    Icons.Outlined.EventRepeat,
                    it.label,
                    whenText(it),
                    it.at,
                    Icons.Outlined.PauseCircle,
                    R.string.pause,
                ) { vm.setScheduleEnabled(it, false) }
            }
        }
        if (automations.isNotEmpty()) {
            item(key = "automations", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(str(R.string.s_automations))
            }
            items(automations, key = { "automation:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) {
                TimerRow(
                    Icons.Outlined.Bolt,
                    it.title,
                    it.label,
                    null,
                    Icons.Outlined.PauseCircle,
                    R.string.pause,
                ) { vm.setAutomationEnabled(it, false) }
            }
        }
        for (section in sections) {
            item(key = section.key, span = { GridItemSpan(maxLineSpan) }) {
                if (section.switchable.isNotEmpty()) {
                    SectionHeader(section.title) { on -> vm.setRoomPower(section.switchable, on) }
                } else {
                    SectionHeader(section.title)
                }
            }
            items(section.entries, key = { "${section.key}|${it.id}" },
                span = { GridItemSpan((if (list) 1 else state.tileSpans[it.id] ?: 1).coerceIn(1, maxLineSpan)) }) { entry ->
                // Out of their rooms, the favorites say which room they are in.
                val room = if (section.key == "favorites") entry.room(state) else null
                CompositionLocalProvider(LocalTileRoom provides room) { entry.Tile(state, vm) }
            }
        }
        if (sections.isNotEmpty()) item(key = "legend", span = { GridItemSpan(maxLineSpan) }) { Legend() }
    }
}

/** Something that has a tile on the home: a device, a custom device, a remote or a group. */
private sealed interface Entry {
    /** What it is called in the order the user gave a section. */
    val id: String
    val name: String

    class Dev(val device: Device) : Entry {
        override val id get() = device.applianceId
        override val name get() = device.name
    }

    class Mac(val macro: Macro) : Entry {
        override val id get() = Quick.macroKey(macro.id)
        override val name get() = macro.name
    }

    class Rem(val remote: Remote) : Entry {
        override val id get() = Quick.remoteKey(remote.id)
        override val name get() = remote.name
    }

    class Grp(val group: Group, val devices: List<Device>) : Entry {
        override val id get() = "group:${group.id}"
        override val name get() = group.name
    }
}

/** The room the user sees it in, null for a group or for what is in none. */
private fun Entry.room(state: UiState): String? = when (this) {
    is Entry.Dev -> state.placed.find { it.applianceId == device.applianceId }?.room
    is Entry.Mac, is Entry.Rem -> state.placeOf(id)
    is Entry.Grp -> null
}

/** The room a tile shows before its state, where the section around it does not tell. */
internal val LocalTileRoom = compositionLocalOf<String?> { null }

@Composable
private fun Entry.Tile(state: UiState, vm: HomeViewModel) = when (this) {
    is Entry.Dev -> DeviceTile(device, state, vm)
    is Entry.Mac -> MacroTile(macro, state, vm)
    is Entry.Rem -> RemoteTile(remote, state, vm)
    is Entry.Grp -> GroupTile(group, devices, state, vm) { vm.toggleGroup(group) }
}

/** The tiles of the home as lists, one per section, to put in order with the arrows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrangeDialog(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val sections = homeSections(PAGE_ALL, state)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text(str(R.string.arrange)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = str(R.string.back))
                        }
                    },
                    actions = { TextButton(onClick = onDismiss) { Text(str(R.string.arrange_done)) } },
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .wrapContentWidth()
                        .widthIn(max = 640.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        str(R.string.arrange_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (section in sections) {
                        SectionCard {
                            Column(Modifier.padding(bottom = 8.dp)) {
                                Text(
                                    section.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                                val ids = section.entries.map { it.id }
                                section.entries.forEachIndexed { index, entry ->
                                    OrderRow(
                                        name = entry.name,
                                        first = index == 0,
                                        last = index == ids.lastIndex,
                                    ) { by ->
                                        val moved = ids.toMutableList()
                                        moved.add(index + by, moved.removeAt(index))
                                        vm.setOrder(state.order + (section.key to moved))
                                    }
                                    Chips(R.string.tile_width, listOf(1 to str(R.string.tile_one_column), 2 to str(R.string.tile_two_columns)), state.tileSpans[entry.id] ?: 1) {
                                        vm.setTileSpan(entry.id, it)
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
        switchingOn = switchingOn(device, state),
        vm = vm,
    )
}

/**
 * A group as one tile: lit while any of its devices is on, and a tap switches them all. Holding
 * it opens the devices one by one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTile(group: Group, devices: List<Device>, state: UiState, vm: HomeViewModel, onToggle: () -> Unit) {
    val lit = devices.count { state.states[it.applianceId]?.power == true }
    val colors = MaterialTheme.colorScheme
    var details by remember { mutableStateOf(false) }
    if (details) GroupSheet(group, devices, state, vm, onDismiss = { details = false })
    Surface(
        color = if (lit > 0) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (lit > 0) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalHomeLook.current.tileHeight)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onToggle, onLongClick = { details = true }),
    ) {
        TileBody(
            icon = Icons.Outlined.Layers,
            busy = devices.any { it.applianceId in state.busy },
            name = group.name,
            status = groupStatus(lit, devices.size),
            hints = SWITCH_HINTS,
        )
    }
}

private fun groupStatus(lit: Int, size: Int): String = when (lit) {
    0 -> str(R.string.off)
    size -> str(R.string.on)
    else -> str(R.string.group_some_on, lit, size)
}

/** A group opened up: everything on or off, a switch-off timer for all, and each device by itself. */
@Composable
private fun GroupSheet(group: Group, devices: List<Device>, state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val askNotifications = rememberNotificationRequest()
    val now = System.currentTimeMillis()
    val timers = devices.mapNotNull { state.timers[it.applianceId]?.takeIf { at -> at > now } }
    DetailsContainer(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Layers, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(group.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        groupStatus(devices.count { state.states[it.applianceId]?.power == true }, devices.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { vm.setRoomPower(devices, true) }, modifier = Modifier.weight(1f)) {
                    Text(str(R.string.all_on))
                }
                FilledTonalButton(onClick = { vm.setRoomPower(devices, false) }, modifier = Modifier.weight(1f)) {
                    Text(str(R.string.all_off))
                }
            }

            Text(
                str(R.string.timer),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 20.dp),
            )
            if (timers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The devices may carry timers of their own: the group says when the last one goes off.
                    val text = if (timers.size == devices.size) {
                        str(R.string.turns_off_at, clock(timers.max()))
                    } else {
                        str(R.string.group_timers_some, timers.size, devices.size)
                    }
                    Text(text, modifier = Modifier.weight(1f))
                    TextButton(onClick = { devices.forEach(vm::cancelTimer) }) { Text(str(R.string.cancel_timer)) }
                }
            }
            // A timer can switch the group on too, one scheduled action for each device.
            val waiting = devices.flatMap { switchingOn(it, state) }
            if (waiting.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        str(R.string.schedule_once, str(R.string.voice_on, group.name), clock(waiting.maxOf { it.at })),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { waiting.forEach { vm.deleteSchedule(it.id) } }) {
                        Text(str(R.string.cancel_timer))
                    }
                }
            }
            TimerChoices(canTurnOn = true) { minutes, on ->
                askNotifications()
                for (device in devices) {
                    if (on) vm.addTimer(switchOn(device), describe(switchOn(device), state), minutes)
                    else vm.setTimer(device, minutes)
                }
            }

            Text(
                str(R.string.s_devices),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            for (device in devices.sortedBy { it.name.lowercase() }) {
                val id = device.applianceId
                val power = state.states[id]?.power
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(iconFor(device), contentDescription = null, modifier = Modifier.size(24.dp))
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = statusText(device, state.states[id])
                        val timer = state.timers[id]?.takeIf { it > now }
                        Text(
                            if (timer == null) status else str(R.string.status_until, status, clock(timer)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (id in state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Switch(
                        checked = power == true,
                        onCheckedChange = { vm.setPower(device, it) },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (!LocalHomeLook.current.locked) {
                val key = "group:${group.id}"
                val favorite = key in state.favorites
                TextButton(onClick = { vm.setFavorite(key, !favorite) }) {
                    Text(str(if (favorite) R.string.favorite_remove else R.string.favorite_add))
                }
                NfcTagButton(NFC_GROUP, group.id, group.name)
            }
        }
    }
}

/** Writes a tag that switches a device or a group, for phones that have NFC. */
@Composable
private fun NfcTagButton(kind: String, id: String, name: String) {
    if (!rememberHasNfc()) return
    var tagging by remember { mutableStateOf(false) }
    TextButton(onClick = { tagging = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text(str(R.string.nfc_write))
    }
    if (tagging) NfcTagDialog(kind, id, name, canSwitch = true) { tagging = false }
}

/**
 * What a tile tells about itself in its corner. Tiles look alike but do not answer a touch alike:
 * some switch, some run, some open, and only some do something more when held.
 */
internal enum class TileHint(val icon: ImageVector, @StringRes val text: Int, @StringRes val short: Int) {
    TOGGLE(Icons.Outlined.PowerSettingsNew, R.string.hint_toggle, R.string.hint_toggle_short),
    RUN(Icons.Outlined.PlayArrow, R.string.hint_run, R.string.hint_run_short),
    OPEN(Icons.AutoMirrored.Outlined.KeyboardArrowRight, R.string.hint_open, R.string.hint_open_short),
    HOLD(Icons.Outlined.MoreHoriz, R.string.hint_hold, R.string.hint_hold_short),
}

/** What the symbols in the corners mean, in a few words: it closes the list, small, for who wonders. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (hint in TileHint.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(hint.icon, contentDescription = null, tint = faint, modifier = Modifier.size(16.dp))
                Text(
                    str(hint.short),
                    style = MaterialTheme.typography.labelSmall,
                    color = faint,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

private val SWITCH_HINTS = listOf(TileHint.TOGGLE, TileHint.HOLD)

/** What every tile shows, sized as the screen it is on asks. */
@Composable
internal fun TileBody(icon: ImageVector, busy: Boolean, name: String, status: String, hints: List<TileHint>) {
    val size = LocalHomeLook.current.tileSize
    val told = LocalTileRoom.current?.let { "$it · $status" } ?: status
    val type = MaterialTheme.typography
    @Composable
    fun Corner() {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            return
        }
        // Small: what a touch does, and whether holding does something else.
        for (hint in hints) {
            Icon(
                hint.icon,
                contentDescription = str(hint.text),
                modifier = Modifier.padding(start = 4.dp).size(listOf(14.dp, 16.dp, 20.dp)[size]).alpha(0.6f),
            )
        }
    }
    // As a row of a list: the icon, the name over the state, the symbols at the end.
    if (LocalHomeLook.current.list) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(listOf(22.dp, 24.dp, 30.dp)[size]))
            Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Text(
                    name,
                    style = if (size == 2) type.titleMedium else type.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    told,
                    style = if (size == 2) type.bodyMedium else type.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(0.75f),
                )
            }
            Corner()
        }
        return
    }
    Column(Modifier.padding(listOf(12.dp, 14.dp, 18.dp)[size])) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(listOf(22.dp, 26.dp, 36.dp)[size]))
            Spacer(Modifier.weight(1f))
            Corner()
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
            told,
            style = if (size == 2) type.bodyLarge else type.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.75f),
        )
    }
}

/** Something due at a time, such as a device with a switch-off timer, with the time left counted down. */
@Composable
private fun TimerRow(
    icon: ImageVector,
    name: String,
    text: String,
    at: Long?,
    stop: ImageVector = Icons.Outlined.Close,
    @StringRes stopLabel: Int = R.string.cancel_timer,
    onStop: () -> Unit,
) {
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.75f))
            }
            // Digits of equal width keep the countdown from jittering as it ticks.
            if (at != null) {
                Text(
                    countdown(at - now),
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                )
            }
            IconButton(onClick = onStop) {
                Icon(stop, contentDescription = str(stopLabel))
            }
        }
    }
}

/** Time left as 1:05:09 or 05:09; a timer that is late, waiting for the network, stays at zero. */
internal fun countdown(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val (h, m, s) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return when {
        // Days away, the seconds would only flicker: days and hours say enough.
        h >= 24 -> str(R.string.countdown_days, h / 24, h % 24)
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%02d:%02d".format(m, s)
    }
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

/**
 * Asks the phone's speech recognizer for a sentence and passes on what it heard; it spins while
 * the assistant is [correcting] the names in it.
 */
@Composable
private fun VoiceButton(vm: HomeViewModel, correcting: Boolean) {
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
        enabled = !correcting,
    ) {
        if (correcting) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.Mic, contentDescription = str(R.string.voice_button))
        }
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
private fun OverflowMenu(state: UiState, vm: HomeViewModel, onSettings: () -> Unit, onArrange: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) SignOutDialog(onConfirm = vm::logout, onDismiss = { confirmSignOut = false })
    var automating by remember { mutableStateOf(false) }
    if (automating) AutomationsDialog(state, vm, onDismiss = { automating = false })
    var scheduling by remember { mutableStateOf(false) }
    if (scheduling) SchedulesDialog(state, vm, onDismiss = { scheduling = false })
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
        if (state.hidden.isNotEmpty() || state.hiddenRemotes.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(str(if (state.showHidden) R.string.hide_hidden else R.string.show_hidden)) },
                onClick = { open = false; vm.toggleShowHidden() },
            )
        }
        DropdownMenuItem(
            text = { Text(str(R.string.check_updates)) },
            onClick = { open = false; vm.checkForUpdate(manual = true) },
        )
        DropdownMenuItem(text = { Text(str(R.string.arrange)) }, onClick = { open = false; guarded(onArrange) })
        DropdownMenuItem(
            text = { Text(str(R.string.s_automations)) },
            onClick = { open = false; guarded { automating = true } },
        )
        DropdownMenuItem(
            text = { Text(str(R.string.s_schedules)) },
            onClick = { open = false; guarded { scheduling = true } },
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
    switchingOn: List<Schedule>,
    vm: HomeViewModel,
) {
    val on = state?.power == true
    val powerKnown = state?.power != null
    val colors = MaterialTheme.colorScheme
    var details by remember { mutableStateOf(false) }
    // The search opens the panel of what it cannot switch.
    val opening = LocalOpening.current
    LaunchedEffect(opening.value) {
        if (opening.value == device.applianceId) {
            opening.value = null
            details = true
        }
    }

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
                        // A sensor has nothing to switch: a touch opens it, as holding does.
                        !device.hasPower -> details = true
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
            // Where a touch switches, holding opens the rest; where it cannot switch, it opens.
            hints = if (device.hasPower && powerKnown) SWITCH_HINTS else listOf(TileHint.OPEN),
        )
    }

    if (details) {
        DeviceSheet(
            device, state, hidden, favorite, tile, tilesFull, timer, alert, switchingOn, vm,
            onDismiss = { details = false },
        )
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
    switchingOn: List<Schedule>,
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
                    str(R.string.timer),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp),
                )
                if (timer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.turns_off_at, clock(timer)), modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelTimer(device) }) { Text(str(R.string.cancel_timer)) }
                    }
                } else {
                    TimerChoices(canTurnOn = true) { minutes, on ->
                        askNotifications()
                        if (on) {
                            vm.addTimer(switchOn(device), str(R.string.voice_on, device.name), minutes)
                        } else {
                            vm.setTimer(device, minutes)
                        }
                    }
                }
                // Switching on is a scheduled action: the one waiting, and what cancels it.
                for (waiting in switchingOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            str(R.string.schedule_once, waiting.label, clock(waiting.at)),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.deleteSchedule(waiting.id) }) { Text(str(R.string.cancel_timer)) }
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
            // A sensor goes on a tile too: there it shows its temperature.
            if (device.hasPower || device.isSensor) {
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
            if (device.hasPower) NfcTagButton(NFC_DEVICE, device.applianceId, device.name)
            TextButton(onClick = { vm.setHidden(device, !hidden); onDismiss() }) {
                Text(str(if (hidden) R.string.unhide else R.string.hide))
            }
        }
    }
}

/**
 * A sheet from the bottom on a phone, a panel on the side where the screen is wide; the whole
 * screen when [fullScreen], for what is held like a remote with many keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsContainer(onDismiss: () -> Unit, fullScreen: Boolean = false, content: @Composable () -> Unit) {
    if (fullScreen) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            // Not dimmed: it covers the home, and the bars of the system would show grey around it.
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { window?.setDimAmount(0f) }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                Column {
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = str(R.string.close))
                    }
                    Box(Modifier.weight(1f)) { content() }
                }
            }
        }
        return
    }
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
internal fun rememberNotificationRequest(onAnswer: () -> Unit = {}): () -> Unit {
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
    val haptic = LocalHaptics.current
    Slider(
        value = value,
        onValueChange = {
            // A light tick every 5%, and a firmer one at either end, felt under the finger.
            val step = it.roundToInt() / BRIGHTNESS_STEP
            if (step != value.roundToInt() / BRIGHTNESS_STEP) {
                haptic(if (it <= 1f || it >= 100f) Haptic.Confirm else Haptic.Tick)
            }
            value = it
        },
        onValueChangeFinished = { onChange(value.roundToInt().coerceIn(1, 100)) },
        valueRange = 1f..100f,
    )
}

private const val BRIGHTNESS_STEP = 5

private fun iconFor(device: Device): ImageVector = when (device.category) {
    "LIGHT" -> Icons.Outlined.Lightbulb
    "SMARTPLUG" -> Icons.Outlined.Power
    "SWITCH" -> Icons.Outlined.ToggleOn
    "TV" -> Icons.Outlined.Tv
    "VACUUM_CLEANER" -> Icons.Outlined.CleaningServices
    "TEMPERATURE_SENSOR", "THERMOSTAT" -> Icons.Outlined.Thermostat
    else -> Icons.Outlined.DevicesOther
}
