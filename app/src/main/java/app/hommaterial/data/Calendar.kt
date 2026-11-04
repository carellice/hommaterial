package app.hommaterial.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.hommaterial.R
import app.hommaterial.str
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A calendar to show events of: [writable] when events can be added to it, [shown] when shown until the user chooses. */
data class CalendarInfo(
    val id: String,
    val name: String,
    val color: Long,
    val account: String,
    val primary: Boolean,
    val writable: Boolean,
    val shown: Boolean,
)

/**
 * An event, each repetition on its own. [start] and [end] are epoch milliseconds; for a whole day
 * they are the midnights of the home, the end not included. [editable] is false for a repetition
 * kept by the phone, which the app does not change; a calendar of others is told by [CalendarInfo.writable].
 */
data class CalendarEvent(
    val id: String,
    val calendar: String,
    val title: String,
    val location: String,
    val notes: String,
    val allDay: Boolean,
    val start: Long,
    val end: Long,
    val recurring: Boolean,
    val editable: Boolean,
) {
    /** Tells one repetition from another of the same event. */
    val key: String get() = "$calendar|$id|$start"
}

/** What the user writes of an event, to create or change it. */
data class EventInput(val title: String, val location: String, val notes: String, val allDay: Boolean, val start: Long, val end: Long)

/** Where the calendars come from: Google Calendar through the home server, or the calendars of the phone. */
interface CalendarSource {
    /** Whether it can be read now: Google linked on the server, or the permission given on the phone. */
    val ready: Boolean

    suspend fun calendars(): List<CalendarInfo>
    suspend fun events(calendars: List<String>, from: Long, to: Long): List<CalendarEvent>
    suspend fun create(calendar: String, input: EventInput)
    suspend fun update(event: CalendarEvent, input: EventInput, moveTo: String?)
    suspend fun delete(event: CalendarEvent)

    companion object {
        fun of(context: Context): CalendarSource = if (ServerSync.active) GoogleCalendar() else DeviceCalendar(context.applicationContext)
    }
}

private val zone: ZoneId get() = ZoneId.systemDefault()

/** Google Calendar on the home server, which keeps the keys and the sign-in (server/src/calendar.ts). */
class GoogleCalendar : CalendarSource {
    override val ready: Boolean get() = ServerSync.calendarLinked

    override suspend fun calendars(): List<CalendarInfo> =
        (ServerSync.call("GET", "/api/calendar/calendars").optJSONArray("calendars") ?: JSONArray()).mapObjects {
            CalendarInfo(
                id = it.getString("id"),
                name = it.optString("name"),
                color = parseColor(it.optString("color")),
                account = ServerSync.calendarAccount,
                primary = it.optBoolean("primary"),
                writable = it.optBoolean("writable"),
                shown = it.optBoolean("shown", true),
            )
        }

    override suspend fun events(calendars: List<String>, from: Long, to: Long): List<CalendarEvent> {
        if (calendars.isEmpty()) return emptyList()
        val query = "calendars=${ServerSync.encode(calendars.joinToString(","))}" +
            "&from=${ServerSync.encode(moment(from))}&to=${ServerSync.encode(moment(to))}"
        return (ServerSync.call("GET", "/api/calendar/events?$query").optJSONArray("events") ?: JSONArray()).mapObjects {
            val allDay = it.optBoolean("allDay")
            CalendarEvent(
                id = it.getString("id"),
                calendar = it.getString("calendar"),
                title = it.optString("title"),
                location = it.optString("location"),
                notes = it.optString("notes"),
                allDay = allDay,
                start = parse(it.optString("start"), allDay),
                end = parse(it.optString("end"), allDay),
                recurring = it.optBoolean("recurring"),
                // Google changes a repetition alone.
                editable = true,
            )
        }
    }

    override suspend fun create(calendar: String, input: EventInput) {
        ServerSync.call("POST", "/api/calendar/events", JSONObject().put("calendar", calendar).put("event", json(input)))
    }

    override suspend fun update(event: CalendarEvent, input: EventInput, moveTo: String?) {
        val body = JSONObject().put("event", json(input))
        if (moveTo != null && moveTo != event.calendar) body.put("moveTo", moveTo)
        ServerSync.call("PUT", "/api/calendar/events/${ServerSync.encode(event.calendar)}/${ServerSync.encode(event.id)}", body)
    }

    override suspend fun delete(event: CalendarEvent) {
        ServerSync.call("DELETE", "/api/calendar/events/${ServerSync.encode(event.calendar)}/${ServerSync.encode(event.id)}")
    }

