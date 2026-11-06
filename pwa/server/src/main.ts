import { createServer as createHttp, type IncomingMessage, type ServerResponse } from 'node:http'
import { createServer as createHttps } from 'node:https'
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { extname, join, normalize } from 'node:path'
import { str } from '../../shared/i18n.js'
import { nextTime } from '../../shared/model.js'
import * as data from './db.ts'
import * as accounts from './accounts.ts'
import * as alexa from './alexa.ts'
import * as assistant from './assistant.ts'
import * as calendar from './calendar.ts'
import * as notion from './notion.ts'
import * as engine from './engine.ts'
import * as football from './football.ts'
import { HttpError, setAccountLanguage } from './errors.ts'
import * as lgtv from './lgtv.ts'
import { send as push, subscribe, unsubscribe, vapidPublicKey } from './push.ts'
import * as switchBot from './switchbot.ts'

const PORT = Number(process.env.PORT ?? 8080)
const HTTPS_PORT = Number(process.env.HTTPS_PORT ?? 8443)
const root = join(import.meta.dirname, '..', '..')
const publicDir = process.env.PUBLIC_DIR ?? join(root, 'web')
const sharedDir = join(root, 'shared')
const certDir = join(data.dataDir, 'certs')
// The version the updater compares with GitHub: bump it in package.json to release.
const VERSION = JSON.parse(readFileSync(join(import.meta.dirname, '..', 'package.json'), 'utf8')).version as string

// --- helpers ---------------------------------------------------------------------------------

function json(res: ServerResponse, status: number, body: unknown) {
  const text = JSON.stringify(body)
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(text)
}

async function readBody(req: IncomingMessage, limit = 6 * 1024 * 1024): Promise<Buffer> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of req) {
    size += (chunk as Buffer).length
    if (size > limit) throw new HttpError(413, 'File troppo grande')
    chunks.push(chunk as Buffer)
  }
  return Buffer.concat(chunks)
}

async function readJson<T>(req: IncomingMessage): Promise<T> {
  const body = await readBody(req)
  if (!body.length) return {} as T
  try {
    return JSON.parse(body.toString('utf8')) as T
  } catch {
    throw new HttpError(400, 'Richiesta non valida')
  }
}

function cookies(req: IncomingMessage): Record<string, string> {
  const out: Record<string, string> = {}
  for (const part of (req.headers.cookie ?? '').split(';')) {
    const [k, ...v] = part.trim().split('=')
    if (k) out[k] = decodeURIComponent(v.join('='))
  }
  return out
}

const secure = (req: IncomingMessage) =>
  (req.socket as { encrypted?: boolean }).encrypted === true || req.headers['x-forwarded-proto'] === 'https'

const text = (value: unknown, max = 2000) => String(value ?? '').slice(0, max)

// --- auth: one account per person, each with its own data -----------------------------------

const SESSION = 'hommaterial_session'

/** The session token, from the cookie of the browser or from the header of the Android app. */
const token = (req: IncomingMessage) => cookies(req)[SESSION] || (req.headers['x-session'] as string | undefined)

const sessionUser = (req: IncomingMessage) => accounts.sessionUser(token(req))

function startSession(req: IncomingMessage, res: ServerResponse, userId: number) {
  const value = accounts.startSession(userId)
  const flags = `Path=/; HttpOnly; SameSite=Lax; Max-Age=${60 * 60 * 24 * 400}${secure(req) ? '; Secure' : ''}`
  res.setHeader('Set-Cookie', `${SESSION}=${value}; ${flags}`)
}

function clearSession(res: ServerResponse) {
  res.setHeader('Set-Cookie', `${SESSION}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`)
}

/** Account mistakes (name taken, short password...) reach the user as they are. */
function accountCall(work: () => void) {
  try {
    work()
  } catch (e) {
    if (e instanceof accounts.AccountError) throw new HttpError(400, e.message)
    throw e
  }
}

// --- static files ----------------------------------------------------------------------------

const types: Record<string, string> = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.woff2': 'font/woff2',
  '.ico': 'image/x-icon', '.crt': 'application/x-x509-ca-cert', '.txt': 'text/plain; charset=utf-8',
}

function sendFile(req: IncomingMessage, res: ServerResponse, file: string, cache: string) {
  const stat = statSync(file)
  const etag = `"${stat.size.toString(16)}-${stat.mtimeMs.toString(16)}"`
  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304)
    res.end()
    return
  }
  res.writeHead(200, {
    'Content-Type': types[extname(file).toLowerCase()] ?? 'application/octet-stream',
    'Content-Length': stat.size,
    'Cache-Control': cache,
    ETag: etag,
  })
  if (req.method === 'HEAD') res.end()
  else createReadStream(file).pipe(res)
}

/** A file under [dir], or null when [path] leads outside of it or to nothing. */
function fileIn(dir: string, path: string): string | null {
  const clean = normalize(decodeURIComponent(path)).replace(/^([/\\])+/, '')
  const file = join(dir, clean)
  return file.startsWith(dir) && clean && existsSync(file) && statSync(file).isFile() ? file : null
}

function serveStatic(req: IncomingMessage, res: ServerResponse, path: string) {
  // The code shared with the server: the model, the texts, the backup.
  const file = path.startsWith('/shared/') ? fileIn(sharedDir, path.slice('/shared/'.length)) : fileIn(publicDir, path)
  if (file) {
    sendFile(req, res, file, 'no-cache')
    return
  }
  if (path.startsWith('/shared/') || extname(path)) throw new HttpError(404, 'Non trovato')
  // Every other path gets the app.
  sendFile(req, res, join(publicDir, 'index.html'), 'no-cache')
}

// --- API -------------------------------------------------------------------------------------

type Handler = (req: IncomingMessage, res: ServerResponse, params: string[], url: URL, user: accounts.User) => Promise<void> | void
const routes: [string, RegExp, Handler][] = []
const route = (method: string, pattern: string, handler: Handler) =>
  routes.push([method, new RegExp('^' + pattern.replace(/:\w+/g, '([^/]+)') + '$'), handler])

const ok = (res: ServerResponse) => json(res, 200, { ok: true })

