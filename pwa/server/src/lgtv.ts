import { createSocket } from 'node:dgram'
import { isIPv4 } from 'node:net'
import { randomUUID } from 'node:crypto'
import { str } from '../../shared/i18n.js'
import { currentUserId, getSecret, set, setSecret } from './db.ts'
import { HttpError } from './errors.ts'
import { SocketError, WebSocketClient } from './websocket.ts'

// The remote control of LG TVs with webOS (LgTv.kt), over a WebSocket on the home network: no
// account and no cloud. The server sits in the home, so it reaches the TVs the browser cannot.

/** The secure port of the webOS remote control; the plain one, 3000, is refused by recent firmware. */
const WEBOS_PORT = Number(process.env.LGTV_PORT ?? 3001)
const SSDP_TARGET = 'urn:lge-com:service:webos-second-screen:1'
const REGISTER_ID = 'register_0'
// Time for the user to find the TV remote and accept the request on screen.
const PAIRING_TIMEOUT_MS = 60_000
const REPLY_TIMEOUT_MS = 10_000
// How long a connection is kept after the last key, so that the next ones go at once.
const IDLE_MS = 30_000
/** What the id of an LG TV starts with, telling it apart from the remotes of SwitchBot. */
export const LG_PREFIX = 'lg:'
export const LG_TYPE = 'LG webOS'

export type LgTv = {
  id: string; name: string; host: string; key: string; pin: string
  macs: string[]; apps: Record<string, string>; inputs: Record<string, string>
}

export type FoundTv = { host: string; name: string | null }

/** A failure of a TV, told with one of the app's texts. */
export class LgTvError extends HttpError {
  text: string
  constructor(text: string, ...args: unknown[]) {
    super(502, str(text, ...args))
    this.text = text
  }
}

// --- the TVs of the account ------------------------------------------------------------------

export const all = (): LgTv[] => getSecret<LgTv[]>('lgtv', [])

/** What the browser sees of the TVs: everything but the key and the fingerprint. */
const shown = (tvs: LgTv[]) => tvs.map(({ key: _key, pin: _pin, ...rest }) => rest)

function save(tvs: LgTv[]): LgTv[] {
  setSecret('lgtv', tvs)
  set('lgTvs', shown(tvs))
  return tvs
}

/** Keeps [tv], in its place when known, else in the place of one paired before at the same address. */
export function put(tv: LgTv): LgTv[] {
  const list = all()
  if (list.some((it) => it.id === tv.id)) return save(list.map((it) => (it.id === tv.id ? tv : it)))
  return save([...list.filter((it) => it.host !== tv.host), tv])
}

export const remove = (id: string) => save(all().filter((it) => it.id !== id))

/** Keeps only [tvs], as those of a backup. */
export function replaceAll(tvs: LgTv[]) {
  const seen = new Set<string>()
  save(tvs.filter((tv) => tv.id.startsWith(LG_PREFIX) && !seen.has(tv.id) && seen.add(tv.id)).map((tv) => ({
    id: String(tv.id), name: String(tv.name ?? 'LG TV'), host: String(tv.host), key: String(tv.key ?? ''), pin: String(tv.pin ?? ''),
    macs: Array.isArray(tv.macs) ? tv.macs.map(String) : [], apps: tv.apps ?? {}, inputs: tv.inputs ?? {},
  })))
}

export const find = (id: string) => all().find((tv) => tv.id === id) ?? null

// --- webOS -----------------------------------------------------------------------------------

type Action = { call: string; payload?: Record<string, unknown> } | { key: string } | { wake: true }

/**
 * The command as webOS takes it. The names are those of the SwitchBot TV remotes, so that the
 * buttons, the voice and the custom devices work the same with either; the rest are LG's own.
 */
