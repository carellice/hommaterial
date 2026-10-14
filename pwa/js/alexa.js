import { Prefs } from './prefs.js';
import { relay, NotLoggedInError, OfflineError } from './net.js';
import { str } from './i18n.js';

/** Amazon always signs the Alexa app in on amazon.com, whatever the account's marketplace. */
export const LOGIN_DOMAIN = 'amazon.com';

/** The national Amazon sites an Alexa account can belong to. */
export const MARKETPLACES = [
  { domain: 'amazon.it', locale: 'it-IT' },
  { domain: 'amazon.de', locale: 'de-DE' },
  { domain: 'amazon.fr', locale: 'fr-FR' },
  { domain: 'amazon.es', locale: 'es-ES' },
  { domain: 'amazon.co.uk', locale: 'en-GB' },
  { domain: 'amazon.com', locale: 'en-US' },
];

// Amazon only issues tokens to clients that identify as its own Alexa app.
const APP_VERSION = '2.2.651540.0';
const USER_AGENT = `AmazonWebView/Amazon Alexa/${APP_VERSION}/iOS/18.3.1/iPhone`;
const APP_NAME = 'Hommaterial';
const DEVICE_TYPE = 'A2IVLV5VM2W81';

const DEVICES_QUERY = `query Endpoints { endpoints { items {
  friendlyName
  displayCategories { primary { value } }
  legacyAppliance { applianceId entityId isEnabled capabilities }
} } }`;

// Asks for more than the app uses, to see what a kind of device not yet supported has to offer.
const DIAGNOSTICS_QUERY = `query Endpoints { endpoints { items {
  friendlyName
  displayCategories { primary { value } }
  legacyAppliance { applianceId entityId isEnabled applianceTypes manufacturerName modelName actions capabilities }
} } }`;

// Echo and Fire TV devices also report a power capability but are not home devices.
const VOICE_DEVICE_CATEGORY = 'ALEXA_VOICE_ENABLED';

const randomBytes = (n) => crypto.getRandomValues(new Uint8Array(n));
const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
const utf8 = (text) => new TextEncoder().encode(text);
const base64 = (bytes) => btoa(String.fromCharCode(...bytes));
const base64Url = (bytes) => base64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function parseCookies(lines) {
  const jar = {};
  for (const line of lines) {
    const pair = line.split(';')[0];
    const i = pair.indexOf('=');
    if (i > 0) jar[pair.slice(0, i).trim()] = pair.slice(i + 1).trim();
  }
  return jar;
}

const cookieHeader = (jar) => Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');

class AlexaAuth {
  constructor() {
    this.prefs = new Prefs('auth');
    this.refreshing = null;
  }

  get isLoggedIn() {
    return this.prefs.has('refreshToken');
  }

  /** Defaults to the marketplace of the browser's country until the user picks one. */
  get marketplace() {
    const saved = this.prefs.get('marketplace');
    const country = (navigator.language || '').split('-')[1]?.toUpperCase();
    return MARKETPLACES.find((m) => m.domain === saved)
      ?? MARKETPLACES.find((m) => country && m.locale.endsWith(`-${country}`))
      ?? MARKETPLACES.find((m) => m.locale.startsWith((navigator.language || '').slice(0, 2) + '-'))
      ?? MARKETPLACES[MARKETPLACES.length - 1];
  }

  set marketplace(market) {
    this.prefs.set('marketplace', market.domain);
  }

  /** Everything generated for one sign-in attempt, kept until it is completed. */
  async newLoginAttempt() {
    const mapMd = JSON.stringify({
      device_user_dictionary: [],
      device_registration_data: { software_version: '1' },
      app_identifier: { app_version: APP_VERSION, bundle_id: 'com.amazon.echo' },
    });
    // Hex of an upper-case hex serial, followed by hex("#" + DEVICE_TYPE).
    const deviceId = hex(utf8(hex(randomBytes(16)).toUpperCase())) + hex(utf8(`#${DEVICE_TYPE}`));
    const verifier = base64Url(randomBytes(32));
    const challenge = base64Url(new Uint8Array(await crypto.subtle.digest('SHA-256', utf8(verifier))));
    const enc = encodeURIComponent;
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
      `&language=${this.marketplace.locale.replace('-', '_')}`;
    const attempt = {
      deviceId,
      frc: base64(randomBytes(313)),
      mapMd: base64(utf8(mapMd)),
      verifier,
      url,
    };
    this.prefs.set('attempt', attempt);
    return attempt;
  }

  get pendingAttempt() {
    return this.prefs.get('attempt');
  }

