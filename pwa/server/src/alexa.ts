import { createHash, randomBytes } from 'node:crypto'
import { str } from '../../shared/i18n.js'
import { currentUserId, getSecret, setSecret } from './db.ts'
import { HttpError, NotLoggedInError, OfflineError, request } from './errors.ts'

// Alexa as the Android app speaks to it (AlexaAuth.kt, AlexaApi.kt): the server holds the refresh
// token and the web session of each account, so the browser never sees them.

/** Amazon always signs the Alexa app in on amazon.com, whatever the account's marketplace. */
const LOGIN_DOMAIN = 'amazon.com'

/** The national Amazon sites an Alexa account can belong to. */
export const MARKETPLACES = [
  { domain: 'amazon.it', locale: 'it-IT' },
  { domain: 'amazon.de', locale: 'de-DE' },
  { domain: 'amazon.fr', locale: 'fr-FR' },
  { domain: 'amazon.es', locale: 'es-ES' },
  { domain: 'amazon.co.uk', locale: 'en-GB' },
  { domain: 'amazon.com', locale: 'en-US' },
]

type Market = (typeof MARKETPLACES)[number]

// Amazon only issues tokens to clients that identify as its own Alexa app.
const APP_VERSION = '2.2.651540.0'
const USER_AGENT = `AmazonWebView/Amazon Alexa/${APP_VERSION}/iOS/18.3.1/iPhone`
const APP_NAME = 'Hommaterial'
const DEVICE_TYPE = 'A2IVLV5VM2W81'

const DEVICES_QUERY = `query Endpoints { endpoints { items {
  friendlyName
  displayCategories { primary { value } }
  legacyAppliance { applianceId entityId isEnabled capabilities }
} } }`

// Asks for more than the app uses, to see what a kind of device not yet supported has to offer.
const DIAGNOSTICS_QUERY = `query Endpoints { endpoints { items {
  friendlyName
  displayCategories { primary { value } }
  legacyAppliance { applianceId entityId isEnabled applianceTypes manufacturerName modelName actions capabilities }
} } }`

// Echo and Fire TV devices also report a power capability but are not home devices.
const VOICE_DEVICE_CATEGORY = 'ALEXA_VOICE_ENABLED'

export type Device = {
  applianceId: string; entityId: string; name: string; category: string; room: string | null
  hasPower: boolean; hasBrightness: boolean; isSensor: boolean; hasColor: boolean; hasColorTemperature: boolean
}

export type DeviceState = {
  power: boolean | null; brightness: number | null; temperature: number | null; humidity: number | null
  colorName: string | null; reachable: boolean
}

type Attempt = { deviceId: string; frc: string; mapMd: string; verifier: string; url: string }
type Session = { cookie: string; csrf: string }
type Answer = { status: number; cookies: string[]; body: string; errorType: string | null }

const hex = (bytes: Buffer) => bytes.toString('hex')
const base64Url = (bytes: Buffer) => bytes.toString('base64url')

function parseCookies(lines: string[]): Record<string, string> {
  const jar: Record<string, string> = {}
  for (const line of lines) {
    const pair = line.split(';')[0]
    const i = pair.indexOf('=')
    if (i > 0) jar[pair.slice(0, i).trim()] = pair.slice(i + 1).trim()
  }
  return jar
}

const cookieHeader = (jar: Record<string, string>) => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ')

/** One request to Amazon, without following redirects: their cookies matter. */
async function call(url: string, method: string, headers: Record<string, string>, body: string | null = null): Promise<Answer> {
  const response = await request(url, { method, headers, body: body ?? undefined, redirect: 'manual' })
  return { status: response.status, cookies: response.headers.getSetCookie(), body: await response.text(), errorType: response.headers.get('x-amzn-errortype') }
}

export const emptyState = (): DeviceState => ({
  power: null, brightness: null, temperature: null, humidity: null, colorName: null, reachable: true,
})

// --- sign-in -----------------------------------------------------------------------------------

/** The marketplace of the account; amazon.it until the user picks another. */
export function marketplace(): Market {
  const saved = getSecret<string | null>('alexa_marketplace', null)
  return MARKETPLACES.find((m) => m.domain === saved) ?? MARKETPLACES[0]
}