route('GET', '/api/session', (req, res) => {
  const user = sessionUser(req)
  json(res, 200, { loggedIn: user != null, username: user?.username ?? null, version: VERSION, invite: accounts.inviteRequired })
})

route('POST', '/api/register', async (req, res) => {
  const { username, password, invite } = await readJson<{ username?: string; password?: string; invite?: string }>(req)
  accountCall(() => {
    const user = accounts.register(text(username, 100), text(password, 300), text(invite, 300))
    startSession(req, res, user.id)
    json(res, 200, { username: user.username, token: token(req) })
  })
})

route('POST', '/api/login', async (req, res) => {
  const { username, password } = await readJson<{ username?: string; password?: string }>(req)
  const user = accounts.login(text(username, 100), text(password, 300))
  if (!user) {
    await new Promise((r) => setTimeout(r, 800))
    throw new HttpError(401, 'Nome utente o password sbagliati')
  }
  startSession(req, res, user.id)
  json(res, 200, { username: user.username })
})

route('POST', '/api/logout', (req, res) => {
  const value = token(req)
  if (value) accounts.endSession(value)
  clearSession(res)
  ok(res)
})

route('POST', '/api/account/password', async (req, res, _p, _u, user) => {
  const { current, password } = await readJson<{ current?: string; password?: string }>(req)
  accountCall(() => accounts.changePassword(user.id, text(current, 300), text(password, 300)))
  ok(res)
})

route('POST', '/api/account/delete', async (req, res, _p, _u, user) => {
  const { password } = await readJson<{ password?: string }>(req)
  accountCall(() => accounts.deleteAccount(user.id, text(password, 300)))
  clearSession(res)
  ok(res)
})

/** Where the accounts stand with the services they use: never the keys themselves. */
function status() {
  return {
    alexa: { loggedIn: alexa.isLoggedIn(), marketplace: alexa.marketplace().domain, attempt: alexa.pendingAttempt() != null },
    switchBot: switchBot.connected(),
    assistant: { configured: assistant.configured(), accountId: assistant.keys()?.accountId ?? '', model: assistant.model() },
    calendar: { client: calendar.client() != null, linked: calendar.linked(), account: calendar.account() },
    notion: { linked: notion.linked(), workspace: notion.workspace() },
  }
}

/** Everything the app shows; "unchanged" when the client already has this revision. */
route('GET', '/api/state', (_req, res, _p, url) => {
  const rev = data.revision()
  if (url.searchParams.get('since') === String(rev)) {
    json(res, 200, { revision: rev, unchanged: true })
    return
  }
  json(res, 200, { revision: rev, data: data.all(), status: status() })
})

route('POST', '/api/language', async (req, res) => {
  const { language } = await readJson<{ language?: string }>(req)
  setAccountLanguage(text(language, 5))
  ok(res)
})

// The choices only the user makes, written as they are: the server never changes them by itself.
const CLIENT_KEYS = new Set([
  'hidden', 'favorites', 'rooms', 'roomList', 'groups', 'order', 'tileSpans', 'macros', 'shortcuts', 'widget', 'tiles',
  'remoteButtons', 'hiddenRemotes', 'acSettings', 'assistantNotes', 'assistantHouse', 'assistantTaught',
  'autoUpdate', 'haptics', 'hapticLevel', 'notionLists', 'notionNotes', 'listViews', 'team', 'teamReminder', 'weather',
])

route('POST', '/api/data', async (req, res) => {
  const { values } = await readJson<{ values?: Record<string, unknown> }>(req)
  const accepted: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(values ?? {})) if (CLIENT_KEYS.has(key)) accepted[key] = value
  if (Object.keys(accepted).length) data.edit(accepted)
  json(res, 200, { revision: data.revision() })
})

// --- Favorite team: ESPN, read here because a browser cannot (football.ts) ----------------------

route('GET', '/api/football/teams', async (_req, res) => json(res, 200, { leagues: await football.teams() }))

route('GET', '/api/football/team/:league/:id', async (_req, res, [leagueId, id]) => json(res, 200, await football.team(leagueId, id)))

// --- Alexa ---------------------------------------------------------------------------------------

route('POST', '/api/alexa/marketplace', async (req, res) => {
  const { domain } = await readJson<{ domain?: string }>(req)
  alexa.setMarketplace(text(domain, 40))
  data.touch()
  ok(res)
})

route('POST', '/api/alexa/attempt', (_req, res) => {
  const attempt = alexa.newLoginAttempt()
  data.touch()
  json(res, 200, { url: attempt.url })
})

route('POST', '/api/alexa/login', async (req, res) => {
  const { landing } = await readJson<{ landing?: string }>(req)
  await alexa.completeLogin(text(landing, 20_000))
  data.touch()
  // The home comes along at once, as after the sign-in of the Android app.
  try {
    await engine.refresh()
  } catch {
    // The app asks again by itself.
  }
  ok(res)
})

/** The sign-in made by the Android app, given to the server: the phone keeps using its own page of Amazon. */
route('POST', '/api/alexa/token', async (req, res) => {
  const { refreshToken, marketplace } = await readJson<{ refreshToken?: string; marketplace?: string }>(req)
  const value = text(refreshToken, 5000).trim()
  if (!value) throw new HttpError(400, str('something_wrong'))
  await alexa.adoptToken(value, marketplace ? text(marketplace, 40) : null)
  data.touch()
  try {
    await engine.refresh()
  } catch {
    // The app asks again by itself.
  }
  ok(res)
})

route('POST', '/api/alexa/logout', (_req, res) => {
  alexa.logout()
  // What Amazon gave goes, with the timers, the schedules and the automations that act on it.
  // SwitchBot, the LG TVs and the assistant are other accounts: they stay.
  data.clearHome(['remotes', 'remoteButtons', 'hiddenRemotes', 'acSettings', 'lgTvs', 'assistantNotes', 'assistantHouse', 'assistantTaught'])
  ok(res)
})

route('POST', '/api/alexa/refresh', async (req, res) => {
  const { showHidden } = await readJson<{ showHidden?: boolean }>(req)
  engine.polled()
  await engine.refresh(showHidden === true)
  json(res, 200, { revision: data.revision() })
})

