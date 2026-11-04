import { str } from '../../shared/i18n.js'
import { get, getSecret, setSecret } from './db.ts'
import { HttpError, OfflineError, request } from './errors.ts'

// The assistant of the Android app (Assistant.kt): Workers AI on the Cloudflare account of the
// user puts right the names in spoken commands. The account id and the token stay on the server.

/** The models of Workers AI the assistant can use, best first; all of them answer in JSON when asked. */
export const ASSISTANT_MODELS = ['@cf/meta/llama-3.3-70b-instruct-fp8-fast', '@cf/meta/llama-3.1-8b-instruct']

const BASE = 'https://api.cloudflare.com/client/v4'

type Keys = { accountId: string; token: string }
export type Note = { target: string; question: string; answer: string }

export class AssistantError extends HttpError {
  text: string
  constructor(text: string, ...args: unknown[]) {
    super(502, str(text, ...args))
    this.text = text
  }
}

export const keys = () => getSecret<Keys | null>('assistant', null)
export const configured = () => keys() != null
export const model = () => {
  const saved = getSecret<string | null>('assistant_model', null)
  return saved && ASSISTANT_MODELS.includes(saved) ? saved : ASSISTANT_MODELS[0]
}
export const setModel = (name: string) => setSecret('assistant_model', ASSISTANT_MODELS.includes(name) ? name : null)
export const connect = (accountId: string, token: string) => setSecret('assistant', { accountId, token })
/** Forgets the account; what was taught stays, for when it is linked again. */
export const disconnect = () => setSecret('assistant', null)

// --- what the assistant knows of the home ---------------------------------------------------------

type Target = {
  id: string
  /** Its key in the app, which the notes are kept under. */
  key: string
  name: string
  describe: (withState: boolean) => string
  /** What a spoken command may call it, with its room; null when it cannot be switched or run. */
  voice: { kind: string; room: string | null } | null
}

const AC_TYPES = ['air conditioner']
const remoteType = (type: string) => type.replace(/^DIY /, '').toLowerCase()

/**
 * Everything of the home, written for the model. The ids are short to spare tokens, and the app
 * alone knows what they stand for: the model can name nothing that is not in the list. The lists
 * come from the client, already without what the user hid; the notes and what was taught from here.
 */
export class Catalog {
  targets: Target[]
  notes: Note[]
  taught: Set<string>

  constructor(home: any) {
    this.notes = get<Note[]>('assistantNotes')
    this.taught = new Set(get<string[]>('assistantTaught'))
    this.targets = []
    const deviceIds = new Map<string, string>()
    const quote = (text: string) => `"${text}"`
    ;(home.devices ?? []).forEach((d: any, i: number) => {
      const id = `d${i + 1}`
      deviceIds.set(d.applianceId, id)
      const st = home.states?.[d.applianceId]
      this.targets.push({
        id, key: d.applianceId, name: d.name,
        voice: d.hasPower ? { kind: 'device', room: d.room ?? null } : null,
        describe: (withState) => {
          let text = `device ${quote(d.name)}`
          if (d.room) text += ` in ${quote(d.room)}`
          text += `, ${String(d.category ?? '').toLowerCase()}`
          const can = [d.hasPower && 'on/off', d.hasBrightness && 'brightness', d.hasColor && 'color', d.hasColorTemperature && 'white'].filter(Boolean)
          if (can.length) text += `; can ${can.join(', ')}`
          if (d.isSensor) text += '; sensor'
          if (withState && st) {
            const now = []
            if (st.reachable === false) now.push('unreachable')
            if (st.power != null) now.push(st.power ? 'on' : 'off')
            if (st.power === true && st.brightness != null) now.push(`${st.brightness}%`)
            if (typeof st.temperature === 'number' && !Number.isNaN(st.temperature)) now.push(`${st.temperature.toFixed(1)}°C`)
            if (st.humidity != null) now.push(`humidity ${st.humidity}%`)
            if (now.length) text += `; now ${now.join(' ')}`
          }
          return text
        },
      })
    })
    ;(home.groups ?? []).forEach((g: any, i: number) => {
      const members = (g.devices ?? []).map((it: string) => deviceIds.get(it)).filter(Boolean)
      if (!members.length) return
      this.targets.push({
        id: `g${i + 1}`, key: `group:${g.id}`, name: g.name, voice: null,
        describe: () => `group ${quote(g.name)} of ${members.join(', ')}`,
      })
    })
    ;(home.macros ?? []).forEach((m: any, i: number) => {
      this.targets.push({
        id: `m${i + 1}`, key: m.key, name: m.name, voice: { kind: 'custom device', room: m.room ?? null },
        describe: () => `custom device ${quote(m.name)}${m.room ? ` in ${quote(m.room)}` : ''}, runs a series of actions`,
      })
    })
    ;(home.remotes ?? []).forEach((r: any, i: number) => {
      const lg = String(r.id).startsWith('lg:')
      const keys = (r.keys ?? []) as { command: string; label: string }[]
      this.targets.push({
        id: `r${i + 1}`, key: r.key, name: r.name, voice: { kind: 'remote', room: r.room ?? null },
        describe: () => {
          let text = `remote ${quote(r.name)}${r.room ? ` in ${quote(r.room)}` : ''}, ${lg ? 'LG TV' : r.type}`
          if (AC_TYPES.includes(remoteType(r.type))) text += '; use "ac"'
          if (keys.length) text += `; keys: ${keys.map((k) => (k.label === k.command ? k.command : `${k.command}=${k.label}`)).join(', ')}`
          return text
        },
      })
    })
  }

