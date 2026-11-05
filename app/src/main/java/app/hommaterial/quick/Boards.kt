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
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import app.hommaterial.MainActivity
import app.hommaterial.R
import app.hommaterial.data.Alexa
import app.hommaterial.data.AlexaList
import app.hommaterial.data.CalendarEvent
import app.hommaterial.data.CalendarSource
import app.hommaterial.data.LIST_NOTION
import app.hommaterial.data.ListItem
import app.hommaterial.data.Notion
import app.hommaterial.data.NotionSource
import app.hommaterial.data.NotionStore
import app.hommaterial.data.Offline
import app.hommaterial.data.eventFromJson
import app.hommaterial.data.eventJson
import app.hommaterial.str
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** What [MainActivity] is asked to open by a widget: the page of the bottom bar, by its key. */
const val EXTRA_TAB = "tab"

private val ITEM_ID = ActionParameters.Key<String>("itemId")
private val TAB = ActionParameters.Key<String>(EXTRA_TAB)

/** The days the agenda of the widget looks ahead, today included. */
private const val AGENDA_DAYS = 7L

/**
 * The widgets of the lists and of the agenda: what they show is what the app kept last (the lists
 * in [Offline], the agenda here), so that they draw at once and without internet; their button
 * reads it again.
 */
object Boards {
    private val _changes = MutableStateFlow(0)
    val changes = _changes.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences("boards", Context.MODE_PRIVATE)

    /** Draws the widgets again with what is kept: after the app read or changed the lists or the calendar. */
    fun update(context: Context) {
        val app = context.applicationContext
        _changes.update { it + 1 }
        Quick.scope.launch {
            runCatching { ListsWidget().updateAll(app) }
            runCatching { AgendaWidget().updateAll(app) }
        }
    }

    // --- the lists ------------------------------------------------------------------------------

    /** The lists of Alexa as kept, then those chosen of Notion. */
    fun lists(context: Context): List<AlexaList> {
        val notion = NotionStore(context)
        val fromNotion = if (notion.linked) notion.lists().map { AlexaList(it.listId, it.name, LIST_NOTION, null) } else emptyList()
        return Offline.lists().orEmpty() + fromNotion
    }

    /** The list the widget shows: the one chosen with its title, else the to-do list of Alexa. */
    fun shownList(context: Context): AlexaList? {
        val all = lists(context)
        val chosen = prefs(context).getString("list", null)
        return all.find { it.id == chosen } ?: all.find { it.type == "TO_DO" } ?: all.firstOrNull()
    }

    fun items(list: AlexaList): List<ListItem>? =
        Offline.items(list.id)?.let { if (list.type == LIST_NOTION) it else Offline.withPending(list.id, it) }

    /** Moves the widget on to the next list. */
    fun nextList(context: Context) {
        val all = lists(context)
        if (all.isEmpty()) return
        val at = all.indexOfFirst { it.id == shownList(context)?.id }
        prefs(context).edit().putString("list", all[(at + 1) % all.size].id).apply()
        update(context)
    }

    /** Reads the lists and the items of the one shown again, from Alexa and Notion. */
    suspend fun refreshLists(context: Context): Boolean {
        val app = context.applicationContext
        val alexa = Alexa.get(app)
        val ok = runCatching {
            Offline.flush()
            if (alexa.auth.isLoggedIn) Offline.putLists(alexa.api.lists())
            val list = shownList(app) ?: return@runCatching
            val source = NotionSource.ofList(list.id)
            val fresh = if (source != null) Notion(alexa.http, NotionStore(app)).items(source) else alexa.api.listItems(list.id)
            Offline.putItems(list.id, fresh)
        }.onFailure { if (it is CancellationException) throw it }.isSuccess
        update(app)
        return ok
    }

    /** Ticks [itemId] of the list shown: Alexa gets it when it can, Notion at once. */
    suspend fun tick(context: Context, itemId: String): Boolean {
        val app = context.applicationContext
        val list = shownList(app) ?: return false
        val item = items(list)?.find { it.id == itemId } ?: return false
        val source = NotionSource.ofList(list.id)
        if (source == null) {
            Offline.setItemDone(list.id, item, !item.completed)
            update(app)
            Quick.scope.launch { refreshLists(app) }
            return true
        }
        val ok = runCatching { Notion(Alexa.get(app).http, NotionStore(app)).setDone(source, item, !item.completed) }
            .onFailure { if (it is CancellationException) throw it }.isSuccess
        if (ok) Offline.putItems(list.id, Offline.items(list.id).orEmpty().map { if (it.id == itemId) it.copy(completed = !it.completed) else it })
        update(app)
        return ok
    }

