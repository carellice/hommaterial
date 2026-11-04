import { str } from '../../shared/i18n.js'
import { currentUserId, getSecret, setSecret } from './db.ts'
import { HttpError, OfflineError, request } from './errors.ts'

// Google Calendar for the account (ui/Calendar.kt), through the OAuth client the user makes in
// Google Cloud. Of type "Desktop app", it signs in with http://localhost as the address to come
// back to: the home server has none Google would take, so the user pastes the address the browser
// ends on. The keys and the sign-in stay on the server.

const AUTH = 'https://accounts.google.com/o/oauth2/v2/auth'
const TOKEN = 'https://oauth2.googleapis.com/token'
const API = 'https://www.googleapis.com/calendar/v3'
const REDIRECT = 'http://localhost'
// The events, and the list of the calendars: nothing else of the account.
const SCOPES = 'https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly'

type Client = { id: string; secret: string }

/** A calendar of the account; [writable] when events can be added to it. */
export type Calendar = { id: string; name: string; color: string; primary: boolean; writable: boolean; shown: boolean }

/**
 * An event, as the apps keep it: [start] and [end] are dates ("2026-11-04", the end not included)
 * for a whole day, otherwise moments with their offset ("2026-11-04T18:30:00+01:00").
 */
export type CalEvent = {
  id: string; calendar: string; title: string; location: string; notes: string
  allDay: boolean; start: string; end: string; recurring: boolean
}

export const client = () => getSecret<Client | null>('google_client', null)
export const linked = () => getSecret<string | null>('google_refresh', null) != null
export const account = () => getSecret<string>('google_account', '')

/** The keys of the OAuth client: changing them asks for the sign-in again. */
export function setClient(id: string, secret: string) {
  setSecret('google_client', { id: id.trim(), secret: secret.trim() })
  unlink()
}

export function unlink() {
  setSecret('google_refresh', null)
  setSecret('google_account', null)
  tokens.delete(currentUserId() ?? 0)
}

/** Where the user signs in with Google and allows the calendar. */
export function authUrl(): string {
  const keys = needClient()
  const query = new URLSearchParams({
    client_id: keys.id, redirect_uri: REDIRECT, response_type: 'code', scope: SCOPES,
    // A refresh token comes only with these two: without it the sign-in would last an hour.
    access_type: 'offline', prompt: 'consent',
  })
  return `${AUTH}?${query}`
}

/** Finishes the sign-in from the address the browser ended on, or from the code alone. */
export async function link(pasted: string) {
  const keys = needClient()
  const text = pasted.trim()
  let code = text
  if (/^https?:/i.test(text)) {
    const url = new URL(text)
    if (url.searchParams.get('error')) throw new HttpError(400, str('calendar_denied'))
    code = url.searchParams.get('code') ?? ''
  }
  if (!code) throw new HttpError(400, str('calendar_bad_link'))
  const answer = await tokenRequest({
    code, client_id: keys.id, client_secret: keys.secret, redirect_uri: REDIRECT, grant_type: 'authorization_code',
  }).catch((e) => {
    // A code lasts minutes and goes once: an old page, or one pasted twice.
    throw e instanceof HttpError && e.code === 'calendar_login' ? new HttpError(400, str('calendar_code_used')) : e
  })
  if (!answer.refresh_token) throw new HttpError(400, str('calendar_bad_link'))
  setSecret('google_refresh', answer.refresh_token)
  tokens.set(currentUserId() ?? 0, { token: answer.access_token, until: Date.now() + (answer.expires_in - 60) * 1000 })
  // The name of the account, to say which one is linked: the id of its main calendar is its address.
  const main = (await calendars()).find((it) => it.primary)
  setSecret('google_account', main?.id ?? '')
}

function needClient(): Client {
  const keys = client()
  if (!keys?.id || !keys.secret) throw new HttpError(409, str('calendar_no_client'), 'calendar_login')
  return keys
}

async function tokenRequest(fields: Record<string, string>): Promise<any> {
  const response = await request(TOKEN, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString(),
  })
  const answer: any = await response.json().catch(() => ({}))
  if (response.ok) return answer
  // A code already used, a sign-in taken back from the Google account, keys that changed.
  if (answer.error === 'invalid_grant') throw new HttpError(400, str('calendar_expired'), 'calendar_login')
  if (answer.error === 'invalid_client' || answer.error === 'unauthorized_client') throw new HttpError(400, str('calendar_bad_client'))
  throw new HttpError(502, str('calendar_error', response.status, answer.error_description ? `: ${answer.error_description}` : ''))
}

// The access tokens last an hour: one per account, renewed with the refresh token when it runs out.
const tokens = new Map<number, { token: string; until: number }>()