  find(id: string) {
    return this.targets.find((it) => it.id === id.trim().toLowerCase()) ?? null
  }

  private said(target: Target) {
    const said = this.notes.filter((it) => it.target === target.key)
    return said.length ? ` — owner's notes: ${said.map((it) => (it.question.trim() ? `${it.question} ${it.answer}` : it.answer)).join(' ')}` : ''
  }

  describe(withState: boolean): string {
    return this.targets.map((t) => `${t.id}: ${t.describe(withState)}${this.taught.has(t.key) ? '' : ' [NEW]'}${this.said(t)}\n`).join('')
  }

  /**
   * What a spoken command may name, one per line: the things that can be switched or run by voice,
   * with their room and what the owner said of them, then the rooms.
   */
  names(): string {
    const rooms = new Set<string>()
    let text = ''
    for (const t of this.targets) {
      if (!t.voice) continue
      if (t.voice.room) rooms.add(t.voice.room)
      text += `"${t.name}" (${t.voice.kind}${t.voice.room ? `, in "${t.voice.room}"` : ''})${this.said(t)}\n`
    }
    if (rooms.size) text += `Rooms: ${[...rooms].map((r) => `"${r}"`).join(', ')}\n`
    return text
  }
}

/** The instructions for the first set-up, when the assistant asks about what it does not understand. */
export function questionsPrompt(catalog: Catalog, house: string, language: string): string {
  return `You are setting up the assistant of a smart home app, which will later act on these things when asked in words.
Find what you could not use with confidence from its name alone: cryptic or generic names ("Presa 2", "Plug A"),
keys of a remote whose purpose is unclear ("F1", "Tasto 3"), custom devices whose name does not say what they do,
remotes whose appliance is not obvious, rooms named after people or nicknames.
Ask at most 12 short, friendly questions in ${language}, one per thing, with "target" its id (for a key: "r2/F1").
Ask about things marked [NEW] first. Do not ask about what is clear ("Luce cucina", "TV soggiorno")
or what the owner's notes already explain. If everything is clear, return no questions.
${house.trim() ? `What the owner says about the home:\n${house.trim()}\n` : ''}
The things of the home:
${catalog.describe(false)}`.trim()
}

/**
 * The instructions for a spoken command. The model only puts right the names the speech recognizer
 * got wrong: the app then reads the sentence as it always does, and acts on nothing it does not know.
 */
export function correctionPrompt(catalog: Catalog, house: string): string {
  return `You correct the voice commands of a smart home app. The speech recognizer wrote down what the user said,
often with the names of things wrong: "televisione flavio" for "TV Flavio", "luce cucine" for "Luce cucina".
Rewrite the command so that the thing or room it is about is called exactly by its name in the list below.
Keep the verb as said (accendi, spegni, esegui, turn on, switch off…) and the language of the user.
Keep "tutto", "tutti", "all" before a room. Add nothing, explain nothing, do not translate the names.
Several guesses of the recognizer may be given, best first: use the one that makes sense with the list.
If nothing in the list fits, give back the first guess unchanged.
Example: guesses "accendi televisione flavio" give {"text":"accendi TV Flavio"}
${house.trim() ? `What the owner says about the home:\n${house.trim()}\n` : ''}
What the home has:
${catalog.names()}`.trim()
}