route('POST', '/api/alexa/poll', async (req, res) => {
  const { ids } = await readJson<{ ids?: string[] }>(req)
  engine.polled()
  await engine.poll(Array.isArray(ids) ? ids.map(String) : null)
  json(res, 200, { revision: data.revision() })
})

/** The states of the devices asked, read now: for the Android app, which shows them at once. */
route('POST', '/api/alexa/states', async (req, res) => {
  const { ids } = await readJson<{ ids?: string[] }>(req)
  const list = (Array.isArray(ids) ? ids : []).map((it) => text(it, 300))
  const known = new Set(data.get<{ applianceId: string }[]>('devices').map((d) => d.applianceId))
  const fresh = await alexa.states(list.filter((id) => known.has(id)))
  engine.received(fresh)
  json(res, 200, { states: fresh })
})

/** What happened on the server since the last note the Android app showed. */
route('GET', '/api/notices', (_req, res, _p, url) => json(res, 200, { notices: engine.notices(Number(url.searchParams.get('after') ?? 0)) }))

function requireDevice(id: string) {
  const device = engine.device(id)
  if (!device) throw new HttpError(404, str('quick_not_found'))
  return device
}

route('POST', '/api/alexa/control', async (req, res) => {
  const body = await readJson<{ applianceId?: string; action?: string; on?: boolean; brightness?: number; color?: string; white?: boolean }>(req)
  const device = requireDevice(text(body.applianceId, 200))
  switch (body.action) {
    case 'power':
      await engine.switchDevice(device, body.on === true)
      break
    case 'brightness': {
      const brightness = Math.round(Math.min(100, Math.max(0, Number(body.brightness) || 0)))
      await alexa.control(device, { action: 'setBrightness', brightness })
      engine.noteState(device.applianceId, { brightness })
      break
    }
    case 'color': {
      const name = text(body.color, 40)
      await alexa.control(device, body.white
        ? { action: 'setColorTemperature', colorTemperatureName: name }
        : { action: 'setColor', colorName: name })
      // Lamps switch on when asked for a color.
      engine.noteState(device.applianceId, { colorName: name, power: true })
      break
    }
    default:
      throw new HttpError(400, str('something_wrong'))
  }
  json(res, 200, { revision: data.revision() })
})

route('GET', '/api/alexa/echos', async (_req, res) => json(res, 200, { echos: await alexa.echos() }))

route('POST', '/api/alexa/say', async (req, res) => {
  const body = await readJson<{ echo?: string; text?: string; quiet?: boolean }>(req)
  const said = text(body.text, 300).trim()
  if (!said) throw new HttpError(400, str('something_wrong'))
  await alexa.say(text(body.echo, 200), said, body.quiet === true)
  ok(res)
})

route('POST', '/api/alexa/volume', async (req, res) => {
  const body = await readJson<{ echo?: string; value?: number }>(req)
  if (!Number.isFinite(body.value)) throw new HttpError(400, str('something_wrong'))
  await alexa.volume(text(body.echo, 200), Number(body.value))
  ok(res)
})

route('GET', '/api/alexa/reply', async (_req, res, _p, url) => {
  const query = url.searchParams
  const since = Number(query.get('since'))
  if (!Number.isFinite(since)) throw new HttpError(400, str('something_wrong'))
  json(res, 200, { text: await alexa.reply(text(query.get('echo'), 200), since) })
})

route('GET', '/api/alexa/diagnostics/:id', async (_req, res, [id]) => {
  json(res, 200, { text: await alexa.diagnostics(requireDevice(decodeURIComponent(id))) })
})

// --- the lists of Alexa: to-do, shopping and those made by the user ---------------------------------

route('GET', '/api/lists', async (_req, res) => json(res, 200, { lists: await alexa.lists() }))

route('GET', '/api/lists/:id/items', async (_req, res, [id]) =>
  json(res, 200, { items: await alexa.listItems(decodeURIComponent(id)) }))

route('POST', '/api/lists/:id/items', async (req, res, [id]) => {
  const { name } = await readJson<{ name?: string }>(req)
  const value = text(name, 500).trim()
  if (!value) throw new HttpError(400, str('something_wrong'))
  json(res, 200, { item: await alexa.addListItem(decodeURIComponent(id), value) })
})

route('PUT', '/api/lists/:id/items/:item', async (req, res, [id, item]) => {
  const body = await readJson<{ version?: number; name?: string; completed?: boolean }>(req)
  await alexa.updateListItem(decodeURIComponent(id), decodeURIComponent(item), Number(body.version) || 1, {
    name: body.name == null ? undefined : text(body.name, 500).trim(),
    completed: typeof body.completed === 'boolean' ? body.completed : undefined,
  })
  ok(res)
})

route('DELETE', '/api/lists/:id/items/:item', async (_req, res, [id, item], url) => {
  await alexa.deleteListItem(decodeURIComponent(id), decodeURIComponent(item), Number(url.searchParams.get('version')) || 1)
  ok(res)
})

route('GET', '/api/history/:id',(_req, res, [id]) => json(res, 200, { readings: data.readings(decodeURIComponent(id)) }))

route('DELETE', '/api/history', (_req, res) => {
  data.clearHistory()
  ok(res)
})

// --- timers, schedules, automations, alerts: the server carries them out -----------------------

route('POST', '/api/timers', async (req, res) => {
  const { applianceId, minutes } = await readJson<{ applianceId?: string; minutes?: number }>(req)
  const device = requireDevice(text(applianceId, 200))
  const length = Number(minutes)
  if (!Number.isFinite(length) || length <= 0 || length > 7 * 24 * 60) throw new HttpError(400, str('something_wrong'))
  data.set('timers', { ...data.get<Record<string, number>>('timers'), [device.applianceId]: Date.now() + Math.round(length * 60_000) })
  ok(res)
})

route('DELETE', '/api/timers/:id', (_req, res, [id]) => {
  engine.cancelTimer(decodeURIComponent(id))
  ok(res)
})

type Action = { type: string; target: string; value: string; custom?: boolean }
type Schedule = { id: string; action: Action; label: string; at: number; hour: number; minute: number; days: number[]; enabled: boolean }

