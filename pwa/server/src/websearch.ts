// The web search of the assistant (data/WebSearch.kt): Tavily, with the free key of the account, kept
// here so that the web app and the phones linked to the server share it. The pages are summed up by
// the assistant when there is one, else Tavily's own answer is given.
import { str } from '../../shared/i18n.js'
import { getSecret, setSecret } from './db.ts'
import { HttpError, request } from './errors.ts'
import * as assistant from './assistant.ts'

const MAX_RESULTS = 8

export const key = () => getSecret<string | null>('tavily', null)
export const setKey = (value: string) => setSecret('tavily', value.trim() || null)

type Found = { answer: string; results: { title: string; url: string; content: string; date: string }[] }

/** The answer and the pages of a reply of Tavily; pages without an address are left out. */
export function parseTavily(json: any): Found {
  const results = (Array.isArray(json?.results) ? json.results : [])
    .map((r: any) => ({
      title: String(r?.title ?? '').trim(), url: String(r?.url ?? '').trim(), content: String(r?.content ?? '').trim(),
      date: String(r?.published_date ?? '').trim().slice(0, 16),
    }))
    .filter((r: { url: string }) => r.url.startsWith('http'))
    .map((r: Found['results'][number]) => ({ ...r, title: r.title || r.url }))
  return { answer: json?.answer == null ? '' : String(json.answer).trim(), results }
}

const NEWS_WORDS = /\b(notizi\w*|news|giornal[ei]|cronaca|ultim[aei]\s+(ore|novit\w*|notizi\w*)|headlines?|latest)\b/
const DAY_WORDS = /\b(oggi|odiern\w*|stamattina|stamani|stasera|stanotte|today|tonight)\b/
const WEEK_WORDS = /\b(ieri|settimana|giorni|yesterday|week|days)\b/
const MONTH_WORDS = /\b(mese|month)\b/

/** The scope of a query: the news, and how far back, as webSearchScope in WebSearch.kt. */
export function webSearchScope(query: string): { topic: string; timeRange: string | null } {
  const q = query.toLowerCase()
  const news = NEWS_WORDS.test(q)
  const timeRange = DAY_WORDS.test(q) ? 'day' : WEEK_WORDS.test(q) ? 'week' : MONTH_WORDS.test(q) ? 'month' : news ? 'week' : null
  return { topic: news ? 'news' : 'general', timeRange }
}

/** Kept word for word the same as webAnswerPrompt in WebSearch.kt; today is "YYYY-MM-DD". */
export function webAnswerPrompt(query: string, found: Found, language: string, today: string): string {
  let text = `Answer in ${language} the question below from these web search results only, in up to six short plain sentences `
  text += `to be read aloud: no lists, no markdown, no links. Today is ${today}. When the question is about a time (today, `
  text += 'this week, the latest), use only results of that time and leave out older ones; for the news, give the main '
  text += 'different facts, one sentence each. If the results do not answer it, say so briefly. '
  text += `Return JSON {"text": the answer}.\n\nQuestion: ${query}\n\nResults:\n`
  found.results.forEach((r, i) => { text += `${i + 1}. ${r.title}${r.date ? ` (${r.date})` : ''}: ${r.content.slice(0, 1200)}\n` })
  return text
}

/** The day of the server, as "YYYY-MM-DD". */
const today = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Looks [query] up on the web: a short answer, read aloud, and the pages it comes from. */
export async function search(query: string): Promise<{ reply: string; links: { title: string; url: string }[] }> {
  const secret = key()
  if (!secret) throw new HttpError(400, str('web_search_no_key'))
  const scope = webSearchScope(query)
  const response = await request('https://api.tavily.com/search', {
    method: 'POST',
    headers: { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query, topic: scope.topic, search_depth: 'advanced', include_answer: 'advanced', max_results: MAX_RESULTS,
      ...(scope.timeRange ? { time_range: scope.timeRange } : {}),
    }),
  }, 30_000)
  if (response.status === 401 || response.status === 403) throw new HttpError(400, str('web_search_bad_key'))
  if ([429, 432, 433].includes(response.status)) throw new HttpError(429, str('web_search_limit'))
  if (!response.ok) throw new HttpError(502, str('something_wrong'))
  const found = parseTavily(await response.json().catch(() => null))
  let summed = ''
  if (assistant.configured() && found.results.length) {
    try {
      summed = await assistant.correct(webAnswerPrompt(query, found, str('assistant_language_name'), today()), [query])
    } catch {
      // Without the assistant, Tavily's own answer.
    }
  }
  const reply = summed || found.answer || str(found.results.length ? 'web_search_pages' : 'web_search_nothing')
  return { reply, links: found.results.map((r) => ({ title: r.title, url: r.url })) }
}
