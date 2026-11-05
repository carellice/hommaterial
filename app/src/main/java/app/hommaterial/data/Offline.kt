package app.hommaterial.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hommaterial.R

import app.hommaterial.quick.Quick
import app.hommaterial.str
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The lists of Alexa and Google Calendar without internet. What they showed last is kept on the
 * phone, and what the user changes meanwhile waits in order ([pending]) to be sent as soon as a
 * connection comes back, by the app or, closed, by WorkManager ([OutboxWorker]). Until then the
 * screens show the copy with the changes already made.
 *
 * Changes to what is itself still waiting are folded into it: an item added and ticked off without
 * internet is sent once, added and done. A change Alexa or Google then refuses (the item changed
 * elsewhere, the event deleted) is dropped and told.
 */
object Offline {
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("offline", Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    private val _pending = MutableStateFlow<List<Change>>(emptyList())
    /** The changes waiting for a connection, oldest first. */
    val pending = _pending.asStateFlow()

    private val _online = MutableStateFlow(true)
    /** Whether the phone has internet, as Android says. */
    val online = _online.asStateFlow()

    private val _sent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires after the changes that waited were sent, for the screens to read again. */
    val sent = _sent.asSharedFlow()

    fun init(context: Context) {
        app = context.applicationContext
        _pending.value = runCatching { JSONArray(prefs.getString(KEY_PENDING, "[]")).mapObjects(Change::fromJson) }.getOrDefault(emptyList())
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        _online.value = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
                if (_pending.value.isNotEmpty()) Quick.scope.launch { runCatching { flush() } }
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
        if (_pending.value.isNotEmpty()) schedule()
    }

    // --- what was shown last ------------------------------------------------------------------------

    fun lists(): List<AlexaList>? = read("lists") { it.mapObjects(AlexaList::fromJson) }

    fun putLists(lists: List<AlexaList>) = write("lists", JSONArray(lists.map(::listJson)))

    fun items(listId: String): List<ListItem>? = read("items:$listId") { it.mapObjects(ListItem::fromJson) }

    fun putItems(listId: String, items: List<ListItem>) = write("items:$listId", JSONArray(items.map(::itemJson)))

    /** The notes of Notion, and the text of each note opened, as read last. */
    fun notes(): List<NotionNote>? = read("notes") { it.mapObjects(NotionNote::fromJson) }

    fun putNotes(notes: List<NotionNote>) = write("notes", JSONArray(notes.map { it.toJson() }))

    fun note(id: String): List<NoteBlock>? = read("note:$id") { it.mapObjects(NoteBlock::fromJson) }

    fun putNote(id: String, blocks: List<NoteBlock>) = write("note:$id", JSONArray(blocks.map { it.toJson() }))

    fun calendars(): List<CalendarInfo>? = read("calendars") { it.mapObjects(::calendarFromJson) }

    fun putCalendars(calendars: List<CalendarInfo>) = write("calendars", JSONArray(calendars.map(::calendarJson)))

    /** The events read last, for the days from [from] to [to] they were read for. */
    fun events(from: Long, to: Long): List<CalendarEvent>? {
        val range = prefs.getString("eventsRange", null)?.split(",")?.mapNotNull { it.toLongOrNull() } ?: return null
        if (range.size != 2) return null
        return read("events") { it.mapObjects(::eventFromJson) }?.filter { it.start < to && it.end > from }
    }

    fun putEvents(from: Long, to: Long, events: List<CalendarEvent>) {
        prefs.edit().putString("eventsRange", "$from,$to").apply()
        write("events", JSONArray(events.map(::eventJson)))
    }

    private fun <T> read(key: String, parse: (JSONArray) -> T): T? =
        prefs.getString(key, null)?.let { text -> runCatching { parse(JSONArray(text)) }.getOrNull() }

    private fun write(key: String, value: JSONArray) = prefs.edit().putString(key, value.toString()).apply()

    // --- what waits to be sent ----------------------------------------------------------------------

    /** The items of [listId] as they will be once what waits is sent. */
    fun withPending(listId: String, items: List<ListItem>): List<ListItem> {
        var shown = items
        for (change in _pending.value) {
            when (change) {
                is Change.ListAdd -> if (change.listId == listId) {
                    shown = listOf(ListItem(change.id, change.name, change.completed, 0, waiting = true)) + shown
                }
                is Change.ListDone -> if (change.listId == listId) {
                    shown = shown.map { if (it.id == change.itemId) it.copy(completed = change.completed, waiting = true) else it }
                }
                is Change.ListDelete -> if (change.listId == listId) shown = shown.filter { it.id != change.itemId }
                else -> Unit
            }
        }
        return shown
    }

    /** The events as they will be once what waits is sent. */
    fun withPending(events: List<CalendarEvent>): List<CalendarEvent> {
        var shown = events
        for (change in _pending.value) {
            when (change) {
                is Change.EventCreate -> shown = shown + change.input.toEvent(change.id, change.calendar)
                is Change.EventUpdate -> shown = shown.map {
                    if (it.id == change.eventId && it.calendar == change.calendar) {
                        change.input.toEvent(it.id, change.moveTo ?: it.calendar).copy(recurring = it.recurring)
                    } else {
                        it
                    }
                }
                is Change.EventDelete -> shown = shown.filter { !(it.id == change.eventId && it.calendar == change.calendar) }
                else -> Unit
            }
        }
        return shown.sortedBy { it.start }
    }

    fun addItem(listId: String, name: String) = change { it + Change.ListAdd(LOCAL + UUID.randomUUID(), listId, name, false) }

    fun setItemDone(listId: String, item: ListItem, completed: Boolean) = change { list ->
        val added = list.find { it is Change.ListAdd && it.id == item.id }
        if (added is Change.ListAdd) {
            list.map { if (it === added) added.copy(completed = completed) else it }
        } else {
            // Ticked and unticked without internet is nothing to send.
            val earlier = list.filterIsInstance<Change.ListDone>().find { it.listId == listId && it.itemId == item.id }
            val rest = list - listOfNotNull(earlier)
            if (earlier != null && earlier.original == completed) rest
            else rest + Change.ListDone(listId, item.id, item.version, completed, original = earlier?.original ?: item.completed)
        }
    }

    fun deleteItem(listId: String, item: ListItem) = change { list ->
        if (list.any { it is Change.ListAdd && it.id == item.id }) {
            list.filterNot { it is Change.ListAdd && it.id == item.id }
        } else {
            list.filterNot { it is Change.ListDone && it.listId == listId && it.itemId == item.id } + Change.ListDelete(listId, item.id, item.version)
        }
    }

    fun createEvent(calendar: String, input: EventInput) = change { it + Change.EventCreate(LOCAL + UUID.randomUUID(), calendar, input) }

    fun updateEvent(event: CalendarEvent, input: EventInput, moveTo: String?) = change { list ->
        val created = list.find { it is Change.EventCreate && it.id == event.id }
        if (created is Change.EventCreate) {
            list.map { if (it === created) created.copy(calendar = moveTo ?: created.calendar, input = input) else it }
        } else {
            val earlier = list.filterIsInstance<Change.EventUpdate>().find { it.eventId == event.id && it.calendar == event.calendar }
            (list - listOfNotNull(earlier)) + Change.EventUpdate(event.calendar, event.id, input, moveTo?.takeIf { it != event.calendar })
        }
    }

    fun deleteEvent(event: CalendarEvent) = change { list ->
        if (list.any { it is Change.EventCreate && it.id == event.id }) {
            list.filterNot { it is Change.EventCreate && it.id == event.id }
        } else {
            list.filterNot { it is Change.EventUpdate && it.eventId == event.id && it.calendar == event.calendar } +
                Change.EventDelete(event.calendar, event.id)
        }
    }

    private fun change(edit: (List<Change>) -> List<Change>) {
        val next = edit(_pending.value)
        _pending.value = next
        prefs.edit().putString(KEY_PENDING, JSONArray(next.map { it.toJson() }).toString()).apply()
        if (next.isNotEmpty()) schedule()
    }

    /** Has WorkManager send what waits once there is a connection, should the app be closed by then. */
    private fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Sends what waits, oldest first, and stops at the first change that finds no connection: the
     * rest goes with it next time. True when nothing is left.
     */
    suspend fun flush(): Boolean = mutex.withLock {
        if (_pending.value.isEmpty()) return@withLock true
        val api = Alexa.get(app).api
        val calendar = GoogleCalendar()
        var sentSome = false
        while (true) {
            val change = _pending.value.firstOrNull() ?: break
            try {
                send(change, api, calendar)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                break
            } catch (e: NotLoggedInException) {
                break
            } catch (e: Exception) {
                // Refused: sending it again would be refused again.
                Quick.toast(app, str(R.string.offline_refused, change.label(), e.message ?: str(R.string.something_wrong)))
            }
            keep(change)
            change { list -> list.filter { it !== change } }
            sentSome = true
        }
        if (sentSome) _sent.tryEmit(Unit)
        _pending.value.isEmpty()
    }

    private suspend fun send(change: Change, api: AlexaApi, calendar: GoogleCalendar) {
        when (change) {
            is Change.ListAdd -> {
                api.addListItem(change.listId, change.name)
                if (change.completed) {
                    // Added and ticked off without internet: the new item has an id only now.
                    api.listItems(change.listId).firstOrNull { it.name == change.name && !it.completed }
                        ?.let { api.setListItemDone(change.listId, it, true) }
                }
            }
            is Change.ListDone -> {
                val item = ListItem(change.itemId, "", !change.completed, change.version)
                api.setListItemDone(change.listId, item, change.completed)
            }
            is Change.ListDelete -> api.deleteListItem(change.listId, ListItem(change.itemId, "", false, change.version))
            is Change.EventCreate -> calendar.create(change.calendar, change.input)
            is Change.EventUpdate -> calendar.update(event(change.calendar, change.eventId), change.input, change.moveTo)
            is Change.EventDelete -> calendar.delete(event(change.calendar, change.eventId))
        }
    }

    /** Writes a change just sent into the copy kept, so that it still shows until the reading after it. */
    private fun keep(change: Change) {
        when (change) {
            is Change.ListAdd -> items(change.listId)?.let { putItems(change.listId, listOf(ListItem(change.id, change.name, change.completed, 0)) + it) }
            is Change.ListDone -> items(change.listId)?.let { list -> putItems(change.listId, list.map { if (it.id == change.itemId) it.copy(completed = change.completed) else it }) }
            is Change.ListDelete -> items(change.listId)?.let { list -> putItems(change.listId, list.filter { it.id != change.itemId }) }
            else -> keptEvents()?.let { (range, events) ->
                val next = when (change) {
                    is Change.EventCreate -> events + change.input.toEvent(change.id, change.calendar).copy(waiting = false)
                    is Change.EventUpdate -> events.map {
                        if (it.id == change.eventId && it.calendar == change.calendar) change.input.toEvent(it.id, change.moveTo ?: it.calendar).copy(waiting = false) else it
                    }
                    is Change.EventDelete -> events.filter { !(it.id == change.eventId && it.calendar == change.calendar) }
                    else -> events
                }
                putEvents(range.first, range.second, next)
            }
        }
    }

    private fun keptEvents(): Pair<Pair<Long, Long>, List<CalendarEvent>>? {
        val range = prefs.getString("eventsRange", null)?.split(",")?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 } ?: return null
        val events = read("events") { it.mapObjects(::eventFromJson) } ?: return null
        return (range[0] to range[1]) to events
    }