function action(raw: any): Action {
  if (!raw || !['power', 'remote', 'macro'].includes(raw.type) || typeof raw.target !== 'string' || typeof raw.value !== 'string') {
    throw new HttpError(400, str('something_wrong'))
  }
  return { type: raw.type, target: text(raw.target, 300), value: text(raw.value, 300), custom: raw.custom === true }
}

/** Sets [action] to be carried out at a moment, and again on each of the days if there are any. */
route('POST', '/api/schedules', async (req, res) => {
  const body = await readJson<{ action?: unknown; label?: string; at?: number; days?: number[] }>(req)
  const at = Number(body.at)
  if (!Number.isFinite(at)) throw new HttpError(400, str('something_wrong'))
  const days = (Array.isArray(body.days) ? body.days : []).map(Number).filter((d) => Number.isInteger(d) && d >= 1 && d <= 7)
  const time = new Date(at)
  // The Android app gives its own id, so that the schedule keeps it on both sides.
  const id = /^[\w-]{8,64}$/.test(String((body as { id?: string }).id ?? '')) ? String((body as { id?: string }).id) : randomUUID()
  const enabled = (body as { enabled?: boolean }).enabled !== false
  const schedule = {
    id, action: action(body.action), label: text(body.label, 300), at,
    hour: time.getHours(), minute: time.getMinutes(), days: [...new Set(days)], enabled,
  }
  engine.saveSchedules([...data.get<Schedule[]>('schedules').filter((it) => it.id !== id), schedule])
  json(res, 200, schedule)
})

/** Switches a repeating schedule off, or on again from its next time. */
route('POST', '/api/schedules/:id/enabled', async (req, res, [id]) => {
  const { enabled } = await readJson<{ enabled?: boolean }>(req)
  const on = enabled === true
  engine.saveSchedules(data.get<Schedule[]>('schedules').map((it) => it.id !== id ? it
    : { ...it, enabled: on, at: on ? nextTime(it.hour, it.minute, it.days, Date.now()) : it.at }))
  ok(res)
})

route('DELETE', '/api/schedules/:id', (_req, res, [id]) => {
  engine.saveSchedules(data.get<Schedule[]>('schedules').filter((it) => it.id !== id))
  ok(res)
})

type Automation = { id: string; device: string; trigger: string; threshold: number; action: Action; title: string; label: string; enabled: boolean }
const TRIGGERS = ['on', 'off', 'temperatureAbove', 'temperatureBelow', 'humidityAbove', 'humidityBelow']

/** Creates an automation: an action when a device gets to what its trigger and threshold say. */
route('POST', '/api/automations', async (req, res) => {
  const body = await readJson<Partial<Automation>>(req)
  const device = requireDevice(text(body.device, 200))
  if (!TRIGGERS.includes(String(body.trigger)) || !Number.isFinite(Number(body.threshold ?? 0))) throw new HttpError(400, str('something_wrong'))
  const automation: Automation = {
    id: /^[\w-]{8,64}$/.test(String(body.id ?? '')) ? String(body.id) : randomUUID(), device: device.applianceId, trigger: String(body.trigger), threshold: Number(body.threshold ?? 0),
    action: action(body.action), title: text(body.title, 300), label: text(body.label, 300), enabled: body.enabled !== false,
  }
  data.set('automations', [...data.get<Automation[]>('automations').filter((a) => a.id !== automation.id), automation])
  engine.armAutomation(automation)
  json(res, 200, automation)
})

route('POST', '/api/automations/:id/enabled', async (req, res, [id]) => {
  const { enabled } = await readJson<{ enabled?: boolean }>(req)
  const list = data.get<Automation[]>('automations').map((a) => (a.id === id ? { ...a, enabled: enabled === true } : a))
  data.set('automations', list)
  const changed = list.find((a) => a.id === id)
  if (changed) engine.armAutomation(changed)
  ok(res)
})

route('DELETE', '/api/automations/:id', (_req, res, [id]) => {
  data.set('automations', data.get<Automation[]>('automations').filter((a) => a.id !== id))
  engine.forgetAutomation(id)
  ok(res)
})

/** Sets the temperatures a sensor notifies about: above and below, each null for none. */
route('POST', '/api/alerts/:id', async (req, res, [raw]) => {
  const id = decodeURIComponent(raw)
  const { above, below } = await readJson<{ above?: number | null; below?: number | null }>(req)
  const bound = (v: unknown) => (v == null || !Number.isFinite(Number(v)) ? null : Number(v))
  const alert = { above: bound(above), below: bound(below) }
  const alerts = data.get<Record<string, unknown>>('alerts')
  if (alert.above == null && alert.below == null) delete alerts[id]
  else alerts[id] = alert
  data.set('alerts', alerts)
  engine.resetAlert(id)
  ok(res)
})

route('POST', '/api/macros/:id/run', async (_req, res, [id]) => json(res, 200, await engine.runMacro(decodeURIComponent(id))))

// --- remotes: SwitchBot and the LG TVs -----------------------------------------------------------

route('POST', '/api/remotes/send', async (req, res) => {
  const body = await readJson<{ id?: string; command?: string; parameter?: string; custom?: boolean }>(req)
  const target = engine.remote(text(body.id, 300))
  if (!target) throw new HttpError(404, str('quick_not_found'))
  await engine.sendRemote(target, text(body.command, 300), text(body.parameter ?? 'default', 300), body.custom === true)
  ok(res)
})

/** Links the SwitchBot account, keeping the keys only if SwitchBot accepts them. */
route('POST', '/api/switchbot/connect', async (req, res) => {
  const { token: key, secret } = await readJson<{ token?: string; secret?: string }>(req)
  const keys = { token: text(key, 500).trim(), secret: text(secret, 500).trim() }
  const remotes = await switchBot.fetchRemotes(keys)
  switchBot.connect(keys.token, keys.secret)
  data.set('remotes', remotes)
  json(res, 200, { count: remotes.length })
})

route('POST', '/api/switchbot/disconnect', (_req, res) => {
  switchBot.disconnect()
  // The buttons and the hiding of the LG TVs are kept with those of SwitchBot, and stay.
  const tv = (id: string) => id.startsWith(lgtv.LG_PREFIX)
  const buttons = Object.fromEntries(Object.entries(data.get<Record<string, string[]>>('remoteButtons')).filter(([id]) => tv(id)))
  data.edit({ remotes: [], remoteButtons: buttons, acSettings: {}, hiddenRemotes: data.get<string[]>('hiddenRemotes').filter(tv) })
  ok(res)
})

