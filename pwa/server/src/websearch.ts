// The web search of the assistant (data/WebSearch.kt): Tavily, with the free key of the account, kept
// here so that the web app and the phones linked to the server share it. The pages are summed up by
// the assistant when there is one, else Tavily's own answer is given.
import { str } from '../../shared/i18n.js'
import { getSecret, setSecret } from './db.ts'
import { HttpError, request } from './errors.ts'
import * as assistant from './assistant.ts'

const MAX_RESULTS = 5

export const key = () => getSecret<string | null>('tavily', null)
export const setKey = (value: string) => setSecret('tavily', value.trim() || null)

type Found = { answer: string; results: { title: string; url: string; content: string }[] }

/** The answer and the pages of a reply of Tavily; pages without an address are left out. */
export function parseTavily(json: any): Found {
  const results = (Array.isArray(json?.results) ? json.results : [])
    .map((r: any) => ({ title: String(r?.title ?? '').trim(), url: String(r?.url ?? '').trim(), content: String(r?.content ?? '').trim() }))
    .filter((r: { url: string }) => r.url.startsWith('http'))
    .map((r: { title: string; url: string; content: string }) => ({ ...r, title: r.title || r.url }))
  return { answer: json?.answer == null ? '' : String(json.answer).trim(), results }
}

/** Kept word for word the same as webAnswerPrompt in WebSearch.kt. */
export function webAnswerPrompt(query: string, found: Found, language: string): string {
  let text = `Answer in ${language} the question below from these web search results only, in three or four short plain sentences `
  text += 'to be read aloud: no lists, no markdown, no links. If the results do not answer it, say so briefly. '
  text += `Return JSON {"text": the answer}.\n\nQuestion: ${query}\n\nResults:\n`
  found.results.forEach((r, i) => { text += `${i + 1}. ${r.title}: ${r.content.slice(0, 1200)}\n` })
  return text
}

/** Looks [query] up on the web: a short answer, read aloud, and the pages it comes from. */
export async function search(query: string): Promise<{ reply: string; links: { title: string; url: string }[] }> {
  const secret = key()
  if (!secret) throw new HttpError(400, str('web_search_no_key'))
  const response = await request('https://api.tavily.com/search', {
    method: 'POST',
    headers: { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, search_depth: 'basic', include_answer: true, max_results: MAX_RESULTS }),
  }, 30_000)
  if (response.status === 401 || response.status === 403) throw new HttpError(400, str('web_search_bad_key'))
  if ([429, 432, 433].includes(response.status)) throw new HttpError(429, str('web_search_limit'))
  if (!response.ok) throw new HttpError(502, str('something_wrong'))
  const found = parseTavily(await response.json().catch(() => null))
  let summed = ''
  if (assistant.configured() && found.results.length) {
    try {
      summed = await assistant.correct(webAnswerPrompt(query, found, str('assistant_language_name')), [query])
    } catch {
      // Without the assistant, Tavily's own answer.
    }
  }
  const reply = summed || found.answer || str(found.results.length ? 'web_search_pages' : 'web_search_nothing')
  return { reply, links: found.results.map((r) => ({ title: r.title, url: r.url })) }
}
