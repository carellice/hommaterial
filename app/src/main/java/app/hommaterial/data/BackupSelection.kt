package app.hommaterial.data

import app.hommaterial.R
import org.json.JSONObject

/** Independent parts of a portable backup; excluded parts keep their local values. */
enum class BackupSection(val title: Int, vararg val fields: String) {
    MACROS(R.string.s_macros, "macros"),
    GROUPS(R.string.s_groups, "groups"),
    ROOMS(R.string.s_rooms, "rooms", "roomList"),
    FAVORITES(R.string.favorites, "favorites"),
    HIDDEN(R.string.backup_hidden, "hidden", "hiddenRemotes"),
    TILES(R.string.backup_tiles, "order", "tileSpans"),
    REMOTES(R.string.s_remotes, "remoteButtons", "lgTvs"),
    SCHEDULES(R.string.s_schedules, "schedules"),
    AUTOMATIONS(R.string.s_automations, "automations"),
    ALERTS(R.string.alerts_title, "alerts"),
    SHORTCUTS(R.string.backup_shortcuts, "shortcuts", "widget", "tiles"),
    SETTINGS(R.string.backup_settings, "autoUpdate", "haptics", "hapticLevel", "theme", "layout", "language"),
    ASSISTANT(R.string.backup_assistant, "assistant"),
    MONITOR(R.string.backup_monitor_title, "monitor"),
    KEYS(R.string.backup_keys, "switchBot", "assistantKeys");
}

fun selectBackup(current: JSONObject, imported: JSONObject, selected: Set<BackupSection>): JSONObject {
    val merged = JSONObject(current.toString())
    for (section in selected) for (field in section.fields) {
        if (imported.has(field)) merged.put(field, imported.get(field))
    }
    return JSONObject(merged.toString())
}

/** Parse every structured value before the first preference or scheduled job is changed. */
fun validateBackup(backup: JSONObject) {
    for (name in listOf("favorites", "hidden", "roomList", "hiddenRemotes")) {
        backup.optJSONArray(name)?.let { array -> repeat(array.length()) { array.getString(it) } }
    }
    backup.optJSONArray("groups")?.mapObjects(Group::fromJson)
    backup.optJSONArray("macros")?.mapObjects(Macro::fromJson)
    backup.optJSONArray("schedules")?.mapObjects(Schedule::fromJson)
    backup.optJSONArray("automations")?.mapObjects(Automation::fromJson)
    backup.optJSONArray("lgTvs")?.mapObjects(LgTv::fromJson)
    backup.optJSONObject("widget")?.let(WidgetConfig::fromJson)
    backup.optJSONObject("monitor")?.let(MonitorConfig::fromJson)
    backup.optJSONObject("assistant")?.optJSONArray("notes")?.mapObjects(AssistantNote::fromJson)
    for (name in listOf("order", "remoteButtons")) backup.optJSONObject(name)?.let { values ->
        for (key in values.keys()) values.getJSONArray(key).let { array -> repeat(array.length()) { array.getString(it) } }
    }
    backup.optJSONObject("rooms")?.let { rooms -> for (key in rooms.keys()) rooms.getString(key) }
    backup.optJSONObject("alerts")?.let { alerts -> for (key in alerts.keys()) {
        val alert = alerts.getJSONObject(key)
        for (bound in listOf("above", "below")) if (!alert.isNull(bound)) require(alert.getDouble(bound).isFinite())
    } }
}

fun tileSpans(json: JSONObject?): Map<String, Int> = json?.keys()?.asSequence()
    ?.associateWith { json.optInt(it, 1).coerceIn(1, 2) }.orEmpty()