route('POST', '/api/switchbot/remotes', async (_req, res) => {
  if (switchBot.connected()) data.set('remotes', await switchBot.fetchRemotes())
  ok(res)
})

type Pairing = { host: string; asking: boolean; done: boolean; error: string | null; name: string | null }
const pairings = new Map<number, Pairing>()

route('POST', '/api/lg/discover', async (_req, res) => {
  const paired = new Set(lgtv.all().map((tv) => tv.host))
  json(res, 200, { found: (await lgtv.discover()).filter((tv) => !paired.has(tv.host)) })
})

/**
 * Pairs the TV at host, which asks the user on its screen. The answer comes at once: the app then
 * asks how it is going (GET), as the TV may wait up to a minute for the user.
 */
route('POST', '/api/lg/pair', async (req, res, _p, _u, user) => {
  const body = await readJson<{ host?: string; name?: string }>(req)
  const host = text(body.host, 200).trim()
  if (!host) throw new HttpError(400, str('something_wrong'))
  const pairing: Pairing = { host, asking: false, done: false, error: null, name: null }
  pairings.set(user.id, pairing)
  const greeting = str('lgtv_hello')
  data.withUser(user.id, async () => {
    try {
      const tv = await lgtv.pair(host, greeting, () => {
        pairing.asking = true
      })
      // Paired again, it stays the same remote, with its buttons, rooms and custom devices.
      const before = lgtv.all().find((it) => it.host === host)
      const again = {
        ...tv,
        // The name the user gave the TV says more than its model.
        name: before?.name ?? (text(body.name, 200).trim() || tv.name),
        id: before?.id ?? tv.id,
        apps: before?.apps ?? {},
        inputs: before?.inputs ?? {},
      }
      lgtv.put(again)
      pairing.name = again.name
      pairing.done = true
    } catch (e) {
      pairing.error = (e as Error).message || str('something_wrong')
    }
  })
  ok(res)
})

route('GET', '/api/lg/pair', (_req, res, _p, _u, user) => json(res, 200, pairings.get(user.id) ?? null))

route('DELETE', '/api/lg/pair', (_req, res, _p, _u, user) => {
  pairings.delete(user.id)
  ok(res)
})

route('POST', '/api/lg/:id/rename', async (req, res, [id]) => {
  const { name } = await readJson<{ name?: string }>(req)
  const tv = lgtv.requireTv(decodeURIComponent(id))
  const trimmed = text(name, 200).trim()
  if (trimmed) lgtv.put({ ...tv, name: trimmed })
  ok(res)
})

route('DELETE', '/api/lg/:id', (_req, res, [id]) => {
  lgtv.remove(decodeURIComponent(id))
  ok(res)
})

/** Asks the TV for its apps and its sources, to offer them as buttons and to switch. */
route('POST', '/api/lg/:id/apps', async (_req, res, [id]) => {
  const tv = lgtv.requireTv(decodeURIComponent(id))
  const apps = await lgtv.apps(tv)
  // Older webOS may not list its sources: the apps are kept all the same.
  let inputs: Record<string, string> | null = null
  try {
    inputs = await lgtv.inputs(tv)
  } catch {
    inputs = null
  }
  const current = lgtv.find(tv.id)
  if (current) lgtv.put({ ...current, apps, inputs: inputs ?? current.inputs })
  ok(res)
})

route('POST', '/api/lg/:id/type', async (req, res, [id]) => {
  const { sent, typed } = await readJson<{ sent?: string; typed?: string }>(req)
  await lgtv.type(lgtv.requireTv(decodeURIComponent(id)), text(sent, 1000), text(typed, 1000))
  ok(res)
})

route('POST', '/api/lg/:id/enter', async (_req, res, [id]) => {
  await lgtv.enter(lgtv.requireTv(decodeURIComponent(id)))
  ok(res)
})

/** Shows a message on the TV, to see that it answers. */
route('POST', '/api/lg/:id/test', async (_req, res, [id]) => {
  await lgtv.send(lgtv.requireTv(decodeURIComponent(id)), 'toast', str('lgtv_hello'))
  ok(res)
})

// --- Notion: to-do lists and notes ------------------------------------------------------------------

route('POST', '/api/notion/connect', async (req, res) => {
  const { token } = await readJson<{ token?: string }>(req)
  const secret = text(token, 300).trim()
  if (!secret) throw new HttpError(400, str('notion_unauthorized'))
  json(res, 200, { workspace: await notion.connect(secret) })
})

route('POST', '/api/notion/disconnect', (_req, res) => {
  notion.disconnect()
  ok(res)
})

route('GET', '/api/notion/sources', async (_req, res) => json(res, 200, { sources: await notion.sources() }))

const notionKind = (kind: string) => (kind === 'database' ? 'database' : 'page')

route('GET', '/api/notion/lists/:kind/:id/items', async (_req, res, [kind, id]) =>
  json(res, 200, { items: await notion.items(notionKind(kind), decodeURIComponent(id)) }))

route('POST', '/api/notion/lists/:kind/:id/items', async (req, res, [kind, id]) => {
  const { name } = await readJson<{ name?: string }>(req)
  const value = text(name, 500).trim()
  if (!value) throw new HttpError(400, str('something_wrong'))
  await notion.add(notionKind(kind), decodeURIComponent(id), value)
  ok(res)
})

route('PUT', '/api/notion/lists/:kind/:id/items/:item', async (req, res, [kind, id, item]) => {
  const { completed } = await readJson<{ completed?: boolean }>(req)
  await notion.setDone(notionKind(kind), decodeURIComponent(id), decodeURIComponent(item), completed === true)
  ok(res)
})

route('DELETE', '/api/notion/lists/:kind/:id/items/:item', async (_req, res, [kind, , item]) => {
  await notion.remove(notionKind(kind), decodeURIComponent(item))
  ok(res)
})

