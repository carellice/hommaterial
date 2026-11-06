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

/**
 * The instructions for suggesting phrases that, said to the microphone of the app, run the custom
 * device [name], which does [does] (or [otherwise] while [condition] is off). As in Assistant.kt.
 */
export function phrasesPrompt(
  name: string, does: string[], condition: string | null, otherwise: string[], phrases: string[], taken: string[], language: string,
): string {
  const list = (items: string[]) => items.map((it) => `- ${it}`).join('\n')
  return `You suggest voice phrases for a custom device of a smart home app: a button that runs a series of actions.
Saying one of the phrases to the microphone of the app runs it; only the whole phrase counts, so it must be
something a person would naturally say, in ${language}, to have exactly these actions done. No quotes, no final dot.
Give 4 "short" phrases (1 to 3 words, like "buonanotte"), 4 "medium" ones (4 to 6 words) and 4 "long" ones
(7 to 12 words, natural and specific). Keep them coherent with what the device does, varied, never generic
commands like "accendi" alone. Do not repeat the phrases it already has or those of other devices.
The custom device is called: ${name.trim() || '(no name yet)'}
What it does, in order:
${list(does) || '- (nothing yet)'}
${condition ? `That is while "${condition}" is on; while it is off it does:\n${list(otherwise)}\n` : ''}
${phrases.length ? `Its phrases already: ${phrases.join('; ')}\n` : ''}
${taken.length ? `Phrases of other devices, not to be used: ${taken.join('; ')}\n` : ''}`.trim()
}

