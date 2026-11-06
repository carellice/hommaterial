import { AsyncLocalStorage } from 'node:async_hooks'
import { DatabaseSync } from 'node:sqlite'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

// Data of one account. Every account has its own SQLite file under data/users/<id>/, picked per
// request with withUser(): the functions below never take a user id, they just read and write
// "the current account".
//
// The Android app keeps its choices in SharedPreferences, as JSON values under names such as
// "groups" or "schedules": the kv table keeps the same values under the same names, so that the
// backup moves from one to the other as it is. What the browser must never see (the Amazon
// session, the keys of SwitchBot, of the assistant and of the TVs) lives in meta instead.

export const dataDir = process.env.DATA_DIR ?? join(process.cwd(), 'data')
mkdirSync(dataDir, { recursive: true })

const schema = `
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS history (
  applianceId TEXT NOT NULL, at INTEGER NOT NULL, temperature REAL NOT NULL, humidity INTEGER);
CREATE INDEX IF NOT EXISTS index_history_device ON history (applianceId, at);
CREATE TABLE IF NOT EXISTS push_subs (endpoint TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL, reminders INTEGER NOT NULL);
`

// --- accounts: every account has its own database, picked per request -------------------------

type UserData = { id: number; db: DatabaseSync }

const opened = new Map<number, UserData>()
const current = new AsyncLocalStorage<UserData>()

export const userDir = (userId: number) => join(dataDir, 'users', String(userId))

function open(userId: number): UserData {
  const known = opened.get(userId)
  if (known) return known
  const dir = userDir(userId)
  mkdirSync(dir, { recursive: true })
  const db = new DatabaseSync(join(dir, 'hommaterial.db'))
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = OFF;')
  db.exec(schema)
  const data = { id: userId, db }
  opened.set(userId, data)
  return data
}

/** Runs [work] on the data of [userId]: every function of this module then reads and writes there. */
export function withUser<T>(userId: number, work: () => T): T {
  return current.run(open(userId), work)
}

export function closeUser(userId: number) {
  opened.get(userId)?.db.close()
  opened.delete(userId)
}

function user(): UserData {
  const data = current.getStore()
  if (!data) throw new Error('Nessun account attivo')
  return data
}

/** The id of the account being served, or null outside of one. */
export const currentUserId = (): number | null => current.getStore()?.id ?? null

/** The database of the current account. */
export const db = {
  prepare: (sql: string) => user().db.prepare(sql),
  exec: (sql: string) => user().db.exec(sql),
}

// --- meta: what stays on the server ----------------------------------------------------------

export function getMeta(key: string): string | null {
  const row = db.prepare('SELECT value FROM meta WHERE key = ?').get(key) as { value: string } | undefined
  return row?.value ?? null
}

export function setMeta(key: string, value: string | null) {
  if (value == null) db.prepare('DELETE FROM meta WHERE key = ?').run(key)
  else db.prepare('INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run(key, value)
}

export function getSecret<T>(key: string, fallback: T): T {
  const text = getMeta(key)
  if (text == null) return fallback
  try {
    return JSON.parse(text) as T
  } catch {
    return fallback
  }
}

export const setSecret = (key: string, value: unknown) => setMeta(key, value == null ? null : JSON.stringify(value))

/** Bumped on every change, so clients can tell when to reload (GET /api/state?since=<revision>). */
export function revision(): number {
  return Number(getMeta('revision') ?? '0')
}

/** Tells the clients that something changed, such as the Amazon sign-in. */
export function touch() {
  setMeta('revision', String(revision() + 1))
}

// --- kv: what the app shows, in the format of the Android app ------------------------------------

/** Every value of the home, with what it is when the account is new. */
export const DEFAULTS: Record<string, unknown> = {
  devices: [],
  states: {},
  updatedAt: null,
  hidden: [],
  favorites: [],
  rooms: {},
  roomList: [],
  groups: [],
  order: {},
  tileSpans: {},
  macros: [],
  shortcuts: null,
  widget: null,
  tiles: Array(10).fill(null),
  timers: {},
  schedules: [],
  automations: [],
  alerts: {},
  remotes: [],
  remoteButtons: {},
  hiddenRemotes: [],
  acSettings: {},
  lgTvs: [],
  assistantNotes: [],
  assistantHouse: '',
  assistantTaught: [],
  // The choices of the Android app that only travel with the backup: kept for the next one.
  autoUpdate: true,
  haptics: true,
  hapticLevel: 70,
  // The lists and the notes of Notion chosen, the same on every device; null until one chooses.
  notionLists: null,
  notionNotes: null,
  // The names given to the lists in the app and the lists joined into one (shared/lists.js).
  listViews: { names: {}, merged: [] },
}

export function get<T>(key: string): T {
  const row = db.prepare('SELECT value FROM kv WHERE key = ?').get(key) as { value: string } | undefined
  if (!row) return structuredClone(DEFAULTS[key]) as T
  try {
    return JSON.parse(row.value) as T
  } catch {
    return structuredClone(DEFAULTS[key]) as T
  }
}

/** Writes several values at once, in one transaction, and tells the clients. */
export function edit(values: Record<string, unknown>) {
  db.exec('BEGIN')
  try {
    for (const [key, value] of Object.entries(values)) {
      if (value === undefined) db.prepare('DELETE FROM kv WHERE key = ?').run(key)
      else db.prepare('INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run(key, JSON.stringify(value))
    }
    db.exec('COMMIT')
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
  touch()
}

export const set = (key: string, value: unknown) => edit({ [key]: value })

/** Every value of the home, as the client keeps it. */
export function all(): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(DEFAULTS)) out[key] = structuredClone(DEFAULTS[key])
  for (const row of db.prepare('SELECT key, value FROM kv').all() as { key: string; value: string }[]) {
    if (!(row.key in DEFAULTS)) continue
    try {
      out[row.key] = JSON.parse(row.value)
    } catch {
      // A broken value falls back to the default.
    }
  }
  return out
}

/** Forgets the home: what "Esci" from Amazon clears in the Android app. */
export function clearHome(keep: string[]) {
  const placeholders = keep.map(() => '?').join(', ')
  db.prepare(`DELETE FROM kv${keep.length ? ` WHERE key NOT IN (${placeholders})` : ''}`).run(...keep)
  db.exec('DELETE FROM history')
  touch()
}

// --- history of the sensors --------------------------------------------------------------------

const HISTORY_KEEP_MS = 7 * 24 * 60 * 60_000
// Readings closer than this to the previous one add nothing to a chart that spans days.
const HISTORY_SPACING_MS = 10 * 60_000

export type Reading = { at: number; temperature: number; humidity: number | null }

export function readings(applianceId: string): Reading[] {
  return db.prepare('SELECT at, temperature, humidity FROM history WHERE applianceId = ? ORDER BY at').all(applianceId) as Reading[]
}

/** Notes down a reading, unless the last one is too recent; forgets those older than a week. */
export function record(applianceId: string, temperature: number, humidity: number | null, now = Date.now()) {
  const last = db.prepare('SELECT MAX(at) AS at FROM history WHERE applianceId = ?').get(applianceId) as { at: number | null }
  if (last.at != null && now - last.at < HISTORY_SPACING_MS) return false
  db.prepare('INSERT INTO history (applianceId, at, temperature, humidity) VALUES (?, ?, ?, ?)').run(applianceId, now, temperature, humidity)
  db.prepare('DELETE FROM history WHERE at < ?').run(now - HISTORY_KEEP_MS)
  return true
}

export function clearHistory() {
  db.exec('DELETE FROM history')
}