    // --- the agenda -----------------------------------------------------------------------------

    /** The events of the next days, as read last. */
    fun agenda(context: Context): List<CalendarEvent>? = runCatching {
        prefs(context).getString("agenda", null)?.let { text ->
            JSONArray(text).let { list -> (0 until list.length()).map { eventFromJson(list.getJSONObject(it)) } }
        }
    }.getOrNull()

    /** Reads the events of the next days again, of the calendars shown in the app. */
    suspend fun refreshAgenda(context: Context): Boolean {
        val app = context.applicationContext
        val source = CalendarSource.of(app)
        if (!source.ready) return false
        val ok = runCatching {
            val calendars = source.calendars()
            val chosen = app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("calendarShown", null)
                ?.let { text -> JSONArray(text).let { list -> (0 until list.length()).map { list.getString(it) }.toSet() } }
            val shown = if (chosen == null || calendars.none { it.id in chosen }) calendars.filter { it.shown } else calendars.filter { it.id in chosen }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = today.plusDays(AGENDA_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
            val events = source.events(shown.map { it.id }, from, to).filter { it.end > System.currentTimeMillis() }.sortedBy { it.start }
            putAgenda(app, events)
        }.onFailure { if (it is CancellationException) throw it }.isSuccess
        update(app)
        return ok
    }

    /** Keeps the events the app read, for the widget to show them without asking again. */
    fun putAgenda(context: Context, events: List<CalendarEvent>) {
        val now = System.currentTimeMillis()
        val kept = events.filter { it.end > now }.sortedBy { it.start }.take(40)
        prefs(context).edit().putString("agenda", JSONArray(kept.map(::eventJson)).toString()).apply()
    }
}

private fun openApp(tab: String) = actionStartActivity<MainActivity>(actionParametersOf(TAB to tab))

@Composable
private fun Frame(content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(8.dp)) { content() }
}

/** The title of a widget, which opens the app or does [onTitle], with the button that reads it again. */
@Composable
private fun BoardHeader(title: String, onTitle: androidx.glance.action.Action, refresh: androidx.glance.action.Action, open: androidx.glance.action.Action, chevron: Boolean) {
    val colors = GlanceTheme.colors
    Row(GlanceModifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (chevron) "$title  ›" else title,
            maxLines = 1,
            style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.defaultWeight().clickable(onTitle),
        )
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = str(R.string.refresh_now),
            colorFilter = ColorFilter.tint(colors.onSurfaceVariant),
            modifier = GlanceModifier.size(28.dp).clickable(refresh),
        )
        Image(
            provider = ImageProvider(R.drawable.ic_open),
            contentDescription = str(R.string.widget_open_app),
            colorFilter = ColorFilter.tint(colors.onSurfaceVariant),
            modifier = GlanceModifier.padding(start = 8.dp).size(28.dp).clickable(open),
        )
    }
}

@Composable
private fun Note(text: String, tap: androidx.glance.action.Action) {
    Box(GlanceModifier.fillMaxSize().clickable(tap), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center))
    }
}

