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
type Answer = { status: number; cookies: string[]; body: string }

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
  return { status: response.status, cookies: response.headers.getSetCookie(), body: await response.text() }
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

export function logout() {
  for (const key of ['alexa_refresh', 'alexa_session', 'alexa_attempt']) setSecret(key, null)
}

// --- the API -------------------------------------------------------------------------------------

async function api(method: string, path: string, body: unknown = null): Promise<any> {
  const market = marketplace()
  const host = `alexa.${market.domain}`
  let current = await session()
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await call(`https://${host}${path}`, method, {
      'User-Agent': USER_AGENT,
      Accept: 'application/json; charset=utf-8',
      'Accept-Language': market.locale,
      Referer: `https://${host}/spa/index.html`,
      Origin: `https://${host}`,
      csrf: current.csrf,
      Cookie: current.cookie,
      ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
    }, body ? JSON.stringify(body) : null)
    // The web session lasts a few days; the first 401 just means it needs renewing.
    if (response.status === 401 && attempt === 0) {
      current = await session(true)
      continue
    }
    if (response.status === 401) throw new NotLoggedInError(str('login_expired'))
    if (response.status < 200 || response.status >= 300) throw new HttpError(502, str('alexa_error', response.status))
    if (!response.body.trim()) return {}
    try {
      return JSON.parse(response.body)
    } catch {
      throw new HttpError(502, str('alexa_unexpected'))
    }
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