export function webOsAction(command: string, parameter = 'default'): Action | null {
  if (command === 'turnOn') return { wake: true }
  if (command === 'turnOff') return { call: 'ssap://system/turnOff' }
  if (command === 'volumeAdd') return { call: 'ssap://audio/volumeUp' }
  if (command === 'volumeSub') return { call: 'ssap://audio/volumeDown' }
  if (command === 'channelAdd') return { call: 'ssap://tv/channelUp' }
  if (command === 'channelSub') return { call: 'ssap://tv/channelDown' }
  // As the key of an infrared remote: it toggles.
  if (command === 'setMute') return { key: 'MUTE' }
  if (command === 'SetChannel' && /^\d+$/.test(parameter)) return { call: 'ssap://tv/openChannel', payload: { channelNumber: parameter } }
  if (command === 'setVolume') {
    const volume = Number(parameter)
    return /^\d+$/.test(parameter) && volume <= 100 ? { call: 'ssap://audio/setVolume', payload: { volume } } : null
  }
  if (['play', 'pause', 'stop', 'rewind', 'fastForward'].includes(command)) return { call: `ssap://media.controls/${command}` }
  if (command === 'toast') return { call: 'ssap://system.notifications/createToast', payload: { message: parameter } }
  if (command.startsWith('app:') && command.length > 4) return { call: 'ssap://system.launcher/launch', payload: { id: command.slice(4) } }
  if (command.startsWith('input:') && command.length > 6) return { call: 'ssap://tv/switchInput', payload: { inputId: command.slice(6) } }
  if (command.startsWith('key:') && /^[A-Z0-9_]+$/.test(command.slice(4))) return { key: command.slice(4) }
  return null
}

/** The request asking the TV to be controlled. Without a [key] the TV asks the user; with one it lets the app in. */
function webOsRegister(key: string | null) {
  // The permissions of the second screen apps, as the open source clients ask for them.
  const permissions = [
    'APP_TO_APP', 'CLOSE', 'CONTROL_AUDIO', 'CONTROL_DISPLAY', 'CONTROL_INPUT_JOYSTICK',
    'CONTROL_INPUT_MEDIA_PLAYBACK', 'CONTROL_INPUT_MEDIA_RECORDING', 'CONTROL_INPUT_TEXT',
    'CONTROL_INPUT_TV', 'CONTROL_MOUSE_AND_KEYBOARD', 'CONTROL_POWER', 'CONTROL_TV_SCREEN',
    'LAUNCH', 'LAUNCH_WEBAPP', 'READ_APP_STATUS', 'READ_COUNTRY_INFO', 'READ_CURRENT_CHANNEL',
    'READ_INPUT_DEVICE_LIST', 'READ_INSTALLED_APPS', 'READ_LGE_SDX', 'READ_LGE_TV_INPUT_EVENTS',
    'READ_NETWORK_STATE', 'READ_NOTIFICATIONS', 'READ_POWER_STATE', 'READ_RUNNING_APPS',
    'READ_SETTINGS', 'READ_TV_CHANNEL_LIST', 'READ_TV_CURRENT_TIME', 'READ_UPDATE_INFO',
    'SEARCH', 'TEST_OPEN', 'TEST_PROTECTED', 'TEST_SECURE', 'UPDATE_FROM_REMOTE_APP',
    'WRITE_NOTIFICATION_ALERT', 'WRITE_NOTIFICATION_TOAST', 'WRITE_SETTINGS',
  ]
  const payload: Record<string, unknown> = {
    forcePairing: false,
    pairingType: 'PROMPT',
    manifest: { appVersion: '1.1', manifestVersion: 1, permissions },
  }
  if (key != null) payload['client-key'] = key
  return { type: 'register', id: REGISTER_ID, payload }
}

const webOsRequest = (id: string, uri: string, payload: Record<string, unknown> = {}) => ({ type: 'request', id, uri, payload })

const isMac = (text: string) => /^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$/.test(text)

/** The hardware addresses in what the TV tells of its network, wired and wireless. */
function webOsMacs(payload: any): string[] {
  const macs = ['wiredInfo', 'wifiInfo'].map((it) => payload?.[it]?.macAddress).filter((it) => typeof it === 'string' && isMac(it))
  return [...new Set(macs.map((it: string) => it.toLowerCase()))]
}

/** The magic packet that wakes up the machine with [mac]: six bytes 0xFF, then the address sixteen times. */
export function wakeOnLanPacket(mac: string): Buffer {
  if (!isMac(mac)) throw new Error(`Not a MAC address: ${mac}`)
  const address = Buffer.from(mac.split(/[:-]/).map((it) => parseInt(it, 16)))
  return Buffer.concat([Buffer.alloc(6, 0xff), ...Array(16).fill(address)])
}