/** A list of Alexa or Notion: what is still to do, ticked with a touch; its name moves to the next list. */
class ListsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by Boards.changes.collectAsState()
            val list = remember(version) { Boards.shownList(context) }
            val items = remember(version) { list?.let(Boards::items) }
            GlanceTheme {
                Frame {
                    Column(GlanceModifier.fillMaxSize()) {
                        val name = list?.let { app.hommaterial.ui.listName(it) } ?: str(R.string.s_lists)
                        val todo = items.orEmpty().filter { !it.completed }
                        BoardHeader(
                            title = name,
                            onTitle = actionRunCallback<NextListAction>(),
                            refresh = actionRunCallback<RefreshListsAction>(),
                            open = openApp(app.hommaterial.ui.TAB_LISTS),
                            chevron = Boards.lists(context).size > 1,
                        )
                        when {
                            list == null || items == null -> Note(str(R.string.widget_lists_none), actionRunCallback<RefreshListsAction>())
                            todo.isEmpty() -> Note(str(R.string.list_empty), openApp(app.hommaterial.ui.TAB_LISTS))
                            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                                items(todo, itemId = { it.id.hashCode().toLong() }) { item -> ItemRow(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ListItem) {
    val colors = GlanceTheme.colors
    Box(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().background(colors.surfaceVariant).cornerRadius(14.dp).padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(actionRunCallback<TickAction>(actionParametersOf(ITEM_ID to item.id))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_circle),
                contentDescription = str(R.string.widget_tick),
                colorFilter = ColorFilter.tint(colors.primary),
                modifier = GlanceModifier.size(20.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Text(item.name, maxLines = 2, style = TextStyle(color = colors.onSurface, fontSize = 14.sp))
        }
    }
}

/** The events of the next days, by day; a touch opens the calendar of the app. */
class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by Boards.changes.collectAsState()
            val events = remember(version) { Boards.agenda(context) }
            GlanceTheme {
                Frame {
                    Column(GlanceModifier.fillMaxSize()) {
                        val open = openApp(app.hommaterial.ui.TAB_CALENDAR)
                        BoardHeader(str(R.string.s_calendar), open, actionRunCallback<RefreshAgendaAction>(), open, chevron = false)
                        val now = System.currentTimeMillis()
                        val shown = events?.filter { it.end > now }
                        when {
                            shown == null -> Note(str(R.string.widget_agenda_none), actionRunCallback<RefreshAgendaAction>())
                            shown.isEmpty() -> Note(str(R.string.widget_agenda_empty), open)
                            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                                val zone = ZoneId.systemDefault()
                                val rows = mutableListOf<Pair<LocalDate?, CalendarEvent?>>()
                                var day: LocalDate? = null
                                for (event in shown) {
                                    val starts = maxOf(Instant.ofEpochMilli(event.start).atZone(zone).toLocalDate(), LocalDate.now(zone))
                                    if (starts != day) {
                                        day = starts
                                        rows += starts to null
                                    }
                                    rows += null to event
                                }
                                items(rows.size, itemId = { it.toLong() }) { index ->
                                    val (date, event) = rows[index]
                                    if (date != null) DayTitle(date) else EventRow(event!!, open)
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
private fun DayTitle(date: LocalDate) {
    val today = LocalDate.now()
    val text = when (date) {
        today -> str(R.string.calendar_today)
        today.plusDays(1) -> str(R.string.calendar_tomorrow)
        else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
    }
    Text(
        text.replaceFirstChar { it.uppercase() },
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        modifier = GlanceModifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun EventRow(event: CalendarEvent, open: androidx.glance.action.Action) {
    val colors = GlanceTheme.colors
    val time = if (event.allDay) {
        str(R.string.calendar_all_day)
    } else {
        Instant.ofEpochMilli(event.start).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    Box(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(
            GlanceModifier.fillMaxWidth().background(colors.surfaceVariant).cornerRadius(14.dp).padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(open),
        ) {
            Text(event.title.ifBlank { str(R.string.calendar_untitled) }, maxLines = 1, style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            Text(
                if (event.location.isBlank()) time else "$time · ${event.location}",
                maxLines = 1,
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
    }
}

class NextListAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Boards.nextList(context)
        Boards.refreshLists(context)
    }
}

class RefreshListsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!Boards.refreshLists(context)) toast(context, str(R.string.widget_refresh_failed))
    }
}

class TickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!Boards.tick(context, parameters[ITEM_ID] ?: return)) toast(context, str(R.string.widget_refresh_failed))
    }
}

class RefreshAgendaAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (!Boards.refreshAgenda(context)) toast(context, str(R.string.widget_refresh_failed))
    }
}

private suspend fun toast(context: Context, text: String) =
    withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

class ListsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ListsWidget()

    // Runs when the widget is placed and then about every half hour.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val app = context.applicationContext
        Quick.scope.launch { Boards.refreshLists(app) }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = AgendaWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val app = context.applicationContext
        Quick.scope.launch { Boards.refreshAgenda(app) }
    }
}