route('GET', '/api/notion/notes', async (_req, res, _p, url) => {
  const chosen = (url.searchParams.get('sources') ?? '').split(',').filter(Boolean).slice(0, 50).map((it) => ({
    kind: notionKind(it.split(':')[0]), id: it.slice(it.indexOf(':') + 1),
  }))
  json(res, 200, { notes: await notion.notes(chosen) })
})

route('GET', '/api/notion/notes/:id', async (_req, res, [id]) => json(res, 200, { blocks: await notion.noteBlocks(decodeURIComponent(id)) }))

// --- Google Calendar ---------------------------------------------------------------------------

/** The keys of the OAuth client the user made in Google Cloud; the sign-in comes after. */
route('POST', '/api/calendar/client', async (req, res) => {
  const body = await readJson<{ clientId?: string; clientSecret?: string }>(req)
  const id = text(body.clientId, 300).trim()
  const secret = text(body.clientSecret, 300).trim()
  if (!id.endsWith('.apps.googleusercontent.com') || !secret) throw new HttpError(400, str('calendar_bad_client'))
  calendar.setClient(id, secret)
  data.touch()
  ok(res)
})

route('GET', '/api/calendar/auth', (_req, res) => json(res, 200, { url: calendar.authUrl() }))

route('POST', '/api/calendar/link', async (req, res) => {
  const { url } = await readJson<{ url?: string }>(req)
  await calendar.link(text(url, 4000))
  data.touch()
  ok(res)
})

route('POST', '/api/calendar/unlink', (_req, res) => {
  calendar.unlink()
  data.touch()
  ok(res)
})

route('GET', '/api/calendar/calendars', async (_req, res) => json(res, 200, { calendars: await calendar.calendars() }))

route('GET', '/api/calendar/events', async (_req, res, _p, url) => {
  const ids = (url.searchParams.get('calendars') ?? '').split(',').filter(Boolean)
  const from = text(url.searchParams.get('from'), 40)
  const to = text(url.searchParams.get('to'), 40)
  if (Number.isNaN(Date.parse(from)) || Number.isNaN(Date.parse(to))) throw new HttpError(400, str('something_wrong'))
  json(res, 200, { events: await calendar.events(ids, from, to) })
})

route('POST', '/api/calendar/events', async (req, res) => {
  const body = await readJson<{ calendar?: string; event?: any }>(req)
  json(res, 200, { event: await calendar.create(text(body.calendar, 300), body.event ?? {}) })
})

route('PUT', '/api/calendar/events/:calendar/:id', async (req, res, [cal, id]) => {
  const body = await readJson<{ event?: any; moveTo?: string }>(req)
  const moveTo = body.moveTo ? text(body.moveTo, 300) : undefined
  json(res, 200, { event: await calendar.update(decodeURIComponent(cal), decodeURIComponent(id), body.event ?? {}, moveTo) })
})

route('DELETE', '/api/calendar/events/:calendar/:id', async (_req, res, [cal, id]) => {
  await calendar.remove(decodeURIComponent(cal), decodeURIComponent(id))
  ok(res)
})

// --- the assistant -------------------------------------------------------------------------------

/** Tries the keys and keeps them if Cloudflare takes them. */
route('POST', '/api/assistant/connect', async (req, res) => {
  const body = await readJson<{ accountId?: string; token?: string }>(req)
  const accountId = text(body.accountId, 100).trim()
  const key = text(body.token, 500).trim()
  await assistant.test(accountId, key)
  assistant.connect(accountId, key)
  data.touch()
  ok(res)
})

route('POST', '/api/assistant/disconnect', (_req, res) => {
  assistant.disconnect()
  data.touch()
  ok(res)
})

route('POST', '/api/assistant/model', async (req, res) => {
  const { model } = await readJson<{ model?: string }>(req)
  assistant.setModel(text(model, 200))
  data.touch()
  ok(res)
})

/** The guesses of the speech recognizer, with the names put right; the home comes from the client, without what it hides. */
route('POST', '/api/assistant/correct', async (req, res) => {
  const { heard, home } = await readJson<{ heard?: string[]; home?: unknown }>(req)
  const catalog = new assistant.Catalog(home ?? {})
  const guesses = (Array.isArray(heard) ? heard : []).map((it) => text(it, 500)).filter((it) => it.trim())
  if (!guesses.length) return json(res, 200, { text: '' })
  json(res, 200, { text: await assistant.correct(assistant.correctionPrompt(catalog, data.get<string>('assistantHouse')), guesses) })
})

/** Has the assistant look at the home and ask about what it does not understand. */
route('POST', '/api/assistant/questions', async (req, res) => {
  const { home } = await readJson<{ home?: unknown }>(req)
  const catalog = new assistant.Catalog(home ?? {})
  const prompt = assistant.questionsPrompt(catalog, data.get<string>('assistantHouse'), str('assistant_language_name'))
  json(res, 200, {
    questions: assistant.parseQuestions(await assistant.questions(prompt), catalog),
    seen: catalog.targets.map((it) => it.key),
  })
})

/**
 * The Android app writes its own instructions, from what it knows of the home: the server only
 * adds the keys. [kind] is "correct", with the guesses heard, or "questions".
 */
route('POST', '/api/assistant/ask', async (req, res) => {
  const { kind, system, heard } = await readJson<{ kind?: string; system?: string; heard?: string[] }>(req)
  const prompt = text(system, 60_000)
  if (kind === 'correct') {
    const guesses = (Array.isArray(heard) ? heard : []).map((it) => text(it, 500)).filter((it) => it.trim())
    json(res, 200, { text: guesses.length ? await assistant.correct(prompt, guesses) : '' })
  } else if (kind === 'phrases') {
    json(res, 200, { answer: await assistant.phrases(prompt) })
  } else if (kind === 'agent') {
    json(res, 200, { answer: await assistant.agent(prompt, (Array.isArray(heard) ? heard : []).map((it) => text(it, 4000)).join('\n')) })
  } else {
    json(res, 200, { answer: await assistant.questions(prompt) })
  }
})

/**
 * A request in words: what the model answers and would do, by the keys of the app. The client
 * gives the home without what it hides, what it knows besides ([context]: lists, agenda, a note),
 * its time and its offset from UTC in minutes, as Date.getTimezoneOffset; the history is added here.
 */
