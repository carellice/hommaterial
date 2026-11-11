package app.hommaterial.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A page found on the web: its [title], its [url] and a piece of its text. */
data class WebResult(val title: String, val url: String, val content: String)

/** What a search found: Tavily's own short [answer], when it gave one, and the pages. */
data class WebFound(val answer: String, val results: List<WebResult>)

class WebSearchException(message: String) : Exception(message)

/** Searches the web with Tavily, with the free key of the user, from app.tavily.com. */
class Tavily(private val http: OkHttpClient, private val base: String = "https://api.tavily.com") {
    suspend fun search(key: String, query: String): WebFound = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("query", query)
            .put("search_depth", "basic")
            .put("include_answer", true)
            .put("max_results", MAX_RESULTS)
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
        const val MAX_RESULTS = 5
    }
}

/** The answer and the pages of a reply of Tavily; pages without an address are left out. */
fun parseTavily(json: JSONObject): WebFound {
    val list = json.optJSONArray("results")
    val results = (0 until (list?.length() ?: 0)).mapNotNull { i ->
        val o = list!!.optJSONObject(i) ?: return@mapNotNull null
        val url = o.optString("url").trim()
        if (!url.startsWith("http")) return@mapNotNull null
        WebResult(o.optString("title").trim().ifEmpty { url }, url, o.optString("content").trim())
    }
    val answer = if (json.isNull("answer")) "" else json.optString("answer").trim()
    return WebFound(answer, results)
}

/** The instructions to answer [query] from what was [found], in [language], to be read aloud. */
fun webAnswerPrompt(query: String, found: WebFound, language: String): String = buildString {
    append("Answer in $language the question below from these web search results only, in three or four short plain sentences ")
    append("to be read aloud: no lists, no markdown, no links. If the results do not answer it, say so briefly. ")
    append("Return JSON {\"text\": the answer}.\n\nQuestion: ").append(query).append("\n\nResults:\n")
    found.results.forEachIndexed { i, r -> append("${i + 1}. ${r.title}: ${r.content.take(1200)}\n") }
}