    private fun json(input: EventInput) = JSONObject()
        .put("title", input.title)
        .put("location", input.location)
        .put("notes", input.notes)
        .put("allDay", input.allDay)
        .put("start", if (input.allDay) date(input.start) else moment(input.start))
        .put("end", if (input.allDay) date(input.end) else moment(input.end))

    private fun moment(at: Long) = OffsetDateTime.ofInstant(Instant.ofEpochMilli(at), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun date(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

    private fun parse(value: String, allDay: Boolean): Long = runCatching {
        if (allDay) LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli()
        else OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun parseColor(hex: String): Long =
        hex.removePrefix("#").toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFF4285F4
}

/**
 * The calendars of the phone (CalendarContract): those of the Google account too, kept in sync by
 * Android. A repetition of an event is shown but not changed: the phone keeps the series as one.
 */
class DeviceCalendar(private val context: Context) : CalendarSource {
    override val ready: Boolean get() = granted(context)

    private val resolver get() = context.contentResolver

    override suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val found = mutableListOf<CalendarInfo>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                found += CalendarInfo(
                    id = c.getLong(0).toString(),
                    name = c.getString(1).orEmpty(),
                    color = 0xFF000000 or (c.getInt(2).toLong() and 0xFFFFFF),
                    account = c.getString(3).orEmpty(),
                    primary = c.getInt(6) == 1,
                    writable = c.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    shown = c.getInt(5) == 1,
                )
            }
        }
        found.sortedWith(compareBy({ it.account }, { !it.primary }, { it.name.lowercase() }))
    }

    override suspend fun events(calendars: List<String>, from: Long, to: Long): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (calendars.isEmpty()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.ORIGINAL_ID,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val marks = calendars.joinToString(",") { "?" }
        val found = mutableListOf<CalendarEvent>()
        resolver.query(uri, projection, "${CalendarContract.Instances.CALENDAR_ID} IN ($marks)", calendars.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(5) == 1
                val recurring = !c.getString(8).isNullOrEmpty() || !c.isNull(9)
                val calendar = c.getLong(1).toString()
                found += CalendarEvent(
                    id = c.getLong(0).toString(),
                    calendar = calendar,
                    title = c.getString(2).orEmpty(),
                    location = c.getString(3).orEmpty(),
                    notes = c.getString(4).orEmpty(),
                    allDay = allDay,
                    // A whole day is kept at the midnights of UTC: it is the same day here.
                    start = if (allDay) utcToLocal(c.getLong(6)) else c.getLong(6),
                    end = if (allDay) utcToLocal(c.getLong(7)) else c.getLong(7),
                    recurring = recurring,
                    editable = !recurring,
                )
            }
        }
        found
    }

    override suspend fun create(calendar: String, input: EventInput) {
        withContext(Dispatchers.IO) {
            resolver.insert(CalendarContract.Events.CONTENT_URI, values(input).apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar.toLong())
            }) ?: throw Exception(str(R.string.calendar_save_failed))
        }
    }

    override suspend fun update(event: CalendarEvent, input: EventInput, moveTo: String?) {
        withContext(Dispatchers.IO) {
            // Another calendar may belong to another account: the event is made again there.
            if (moveTo != null && moveTo != event.calendar) {
                create(moveTo, input)
                delete(event)
                return@withContext
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id.toLong())
            if (resolver.update(uri, values(input), null, null) == 0) throw Exception(str(R.string.calendar_event_gone))
        }
    }

    override suspend fun delete(event: CalendarEvent) {
        withContext(Dispatchers.IO) {
            resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id.toLong()), null, null)
        }
    }

    private fun values(input: EventInput) = ContentValues().apply {
        put(CalendarContract.Events.TITLE, input.title)
        put(CalendarContract.Events.EVENT_LOCATION, input.location)
        put(CalendarContract.Events.DESCRIPTION, input.notes)
        put(CalendarContract.Events.ALL_DAY, if (input.allDay) 1 else 0)
        put(CalendarContract.Events.DTSTART, if (input.allDay) localToUtc(input.start) else input.start)
        put(CalendarContract.Events.DTEND, if (input.allDay) localToUtc(input.end) else input.end)
        put(CalendarContract.Events.EVENT_TIMEZONE, if (input.allDay) "UTC" else zone.id)
    }

    private fun utcToLocal(at: Long) =
        Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private fun localToUtc(at: Long) =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        fun granted(context: Context) = PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
