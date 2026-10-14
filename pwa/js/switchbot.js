import { Prefs } from './prefs.js';
import { relay } from './net.js';
import { str } from './i18n.js';

const HOST = 'https://api.switch-bot.com/v1.1';
const SUCCESS = 100;

/** The signature SwitchBot wants on a request: the token, the time and a one-off value, keyed with the secret. */
export async function switchBotSign(token, secret, time, nonce) {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, [
    'sign',
  ]);
  const signature = new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(`${token}${time}${nonce}`)));
  return btoa(String.fromCharCode(...signature)).toUpperCase();
}

/** What a remote can do, which decides the buttons the app gives it. */
export function remoteKind(remote) {
  // The remotes learnt button by button are named as the ready-made ones, after "DIY".
  switch (remote.type.replace(/^DIY /, '').toLowerCase()) {
    case 'tv': case 'iptv': case 'streamer': case 'set top box': return 'TV';
    case 'air conditioner': return 'AIR_CONDITIONER';
    case 'others': return 'CUSTOM';
    default: return 'OTHER';
  }
}

/**
 * The official SwitchBot API, used only for the infrared remotes, which Alexa barely knows. The
 * token and the secret come from the SwitchBot app and stay in this browser.
 */
class SwitchBot {
  constructor() {
    this.prefs = new Prefs('switchbot');
  }

  get connected() {
    return this.prefs.has('token');
  }

  connect(token, secret) {
    this.prefs.edit({ token, secret });
  }

  disconnect() {
    this.prefs.clear();
  }

  /** The remotes as last fetched. */
  remotes() {
    return this.prefs.get('remotes', []);
  }

  async fetchRemotes() {
    const body = await this.call('GET', '/devices');
    const remotes = (body.infraredRemoteList || []).map((it) => ({
      id: it.deviceId,
      name: it.deviceName || '',
      type: it.remoteType || '',
    }));
    this.prefs.set('remotes', remotes);
    return remotes;
  }

  /** Presses a button: one of those SwitchBot knows for the kind of remote, or a [custom] one by its name. */
  send(remote, command, parameter = 'default', custom = false) {
    return this.call('POST', `/devices/${remote.id}/commands`, {
      command,
      parameter,
      commandType: custom ? 'customize' : 'command',
    });
  }

  /** The names of the buttons the user added to each remote, by remote. */
  buttons() {
    return this.prefs.get('buttons', {});
  }

  putButtons(buttons) {
    this.prefs.set('buttons', buttons);
  }

  /** The ids of the remotes the user does not want in the list. */
  hidden() {
    return this.prefs.get('hidden', []);
  }

  putHidden(hidden) {
    this.prefs.set('hidden', hidden);
  }

  acSettings() {
    return this.prefs.get('ac', {});
  }

  putAcSetting(id, setting) {
    this.prefs.set('ac', { ...this.acSettings(), [id]: setting });
  }

  async call(method, path, body = null) {
    const token = this.prefs.get('token');
    if (!token) throw new Error(str('switchbot_not_connected'));
    const secret = this.prefs.get('secret', '');
    // Every request is signed with the secret over the token, the time and a one-off value.
    const time = String(Date.now());
    const nonce = crypto.randomUUID();
    const response = await relay({
      url: HOST + path,
      method,
      headers: {
        Authorization: token,
        sign: await switchBotSign(token, secret, time, nonce),
        t: time,
        nonce,
        ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      },
      body: body ? JSON.stringify(body) : null,
    });
    // A wrong token, a wrong secret and the daily limit all come back as unauthorized.
    if (response.status === 401) throw new Error(str('switchbot_unauthorized'));
    let json;
    try {
      json = JSON.parse(response.body);
    } catch {
      throw new Error(str('switchbot_error', response.status));
    }
    const status = json.statusCode ?? response.status;
    if (status !== SUCCESS) throw new Error(str('switchbot_refused', json.message || '', status));
    return json.body || {};
  }
}

export const switchBot = new SwitchBot();