  /** Trades the authorization code from the sign-in page for a long-lived refresh token. */
  async completeLogin(attempt, authorizationCode) {
    const cookies = { frc: attempt.frc, 'map-md': attempt.mapMd };
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
        authorization_code: authorizationCode,
        code_verifier: attempt.verifier,
        code_algorithm: 'SHA-256',
        client_domain: 'DeviceLegacy',
      },
      user_context_map: { frc: attempt.frc },
      requested_token_type: ['bearer', 'mac_dms', 'website_cookies'],
    };
    const response = await relay({
      url: `https://api.${LOGIN_DOMAIN}/auth/register`,
      method: 'POST',
      headers: {
        'User-Agent': USER_AGENT,
        'Accept-Language': this.marketplace.locale,
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Cookie: cookieHeader(cookies),
        'x-amzn-identity-auth-domain': `api.${LOGIN_DOMAIN}`,
      },
      body: JSON.stringify(body),
    });
    let json = null;
    try {
      json = JSON.parse(response.body);
    } catch {
      // Told below, with the status of the answer.
    }
    const refreshToken = json?.response?.success?.tokens?.bearer?.refresh_token;
    if (!refreshToken) throw new Error(str('registration_refused', response.status));
    this.prefs.remove('cookie', 'csrf', 'attempt');
    this.prefs.set('refreshToken', refreshToken);
    await this.session(true);
  }

  /** Returns the web session used by the Alexa API, creating it from the refresh token if needed. */
  async session(forceRefresh = false) {
    const cookie = this.prefs.get('cookie');
    const csrf = this.prefs.get('csrf');
    if (!forceRefresh && cookie && csrf) return { cookie, csrf };
    // Callers that find the session stale together wait for one renewal.
    if (!this.refreshing) {
      this.refreshing = this.exchangeForSession().finally(() => {
        this.refreshing = null;
      });
    }
    return this.refreshing;
  }

  async exchangeForSession() {
    const refreshToken = this.prefs.get('refreshToken');
    if (!refreshToken) throw new NotLoggedInError();
    const market = this.marketplace;
    const host = `alexa.${market.domain}`;
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
    });
    const exchange = await relay({
      url: `https://www.${market.domain}/ap/exchangetoken/cookies`,
      method: 'POST',
      headers: {
        'User-Agent': USER_AGENT,
        'Accept-Language': market.locale,
        Accept: '*/*',
        'Content-Type': 'application/x-www-form-urlencoded',
        'x-amzn-identity-auth-domain': `api.${market.domain}`,
      },
      body: form.toString(),
    });
    let json = null;
    try {
      json = JSON.parse(exchange.body);
    } catch {
      // Told below.
    }
    const cookies = json?.response?.tokens?.cookies?.[`.${market.domain}`];
    if (!Array.isArray(cookies)) {
      // The stored token is kept: a later sign-in replaces it, and a transient refusal heals itself.
      if (exchange.status === 400 || exchange.status === 401) throw new NotLoggedInError(str('login_expired'));
      throw new Error(str('amazon_no_answer', exchange.status));
    }
    const jar = {};
    for (const c of cookies) jar[c.Name] = c.Value;

    // Any authenticated Alexa page hands out the csrf cookie the API requires.
    const page = await relay({
      url: `https://${host}/api/language`,
      headers: {
        'User-Agent': USER_AGENT,
        Accept: '*/*',
        Referer: `https://${host}/spa/index.html`,
        Origin: `https://${host}`,
        Cookie: cookieHeader(jar),
      },
    });
    Object.assign(jar, parseCookies(page.cookies || []));
    if (!jar.csrf) throw new Error(str('amazon_no_csrf'));
    const session = { cookie: cookieHeader(jar), csrf: jar.csrf };
    this.prefs.edit(session);
    return session;
  }

  logout() {
    this.prefs.remove('refreshToken', 'cookie', 'csrf', 'attempt');
  }
}

class AlexaApi {
  constructor(auth) {
    this.auth = auth;
  }

  async call(method, path, body = null) {
    const market = this.auth.marketplace;
    const host = `alexa.${market.domain}`;
    let session = await this.auth.session();
    for (let attempt = 0; attempt < 2; attempt++) {
      const response = await relay({
        url: `https://${host}${path}`,
        method,
        headers: {
          'User-Agent': USER_AGENT,
          Accept: 'application/json; charset=utf-8',
          'Accept-Language': market.locale,
          Referer: `https://${host}/spa/index.html`,
          Origin: `https://${host}`,
          csrf: session.csrf,
          Cookie: session.cookie,
          ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
        },
        body: body ? JSON.stringify(body) : null,
      });
      // The web session lasts a few days; the first 401 just means it needs renewing.
      if (response.status === 401 && attempt === 0) {
        session = await this.auth.session(true);
        continue;
      }
      if (response.status === 401) throw new NotLoggedInError(str('login_expired'));
      if (response.status < 200 || response.status >= 300) throw new Error(str('alexa_error', response.status));
      if (!response.body.trim()) return {};
      try {
        return JSON.parse(response.body);
      } catch {
        throw new Error(str('alexa_unexpected'));
      }
    }
    throw new Error(str('alexa_unexpected'));
  }