route('POST', '/api/assistant/agent', async (req, res) => {
  const body = await readJson<{ home?: any; request?: string; context?: string; now?: string; offset?: number }>(req)
  const catalog = new assistant.Catalog(body.home ?? {})
  const offset = Number(body.offset) || 0
  const history = (Array.isArray(body.home?.devices) ? body.home.devices : [])
    .map((d: any) => ({ name: String(d.name ?? ''), readings: data.readings(String(d.applianceId ?? '')) }))
    .filter((it: { readings: unknown[] }) => it.readings.length)
    .map((it: { name: string; readings: data.Reading[] }) => assistant.historyLine(it.name, it.readings, offset))
  const context = [text(body.context, 30_000), history.length ? `Sensor history:\n${history.join('\n')}` : ''].filter(Boolean).join('\n')
  const prompt = assistant.agentPrompt(catalog, data.get<string>('assistantHouse'), str('assistant_language_name'), text(body.now, 100), context)
  json(res, 200, assistant.parseAgent(await assistant.agent(prompt, text(body.request, 4000)), catalog))
})

/** Phrases to run a custom device, suggested from its name and what it does. */
route('POST', '/api/assistant/phrases', async (req, res) => {
  const body = await readJson<{ name?: string; does?: string[]; condition?: string | null; otherwise?: string[]; phrases?: string[]; taken?: string[] }>(req)
  const texts = (value: unknown, max = 40) => (Array.isArray(value) ? value : []).slice(0, max).map((it) => text(it, 300)).filter((it) => it.trim())
  const phrases = texts(body.phrases)
  const taken = texts(body.taken, 200)
  const prompt = assistant.phrasesPrompt(
    text(body.name, 200), texts(body.does), body.condition ? text(body.condition, 200) : null, texts(body.otherwise), phrases, taken,
    str('assistant_language_name'),
  )
  json(res, 200, assistant.parsePhrases(await assistant.phrases(prompt), [...phrases, ...taken]))
})

// --- backup, in the format of the Android app ----------------------------------------------------

/** What the backup holds besides the choices: the keys, which only the server knows. */
route('GET', '/api/backup/keys', (_req, res) => {
  const sb = switchBot.keys()
  const ai = assistant.keys()
  json(res, 200, {
    switchBot: sb ? { token: sb.token, secret: sb.secret } : null,
    assistantKeys: ai ? { accountId: ai.accountId, token: ai.token } : null,
    lgTvs: lgtv.all(),
  })
})

/** Restores the choices of a backup, already narrowed by the client to the chosen categories. */
route('POST', '/api/backup/restore', async (req, res) => {
  const body = await readJson<{ values?: Record<string, unknown>; selected?: string[]; switchBot?: any; assistantKeys?: any; lgTvs?: any }>(req)
  const selected = new Set(Array.isArray(body.selected) ? body.selected : [])
  const values: Record<string, unknown> = {}
  const allowed = new Set([...CLIENT_KEYS, 'schedules', 'automations', 'alerts'])
  for (const [key, value] of Object.entries(body.values ?? {})) if (allowed.has(key)) values[key] = value
  if (Array.isArray(values.schedules)) {
    // A schedule restored after its time waits for the next one, rather than going off as missed.
    const now = Date.now()
    values.schedules = (values.schedules as Schedule[]).map((it) => (it.days?.length && it.enabled && it.at <= now
      ? { ...it, at: nextTime(it.hour, it.minute, it.days, now) } : it)).filter((it) => it.days?.length || it.at > now)
  }
  data.edit(values)
  // Restored automations wait for a new crossing when their condition already holds.
  if (selected.has('automations')) for (const automation of data.get<Automation[]>('automations')) engine.armAutomation(automation)
  if (selected.has('alerts')) for (const id of Object.keys(data.get<Record<string, unknown>>('alerts'))) engine.resetAlert(id)
  if (selected.has('remotes') && Array.isArray(body.lgTvs)) lgtv.replaceAll(body.lgTvs)
  let linked = false
  if (selected.has('keys') && body.switchBot?.token) {
    switchBot.connect(text(body.switchBot.token, 500), text(body.switchBot.secret, 500))
    linked = true
    try {
      data.set('remotes', await switchBot.fetchRemotes())
    } catch {
      // The remotes come with the next refresh.
    }
  }
  if (selected.has('keys') && body.assistantKeys?.token) {
    assistant.connect(text(body.assistantKeys.accountId, 100), text(body.assistantKeys.token, 500))
  }
  data.touch()
  json(res, 200, { switchBot: linked })
})

/**
 * Replaces the choices of the account with a whole backup in the format of the Android app: what
 * the phone sends when it starts using the server with the data it already has.
 */
route('POST', '/api/backup/import', async (req, res) => {
  const backup = await readJson<Record<string, any>>(req)
  if (backup?.app !== 'hommaterial' || backup.version !== 1) throw new HttpError(400, str('backup_invalid'))
  const list = (v: unknown) => (Array.isArray(v) ? v.map(String) : [])
  const object = (v: unknown) => (v && typeof v === 'object' && !Array.isArray(v) ? v : {})
  const alerts: Record<string, { above: number | null; below: number | null }> = {}
  for (const [id, a] of Object.entries(object(backup.alerts) as Record<string, any>)) {
    alerts[id] = { above: Number.isFinite(a?.above) ? a.above : null, below: Number.isFinite(a?.below) ? a.below : null }
  }
  const now = Date.now()
  const schedules = (Array.isArray(backup.schedules) ? backup.schedules : [])
    .map((it: Schedule) => (it.days?.length && it.enabled && it.at <= now ? { ...it, at: nextTime(it.hour, it.minute, it.days, now) } : it))
    .filter((it: Schedule) => it.days?.length || it.at > now)
  data.edit({
    favorites: list(backup.favorites), hidden: list(backup.hidden), rooms: object(backup.rooms), roomList: list(backup.roomList),
    groups: Array.isArray(backup.groups) ? backup.groups : [], macros: Array.isArray(backup.macros) ? backup.macros : [],
    order: object(backup.order), tileSpans: object(backup.tileSpans), shortcuts: Array.isArray(backup.shortcuts) ? backup.shortcuts : null,
    widget: backup.widget ?? null, tiles: Array.from({ length: 10 }, (_, i) => backup.tiles?.[i] ?? null),
    schedules, automations: Array.isArray(backup.automations) ? backup.automations : [], alerts,
    remoteButtons: object(backup.remoteButtons), hiddenRemotes: list(backup.hiddenRemotes),
    autoUpdate: backup.autoUpdate !== false, haptics: backup.haptics !== false, hapticLevel: Number(backup.hapticLevel ?? 70),
    assistantNotes: Array.isArray(backup.assistant?.notes) ? backup.assistant.notes : [],
    assistantHouse: String(backup.assistant?.house ?? ''), assistantTaught: list(backup.assistant?.taught),
  })
  for (const automation of data.get<Automation[]>('automations')) engine.armAutomation(automation)
  for (const id of Object.keys(alerts)) engine.resetAlert(id)
  if (Array.isArray(backup.lgTvs)) lgtv.replaceAll(backup.lgTvs)
  if (backup.switchBot?.token) {
    switchBot.connect(text(backup.switchBot.token, 500), text(backup.switchBot.secret, 500))
    try {
      data.set('remotes', await switchBot.fetchRemotes())
    } catch {
      // The remotes come with the next refresh.
    }
  }
  if (backup.assistantKeys?.token) assistant.connect(text(backup.assistantKeys.accountId, 100), text(backup.assistantKeys.token, 500))
  data.touch()
  ok(res)
})