/** Reads the phrases of the model, without those already used and each once, a few per length. */
export function parsePhrases(answer: any, taken: string[], max = 4) {
  const seen = new Set(taken.map((it) => it.trim().toLowerCase()))
  const read = (key: string) => (Array.isArray(answer?.[key]) ? answer[key] : [])
    .map((it: unknown) => String(it ?? '').trim().replace(/^["“”.]+|["“”.]+$/g, '').trim())
    .filter((it: string) => {
      if (!it || it.length > 80 || seen.has(it.toLowerCase())) return false
      seen.add(it.toLowerCase())
      return true
    })
    .slice(0, max)
  return { short: read('short'), medium: read('medium'), long: read('long') }
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

const SCHEMA_PHRASES = {
  type: 'object',
  properties: {
    short: { type: 'array', items: { type: 'string' } },
    medium: { type: 'array', items: { type: 'string' } },
    long: { type: 'array', items: { type: 'string' } },
  },
  required: ['short', 'medium', 'long'],
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

async function run(account: Keys, name: string, messages: [string, string][], schema: unknown, maxTokens: number, temperature = 0.1): Promise<unknown> {
  if (!/^[0-9a-fA-F]{32}$/.test(account.accountId)) throw new AssistantError('assistant_bad_account')
  const body: Record<string, unknown> = {
    messages: messages.map(([role, content]) => ({ role, content })),
    max_tokens: maxTokens,
    temperature,
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

async function askJson(account: Keys, messages: [string, string][], schema: unknown, maxTokens: number, temperature = 0.1): Promise<any> {
  let answer
  try {
    answer = await run(account, model(), messages, schema, maxTokens, temperature)
  } catch (e) {
    // When the model cannot keep to the schema, it is asked again free: the JSON is then looked for in the text.
    if (!(e instanceof AssistantError) || e.text !== 'assistant_json_failed') throw e
    answer = await run(account, model(), messages, null, maxTokens, temperature)
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

/** Phrases to run a custom device, as the model suggests them, a bit freely to have them varied. */
export const phrases = (system: string) => askJson(account(), [['system', system], ['user', 'Suggest the phrases.']], SCHEMA_PHRASES, 800, 0.7)

export const questions = (system: string) => askJson(account(), [['system', system], ['user', 'Ask your questions.']], SCHEMA_QUESTIONS, 1500)

// --- the agent: requests in words, acted on by the app (as agentPrompt in Assistant.kt) ----------

const SCHEMA_AGENT = {
  type: 'object',
  properties: {
    reply: { type: 'string' },
    alexa: { type: 'boolean' },
    actions: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          do: { type: 'string' }, target: { type: 'string' }, on: { type: 'boolean' }, minutes: { type: 'number' },
          sensor: { type: 'string' }, when: { type: 'string' }, threshold: { type: 'number' },
          list: { type: 'string' }, items: { type: 'array', items: { type: 'string' } },
          title: { type: 'string' }, start: { type: 'string' }, date: { type: 'string' },
        },
        required: ['do'],
      },
    },
  },
  required: ['reply', 'actions'],
}

/**
 * The instructions for a request in words: the model answers and says what to do, by the ids of
 * the list; the app does it, and asks first for what is more than switching one thing.
 */
export function agentPrompt(catalog: Catalog, house: string, language: string, now: string, context: string): string {
  return `${AGENT_RULES.replace('{language}', language).replace('{now}', now)}
${house.trim() ? `What the owner says about the home:\n${house.trim()}\n` : ''}
The things of the home:
${catalog.describe(true)}${context.trim() ? `\n${context.trim()}` : ''}`.trim()
}

/** Kept word for word the same as AGENT_RULES in Assistant.kt. */
const AGENT_RULES = `You are the assistant of a smart home app. Answer in {language}. Now it is {now}.
Return JSON {"reply": text for the user, "alexa": boolean, "actions": [...]}. Actions, by the ids below:
- {"do":"power","target":id,"on":true|false}: switch a device (d...), a group (g...) or a remote (r...) on or off.
- {"do":"run","target":id}: run a custom device (m...).
- {"do":"later","target":id,"on":true|false,"minutes":n}: the same, in n minutes from now ("tra 20 minuti", "alle 23").
- {"do":"automation","sensor":id of a device,"when":"temperatureAbove"|"temperatureBelow"|"humidityAbove"|"humidityBelow"|"on"|"off","threshold":number,"target":id,"on":true|false}:
  from now on, each time that device gets there, switch or run the target. One condition and one action only:
  for anything more (a time of day, several actions) make the closest one and say in the reply what was left out.
- {"do":"list","list":exact name of a list,"items":[...]}: add items to a list; a dish asked for becomes its ingredients, one per item.
- {"do":"event","title":text,"start":"YYYY-MM-DDTHH:MM","minutes":duration} or {"do":"event","title":text,"date":"YYYY-MM-DD"} for a whole day: add to the calendar.
"Tutto tranne X" means each switchable device (of the room, if one is said) except X, one action each.
Questions about the home (what is on, temperatures, how a sensor went, the agenda) are answered
in the reply from the data below, with no actions. A brief of the day: greet, then what is on, the temperatures,
the events of today and tomorrow, the timers; six sentences at most. A note given below is summed up in a few sentences.
Set "alexa" true, with no actions and an empty reply, only when the request is about nothing of this app
(weather, music, news, general knowledge, Alexa routines): it is then passed to Alexa.
The reply is read aloud: short plain sentences, no lists, no markdown, never the ids. When acting, say briefly what you do.
Use only what is listed; when something asked for is not there, say so and do nothing about it.`

type Reading = { at: number; temperature: number; humidity: number | null }

/** The readings of a sensor of the last week, a line a day: what the model needs to tell how it went. */
export function historyLine(name: string, readings: Reading[], offsetMinutes: number): string {
  const days = new Map<string, Reading[]>()
  for (const r of readings) {
    const day = new Date(r.at - offsetMinutes * 60_000).toISOString().slice(5, 10)
    days.set(day, [...(days.get(day) ?? []), r])
  }
  const range = (values: number[], unit: string) => `${Math.min(...values).toFixed(1)}-${Math.max(...values).toFixed(1)}${unit}`
  const parts = [...days].map(([day, list]) => {
    const humidity = list.map((r) => r.humidity).filter((it): it is number => it != null)
    return `${day} ${range(list.map((r) => r.temperature), '°C')}${humidity.length ? ` ${range(humidity, '%')}` : ''}`
  })
  return `"${name}" by day (MM-DD min-max): ${parts.join('; ')}`
}

export const AUTOMATION_TRIGGERS = ['temperatureAbove', 'temperatureBelow', 'humidityAbove', 'humidityBelow', 'on', 'off']

/**
 * Reads what the model would do, keeping only what names things of the list, each by its key in
 * the app: what is left the client does as it is.
 */
export function parseAgent(answer: any, catalog: Catalog) {
  const actions: any[] = []
  const find = (id: unknown) => catalog.find(String(id ?? ''))
  for (const a of Array.isArray(answer?.actions) ? answer.actions : []) {
    const target = find(a?.target)
    const kind = target?.id[0]
    // A device that cannot be switched has no voice.
    const switchable = target != null && (kind === 'g' || kind === 'r' || (kind === 'd' && target.voice != null))
    const on = a?.on !== false
    switch (a?.do) {
      case 'power':
        if (switchable) actions.push({ do: 'power', key: target!.key, name: target!.name, on })
        break
      case 'run':
        if (kind === 'm') actions.push({ do: 'run', key: target!.key, name: target!.name })
        break
      case 'later': {
        const minutes = Math.round(Number(a.minutes))
        if ((switchable || kind === 'm') && minutes >= 1 && minutes <= 7 * 24 * 60) {
          actions.push({ do: 'later', key: target!.key, name: target!.name, on, minutes })
        }
        break
      }
      case 'automation': {
        const sensor = find(a.sensor)
        const threshold = Number(a.threshold ?? 0)
        if (sensor?.id[0] === 'd' && kind !== 'g' && (switchable || kind === 'm')
          && AUTOMATION_TRIGGERS.includes(a.when) && Number.isFinite(threshold)) {
          actions.push({ do: 'automation', sensor: sensor.key, sensorName: sensor.name, when: a.when, threshold, key: target!.key, name: target!.name, on })
        }
        break
      }
      case 'list': {
        const items = (Array.isArray(a.items) ? a.items : []).map((it: unknown) => String(it ?? '').trim()).filter(Boolean).slice(0, 40)
        if (String(a.list ?? '').trim() && items.length) actions.push({ do: 'list', list: String(a.list).trim(), items })
        break
      }
      case 'event': {
        const title = String(a.title ?? '').trim()
        const start = String(a.start ?? '')
        const date = String(a.date ?? '')
        const minutes = Math.round(Number(a.minutes)) || 60
        if (title && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(start)) {
          actions.push({ do: 'event', title, start: start.slice(0, 16), minutes: Math.min(Math.max(minutes, 5), 24 * 60) })
        } else if (title && /^\d{4}-\d{2}-\d{2}$/.test(date)) {
          actions.push({ do: 'event', title, date })
        }
        break
      }
    }
  }
  return { reply: String(answer?.reply ?? '').trim(), alexa: answer?.alexa === true && !actions.length, actions }
}

export const agent = (system: string, request: string) => askJson(account(), [['system', system], ['user', request]], SCHEMA_AGENT, 1200, 0.2)

/** A push about a sensor past its threshold, said as a person would; [facts] are what is known of it. */
export async function alertText(facts: string, language: string): Promise<string> {
  const system = `You write the body of a push notification of a smart home app, in ${language}: one or two short, friendly sentences,
at most 25 words, saying what happened and, if useful, what could be done. No emoji, no greeting, nothing invented.`
  const answer = await askJson(account(), [['system', system], ['user', facts]], SCHEMA_TEXT, 120, 0.3)
  return String(answer?.text ?? '').trim()
}