async function accessToken(fresh = false): Promise<string> {
  const user = currentUserId() ?? 0
  const known = tokens.get(user)
  if (!fresh && known && known.until > Date.now()) return known.token
  const refresh = getSecret<string | null>('google_refresh', null)
  if (!refresh) throw new HttpError(409, str('calendar_not_linked'), 'calendar_login')
  const keys = needClient()
  try {
    const answer = await tokenRequest({ refresh_token: refresh, client_id: keys.id, client_secret: keys.secret, grant_type: 'refresh_token' })
    tokens.set(user, { token: answer.access_token, until: Date.now() + (answer.expires_in - 60) * 1000 })
    return answer.access_token
  } catch (e) {
    // Taken back from the Google account, or expired: the sign-in has to be done again.
    if (e instanceof HttpError && e.code === 'calendar_login') unlink()
    throw e
  }
}

async function api(method: string, path: string, body?: unknown): Promise<any> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await request(`${API}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${await accessToken(attempt > 0)}`,
        ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    })
    if (response.status === 401 && attempt === 0) continue
    if (response.status === 204) return {}
    const answer: any = await response.json().catch(() => ({}))
    if (response.ok) return answer
    const reason = answer.error?.message ? `: ${answer.error.message}` : ''
    if (response.status === 403 && /has not been used|is disabled/i.test(reason)) throw new HttpError(502, str('calendar_api_off'))
    if (response.status === 404 || response.status === 410) throw new HttpError(404, str('calendar_event_gone'))
    throw new HttpError(502, str('calendar_error', response.status, reason))
  }
  throw new OfflineError()
}

const path = (id: string) => encodeURIComponent(id)

export async function calendars(): Promise<Calendar[]> {
  const answer = await api('GET', '/users/me/calendarList?maxResults=250')
  return (answer.items ?? []).map((it: any) => ({
    id: String(it.id),
    name: String(it.summaryOverride ?? it.summary ?? it.id),
    color: String(it.backgroundColor ?? '#4285F4'),
    primary: it.primary === true,
    writable: it.accessRole === 'owner' || it.accessRole === 'writer',
    // Those not ticked in Google Calendar are not shown until chosen in the app.
    shown: it.selected === true || it.primary === true,
  }))
    .sort((a: Calendar, b: Calendar) => Number(b.primary) - Number(a.primary) || a.name.localeCompare(b.name))
}

const toEvent = (calendar: string, it: any): CalEvent => ({
  id: String(it.id),
  calendar,
  title: String(it.summary ?? ''),
  location: String(it.location ?? ''),
  notes: String(it.description ?? ''),
  allDay: it.start?.date != null,
  start: String(it.start?.date ?? it.start?.dateTime ?? ''),
  end: String(it.end?.date ?? it.end?.dateTime ?? ''),
  recurring: it.recurringEventId != null,
})

/** The events of [ids] between [from] and [to] (moments with their offset), each repetition on its own, by start. */
export async function events(ids: string[], from: string, to: string): Promise<CalEvent[]> {
  const query = new URLSearchParams({ timeMin: from, timeMax: to, singleEvents: 'true', orderBy: 'startTime', maxResults: '250' })
  const lists = await Promise.all(ids.slice(0, 50).map(async (id) => {
    const answer = await api('GET', `/calendars/${path(id)}/events?${query}`)
    return (answer.items ?? []).filter((it: any) => it.status !== 'cancelled').map((it: any) => toEvent(id, it))
  }))
  return lists.flat().sort((a, b) => moment(a.start) - moment(b.start))
}

// A whole day starts at the midnight of the home; close enough to order the events.
const moment = (value: string) => Date.parse(value.length === 10 ? `${value}T00:00:00` : value)

type Input = { title?: string; location?: string; notes?: string; allDay?: boolean; start?: string; end?: string }

const DATE = /^\d{4}-\d{2}-\d{2}$/

function toGoogle(input: Input) {
  const allDay = input.allDay === true
  const start = String(input.start ?? '')
  const end = String(input.end ?? '')
  const valid = allDay ? DATE.test(start) && DATE.test(end) : !Number.isNaN(Date.parse(start)) && !Number.isNaN(Date.parse(end))
  if (!valid || moment(end) < moment(start)) throw new HttpError(400, str('calendar_bad_times'))
  return {
    summary: String(input.title ?? '').slice(0, 500),
    location: String(input.location ?? '').slice(0, 500),
    description: String(input.notes ?? '').slice(0, 5000),
    start: allDay ? { date: start } : { dateTime: start },
    end: allDay ? { date: end } : { dateTime: end },
  }
}

export async function create(calendar: string, input: Input): Promise<CalEvent> {
  return toEvent(calendar, await api('POST', `/calendars/${path(calendar)}/events`, toGoogle(input)))
}

/** Changes an event, moving it first to [moveTo] when it goes to another calendar. A repetition changes alone. */
export async function update(calendar: string, id: string, input: Input, moveTo?: string): Promise<CalEvent> {
  let where = calendar
  if (moveTo && moveTo !== calendar) {
    await api('POST', `/calendars/${path(calendar)}/events/${path(id)}/move?destination=${path(moveTo)}`)
    where = moveTo
  }
  return toEvent(where, await api('PATCH', `/calendars/${path(where)}/events/${path(id)}`, toGoogle(input)))
}

export async function remove(calendar: string, id: string) {
  await api('DELETE', `/calendars/${path(calendar)}/events/${path(id)}`)
}