/** A TV in an answer to the search, null when the answer came from something else. */
export function parseSsdp(text: string, host: string): FoundTv | null {
  const lines = text.split(/\r?\n/)
  if (!lines[0]?.startsWith('HTTP/1.1 200')) return null
  const headers: Record<string, string> = {}
  for (const line of lines.slice(1)) {
    const i = line.indexOf(':')
    if (i > 0) headers[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim()
  }
  if (headers.st !== SSDP_TARGET) return null
  // LG TVs put the name the user gave them in a header of their own, encoded as in a URL.
  let name: string | null = null
  try {
    name = decodeURIComponent((headers['dlnadevicename.lge.com'] ?? '').replace(/\+/g, ' ')).trim() || null
  } catch {
    name = null
  }
  return { host, name }
}

/** The apps in the answer of the TV, by id, in its order. */
function webOsApps(payload: any): Record<string, string> {
  const out: Record<string, string> = {}
  for (const it of payload?.launchPoints ?? []) if (it?.id && String(it.title ?? '').trim()) out[it.id] = it.title
  return out
}

/** The sources of the TV in its answer, by id, with the names it shows for them. */
function webOsInputs(payload: any): Record<string, string> {
  const out: Record<string, string> = {}
  for (const it of payload?.devices ?? []) if (it?.id) out[it.id] = String(it.label ?? '').trim() || it.id
  return out
}

/**
 * What turns the text [sent] to the TV into [typed], on the keyboard the TV shows in its text
 * fields: the characters after what the two share are deleted, then the new ones written.
 */
export function webOsTyping(sent: string, typed: string): { call: string; payload: Record<string, unknown> }[] {
  let same = 0
  while (same < sent.length && same < typed.length && sent[same] === typed[same]) same++
  // Not halfway into a character made of two, as an emoji.
  if (same > 0 && /[\uD800-\uDBFF]/.test(sent[same - 1])) same--
  const removed = [...sent.slice(same)].length
  const added = typed.slice(same)
  const calls = []
  if (removed > 0) calls.push({ call: 'ssap://com.webos.service.ime/deleteCharacters', payload: { count: removed } })
  if (added) calls.push({ call: 'ssap://com.webos.service.ime/insertText', payload: { text: added, replace: 0 } })
  return calls
}

// --- sessions with a TV ------------------------------------------------------------------------

class Session {
  socket: WebSocketClient
  private count = 0
  private inbox: any[] = []
  private waiting: (() => void)[] = []

  constructor(socket: WebSocketClient) {
    this.socket = socket
    socket.onText = (text) => {
      try {
        this.inbox.push(JSON.parse(text))
      } catch {
        return
      }
      this.wake()
    }
    socket.onClose = () => this.wake()
  }

  get open() {
    return this.socket.open
  }

  private wake() {
    const waiting = this.waiting
    this.waiting = []
    for (const resolve of waiting) resolve()
  }

  send(message: unknown) {
    this.socket.send(JSON.stringify(message))
  }

  /** The next message about the request [id]; those about others are let go. */
  async reply(id: string, timeoutMs: number): Promise<any> {
    const end = Date.now() + timeoutMs
    for (;;) {
      const i = this.inbox.findIndex((m) => m?.id === id)
      if (i >= 0) return this.inbox.splice(i, 1)[0]
      this.inbox = []
      if (!this.open) throw new LgTvError('lgtv_closed')
      const left = end - Date.now()
      if (left <= 0) return null
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, left)
        this.waiting.push(() => {
          clearTimeout(timer)
          resolve()
        })
      })
    }
  }

  /** Sends a request and returns the payload of its answer, if the TV did it. */
  async call(uri: string, payload: Record<string, unknown> = {}): Promise<any> {
    const id = `call_${this.count++}`
    this.send(webOsRequest(id, uri, payload))
    const answer = await this.reply(id, REPLY_TIMEOUT_MS)
    if (!answer) throw new LgTvError('lgtv_no_answer')
    const body = answer.payload ?? {}
    if (answer.type === 'error' || body.returnValue === false) throw new LgTvError('lgtv_refused', answer.error || body.errorText || '')
    return body
  }

  close() {
    this.socket.close()
  }
}

async function open(url: string, host: string, pin: string | null): Promise<Session> {
  try {
    return new Session(await WebSocketClient.connect(url, { pin, timeoutMs: REPLY_TIMEOUT_MS }))
  } catch (e) {
    throw new LgTvError(e instanceof SocketError && e.changed ? 'lgtv_changed' : 'lgtv_unreachable', host)
  }
}