export function setMarketplace(domain: string) {
  if (!MARKETPLACES.some((m) => m.domain === domain)) throw new HttpError(400, str('something_wrong'))
  setSecret('alexa_marketplace', domain)
}

export const isLoggedIn = () => getSecret<string | null>('alexa_refresh', null) != null

/** Everything generated for one sign-in attempt, kept until it is completed. */
export function newLoginAttempt(): Attempt {
  const mapMd = JSON.stringify({
    device_user_dictionary: [],
    device_registration_data: { software_version: '1' },
    app_identifier: { app_version: APP_VERSION, bundle_id: 'com.amazon.echo' },
  })
  // Hex of an upper-case hex serial, followed by hex("#" + DEVICE_TYPE).
  const deviceId = hex(Buffer.from(hex(randomBytes(16)).toUpperCase())) + hex(Buffer.from(`#${DEVICE_TYPE}`))
  const verifier = base64Url(randomBytes(32))
  const challenge = base64Url(createHash('sha256').update(verifier).digest())
  const enc = encodeURIComponent
  const url = `https://www.${LOGIN_DOMAIN}/ap/signin` +
    `?openid.return_to=${enc(`https://www.${LOGIN_DOMAIN}/ap/maplanding`)}` +
    '&openid.assoc_handle=amzn_dp_project_dee_ios' +
    `&openid.identity=${enc('http://specs.openid.net/auth/2.0/identifier_select')}` +
    '&pageId=amzn_dp_project_dee_ios' +
    '&accountStatusPolicy=P1' +
    `&openid.claimed_id=${enc('http://specs.openid.net/auth/2.0/identifier_select')}` +
    '&openid.mode=checkid_setup' +
    `&openid.ns.oa2=${enc(`http://www.${LOGIN_DOMAIN}/ap/ext/oauth/2`)}` +
    `&openid.oa2.client_id=${enc(`device:${deviceId}`)}` +
    `&openid.ns.pape=${enc('http://specs.openid.net/extensions/pape/1.0')}` +
    '&openid.oa2.response_type=code' +
    `&openid.ns=${enc('http://specs.openid.net/auth/2.0')}` +
    '&openid.pape.max_auth_age=0' +
    '&openid.oa2.scope=device_auth_access' +
    '&openid.oa2.code_challenge_method=S256' +
    `&openid.oa2.code_challenge=${challenge}` +
    `&language=${marketplace().locale.replace('-', '_')}`
  const attempt = {
    deviceId,
    frc: randomBytes(313).toString('base64'),
    mapMd: Buffer.from(mapMd).toString('base64'),
    verifier,
    url,
  }
  setSecret('alexa_attempt', attempt)
  return attempt
}

export const pendingAttempt = () => getSecret<Attempt | null>('alexa_attempt', null)

/** Trades the authorization code from the sign-in page for a long-lived refresh token. */
export async function completeLogin(landing: string) {
  const attempt = pendingAttempt()
  let code = landing.trim()
  try {
    code = new URL(code).searchParams.get('openid.oa2.authorization_code') || ''
  } catch {
    // Not an address: the code itself was pasted.
  }
  if (!attempt) throw new HttpError(400, str('pwa_login_restart'))
  if (!code) throw new HttpError(400, str('pwa_login_no_code'))
  const cookies = { frc: attempt.frc, 'map-md': attempt.mapMd }
  const body = {
    requested_extensions: ['device_info', 'customer_info'],
    cookies: {
      domain: `.${LOGIN_DOMAIN}`,
      website_cookies: Object.entries(cookies).map(([Name, Value]) => ({ Name, Value })),
    },
    registration_data: {
      domain: 'Device',
      app_version: APP_VERSION,
      device_type: DEVICE_TYPE,
      device_name: `%FIRST_NAME%'s%DUPE_STRATEGY_1ST%${APP_NAME}`,
      os_version: '18.3.1',
      device_serial: hex(randomBytes(16)),
      device_model: 'iPhone',
      app_name: APP_NAME,
      software_version: '1',
    },
    auth_data: {
      client_id: attempt.deviceId,
      authorization_code: code,
      code_verifier: attempt.verifier,
      code_algorithm: 'SHA-256',
      client_domain: 'DeviceLegacy',
    },
    user_context_map: { frc: attempt.frc },
    requested_token_type: ['bearer', 'mac_dms', 'website_cookies'],
  }
  const response = await call(`https://api.${LOGIN_DOMAIN}/auth/register`, 'POST', {
    'User-Agent': USER_AGENT,
    'Accept-Language': marketplace().locale,
    Accept: 'application/json',
    'Content-Type': 'application/json',
    Cookie: cookieHeader(cookies),
    'x-amzn-identity-auth-domain': `api.${LOGIN_DOMAIN}`,
  }, JSON.stringify(body))
  let json: { response?: { success?: { tokens?: { bearer?: { refresh_token?: string } } } } } | null = null
  try {
    json = JSON.parse(response.body)
  } catch {
    // Told below, with the status of the answer.
  }
  const refreshToken = json?.response?.success?.tokens?.bearer?.refresh_token
  if (!refreshToken) throw new HttpError(400, str('registration_refused', response.status))
  setSecret('alexa_session', null)
  setSecret('alexa_attempt', null)
  setSecret('alexa_refresh', refreshToken)
  await session(true)
}