/** Reads the questions of the model, keeping one per thing and only about things that exist. */
export function parseQuestions(answer: any, catalog: Catalog, max = 12) {
  const questions: { target: string; name: string; question: string }[] = []
  for (const o of Array.isArray(answer?.questions) ? answer.questions : []) {
    // A key of a remote may come as "r2/F1": the question is about the remote all the same.
    const target = catalog.find(String(o?.target ?? '').split('/')[0])
    const question = String(o?.question ?? '').trim()
    if (!target || !question || questions.length >= max) continue
    questions.push({ target: target.key, name: target.name, question })
  }
  return questions
}

// --- Workers AI ----------------------------------------------------------------------------------

const SCHEMA_TEXT = { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] }
const SCHEMA_QUESTIONS = {
  type: 'object',
  properties: {
    questions: {
      type: 'array',
      items: { type: 'object', properties: { target: { type: 'string' }, question: { type: 'string' } }, required: ['target', 'question'] },
    },
  },
  required: ['questions'],
}

/** The first JSON object in [text], which some models wrap in words or in a code block. */
export function extractJson(text: string): any {
  // Reasoning models think aloud first, inside these tags.
  const clean = text.replace(/<think>[\s\S]*?<\/think>/g, '')
  const start = clean.indexOf('{')
  const end = clean.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  try {
    return JSON.parse(clean.slice(start, end + 1))
  } catch {
    return null
  }
}

async function run(account: Keys, name: string, messages: [string, string][], schema: unknown, maxTokens: number): Promise<unknown> {
  if (!/^[0-9a-fA-F]{32}$/.test(account.accountId)) throw new AssistantError('assistant_bad_account')
  const body: Record<string, unknown> = {
    messages: messages.map(([role, content]) => ({ role, content })),
    max_tokens: maxTokens,
    temperature: 0.1,
  }
  if (schema) body.response_format = { type: 'json_schema', json_schema: schema }
  let response
  try {
    response = await request(`${BASE}/accounts/${account.accountId}/ai/run/${name}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${account.token.trim()}`, 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body),
    }, 60_000)
  } catch (e) {
    if (e instanceof OfflineError) throw e
    throw new OfflineError()
  }
  const text = await response.text()
  let json: any = null
  try {
    json = JSON.parse(text)
  } catch {
    json = null
  }
  const error = json?.errors?.[0]
  const message = String(error?.message ?? '')
  if (response.status === 401 || response.status === 403) throw new AssistantError('assistant_unauthorized')
  if (response.status === 429 || /neurons/i.test(message)) throw new AssistantError('assistant_limit')
  if (/JSON Mode/i.test(message)) throw new AssistantError('assistant_json_failed')
  if (response.status === 404 || error?.code === 7003) throw new AssistantError('assistant_bad_account')
  if (json == null || !response.ok || json.success === false) throw new AssistantError('assistant_error', message || String(response.status))
  const result = json.result?.response
  if (result == null) throw new AssistantError('assistant_unreadable')
  return result
}

async function askJson(account: Keys, messages: [string, string][], schema: unknown, maxTokens: number): Promise<any> {
  let answer
  try {
    answer = await run(account, model(), messages, schema, maxTokens)
  } catch (e) {
    // When the model cannot keep to the schema, it is asked again free: the JSON is then looked for in the text.
    if (!(e instanceof AssistantError) || e.text !== 'assistant_json_failed') throw e
    answer = await run(account, model(), messages, null, maxTokens)
  }
  if (answer && typeof answer === 'object') return answer
  const found = extractJson(String(answer))
  if (!found) throw new AssistantError('assistant_unreadable')
  return found
}

/** Checks the account and the token with the smallest request there is. */
export const test = (accountId: string, token: string) => run({ accountId, token }, model(), [['user', 'Reply with OK.']], null, 4)

function account(): Keys {
  const found = keys()
  if (!found) throw new HttpError(400, str('something_wrong'))
  return found
}

/** What the user meant by the guesses of the speech recognizer [heard], with the names put right. */
export async function correct(system: string, heard: string[]): Promise<string> {
  const guesses = heard.map((it, i) => `${i + 1}. ${it}`).join('\n')
  const answer = await askJson(account(), [['system', system], ['user', guesses]], SCHEMA_TEXT, 120)
  return String(answer?.text ?? '').trim()
}

export const questions = (system: string) => askJson(account(), [['system', system], ['user', 'Ask your questions.']], SCHEMA_QUESTIONS, 1500)
