package app.hommaterial.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * How the lists show in the app only: names given here, lists joined into one, and their order. Alexa and Notion
 * never see it. On the home server it is the same on every device. The web app does the same in
 * pwa/shared/lists.js: keep them alike.
 */
data class ListViews(
    /** The name given in the app, by list id, or by the id of joined lists. */
    val names: Map<String, String> = emptyMap(),
    /** The lists joined, each group by the ids of its lists, the first one taking what is added. */
    val merged: List<List<String>> = emptyList(),
    /** The ids of the lists shown, in the order chosen; those not in it follow, as Alexa gives them. */
    val order: List<String> = emptyList(),
    /** Whether the lists of Alexa show; off, only those of Notion. */
    val alexa: Boolean = true,
    /** The id of the list things to do go to, and of the one for the shopping; null for those of Alexa. */
    val todo: String? = null,
    val shopping: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("names", JSONObject(names))
        .put("merged", JSONArray(merged.map { JSONArray(it) }))
        .put("order", JSONArray(order))
        .put("alexa", alexa)
        .put("todo", todo ?: JSONObject.NULL)
        .put("shopping", shopping ?: JSONObject.NULL)

    /** The list of [type] (TO_DO or SHOPPING_LIST) among [lists]: the one chosen, else that of Alexa. */
    fun defaultOf(lists: List<AlexaList>, type: String): AlexaList? {
        val chosen = if (type == TO_DO) todo else shopping
        return lists.find { it.id == chosen } ?: lists.find { it.type == type }
    }

    /** The lists as shown: renamed, those joined as one in the place of the first of them. */
    fun apply(lists: List<AlexaList>, nameOf: (AlexaList) -> String): List<AlexaList> {
        val byId = lists.associateBy { it.id }
        val done = mutableSetOf<String>()
        val out = mutableListOf<AlexaList>()
        for (list in lists) {
            if (list.id in done) continue
            val group = merged.find { list.id in it }?.filter { it in byId }
            if (group == null || group.size < 2) {
                out += list.copy(name = names[list.id] ?: list.name)
                continue
            }
            done += group
            val members = group.map { byId.getValue(it) }
            val id = idOf(group)
            val active = members.map { it.active }.takeIf { counts -> counts.all { it != null } }?.sumOf { it!! }
            out += AlexaList(id, names[id] ?: members.joinToString(" + ") { nameOf(it) }, MERGED, active)
        }
        return out.sortedBy { list -> order.indexOf(list.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }

    /** Moves the list at [from] of those [shown] to [to]. */
    fun move(shown: List<String>, from: Int, to: Int): ListViews {
        if (from !in shown.indices || to !in shown.indices) return this
        val next = shown.toMutableList().apply { add(to, removeAt(from)) }
        return copy(order = next)
    }

    /** Joins [ids] into one list; a list already joined leaves its old group. */
    fun merge(ids: List<String>): ListViews {
        val rest = merged.map { group -> group - ids.toSet() }.filter { it.size >= 2 }
        return copy(merged = if (ids.size >= 2) rest + listOf(ids) else rest)
    }

    /** Parts again the lists joined as [id], forgetting the name given to them. */
    fun split(id: String): ListViews = copy(merged = merged.filter { idOf(it) != id }, names = names - id)

    fun rename(id: String, name: String): ListViews =
        copy(names = if (name.isBlank()) names - id else names + (id to name.trim()))

    companion object {
        const val MERGED = "MERGED"
        const val TO_DO = "TO_DO"
        const val SHOPPING = "SHOPPING_LIST"
        private const val PREFIX = "merged:"

        fun idOf(group: List<String>) = PREFIX + group.joinToString("|")

        /** The lists behind a list shown: itself, or those joined in it. */
        fun members(id: String): List<String> = if (id.startsWith(PREFIX)) id.removePrefix(PREFIX).split('|') else listOf(id)

        fun fromJson(o: JSONObject?): ListViews {
            if (o == null) return ListViews()
            val names = o.optJSONObject("names") ?: JSONObject()
            val merged = o.optJSONArray("merged") ?: JSONArray()
            val order = o.optJSONArray("order") ?: JSONArray()
            return ListViews(
                names.keys().asSequence().associateWith { names.optString(it) }.filterValues { it.isNotBlank() },
                (0 until merged.length()).mapNotNull { i ->
                    merged.optJSONArray(i)?.let { a -> (0 until a.length()).map { a.optString(it) } }?.takeIf { it.size >= 2 }
                },
                (0 until order.length()).map { order.optString(it) }.filter { it.isNotBlank() },
                o.optBoolean("alexa", true),
                o.optString("todo").takeIf { !o.isNull("todo") && it.isNotBlank() },
                o.optString("shopping").takeIf { !o.isNull("shopping") && it.isNotBlank() },
            )
        }
    }
}