const refreshing = new Map<number, Promise<Session>>()

/** Returns the web session used by the Alexa API, creating it from the refresh token if needed. */
async function session(forceRefresh = false): Promise<Session> {
  const saved = getSecret<Session | null>('alexa_session', null)
  if (!forceRefresh && saved) return saved
  // Callers that find the session stale together wait for one renewal.
  const id = currentUserId() ?? 0
  let pending = refreshing.get(id)
  if (!pending) {
    pending = exchangeForSession().finally(() => refreshing.delete(id))
    refreshing.set(id, pending)
  }
  return pending
}

async function exchangeForSession(): Promise<Session> {
  const refreshToken = getSecret<string | null>('alexa_refresh', null)
  if (!refreshToken) throw new NotLoggedInError()
  const market = marketplace()
  const host = `alexa.${market.domain}`
  const form = new URLSearchParams({
    'di.os.name': 'iOS',
    app_version: APP_VERSION,
    domain: `.${market.domain}`,
    source_token: refreshToken,
    requested_token_type: 'auth_cookies',
    source_token_type: 'refresh_token',
    'di.hw.version': 'iPhone',
    'di.sdk.version': '6.12.4',
    app_name: APP_NAME,
    'di.os.version': '16.6',
  })
  const exchange = await call(`https://www.${market.domain}/ap/exchangetoken/cookies`, 'POST', {
    'User-Agent': USER_AGENT,
    'Accept-Language': market.locale,
    Accept: '*/*',
    'Content-Type': 'application/x-www-form-urlencoded',
    'x-amzn-identity-auth-domain': `api.${market.domain}`,
  }, form.toString())
  let json: { response?: { tokens?: { cookies?: Record<string, { Name: string; Value: string }[]> } } } | null = null
  try {
    json = JSON.parse(exchange.body)
  } catch {
    // Told below.
  }
  const cookies = json?.response?.tokens?.cookies?.[`.${market.domain}`]
  if (!Array.isArray(cookies)) {
    // The stored token is kept: a later sign-in replaces it, and a transient refusal heals itself.
    if (exchange.status === 400 || exchange.status === 401) throw new NotLoggedInError(str('login_expired'))
    throw new HttpError(502, str('amazon_no_answer', exchange.status))
  }
  const jar: Record<string, string> = {}
  for (const c of cookies) jar[c.Name] = c.Value

  // Any authenticated Alexa page hands out the csrf cookie the API requires.
  const page = await call(`https://${host}/api/language`, 'GET', {
    'User-Agent': USER_AGENT,
    Accept: '*/*',
    Referer: `https://${host}/spa/index.html`,
    Origin: `https://${host}`,
    Cookie: cookieHeader(jar),
  })
  Object.assign(jar, parseCookies(page.cookies))
  if (!jar.csrf) throw new HttpError(502, str('amazon_no_csrf'))
  const fresh = { cookie: cookieHeader(jar), csrf: jar.csrf }
  setSecret('alexa_session', fresh)
  return fresh
}

/** Takes the refresh token the Android app got with its own sign-in, checking that Amazon accepts it. */
export async function adoptToken(refreshToken: string, domain: string | null) {
  if (domain) setMarketplace(domain)
  const before = getSecret<string | null>('alexa_refresh', null)
  setSecret('alexa_refresh', refreshToken)
  setSecret('alexa_session', null)
  try {
    await session(true)
  } catch (e) {
    setSecret('alexa_refresh', before)
    throw e
  }
}