    private fun event(calendar: String, id: String) = CalendarEvent(id, calendar, "", "", "", false, 0, 0, false, true)

    /** What was made without internet has an id of its own until it is sent. */
    const val LOCAL = "local:"
    private const val KEY_PENDING = "pending"
    private const val WORK = "offline-outbox"
}

/** A change made without internet, as it waits to be sent. */
sealed interface Change {
    fun toJson(): JSONObject
    fun label(): String

    data class ListAdd(val id: String, val listId: String, val name: String, val completed: Boolean) : Change {
        override fun toJson(): JSONObject = JSONObject().put("kind", "listAdd").put("id", id).put("listId", listId).put("name", name).put("completed", completed)
        override fun label() = name
    }

    /** [original] is how the item was before, to tell a tick undone without internet. */
    data class ListDone(val listId: String, val itemId: String, val version: Long, val completed: Boolean, val original: Boolean) : Change {
        override fun toJson(): JSONObject = JSONObject().put("kind", "listDone").put("listId", listId).put("itemId", itemId)
            .put("version", version).put("completed", completed).put("original", original)
        override fun label() = Offline.items(listId)?.find { it.id == itemId }?.name ?: itemId
    }

    data class ListDelete(val listId: String, val itemId: String, val version: Long) : Change {
        override fun toJson(): JSONObject = JSONObject().put("kind", "listDelete").put("listId", listId).put("itemId", itemId).put("version", version)
        override fun label() = Offline.items(listId)?.find { it.id == itemId }?.name ?: itemId
    }

