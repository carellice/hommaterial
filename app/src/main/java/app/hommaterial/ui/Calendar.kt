package app.hommaterial.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.text.format.DateFormat
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.CalendarEvent
import app.hommaterial.data.CalendarInfo
import app.hommaterial.data.DeviceCalendar
import app.hommaterial.data.EventInput
import app.hommaterial.data.ServerSync
import app.hommaterial.quick.clock
import app.hommaterial.str
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The page of the bottom bar with the agenda. */
const val TAB_CALENDAR = "calendar"

// How far the agenda reads at first, and how much more each time it is asked.
private const val DAYS_FIRST = 14
private const val DAYS_MORE = 14
// The events of today and tomorrow the home shows before saying how many more there are.
private const val HOME_EVENTS = 4

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun dayOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
private fun midnight(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
private fun colorOf(calendar: CalendarInfo?) = Color(calendar?.color ?: 0xFF4285F4)

/**
 * The calendars and the events of the coming days, kept while the app is open so that the home and
 * the agenda show them at once: read when either first shows, on a pull and after every change.
 */
@Stable
internal class CalendarModel(private val vm: HomeViewModel, private val scope: CoroutineScope) {
    var calendars by mutableStateOf<List<CalendarInfo>?>(null)
        private set
    var events by mutableStateOf<List<CalendarEvent>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var days by mutableIntStateOf(DAYS_FIRST)
        private set

    /** The calendars shown: those chosen on this device, or, until the user chooses, those the calendar itself shows. */
    fun visible(state: UiState): List<CalendarInfo> {
        val all = calendars.orEmpty()
        val chosen = state.calendarShown ?: return all.filter { it.shown }
        // Chosen among the calendars of another source, before a server was linked or unlinked.
        if (chosen.isNotEmpty() && all.none { it.id in chosen }) return all.filter { it.shown }
        return all.filter { it.id in chosen }
    }

    fun calendar(id: String) = calendars?.find { it.id == id }

    /** Where a new event goes: the one chosen, else the main calendar, else the first that takes events. */
    fun defaultCalendar(state: UiState): CalendarInfo? {
        val writable = calendars.orEmpty().filter { it.writable }
        return writable.find { it.id == state.calendarDefault } ?: writable.find { it.primary } ?: writable.firstOrNull()
    }

    fun reload(state: UiState) {
        if (loading || !state.calendarReady) return
        scope.launch {
            loading = true
            val found = vm.calendars()
            if (found != null) calendars = found
            val shown = visible(state).map { it.id }
            val today = LocalDate.now(zone)
            val read = if (found == null) null else vm.calendarEvents(shown, midnight(today), midnight(today.plusDays(days.toLong())))
            failed = read == null
            if (read != null) events = read.sortedBy { it.start }
            loading = false
        }
    }

    fun more(state: UiState) {
        days += DAYS_MORE
        reload(state)
    }

    fun save(state: UiState, event: CalendarEvent?, calendar: String, input: EventInput, done: () -> Unit) {
        scope.launch {
            if (vm.saveEvent(event, calendar, input)) {
                done()
                showKept(state)
                reload(state)
            }
        }
    }

    fun delete(state: UiState, event: CalendarEvent, done: () -> Unit) {
        scope.launch {
            if (vm.deleteEvent(event)) {
                // Gone at once; the reading after confirms it.
                events = events?.filter { it.key != event.key }
                done()
                showKept(state)
                reload(state)
            }
        }
    }

    /** The events as kept on the phone, with the change just made, before the reading after it. */
    private fun showKept(state: UiState) {
        val today = LocalDate.now(zone)
        val shown = visible(state).map { it.id }.toSet()
        vm.keptEvents(midnight(today), midnight(today.plusDays(days.toLong())))?.let { kept -> events = kept.filter { it.calendar in shown } }
    }
}

@Composable
internal fun rememberCalendarModel(vm: HomeViewModel): CalendarModel {
    val scope = rememberCoroutineScope()
    return remember(vm) { CalendarModel(vm, scope) }
}

/** The day an event is listed under: its first, or today for what began before and still goes on. */
private fun listedDay(event: CalendarEvent, from: LocalDate): LocalDate = maxOf(dayOf(event.start), from)

/** When an event happens, as a line: its hours, the whole day, or until the day it ends. */
private fun whenText(event: CalendarEvent): String {
    val lastDay = dayOf(event.end - 1)
    return when {
        event.allDay && lastDay != dayOf(event.start) -> str(R.string.calendar_until, dateText(event.end - 1))
        event.allDay -> str(R.string.calendar_all_day)
        dayOf(event.start) != lastDay -> "${clock(event.start)} · " + str(R.string.calendar_until, dateText(event.end) + " " + clock(event.end))
        else -> "${clock(event.start)} – ${clock(event.end)}"
    }
}

/** A day as the agenda heads it: "Today", "Tomorrow" or its name, always with its date. */
private fun dayTitle(day: LocalDate): Pair<String, String?> {
    val today = LocalDate.now(zone)
    val date = dateText(midnight(day))
    return when (day) {
        today -> str(R.string.calendar_today) to date
        today.plusDays(1) -> str(R.string.calendar_tomorrow) to date
        else -> date.replaceFirstChar { it.uppercase() } to null
    }
}

/** The page of the agenda: the events of the coming days by day, a + to add one, a tap to change one. */
@Composable
internal fun CalendarPage(model: CalendarModel, state: UiState, vm: HomeViewModel, onSettings: () -> Unit) {
    LaunchedEffect(state.calendarReady, state.calendarShown) { model.reload(state) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var creating by remember { mutableStateOf(false) }
    editing?.let { EventEditor(it, model, state, vm) { editing = null } }
    if (creating) EventEditor(null, model, state, vm) { creating = false }

    if (!state.calendarReady) {
        CalendarSetupCard(state, vm, onSettings, Modifier.fillMaxSize())
        return
    }
    Column(Modifier.fillMaxSize()) {
    if (ServerSync.active) OfflineBanner()
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = model.loading && model.events != null, onRefresh = { model.reload(state) }, modifier = Modifier.fillMaxSize()) {
            val events = model.events
            when {
                events == null && model.failed -> CalendarMessage(Icons.Outlined.EventBusy, str(R.string.calendar_failed)) {
                    AppTonalButton(onClick = { model.reload(state) }) { Text(str(R.string.calendar_retry)) }
                }
                events == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                model.visible(state).isEmpty() -> CalendarMessage(Icons.Outlined.CalendarMonth, str(R.string.calendar_none_chosen)) {
                    AppTonalButton(onClick = onSettings) { Text(str(R.string.calendar_open_settings)) }
                }
                else -> Agenda(events, model, state, onOpen = { editing = it })
            }
        }
        if (model.defaultCalendar(state) != null) {
            val skeuo = LocalSkeuo.current
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(str(R.string.calendar_new)) },
                // In the skeuomorphic look a gel key in the accent.
                containerColor = if (skeuo) Color.Transparent else androidx.compose.material3.FloatingActionButtonDefaults.containerColor,
                contentColor = if (skeuo) Color.White else androidx.compose.material3.contentColorFor(androidx.compose.material3.FloatingActionButtonDefaults.containerColor),
                elevation = if (skeuo) androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp) else androidx.compose.material3.FloatingActionButtonDefaults.elevation(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = BOTTOM_BAR_SPACE + 8.dp)
                    .skeuoRaised(corners(16.dp), lit = true, elevation = 6.dp),
            )
        }
    }
    }
}