export function logout() {
  for (const key of ['alexa_refresh', 'alexa_session', 'alexa_attempt']) setSecret(key, null)
}

// --- the API -------------------------------------------------------------------------------------

/** Why Amazon refused a call, in its own words when it gives them, to show after the code. */
function amazonReason(body: string, errorType: string | null): string {
  let said: string | null = null
  try {
    const o = JSON.parse(body)
    for (const key of ['message', 'Message', 'errorMessage', 'error', 'code', 'type']) {
      if (typeof o?.[key] === 'string' && o[key].trim()) {
        said = o[key]
        break
      }
    }
  } catch {
    said = null
  }
  said ??= errorType?.split(':')[0] || null
  return said ? ': ' + said.slice(0, 120) : ''
}

/** A call to the Alexa web API; [site] is "www" for the lists, which live on the shop's own host. */
async function api(method: string, path: string, body: unknown = null, site = 'alexa'): Promise<any> {
  const text = await apiText(method, path, body, site)
  if (!text.trim()) return {}
  try {
    return JSON.parse(text)
  } catch {
    throw new HttpError(502, str('alexa_unexpected'))
  }
}

/** The same call, its answer as it comes: [headers] are added to those of the Alexa app. */
async function apiText(method: string, path: string, body: unknown = null, site = 'alexa', headers: Record<string, string> = {}): Promise<string> {
  const market = marketplace()
  const host = `alexa.${market.domain}`
  let current = await session()
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await call(`https://${site}.${market.domain}${path}`, method, {
      // The lists answer only to the agent of the Alexa app itself, as alexa-remote sends it.
      'User-Agent': site === 'www' ? LISTS_USER_AGENT : USER_AGENT,
      Accept: 'application/json; charset=utf-8',
      'Accept-Language': market.locale,
      Referer: `https://${host}/spa/index.html`,
      Origin: `https://${host}`,
      csrf: current.csrf,
      Cookie: current.cookie,
      ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      ...headers,
    }, body ? JSON.stringify(body) : null)
    // The web session lasts a few days; the first 401 just means it needs renewing.
    if (response.status === 401 && attempt === 0) {
      current = await session(true)
      continue
    }
    if (response.status === 401) throw new NotLoggedInError(str('login_expired'))
    if (response.status < 200 || response.status >= 300) {
      throw new HttpError(502, str('alexa_error', response.status) + amazonReason(response.body, response.errorType))
    }
    return response.body
  }
  throw new HttpError(502, str('alexa_unexpected'))
}

/** Controllable devices and sensors, each tagged with its Alexa room. */
export async function devices(): Promise<Device[]> {
  const [endpoints, groups] = await Promise.all([
    api('POST', '/nexus/v1/graphql', { query: DEVICES_QUERY }),
    api('GET', '/api/phoenix/group'),
  ])
  const roomOf: Record<string, string> = {}
  for (const group of groups.applianceGroups || []) {
    for (const id of group.applianceIds || []) if (!(id in roomOf)) roomOf[id] = group.name
  }
  const items = endpoints?.data?.endpoints?.items
  if (!Array.isArray(items)) throw new HttpError(502, str('alexa_unexpected'))
  const list: Device[] = []
  for (const item of items) {
    const appliance = item.legacyAppliance
    if (!appliance?.applianceId || appliance.isEnabled === false) continue
    const interfaces = new Set((appliance.capabilities || []).map((c: { interfaceName: string }) => c.interfaceName))
    const category = item.displayCategories?.primary?.value || 'OTHER'
    const device: Device = {
      applianceId: appliance.applianceId,
      entityId: appliance.entityId || '',
      name: item.friendlyName || '',
      category,
      room: roomOf[appliance.applianceId] ?? null,
      hasPower: interfaces.has('Alexa.PowerController'),
      hasBrightness: interfaces.has('Alexa.BrightnessController'),
      isSensor: interfaces.has('Alexa.TemperatureSensor'),
      hasColor: interfaces.has('Alexa.ColorController'),
      hasColorTemperature: interfaces.has('Alexa.ColorTemperatureController'),
    }
    if (category !== VOICE_DEVICE_CATEGORY && (device.hasPower || device.isSensor)) list.push(device)
  }
  return list.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
}

