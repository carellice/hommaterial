import { createHmac, randomUUID } from 'node:crypto'
import { str } from '../../shared/i18n.js'
import { getSecret, setSecret } from './db.ts'
import { HttpError, request } from './errors.ts'

// The official SwitchBot API (SwitchBot.kt), used only for the infrared remotes, which Alexa
// barely knows. The token and the secret come from the SwitchBot app and stay on the server.

const HOST = 'https://api.switch-bot.com/v1.1'
const SUCCESS = 100

export type Remote = { id: string; name: string; type: string }
type Keys = { token: string; secret: string }

/** The signature SwitchBot wants on a request: the token, the time and a one-off value, keyed with the secret. */
export function switchBotSign(token: string, secret: string, time: string, nonce: string): string {
  return createHmac('sha256', secret).update(`${token}${time}${nonce}`).digest('base64').toUpperCase()
}

export const keys = () => getSecret<Keys | null>('switchbot', null)

export const connected = () => keys() != null

export const connect = (token: string, secret: string) => setSecret('switchbot', { token, secret })

export const disconnect = () => setSecret('switchbot', null)

async function call(method: string, path: string, body: unknown = null, with_: Keys | null = keys()): Promise<any> {
  if (!with_) throw new HttpError(400, str('switchbot_not_connected'))
  // Every request is signed with the secret over the token, the time and a one-off value.
  const time = String(Date.now())
  const nonce = randomUUID()
  const response = await request(HOST + path, {
    method,
    headers: {
      Authorization: with_.token,
      sign: switchBotSign(with_.token, with_.secret, time, nonce),
      t: time,
      nonce,
      ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  // A wrong token, a wrong secret and the daily limit all come back as unauthorized.
  if (response.status === 401) throw new HttpError(400, str('switchbot_unauthorized'))
  let json
  try {
    json = JSON.parse(await response.text())
  } catch {
    throw new HttpError(502, str('switchbot_error', response.status))
  }
  const status = json.statusCode ?? response.status
  if (status !== SUCCESS) throw new HttpError(502, str('switchbot_refused', json.message || '', status))
  return json.body || {}
}

/** The infrared remotes of the account, with the keys [with_] when they are being tried. */
export async function fetchRemotes(with_: Keys | null = keys()): Promise<Remote[]> {
  const body = await call('GET', '/devices', null, with_)
  return (body.infraredRemoteList || []).map((it: { deviceId: string; deviceName?: string; remoteType?: string }) => ({
    id: it.deviceId,
    name: it.deviceName || '',
    type: it.remoteType || '',
  }))
}

/** Presses a button: one of those SwitchBot knows for the kind of remote, or a [custom] one by its name. */
export function send(remote: Remote, command: string, parameter = 'default', custom = false) {
  return call('POST', `/devices/${remote.id}/commands`, {
    command,
    parameter,
    commandType: custom ? 'customize' : 'command',
  })
}
