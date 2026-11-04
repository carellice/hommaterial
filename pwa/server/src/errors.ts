import { setLanguageProvider, str } from '../../shared/i18n.js'
import { currentUserId, getMeta, setMeta } from './db.ts'

// Node runs this file with type stripping: no enums, namespaces or parameter properties.
export class HttpError extends Error {
  status: number
  /** What the app does about it: "alexa_login" brings back the Amazon sign-in, "offline" says there is no connection. */
  code: string | null
  constructor(status: number, message: string, code: string | null = null) {
    super(message)
    this.status = status
    this.code = code
  }
}

/** The Amazon session is missing or no longer accepted. */
export class NotLoggedInError extends HttpError {
  constructor(message = str('login_required')) {
    super(409, message, 'alexa_login')
  }
}

/** A server out there (Amazon, SwitchBot, Cloudflare) cannot be reached. */
export class OfflineError extends HttpError {
  constructor(message = str('no_connection')) {
    super(502, message, 'offline')
  }
}

// The texts the server says (errors, notifications) are in the language of the account.
const languages = new Map<number, string>()

setLanguageProvider(() => {
  const id = currentUserId()
  if (id == null) return null
  if (!languages.has(id)) languages.set(id, getMeta('language') ?? 'it')
  return languages.get(id) ?? null
})

/** The language the app is in on the devices of the current account: "it" or "en". */
export function setAccountLanguage(language: string) {
  const id = currentUserId()
  if (id == null || (language !== 'it' && language !== 'en')) return
  setMeta('language', language)
  languages.set(id, language)
}

/** fetch, with a failure to connect told as having no connection. */
export async function request(url: string, init: RequestInit = {}, timeoutMs = 30_000): Promise<Response> {
  try {
    return await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) })
  } catch {
    throw new OfflineError()
  }
}