/** Current state of every given device, keyed by applianceId. */
export async function states(applianceIds: string[]): Promise<Record<string, DeviceState>> {
  if (!applianceIds.length) return {}
  const stateRequests = applianceIds.map((entityId) => ({ entityId, entityType: 'APPLIANCE' }))
  const response = await api('POST', '/api/phoenix/state', { stateRequests })
  const result: Record<string, DeviceState> = {}
  for (const entry of response.deviceStates || []) {
    const state = emptyState()
    for (const raw of entry.capabilityStates || []) {
      // Each capability arrives as a JSON document encoded inside a string.
      let cap
      try {
        cap = typeof raw === 'string' ? JSON.parse(raw) : raw
      } catch {
        continue
      }
      switch (cap.name) {
        case 'powerState': state.power = cap.value === 'ON'; break
        case 'brightness': state.brightness = Number(cap.value); break
        case 'temperature': state.temperature = cap.value?.value ?? null; break
        case 'relativeHumidity': state.humidity = Math.trunc(Number(cap.value)); break
        case 'colorProperties': state.colorName = cap.value?.name || null; break
        default: break
      }
    }
    result[entry.entity.entityId] = state
  }
  for (const error of response.errors || []) {
    const id = error.entity?.entityId
    if (id) result[id] = { ...emptyState(), reachable: false }
  }
  return result
}

/** Everything Alexa says about [device], as readable JSON, for writing support for a new kind. */
export async function diagnostics(device: Device): Promise<string> {
  const find = (response: any) => response?.data?.endpoints?.items
    ?.find((it: any) => it.legacyAppliance?.applianceId === device.applianceId)
  let wide = null
  try {
    wide = find(await api('POST', '/nexus/v1/graphql', { query: DIAGNOSTICS_QUERY }))
  } catch (e) {
    // Should Alexa refuse a field of the wider query, the one the app normally uses still answers.
    if (e instanceof NotLoggedInError || e instanceof OfflineError) throw e
  }
  const description = wide ?? find(await api('POST', '/nexus/v1/graphql', { query: DEVICES_QUERY }))
  const state = await api('POST', '/api/phoenix/state', {
    stateRequests: [{ entityId: device.applianceId, entityType: 'APPLIANCE' }],
  })
  for (const entry of state.deviceStates || []) {
    entry.capabilityStates = (entry.capabilityStates || []).map((c: string) => {
      try {
        return JSON.parse(c)
      } catch {
        return c
      }
    })
  }
  return JSON.stringify({ device: description ?? null, state }, null, 2)
}

/** Sends [parameters] to [device]: {action: 'turnOn'}, {action: 'setBrightness', brightness}... */
export async function control(device: Device, parameters: Record<string, unknown>) {
  const request = { entityId: device.entityId, entityType: 'APPLIANCE', parameters }
  const response = await api('PUT', '/api/phoenix/state', { controlRequests: [request] })
  const error = response.errors?.[0]
  if (error) {
    throw new HttpError(502, ['ENDPOINT_UNREACHABLE', 'NO_SUCH_ENDPOINT'].includes(error.code)
      ? str('device_unreachable', device.name)
      : str('command_refused', device.name, error.code))
  }
}

export const setPower = (device: Device, on: boolean) => control(device, { action: on ? 'turnOn' : 'turnOff' })

// --- the Echo devices, to say things to Alexa ---------------------------------------------------
// What is said goes to Alexa as text, as if spoken to an Echo, which answers aloud: the command of
// the routines of the Alexa app ("Custom" / "Tell Alexa"), as alexa-remote sends it. Undocumented.

export type Echo = { serial: string; name: string; type: string; customer: string; online: boolean }

let knownEchos: Echo[] = []