@Composable
private fun Agenda(events: List<CalendarEvent>, model: CalendarModel, state: UiState, onOpen: (CalendarEvent) -> Unit) {
    val today = LocalDate.now(zone)
    val byDay = events.groupBy { listedDay(it, today) }.toSortedMap()
    LazyColumn(
        Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 720.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BOTTOM_BAR_SPACE + 88.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (byDay.isEmpty()) {
            item(key = "empty") {
                CalendarMessage(Icons.Outlined.CalendarMonth, str(R.string.calendar_empty), str(R.string.calendar_empty_text), Modifier.padding(top = 48.dp))
            }
        }
        for ((day, list) in byDay) {
            item(key = "day:$day") {
                val (title, date) = dayTitle(day)
                Row(Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (date != null) {
                        Text(
                            date,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            items(list, key = { "event:${it.key}" }) { event ->
                EventRow(event, model.calendar(event.calendar), Modifier.animateItem()) { onOpen(event) }
            }
        }
        item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                OutlinedButton(onClick = { model.more(state) }, enabled = !model.loading) {
                    Text(str(R.string.calendar_load_more))
                }
            }
        }
    }
}

/** An event in the agenda: the color of its calendar, when, what and where. */
@Composable
private fun EventRow(event: CalendarEvent, calendar: CalendarInfo?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val skeuo = LocalSkeuo.current
    val shape = if (LocalMetro.current) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(if (skeuo) 12.dp else 18.dp)
    Surface(
        color = if (skeuo) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        modifier = modifier.fillMaxWidth().skeuoPanel(shape).clip(shape).clickable(onClick = onClick),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).fillMaxHeight().clip(CircleShape).background(colorOf(calendar)))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.title.ifBlank { str(R.string.calendar_untitled) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Changed here and not yet sent: it goes as soon as there is internet.
                    if (event.waiting) {
                        Icon(
                            Icons.Outlined.CloudUpload,
                            contentDescription = str(R.string.offline_waiting),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        )
                    }
                }
                Text(whenText(event), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.location.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text(
                            event.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(34.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

/** What the agenda needs before it can read: the permission on the phone, or Google linked on the server. */
@Composable
private fun CalendarSetupCard(state: UiState, vm: HomeViewModel, onSettings: () -> Unit, modifier: Modifier) {
    val ask = rememberCalendarPermission(vm)
    if (ServerSync.active) {
        CalendarMessage(Icons.Outlined.CalendarMonth, str(R.string.calendar_server_title), str(R.string.calendar_server_text), modifier) {
            AppTonalButton(onClick = onSettings) { Text(str(R.string.calendar_open_settings)) }
        }
    } else {
        CalendarMessage(Icons.Outlined.CalendarMonth, str(R.string.calendar_device_title), str(R.string.calendar_device_text), modifier) {
            AppTonalButton(onClick = ask) { Text(str(R.string.calendar_allow)) }
        }
    }
}

/** Asks for the calendars of the phone, then reads again whether they can be read. */
@Composable
internal fun rememberCalendarPermission(vm: HomeViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.checkCalendar() }
    return { launcher.launch(DeviceCalendar.PERMISSIONS) }
}

/**
 * The events of today and tomorrow at the top of the home, a few of them and how many more: a
 * tap opens the agenda. Nothing shows without a calendar to read.
 */
@Composable
internal fun HomeAgenda(model: CalendarModel, state: UiState, onOpen: () -> Unit) {
    LaunchedEffect(state.calendarReady, state.calendarShown) { model.reload(state) }
    val today = LocalDate.now(zone)
    val until = midnight(today.plusDays(2))
    val soon = model.events?.filter { it.start < until && it.end > System.currentTimeMillis() } ?: return
    val skeuo = LocalSkeuo.current
    val metro = LocalMetro.current
    val base = MaterialTheme.colorScheme
    // Modern UI: the live tile of the calendar, the accent with white words; what was tinted turns white too.
    val scheme = if (metro) base.copy(onSurfaceVariant = Color.White.copy(alpha = 0.8f), primary = Color.White, onSurface = Color.White) else base
    MaterialTheme(colorScheme = scheme) { Surface(
        color = when {
            metro -> base.primary
            skeuo -> Color.Transparent
            else -> base.secondaryContainer.copy(alpha = 0.55f)
        },
        contentColor = if (metro) Color.White else base.onSurface,
        shape = corners(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .then(if (skeuo) Modifier.skeuoPanel(corners(16.dp)) else Modifier)
            .then(if (metro) Modifier.metroTilt() else Modifier)
            .clip(corners(if (skeuo) 16.dp else 24.dp))
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.animateContentSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Text(
                    str(R.string.calendar_home_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (soon.isEmpty()) {
                Text(
                    str(R.string.calendar_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            for (event in soon.take(HOME_EVENTS)) {
                val tomorrow = listedDay(event, today) != today
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colorOf(model.calendar(event.calendar))))
                    Text(
                        if (event.allDay) str(R.string.calendar_all_day) else clock(event.start),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp).width(if (event.allDay) 104.dp else 56.dp),
                        maxLines = 1,
                    )
                    Text(
                        event.title.ifBlank { str(R.string.calendar_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (tomorrow) {
                        Text(
                            str(R.string.calendar_tomorrow),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (soon.size > HOME_EVENTS) {
                Text(
                    str(R.string.calendar_more, soon.size - HOME_EVENTS),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, start = 20.dp),
                )
            }
        }
    } }
}

/**
 * Creates an event, when [event] is null, or shows and changes one: what, when, where, in which
 * calendar. What cannot be changed from here is shown as it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditor(event: CalendarEvent?, model: CalendarModel, state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val writable = model.calendars.orEmpty().filter { it.writable }
    val original = event?.let { model.calendar(it.calendar) }
    val canEdit = event == null || (event.editable && original?.writable == true)
    // A new event starts at the next full hour, for one hour.
    val startAt = event?.start ?: (LocalTime.now(zone).hour + 1).let { hour ->
        midnight(LocalDate.now(zone)) + hour * 3_600_000L
    }
    var title by remember { mutableStateOf(event?.title.orEmpty()) }
    var location by remember { mutableStateOf(event?.location.orEmpty()) }
    var notes by remember { mutableStateOf(event?.notes.orEmpty()) }
    var allDay by remember { mutableStateOf(event?.allDay ?: false) }
    var start by remember { mutableStateOf(startAt) }
    // A whole day ends at the midnight after it: shown, it is the day before.
    var end by remember { mutableStateOf(event?.end ?: (startAt + 3_600_000L)) }
    var calendarId by remember { mutableStateOf(event?.calendar ?: model.defaultCalendar(state)?.id.orEmpty()) }
    var choosingCalendar by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun setStart(at: Long) {
        // The end keeps the length of the event.
        end = at + (end - start).coerceAtLeast(0)
        start = at
    }

    if (confirmDelete && event != null) {
        ConfirmDialog(
            title = str(R.string.calendar_delete_title, event.title.ifBlank { str(R.string.calendar_untitled) }),
            text = str(R.string.calendar_delete_text),
            confirm = str(R.string.delete),
            onConfirm = { confirmDelete = false; model.delete(state, event, onDismiss) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (choosingCalendar) {
        ListDialog(
            title = str(R.string.s_calendar),
            items = writable.map { it.name to it.account.takeIf { account -> account.isNotBlank() && account != it.name } },
            onDismiss = { choosingCalendar = false },
        ) { calendarId = writable[it].id; choosingCalendar = false }
    }
    when (picking) {
        "startDate" -> DateDialog(start, onDismiss = { picking = null }) { day ->
            setStart(midnight(day) + (start - midnight(dayOf(start))))
            picking = null
        }
        "endDate" -> DateDialog(if (allDay) end - 1 else end, onDismiss = { picking = null }) { day ->
            end = if (allDay) midnight(day.plusDays(1)) else midnight(day) + (end - midnight(dayOf(end)))
            picking = null
        }
        "startTime" -> TimeDialog(start, onDismiss = { picking = null }) { setStart(it); picking = null }
        "endTime" -> TimeDialog(end, onDismiss = { picking = null }) { end = it; picking = null }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScreenSurface {
            Column {
                AppTopBar(
                    title = { Text(str(if (event == null) R.string.calendar_new else R.string.calendar_edit)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = str(R.string.cancel)) }
                    },
                    actions = {
                        if (event != null && canEdit) {
                            TextButton(onClick = { confirmDelete = true }) { Text(str(R.string.delete)) }
                        }
                        if (canEdit) {
                            TextButton(
                                onClick = {
                                    saving = true
                                    val input = EventInput(title.trim(), location.trim(), notes.trim(), allDay,
                                        if (allDay) midnight(dayOf(start)) else start,
                                        if (allDay) maxOf(end, midnight(dayOf(start).plusDays(1))) else end)
                                    model.save(state, event, calendarId, input) { onDismiss() }
                                    saving = false
                                },
                                enabled = title.isNotBlank() && calendarId.isNotEmpty() && end >= start && !saving,
                            ) { Text(str(R.string.save)) }
                        }
                    },
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 640.dp).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val note = when {
                        event == null -> null
                        original?.writable != true -> str(R.string.calendar_readonly)
                        !event.editable -> str(R.string.calendar_recurring_device)
                        event.recurring -> str(R.string.calendar_recurring)
                        else -> null
                    }
                    if (note != null) {
                        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(str(R.string.calendar_title)) },
                        singleLine = true,
                        enabled = canEdit,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SectionCard {
                        Column {
                            val chosen = model.calendar(calendarId)
                            ListItem(
                                leadingContent = { Box(Modifier.size(14.dp).clip(CircleShape).background(colorOf(chosen))) },
                                headlineContent = { Text(chosen?.name ?: str(R.string.s_calendar)) },
                                supportingContent = chosen?.account?.takeIf { it.isNotBlank() && it != chosen.name }?.let { { Text(it) } },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable(enabled = canEdit && writable.size > 1) { choosingCalendar = true },
                            )
                        }
                    }
                    SectionCard {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ListItem(
                                headlineContent = { Text(str(R.string.calendar_all_day)) },
                                trailingContent = { AppSwitch(checked = allDay, onCheckedChange = { allDay = it; if (it) end = maxOf(end, midnight(dayOf(start).plusDays(1))) }, enabled = canEdit) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            WhenRow(str(R.string.calendar_start), start, allDay, canEdit, onDate = { picking = "startDate" }, onTime = { picking = "startTime" })
                            WhenRow(str(R.string.calendar_end), if (allDay) end - 1 else end, allDay, canEdit, onDate = { picking = "endDate" }, onTime = { picking = "endTime" })
                            if (end < start) {
                                Text(
                                    str(R.string.calendar_bad_times),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text(str(R.string.calendar_location)) },
                        singleLine = true,
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(str(R.string.calendar_notes)) },
                        minLines = 3,
                        enabled = canEdit,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** A start or an end: its day, and its time unless it lasts whole days. */
@Composable
private fun WhenRow(label: String, at: Long, allDay: Boolean, enabled: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(72.dp))
        TextButton(onClick = onDate, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(dateText(at).replaceFirstChar { it.uppercase() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!allDay) TextButton(onClick = onTime, enabled = enabled) { Text(clock(at), fontWeight = FontWeight.Medium) }
    }
}

/** Picks a day; the picker counts in UTC, the event in the time of the home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(at: Long, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = dayOf(at).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    ) { DatePicker(picker) }
}

/** Picks the time of day of [at], keeping its day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(at: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val picker = rememberTimePickerState(time.hour, time.minute, DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(picker) },
        confirmButton = {
            TextButton(onClick = {
                onPick(time.toLocalDate().atTime(picker.hour, picker.minute).atZone(zone).toInstant().toEpochMilli())
            }) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

// --- the settings --------------------------------------------------------------------------------

private const val GOOGLE_PROJECT = "https://console.cloud.google.com/projectcreate"
private const val GOOGLE_API = "https://console.cloud.google.com/apis/library/calendar-json.googleapis.com"
private const val GOOGLE_CONSENT = "https://console.cloud.google.com/auth/overview"
private const val GOOGLE_AUDIENCE = "https://console.cloud.google.com/auth/audience"
private const val GOOGLE_CLIENTS = "https://console.cloud.google.com/auth/clients"

/**
 * The calendar in the settings: Google linked step by step on the home server, or the permission
 * for those of the phone; then which calendars to show and where new events go.
 */
@Composable
internal fun CalendarSettings(state: UiState, vm: HomeViewModel) {
    val scope = rememberCoroutineScope()
    var calendars by remember { mutableStateOf<List<CalendarInfo>?>(null) }
    LaunchedEffect(state.calendarReady) { calendars = if (state.calendarReady) vm.calendars() else null }
    val ask = rememberCalendarPermission(vm)

    if (ServerSync.active) {
        GoogleSetup(state, vm)
    } else if (!state.calendarReady) {
        Setting(title = str(R.string.calendar_permission), text = str(R.string.calendar_permission_off), onClick = ask)
    }
    if (!state.calendarReady) return

    Toggle(R.string.calendar_home_toggle, R.string.calendar_home_toggle_text, state.calendarHome) { vm.setCalendarHome(it) }
    val list = calendars ?: run {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        return
    }
    val chosen = state.calendarShown?.takeIf { ids -> ids.isEmpty() || list.any { it.id in ids } }
        ?: list.filter { it.shown }.map { it.id }.toSet()
    Text(
        str(R.string.calendar_shown),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
    var account: String? = null
    for (calendar in list) {
        if (calendar.account != account && !ServerSync.active) {
            account = calendar.account
            Text(
                calendar.account,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
        }
        val on = calendar.id in chosen
        ListItem(
            leadingContent = { Box(Modifier.size(14.dp).clip(CircleShape).background(colorOf(calendar))) },
            headlineContent = { Text(calendar.name) },
            trailingContent = { Checkbox(checked = on, onCheckedChange = { vm.setCalendarShown(if (it) chosen + calendar.id else chosen - calendar.id) }) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { vm.setCalendarShown(if (on) chosen - calendar.id else chosen + calendar.id) },
        )
    }
    val writable = list.filter { it.writable }
    if (writable.isNotEmpty()) {
        var choosing by remember { mutableStateOf(false) }
        val current = writable.find { it.id == state.calendarDefault } ?: writable.find { it.primary } ?: writable.first()
        if (choosing) {
            ListDialog(
                title = str(R.string.calendar_default),
                items = writable.map { it.name to it.account.takeIf { account -> account.isNotBlank() && account != it.name } },
                onDismiss = { choosing = false },
            ) { vm.setCalendarDefault(writable[it].id); choosing = false }
        }
        Setting(title = str(R.string.calendar_default), text = current.name, onClick = { choosing = true })
    }
    if (ServerSync.active) {
        Setting(title = str(R.string.calendar_unlink), text = str(R.string.calendar_linked, state.calendarAccount), onClick = {
            scope.launch { vm.unlinkGoogle() }
        })
    }
}

/** Google Calendar on the home server, six steps with the links to follow; once linked, nothing of it shows. */
@Composable
private fun GoogleSetup(state: UiState, vm: HomeViewModel) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var changingKeys by remember { mutableStateOf(false) }
    // Once linked, the keys change after unlinking.
    if (state.calendarLinked) return
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(str(R.string.gcal_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Step(R.string.gcal_step1_title, R.string.gcal_step1_text) { uri.openUri(GOOGLE_PROJECT) }
        Step(R.string.gcal_step2_title, R.string.gcal_step2_text) { uri.openUri(GOOGLE_API) }
        Step(R.string.gcal_step3_title, R.string.gcal_step3_text) { uri.openUri(GOOGLE_CONSENT) }
        Step(R.string.gcal_step4_title, R.string.gcal_step4_text) { uri.openUri(GOOGLE_AUDIENCE) }
        Step(R.string.gcal_step5_title, R.string.gcal_step5_text, done = state.calendarClient && !changingKeys) { uri.openUri(GOOGLE_CLIENTS) }
        if (!state.calendarClient || changingKeys) {
            OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text(str(R.string.gcal_client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                secret, { secret = it.trim() },
                label = { Text(str(R.string.gcal_client_secret)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTonalButton(
                onClick = {
                    busy = true
                    scope.launch {
                        if (vm.setGoogleClient(clientId, secret)) changingKeys = false
                        busy = false
                    }
                },
                enabled = clientId.isNotBlank() && secret.isNotBlank() && !busy,
            ) { Text(str(R.string.gcal_save_keys)) }
        }
        if (state.calendarClient && !changingKeys) {
            Text(str(R.string.gcal_step6_title), style = MaterialTheme.typography.titleSmall)
            Text(str(R.string.gcal_step6_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTonalButton(onClick = { scope.launch { vm.googleAuthUrl()?.let(uri::openUri) } }) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(str(R.string.gcal_sign_in))
            }
            OutlinedTextField(address, { address = it.trim() }, label = { Text(str(R.string.gcal_paste)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AppTonalButton(
                onClick = {
                    busy = true
                    scope.launch {
                        vm.linkGoogle(address)
                        busy = false
                    }
                },
                enabled = address.isNotBlank() && !busy,
            ) { Text(str(R.string.gcal_link)) }
            TextButton(onClick = { changingKeys = true }) { Text(str(R.string.calendar_change_keys)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** A step of the setup, with the page of Google Cloud it happens on. */
@Composable
internal fun Step(title: Int, text: Int, done: Boolean = false, onOpen: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = corners(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(str(title), style = MaterialTheme.typography.titleSmall, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(str(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onOpen != null) TextButton(onClick = onOpen) { Text(str(R.string.gcal_open)) }
        }
    }
}

/** The events of today and tomorrow on the rest screen of the monitor, a few of them. */
@Composable
internal fun RestAgenda(model: CalendarModel, state: UiState) {
    LaunchedEffect(state.calendarReady, state.calendarShown) { model.reload(state) }
    val today = LocalDate.now(zone)
    val until = midnight(today.plusDays(2))
    val soon = model.events?.filter { it.start < until && it.end > System.currentTimeMillis() }.orEmpty()
    if (soon.isEmpty()) return
    Column(Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (event in soon.take(HOME_EVENTS)) {
            val tomorrow = listedDay(event, today) != today
            Text(
                listOfNotNull(
                    if (event.allDay) str(R.string.calendar_all_day) else clock(event.start),
                    event.title.ifBlank { str(R.string.calendar_untitled) },
                    if (tomorrow) str(R.string.calendar_tomorrow) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