/** Registers on [session], returning the key; [onAsking] when the TV asks the user. */
async function register(session: Session, key: string | null, onAsking: () => void): Promise<string> {
  session.send(webOsRegister(key))
  const end = Date.now() + PAIRING_TIMEOUT_MS
  let message = await session.reply(REGISTER_ID, PAIRING_TIMEOUT_MS)
  // A plain response means the TV is asking the user; the outcome comes after.
  while (message?.type === 'response') {
    if (message.payload?.pairingType === 'PROMPT') onAsking()
    message = await session.reply(REGISTER_ID, Math.max(0, end - Date.now()))
  }
  if (!message) throw new LgTvError('lgtv_no_answer')
  if (message.type !== 'registered') throw new LgTvError('lgtv_denied')
  const given = message.payload?.['client-key']
  if (given) return given
  if (key) return key
  throw new LgTvError('lgtv_denied')
}

type Kept = { main: Session; keys: Session | null; idle: NodeJS.Timeout | null }

// The connections kept open for a while, by TV, and one request at a time for each TV.
const kept = new Map<string, Kept>()
const locks = new Map<string, Promise<unknown>>()

function closeKept(connection: Kept) {
  if (connection.idle) clearTimeout(connection.idle)
  connection.keys?.close()
  connection.main.close()
}

function forget(tv: LgTv, connection: Kept) {
  if (kept.get(tv.id) === connection) kept.delete(tv.id)
  closeKept(connection)
}

function keep(tv: LgTv, connection: Kept) {
  kept.set(tv.id, connection)
  if (connection.idle) clearTimeout(connection.idle)
  connection.idle = setTimeout(() => forget(tv, connection), IDLE_MS)
}

async function connect(tv: LgTv): Promise<Kept> {
  const session = await open(`wss://${tv.host}:${WEBOS_PORT}`, tv.host, tv.pin)
  try {
    await register(session, tv.key, () => {
      throw new LgTvError('lgtv_pair_again', tv.name)
    })
  } catch (e) {
    session.close()
    throw e
  }
  return { main: session, keys: null, idle: null }
}

/**
 * Runs [block] on the connection to [tv], kept from before when still open. One request at a
 * time: the answers come on the same socket, and keys are pressed one after the other anyway.
 */
async function withSession<T>(tv: LgTv, block: (connection: Kept) => Promise<T>): Promise<T> {
  const lockKey = `${currentUserId()}:${tv.id}`
  const before = locks.get(lockKey) ?? Promise.resolve()
  const run = before.catch(() => {}).then(async () => {
    const previous = kept.get(tv.id)
    const reused = previous?.main.open ? previous : null
    if (reused?.idle) clearTimeout(reused.idle)
    const connection = reused ?? await connect(tv)
    try {
      const result = await block(connection)
      keep(tv, connection)
      return result
    } catch (e) {
      forget(tv, connection)
      if (!reused || !(e instanceof LgTvError) || e.text !== 'lgtv_closed') throw e
      // The TV closed it in the meantime, as it does going to standby: once more on a new one.
      const fresh = await connect(tv)
      try {
        const result = await block(fresh)
        keep(tv, fresh)
        return result
      } catch (again) {
        closeKept(fresh)
        throw again
      }
    }
  })
  locks.set(lockKey, run)
  try {
    return await run
  } finally {
    if (locks.get(lockKey) === run) locks.delete(lockKey)
  }
}

/** Looks for TVs on the network for [millis], with the search of the second screen apps. */
export function discover(millis = 3000): Promise<FoundTv[]> {
  return new Promise((resolve) => {
    const found = new Map<string, FoundTv>()
    const socket = createSocket({ type: 'udp4', reuseAddr: true })
    const search = Buffer.from(`M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ${SSDP_TARGET}\r\n\r\n`)
    const finish = () => {
      try {
        socket.close()
      } catch {
        // Already closed.
      }
      resolve([...found.values()])
    }
    socket.on('message', (message, remote) => {
      const tv = parseSsdp(message.toString(), remote.address)
      if (tv && !found.has(tv.host)) found.set(tv.host, tv)
    })
    socket.on('error', finish)
    socket.bind(0, () => {
      // Twice, as datagrams may get lost.
      for (let i = 0; i < 2; i++) socket.send(search, 1900, '239.255.255.250')
      setTimeout(finish, millis)
    })
  })
}