/** The devices Alexa can be spoken to through, those with a speaker of their own. */
export async function echos(): Promise<Echo[]> {
  const answer = await api('GET', '/api/devices-v2/device?cached=true')
  knownEchos = (Array.isArray(answer.devices) ? answer.devices : [])
    .filter((it: any) => it?.serialNumber && it.deviceFamily !== 'WHA' && (it.capabilities ?? []).includes('VOLUME_SETTING'))
    .map((it: any) => ({
      serial: String(it.serialNumber), name: String(it.accountName ?? it.serialNumber), type: String(it.deviceType ?? ''),
      customer: String(it.deviceOwnerCustomerId ?? ''), online: it.online !== false,
    }))
    .sort((a: Echo, b: Echo) => a.name.localeCompare(b.name))
  return knownEchos
}

async function echo(serial: string): Promise<Echo> {
  const found = knownEchos.find((it) => it.serial === serial) ?? (await echos()).find((it) => it.serial === serial)
  if (!found) throw new HttpError(404, str('echo_gone'))
  return found
}

function node(echo: Echo, type: string, payload: Record<string, unknown>, skillId?: string) {
  return {
    '@type': 'com.amazon.alexa.behaviors.model.OpaquePayloadOperationNode',
    type,
    ...(skillId ? { skillId } : {}),
    operationPayload: {
      deviceType: echo.type, deviceSerialNumber: echo.serial, locale: marketplace().locale, customerId: echo.customer, ...payload,
    },
  }
}

async function run(startNode: unknown) {
  const sequence = { '@type': 'com.amazon.alexa.behaviors.model.Sequence', startNode }
  await api('POST', '/api/behaviors/preview', { behaviorId: 'PREVIEW', sequenceJson: JSON.stringify(sequence), status: 'ENABLED' })
}

const volumeNode = (target: Echo, value: number) => node(target, 'Alexa.DeviceControls.Volume', { value })

/**
 * Tells Alexa [text] through the Echo [serial], as if it had been said to it; when [quiet], with the
 * volume of the Echo at zero first, so that it does not answer aloud in the house: [volume] brings it back.
 */
export async function say(serial: string, text: string, quiet = false) {
  const target = await echo(serial)
  const command = node(target, 'Alexa.TextCommand', { text: text.trim().toLowerCase() }, 'amzn1.ask.1p.tellalexa')
  await run(quiet ? { '@type': 'com.amazon.alexa.behaviors.model.SerialNode', nodesToExecute: [volumeNode(target, 0), command] } : command)
}

/** Sets the volume of the Echo [serial], 0 to 100. */
export async function volume(serial: string, value: number) {
  await run(volumeNode(await echo(serial), Math.max(0, Math.min(100, Math.round(value)))))
}

// The answer of Alexa is read back from the voice history of the account, as alexa-remote does:
// the page of the history hands out the token its own calls need. Undocumented too.

const HISTORY_PAGE = '/alexa-privacy/apd/activity?disableGlobalNav=true&ref=activityHistory'
const historyTokens = new Map<number, { token: string; until: number }>()

async function historyToken(): Promise<string> {
  const id = currentUserId() ?? 0
  const saved = historyTokens.get(id)
  if (saved && saved.until > Date.now()) return saved.token
  const page = await apiText('GET', HISTORY_PAGE, null, 'www', { Accept: 'text/html' })
  const token = /meta name="csrf-token" content="([^"]+)"/.exec(page)?.[1]
  if (!token) throw new HttpError(502, str('alexa_unexpected'))
  historyTokens.set(id, { token, until: Date.now() + 2 * 3600_000 })
  return token
}

/** The words of Alexa in the latest record of the history heard by [serial] since [since]. */
export function replyIn(history: any, serial: string, since: number): string | null {
  const records: any[] = Array.isArray(history?.customerHistoryRecords) ? history.customerHistoryRecords : []
  const record = records
    .filter((it) => String(it?.recordKey ?? '').split('#')[3] === serial && Number(it?.timestamp) >= since - 2_000)
    .sort((a, b) => Number(b.timestamp) - Number(a.timestamp))[0]
  if (!record || !Array.isArray(record.voiceHistoryRecordItems)) return null
  const words = record.voiceHistoryRecordItems
    .filter((it: any) => it?.recordItemType === 'ALEXA_RESPONSE' || it?.recordItemType === 'TTS_REPLACEMENT_TEXT')
    .map((it: any) => String(it.transcriptText ?? '').trim())
    // "null" is what the history holds when Alexa only chimed: nothing to show.
    .filter((it: string, i: number, all: string[]) => it && it.toLowerCase() !== 'null' && all.indexOf(it) === i)
  return words.length ? words.join(' ') : null
}