// --- push ----------------------------------------------------------------------------------------

route('GET', '/api/push/key', (_req, res) => json(res, 200, { key: vapidPublicKey }))
route('POST', '/api/push/subscribe', async (req, res, _p, _u, user) => {
  const { subscription } = await readJson<{ subscription: Parameters<typeof subscribe>[0] }>(req)
  if (!subscription?.endpoint) throw new HttpError(400, 'Iscrizione non valida')
  subscribe(subscription, true, user.id)
  ok(res)
})
route('POST', '/api/push/unsubscribe', async (req, res) => {
  const { endpoint } = await readJson<{ endpoint: string }>(req)
  if (endpoint) unsubscribe(endpoint)
  ok(res)
})
route('POST', '/api/push/test', async (_req, res) => {
  await push({ title: 'Hommaterial', body: str('srv_push_test'), url: '/', tag: 'test' })
  ok(res)
})

// --- server ----------------------------------------------------------------------------------

const open = new Set(['/api/session', '/api/register', '/api/login', '/api/logout'])

async function handle(req: IncomingMessage, res: ServerResponse) {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const path = url.pathname
  try {
    if (path === '/certificato' || path === '/hommaterial-ca.crt') {
      const ca = join(certDir, 'hommaterial-ca.crt')
      if (!existsSync(ca)) throw new HttpError(404, 'HTTPS locale non attivo')
      if (path === '/hommaterial-ca.crt') sendFile(req, res, ca, 'no-cache')
      else {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
        res.end(certificatePage(req))
      }
      return
    }
    if (path.startsWith('/api/')) {
      const user = sessionUser(req)
      if (!open.has(path) && !user) throw new HttpError(401, 'Accesso richiesto')
      for (const [method, pattern, handler] of routes) {
        const match = pattern.exec(path)
        if (match && method === req.method) {
          // Everything the handler reads or writes belongs to the logged in account.
          if (user) await data.withUser(user.id, () => handler(req, res, match.slice(1), url, user))
          else await handler(req, res, match.slice(1), url, { id: 0, username: '' })
          return
        }
      }
      throw new HttpError(404, 'Non trovato')
    }
    serveStatic(req, res, path)
  } catch (e) {
    const status = e instanceof HttpError ? e.status : 500
    if (status === 500) console.error(e)
    const body = { error: e instanceof HttpError ? e.message : 'Errore del server', code: e instanceof HttpError ? e.code : null }
    if (!res.headersSent) json(res, status, body)
    else res.end()
  }
}

/** How to trust the local certificate authority, for the "HTTPS in casa" mode. */
function certificatePage(req: IncomingMessage): string {
  const host = (req.headers.host ?? '').split(':')[0]
  return `<!doctype html><html lang="it"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hommaterial · certificato</title><style>body{font-family:system-ui,sans-serif;background:#FAF8FF;color:#1A1B21;max-width:560px;margin:auto;padding:24px;line-height:1.5}
a.b{display:inline-block;background:#1A3FB0;color:#fff;padding:14px 22px;border-radius:99px;text-decoration:none;font-weight:600}li{margin:6px 0}</style></head><body>
<h1>Installa il certificato di Hommaterial</h1><p>Serve una volta sola per telefono: dopo, Hommaterial si apre in HTTPS e si può installare come app.</p>
<p><a class="b" href="/hommaterial-ca.crt">Scarica il certificato</a></p>
<h3>Android</h3><ol><li>Scarica il file qui sopra.</li><li>Impostazioni → Sicurezza e privacy → Altre impostazioni di sicurezza → Installa da memoria → <b>Certificato CA</b>.</li><li>Scegli <i>hommaterial-ca.crt</i> e conferma.</li></ol>
<h3>iPhone</h3><ol><li>Apri questa pagina con Safari e scarica il file.</li><li>Impostazioni → Profilo scaricato → Installa.</li><li>Impostazioni → Generali → Info → Impostazioni attendibilità certificati → attiva <i>Hommaterial CA</i>.</li></ol>
<p>Poi apri <b>https://${host}:${HTTPS_PORT}</b></p></body></html>`
}

engine.start()

// A custom device may wait minutes between its actions, and a TV a minute for the user.
const configure = <T extends { requestTimeout: number; headersTimeout: number }>(server: T) => {
  server.requestTimeout = 10 * 60_000
  return server
}

configure(createHttp(handle)).listen(PORT, () => console.log(`Hommaterial ${VERSION} su http://0.0.0.0:${PORT}`))

const cert = join(certDir, 'server.crt')
const key = join(certDir, 'server.key')
if (existsSync(cert) && existsSync(key)) {
  configure(createHttps({ cert: readFileSync(cert), key: readFileSync(key) }, handle))
    .listen(HTTPS_PORT, () => console.log(`Hommaterial in HTTPS su https://0.0.0.0:${HTTPS_PORT}`))
}