/** Wakes up [tv] from standby, which works only when its network stays on. */
export function wake(tv: LgTv): Promise<void> {
  if (!tv.macs.length) throw new LgTvError('lgtv_no_mac')
  const targets = ['255.255.255.255']
  // The broadcast of the TV's own network as well, which some routers prefer.
  if (isIPv4(tv.host)) targets.push(tv.host.split('.').slice(0, 3).concat('255').join('.'))
  return new Promise((resolve) => {
    const socket = createSocket('udp4')
    socket.bind(0, () => {
      socket.setBroadcast(true)
      let pending = 0
      for (const mac of tv.macs) {
        const packet = wakeOnLanPacket(mac)
        for (const target of targets) {
          pending++
          socket.send(packet, 9, target, () => {
            if (--pending === 0) {
              socket.close()
              resolve()
            }
          })
        }
      }
      if (pending === 0) {
        socket.close()
        resolve()
      }
    })
  })
}

/**
 * Asks the TV at [host] to be controlled, which it does by asking the user on screen, and
 * returns it paired. [onAsking] is called while the TV waits for the user; [greeting] is shown
 * on screen once it accepted.
 */
export async function pair(host: string, greeting: string, onAsking: () => void): Promise<LgTv> {
  const session = await open(`wss://${host}:${WEBOS_PORT}`, host, null)
  try {
    session.send({ id: 'hello', type: 'hello', payload: {} })
    await session.reply('hello', REPLY_TIMEOUT_MS)
    // Recent webOS tells about itself only before the registration.
    session.send(webOsRequest('info', 'ssap://system/getSystemInfo'))
    const model = String((await session.reply('info', REPLY_TIMEOUT_MS))?.payload?.modelName ?? '').trim()
    const key = await register(session, null, onAsking)
    session.send(webOsRequest('network', 'ssap://com.webos.service.connectionmanager/getinfo'))
    const macs = webOsMacs((await session.reply('network', REPLY_TIMEOUT_MS))?.payload)
    try {
      await session.call('ssap://system.notifications/createToast', { message: greeting })
    } catch {
      // Only a greeting.
    }
    const pin = session.socket.seenPin
    if (!pin) throw new LgTvError('lgtv_unreachable', host)
    return { id: LG_PREFIX + randomUUID(), name: model ? `LG ${model}` : 'LG TV', host, key, pin, macs, apps: {}, inputs: {} }
  } finally {
    session.close()
  }
}

/** Carries out a [command] of the app on [tv]; see [webOsAction]. */
export async function send(tv: LgTv, command: string, parameter = 'default') {
  const action = webOsAction(command, parameter)
  if (!action) throw new LgTvError('lgtv_unknown_command', command)
  if ('wake' in action) return wake(tv)
  if ('call' in action) {
    await withSession(tv, (c) => c.main.call(action.call, action.payload ?? {}))
    return
  }
  await withSession(tv, async (connection) => {
    // The keys go on a socket of their own, which the TV opens on request.
    if (!connection.keys?.open) {
      const path = String((await connection.main.call('ssap://com.webos.service.networkinput/getPointerInputSocket')).socketPath ?? '')
      if (!/^wss?:\/\//.test(path)) throw new LgTvError('lgtv_refused', 'socketPath')
      connection.keys = await open(path, tv.host, path.startsWith('wss://') ? tv.pin : null)
    }
    connection.keys.socket.send(`type:button\nname:${action.key}\n\n`)
  })
}

/** The apps of [tv], by id. */
export const apps = (tv: LgTv) => withSession(tv, async (c) => webOsApps(await c.main.call('ssap://com.webos.applicationManager/listLaunchPoints')))

/** The sources of [tv], by id, such as the HDMI ports and the antenna. */
export const inputs = (tv: LgTv) => withSession(tv, async (c) => webOsInputs(await c.main.call('ssap://tv/getExternalInputList')))

/** Turns the text [sent] in the field open on [tv] into [typed]; see [webOsTyping]. */
export async function type(tv: LgTv, sent: string, typed: string) {
  const calls = webOsTyping(sent, typed)
  if (calls.length) await withSession(tv, async (c) => {
    for (const it of calls) await c.main.call(it.call, it.payload)
  })
}

/** Presses Enter on the keyboard of [tv], as at the end of a search. */
export const enter = (tv: LgTv) => withSession(tv, (c) => c.main.call('ssap://com.webos.service.ime/sendEnterKey'))

export function requireTv(id: string): LgTv {
  const tv = find(id)
  if (!tv) throw new HttpError(404, str('quick_not_found'))
  return tv
}
