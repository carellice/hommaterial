package app.hommaterial.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A page found on the web: its [title], its [url], a piece of its text and, for news, the [date] it came out. */
data class WebResult(val title: String, val url: String, val content: String, val date: String = "")

/** What a search found: Tavily's own short [answer], when it gave one, and the pages. */
data class WebFound(val answer: String, val results: List<WebResult>)

class WebSearchException(message: String) : Exception(message)

/** Searches the web with Tavily, with the free key of the user, from app.tavily.com. */
class Tavily(private val http: OkHttpClient, private val base: String = "https://api.tavily.com") {
    suspend fun search(key: String, query: String): WebFound = withContext(Dispatchers.IO) {
        val scope = webSearchScope(query)
        val body = JSONObject()
            .put("query", query)
            .put("topic", scope.topic)
            .put("search_depth", "advanced")
            .put("include_answer", "advanced")
            .put("max_results", MAX_RESULTS)
        scope.timeRange?.let { body.put("time_range", it) }
        val request = Request.Builder()
            .url("$base/search")
            .header("Authorization", "Bearer ${key.trim()}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when (response.code) {
                401, 403 -> throw WebSearchException("unauthorized")
                429, 432, 433 -> throw WebSearchException("limit")
            }
            if (!response.isSuccessful) throw WebSearchException("error ${response.code}")
            parseTavily(JSONObject(text))
        }
    }

    private companion object {
        const val MAX_RESULTS = 8
    }
}

/** The answer and the pages of a reply of Tavily; pages without an address are left out. */
fun parseTavily(json: JSONObject): WebFound {
    val list = json.optJSONArray("results")
    val results = (0 until (list?.length() ?: 0)).mapNotNull { i ->
        val o = list!!.optJSONObject(i) ?: return@mapNotNull null
        val url = o.optString("url").trim()
        if (!url.startsWith("http")) return@mapNotNull null
        WebResult(o.optString("title").trim().ifEmpty { url }, url, o.optString("content").trim(), o.optString("published_date").trim().take(16))
    }
    val answer = if (json.isNull("answer")) "" else json.optString("answer").trim()
    return WebFound(answer, results)
}

/** How Tavily is asked: [topic] "news" for the news, [timeRange] "day", "week" or "month" when the question says when. */
data class WebScope(val topic: String, val timeRange: String?)

private val NEWS_WORDS = Regex("""\b(notizi\w*|news|giornal[ei]|cronaca|ultim[aei]\s+(ore|novit\w*|notizi\w*)|headlines?|latest)\b""")
private val DAY_WORDS = Regex("""\b(oggi|odiern\w*|stamattina|stamani|stasera|stanotte|today|tonight)\b""")
private val WEEK_WORDS = Regex("""\b(ieri|settimana|giorni|yesterday|week|days)\b""")
private val MONTH_WORDS = Regex("""\b(mese|month)\b""")

/** The scope of [query]: the news, and how far back, so that "le notizie di oggi" are not old ones. Kept the same as webSearchScope in websearch.ts. */
fun webSearchScope(query: String): WebScope {
    val q = query.lowercase()
    val news = NEWS_WORDS.containsMatchIn(q)
    val range = when {
        DAY_WORDS.containsMatchIn(q) -> "day"
        WEEK_WORDS.containsMatchIn(q) -> "week"
        MONTH_WORDS.containsMatchIn(q) -> "month"
        news -> "week"
        else -> null
    }
    return WebScope(if (news) "news" else "general", range)
}

/** The instructions to answer [query] from what was [found], in [language], on the day [today] ("YYYY-MM-DD"), to be read aloud. */
fun webAnswerPrompt(query: String, found: WebFound, language: String, today: String): String = buildString {
    append("Answer in $language the question below from these web search results only, in up to six short plain sentences ")
    append("to be read aloud: no lists, no markdown, no links. Today is $today. When the question is about a time (today, ")
    append("this week, the latest), use only results of that time and leave out older ones; for the news, give the main ")
    append("different facts, one sentence each. If the results do not answer it, say so briefly. ")
    append("Return JSON {\"text\": the answer}.\n\nQuestion: ").append(query).append("\n\nResults:\n")
    found.results.forEachIndexed { i, r ->
        append("${i + 1}. ${r.title}${if (r.date.isNotEmpty()) " (${r.date})" else ""}: ${r.content.take(1200)}\n")
    }
}