    data class EventCreate(val id: String, val calendar: String, val input: EventInput) : Change {
        override fun toJson(): JSONObject = JSONObject().put("kind", "eventCreate").put("id", id).put("calendar", calendar).put("input", inputJson(input))
        override fun label() = input.title
    }

    data class EventUpdate(val calendar: String, val eventId: String, val input: EventInput, val moveTo: String?) : Change {
        override fun toJson(): JSONObject = JSONObject().put("kind", "eventUpdate").put("calendar", calendar).put("eventId", eventId)
            .put("input", inputJson(input)).put("moveTo", moveTo ?: JSONObject.NULL)
        override fun label() = input.title
    }

    data class EventDelete(val calendar: String, val eventId: String) : Change {
        override fun toJson(): JSONObject = JSONObject().put("kind", "eventDelete").put("calendar", calendar).put("eventId", eventId)
        override fun label() = eventId
    }

    companion object {
        fun fromJson(o: JSONObject): Change = when (o.getString("kind")) {
            "listAdd" -> ListAdd(o.getString("id"), o.getString("listId"), o.getString("name"), o.optBoolean("completed"))
            "listDone" -> ListDone(o.getString("listId"), o.getString("itemId"), o.optLong("version", 1), o.optBoolean("completed"), o.optBoolean("original"))
            "listDelete" -> ListDelete(o.getString("listId"), o.getString("itemId"), o.optLong("version", 1))
            "eventCreate" -> EventCreate(o.getString("id"), o.getString("calendar"), inputFromJson(o.getJSONObject("input")))
            "eventUpdate" -> EventUpdate(o.getString("calendar"), o.getString("eventId"), inputFromJson(o.getJSONObject("input")),
                if (o.isNull("moveTo")) null else o.optString("moveTo"))
            "eventDelete" -> EventDelete(o.getString("calendar"), o.getString("eventId"))
            else -> error("unknown change")
        }
    }
}