/** What Alexa answered aloud through the Echo [serial] since [since]; null while the history does not have it yet. */
export async function reply(serial: string, since: number): Promise<string | null> {
  const path = `/alexa-privacy/apd/rvh/customer-history-records-v2?startTime=${since - 5_000}&endTime=${Date.now() + 60_000}`
  const headers = { 'anti-csrftoken-a2z': await historyToken(), Referer: `https://www.${marketplace().domain}${HISTORY_PAGE}` }
  const text = await apiText('POST', path, { previousRequestToken: null }, 'www', headers)
  try {
    return replyIn(text.trim() ? JSON.parse(text) : {}, serial, since)
  } catch {
    throw new HttpError(502, str('alexa_unexpected'))
  }
}

// --- lists -------------------------------------------------------------------------------------
// The to-do and shopping lists of Alexa, through the API its own app uses (as alexa-remote does):
// undocumented, so the answers are read loosely and kept as they come.

export type AlexaList = { id: string; name: string; type: string; active: number | null }
export type ListItem = { id: string; name: string; completed: boolean; version: number; updatedAt: number | null }

const LISTS = '/alexashoppinglists/api/v2/lists'
const LISTS_USER_AGENT = 'AppleWebKit PitanguiBridge/2.2.595606.0-[HARDWARE=iPhone14_7][SOFTWARE=17.4.1][DEVICE=iPhone]'

// The ids go in the path as they are, as alexa-remote sends them: Amazon refuses them encoded
// (an id ending in "=" sent as "%3D"). Only what would break the path is escaped.
const pathId = (id: string) => id.replace(/%/g, '%25').replace(/ /g, '%20').replace(/\?/g, '%3F').replace(/#/g, '%23')

export async function lists(): Promise<AlexaList[]> {
  const answer = await api('POST', `${LISTS}/fetch`, {
    listAttributesToAggregate: [{ type: 'totalActiveItemsCount' }],
    listOwnershipType: null,
  }, 'www')
  return (answer.listInfoList ?? []).filter((it: any) => !it.archived).map((it: any) => {
    let active: number | null = null
    try {
      active = JSON.parse(it.totalActiveItemsCount).count ?? null
    } catch {
      active = null
    }
    return { id: String(it.listId), name: String(it.listName || ''), type: String(it.listType || 'CUSTOM'), active }
  })
}

const toItem = (it: any): ListItem => ({
  id: String(it.itemId),
  name: String(it.itemName ?? ''),
  completed: it.itemStatus === 'COMPLETE',
  version: Number(it.version ?? 1),
  updatedAt: Number(it.updateAt ?? it.createAt) || null,
})

export async function listItems(listId: string): Promise<ListItem[]> {
  const answer = await api('POST', `${LISTS}/${pathId(listId)}/items/fetch?limit=100`, {
    itemAttributesToProject: ['quantity', 'note'],
  }, 'www')
  return (answer.itemInfoList ?? []).map(toItem)
}

export async function addListItem(listId: string, name: string): Promise<ListItem | null> {
  const answer = await api('POST', `${LISTS}/${pathId(listId)}/items`, {
    items: [{ itemType: 'KEYWORD', itemName: name }],
  }, 'www')
  const created = (answer.itemInfoList ?? [])[0]
  return created ? toItem(created) : null
}

/** Renames an item or ticks it off; [version] is the one last read, as Amazon wants it. */
export async function updateListItem(listId: string, itemId: string, version: number, change: { name?: string; completed?: boolean }) {
  const itemAttributesToUpdate = []
  if (change.name != null) itemAttributesToUpdate.push({ type: 'itemName', value: change.name })
  if (change.completed != null) itemAttributesToUpdate.push({ type: 'itemStatus', value: change.completed ? 'COMPLETE' : 'ACTIVE' })
  await api('PUT', `${LISTS}/${pathId(listId)}/items/${pathId(itemId)}?version=${version}`, {
    itemAttributesToUpdate,
    itemAttributesToRemove: [],
  }, 'www')
}

export async function deleteListItem(listId: string, itemId: string, version: number) {
  await api('DELETE', `${LISTS}/${pathId(listId)}/items/${pathId(itemId)}?version=${version}`, null, 'www')
}