  /** Controllable devices and sensors, each tagged with its Alexa room. */
  async devices() {
    const [endpoints, groups] = await Promise.all([
      this.call('POST', '/nexus/v1/graphql', { query: DEVICES_QUERY }),
      this.call('GET', '/api/phoenix/group'),
    ]);
    const roomOf = {};
    for (const group of groups.applianceGroups || []) {
      for (const id of group.applianceIds || []) if (!(id in roomOf)) roomOf[id] = group.name;
    }
    const items = endpoints?.data?.endpoints?.items;
    if (!Array.isArray(items)) throw new Error(str('alexa_unexpected'));
    const devices = [];
    for (const item of items) {
      const appliance = item.legacyAppliance;
      if (!appliance?.applianceId || appliance.isEnabled === false) continue;
      const interfaces = new Set((appliance.capabilities || []).map((c) => c.interfaceName));
      const category = item.displayCategories?.primary?.value || 'OTHER';
      const device = {
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
      };
      if (category !== VOICE_DEVICE_CATEGORY && (device.hasPower || device.isSensor)) devices.push(device);
    }
    return devices.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  }

  /** Current state of every given device, keyed by applianceId. */
  async states(devices) {
    if (!devices.length) return {};
    const stateRequests = devices.map((d) => ({ entityId: d.applianceId, entityType: 'APPLIANCE' }));
    const response = await this.call('POST', '/api/phoenix/state', { stateRequests });
    const result = {};
    for (const entry of response.deviceStates || []) {
      const state = { power: null, brightness: null, temperature: null, humidity: null, colorName: null, reachable: true };
      for (const raw of entry.capabilityStates || []) {
        // Each capability arrives as a JSON document encoded inside a string.
        let cap;
        try {
          cap = typeof raw === 'string' ? JSON.parse(raw) : raw;
        } catch {
          continue;
        }
        switch (cap.name) {
          case 'powerState': state.power = cap.value === 'ON'; break;
          case 'brightness': state.brightness = Number(cap.value); break;
          case 'temperature': state.temperature = cap.value?.value ?? null; break;
          case 'relativeHumidity': state.humidity = Math.trunc(Number(cap.value)); break;
          case 'colorProperties': state.colorName = cap.value?.name || null; break;
          default: break;
        }
      }
      result[entry.entity.entityId] = state;
    }
    for (const error of response.errors || []) {
      const id = error.entity?.entityId;
      if (id) result[id] = { power: null, brightness: null, temperature: null, humidity: null, colorName: null, reachable: false };
    }
    return result;
  }

  /** Everything Alexa says about [device], as readable JSON, for writing support for a new kind. */
  async diagnostics(device) {
    const find = (response) => response?.data?.endpoints?.items
      ?.find((it) => it.legacyAppliance?.applianceId === device.applianceId);
    let wide = null;
    try {
      wide = find(await this.call('POST', '/nexus/v1/graphql', { query: DIAGNOSTICS_QUERY }));
    } catch (e) {
      // Should Alexa refuse a field of the wider query, the one the app normally uses still answers.
      if (e instanceof NotLoggedInError || e instanceof OfflineError) throw e;
    }
    const description = wide ?? find(await this.call('POST', '/nexus/v1/graphql', { query: DEVICES_QUERY }));
    const state = await this.call('POST', '/api/phoenix/state', {
      stateRequests: [{ entityId: device.applianceId, entityType: 'APPLIANCE' }],
    });
    for (const entry of state.deviceStates || []) {
      entry.capabilityStates = (entry.capabilityStates || []).map((c) => {
        try {
          return JSON.parse(c);
        } catch {
          return c;
        }
      });
    }
    return JSON.stringify({ device: description ?? null, state }, null, 2);
  }

  setPower(device, on) {
    return this.control(device, { action: on ? 'turnOn' : 'turnOff' });
  }

  setBrightness(device, percent) {
    return this.control(device, { action: 'setBrightness', brightness: percent });
  }

  /** [colorName] is one of the names Alexa gives to colors, such as "red" or "sky_blue". */
  setColor(device, colorName) {
    return this.control(device, { action: 'setColor', colorName });
  }

  /** [whiteName] is one of the names Alexa gives to shades of white, such as "warm_white". */
  setColorTemperature(device, whiteName) {
    return this.control(device, { action: 'setColorTemperature', colorTemperatureName: whiteName });
  }

  async control(device, parameters) {
    const request = { entityId: device.entityId, entityType: 'APPLIANCE', parameters };
    const response = await this.call('PUT', '/api/phoenix/state', { controlRequests: [request] });
    const error = response.errors?.[0];
    if (error) {
      throw new Error(['ENDPOINT_UNREACHABLE', 'NO_SUCH_ENDPOINT'].includes(error.code)
        ? str('device_unreachable', device.name)
        : str('command_refused', device.name, error.code));
    }
  }
}

export const auth = new AlexaAuth();
export const api = new AlexaApi(auth);