/** Sends the changes made without internet once there is a connection, the app open or not. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (Offline.flush()) Result.success() else Result.retry()
}

private fun EventInput.toEvent(id: String, calendar: String) =
    CalendarEvent(id, calendar, title, location, notes, allDay, start, end, recurring = false, editable = true, waiting = true)

private fun listJson(it: AlexaList) = JSONObject().put("id", it.id).put("name", it.name).put("type", it.type).put("active", it.active ?: JSONObject.NULL)

private fun itemJson(it: ListItem) = JSONObject().put("id", it.id).put("name", it.name).put("completed", it.completed).put("version", it.version)

private fun calendarJson(it: CalendarInfo) = JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
    .put("account", it.account).put("primary", it.primary).put("writable", it.writable).put("shown", it.shown)

private fun calendarFromJson(o: JSONObject) = CalendarInfo(
    o.getString("id"), o.optString("name"), o.optLong("color"), o.optString("account"),
    o.optBoolean("primary"), o.optBoolean("writable"), o.optBoolean("shown", true),
)

internal fun eventJson(it: CalendarEvent) = JSONObject().put("id", it.id).put("calendar", it.calendar).put("title", it.title)
    .put("location", it.location).put("notes", it.notes).put("allDay", it.allDay).put("start", it.start).put("end", it.end)
    .put("recurring", it.recurring).put("editable", it.editable)

internal fun eventFromJson(o: JSONObject) = CalendarEvent(
    o.getString("id"), o.getString("calendar"), o.optString("title"), o.optString("location"), o.optString("notes"),
    o.optBoolean("allDay"), o.optLong("start"), o.optLong("end"), o.optBoolean("recurring"), o.optBoolean("editable", true),
)

private fun inputJson(it: EventInput) = JSONObject().put("title", it.title).put("location", it.location).put("notes", it.notes)
    .put("allDay", it.allDay).put("start", it.start).put("end", it.end)

private fun inputFromJson(o: JSONObject) = EventInput(
    o.optString("title"), o.optString("location"), o.optString("notes"), o.optBoolean("allDay"), o.optLong("start"), o.optLong("end"),
)
