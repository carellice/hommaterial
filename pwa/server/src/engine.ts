import { str, clock, degrees } from '../../shared/i18n.js'
import { ACTION_MACRO, ACTION_POWER, ACTION_REMOTE, ACTION_WAIT, holds, cleared, nextTime } from '../../shared/model.js'
import { userIds } from './accounts.ts'
import * as alexa from './alexa.ts'
import { type Device, type DeviceState, emptyState } from './alexa.ts'
import { currentUserId, edit, get, getSecret, record, setSecret, withUser } from './db.ts'
import { HttpError, NotLoggedInError, OfflineError } from './errors.ts'
import * as lgtv from './lgtv.ts'
import { send as push } from './push.ts'
import * as switchBot from './switchbot.ts'

// What the Android app does outside of its screens (Timers.kt, Schedules.kt, Automations.kt,
// Alerts.kt, the history of the sensors), here done by the server for every account, all the time.

const TICK_MS = 5_000
const RETRY_DELAY_MS = 60_000
// Past this delay a switch-off or an action would come as a surprise rather than at the time asked for.
const RETRY_WINDOW_MS = 10 * 60_000
const COMMAND_TIMEOUT_MS = 45_000
// A custom device may wait between its actions: it is given longer than a single command.
const RUN_TIMEOUT_MS = 5 * 60_000
// How often the states are read when nobody looks: often enough for the automations, which go off
// when a state is read; less often when only alerts and the history of the sensors need them.
const WATCH_AUTOMATIONS_MS = 2 * 60_000
const WATCH_SENSORS_MS = 10 * 60_000
// A temperature hovering around the threshold must not notify at every check.
const HYSTERESIS = 0.3

type Action = { type: string; target: string; value: string; custom?: boolean }
type Schedule = { id: string; action: Action; label: string; at: number; hour: number; minute: number; days: number[]; enabled: boolean }
type Automation = { id: string; device: string; trigger: string; threshold: number; action: Action; title: string; label: string; enabled: boolean }
type Macro = { id: string; name: string; condition: string | null; steps: Action[]; otherwise: Action[] }
export type Outcome = { ok: boolean; message: string; noConnection?: boolean; loginExpired?: boolean }

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))
const withTimeout = <T>(promise: Promise<T>, ms: number) =>
  Promise.race([promise, new Promise<null>((resolve) => setTimeout(() => resolve(null), ms))])

/** A key unique on the whole server for something of the current account. */
const scoped = (id: string) => `${currentUserId()}:${id}`

function notify(title: string, body: string, tag: string) {
  push({ title, body, tag, url: '/' }).catch((e) => console.warn('Notifica non inviata:', e))
}

// --- devices and their states -----------------------------------------------------------------

export function device(applianceId: string): Device | null {
  return get<Device[]>('devices').find((d) => d.applianceId === applianceId) ?? null
}

/** Takes in freshly read [states]: they are kept, and the history, the alerts and the automations look at them. */
export function received(states: Record<string, DeviceState>, now = Date.now()) {
  for (const [id, st] of Object.entries(states)) {
    if (typeof st.temperature === 'number' && !Number.isNaN(st.temperature)) record(id, st.temperature, st.humidity, now)
  }
  checkAlerts(states)
  checkAutomations(states)
  edit({ states: { ...get<Record<string, DeviceState>>('states'), ...states }, updatedAt: now })
}

/** Notes a device as switched, as Alexa was told, without waiting for the next reading. */
export function noteSwitched(applianceId: string, on: boolean) {
  const states = get<Record<string, DeviceState>>('states')
  edit({ states: { ...states, [applianceId]: { ...(states[applianceId] ?? emptyState()), power: on, reachable: true } } })
}

/** Notes [change] on the state of a device that Alexa just took. */
export function noteState(applianceId: string, change: Partial<DeviceState>) {
  const states = get<Record<string, DeviceState>>('states')
  edit({ states: { ...states, [applianceId]: { ...(states[applianceId] ?? emptyState()), ...change, reachable: true } } })
}

/** The device list and the states of the devices, as the home shows them. */
export async function refresh(showHidden = false) {
  const hidden = new Set(get<string[]>('hidden'))
  const queryable = (list: Device[]) => list.filter((d) => showHidden || !hidden.has(d.applianceId)).map((d) => d.applianceId)
  // States of the already known devices are fetched alongside the device list.
  const known = queryable(get<Device[]>('devices'))
  const early = known.length ? alexa.states(known) : null
  early?.catch(() => {})
  const devices = await alexa.devices()
  edit({ devices })
  const states = { ...(early ? await early : {}) }
  const missing = queryable(devices).filter((id) => !(id in states))
  if (missing.length) Object.assign(states, await alexa.states(missing))
  received(states)
}

/** The states alone, of the shown devices and of those an automation watches. */
export async function poll(ids: string[] | null = null) {
  const hidden = new Set(get<string[]>('hidden'))
  const watched = get<Automation[]>('automations').filter((a) => a.enabled).map((a) => a.device)
  const all = get<Device[]>('devices').map((d) => d.applianceId)
  const wanted = ids ?? all.filter((id) => !hidden.has(id))
  const list = [...new Set([...wanted, ...watched])].filter((id) => all.includes(id))
  if (!list.length) return
  received(await alexa.states(list))
}

/** How a command sent by the server on its own ended, with the sentence to show for it. */
async function attempt(block: () => Promise<Outcome>): Promise<Outcome> {
  try {
    return await block()
  } catch (e) {
    if (e instanceof NotLoggedInError) return { ok: false, message: str('login_expired'), loginExpired: true }
    if (e instanceof OfflineError) return { ok: false, message: str('no_connection'), noConnection: true }
    return { ok: false, message: (e as Error).message || str('something_wrong') }
  }
}

export function cancelTimer(applianceId: string) {
  const timers = get<Record<string, number>>('timers')
  if (!(applianceId in timers)) return
  delete timers[applianceId]
  edit({ timers })
}

/** Switches [target] as asked, keeping its state and dropping its timer when it goes off. */
export async function switchDevice(target: Device, on: boolean) {
  // A device switched off by hand no longer needs its timer.
  if (!on) cancelTimer(target.applianceId)
  await alexa.setPower(target, on)
  noteSwitched(target.applianceId, on)
}

/** Switches a device by its applianceId, as a timer or a schedule does. */
async function powerById(applianceId: string, on: boolean): Promise<Outcome> {
  const target = device(applianceId)
  if (!target?.hasPower) return { ok: false, message: str('quick_not_found') }
  return attempt(async () => {
    await switchDevice(target, on)
    return { ok: true, message: str(on ? 'quick_on' : 'quick_off', target.name) }
  })
}

// --- remotes -----------------------------------------------------------------------------------

/** A remote by its id: one of SwitchBot or an LG TV. */
export function remote(id: string): switchBot.Remote | null {
  const tv = lgtv.find(id)
  if (tv) return { id: tv.id, name: tv.name, type: lgtv.LG_TYPE }
  return get<switchBot.Remote[]>('remotes').find((r) => r.id === id) ?? null
}

/** Presses a key of [target], on the network for an LG TV, through SwitchBot otherwise. */
export async function sendRemote(target: switchBot.Remote, command: string, parameter = 'default', custom = false) {
  const tv = lgtv.find(target.id)
  if (tv) await lgtv.send(tv, command, parameter)
  else await switchBot.send(target, command, parameter, custom)
}

async function pressRemote(remoteId: string, command: string, custom = false): Promise<Outcome> {
  const target = remote(remoteId)
  if (!target) return { ok: false, message: str('quick_not_found') }
  return attempt(async () => {
    await sendRemote(target, command, 'default', custom)
    return { ok: true, message: target.name }
  })
}

// --- custom devices ----------------------------------------------------------------------------

/**
 * Runs the actions of a custom device, one after the other, and stops at the first that fails.
 * The device it depends on is asked to Alexa first: what was read before may be old.
 */
export async function runMacro(macroId: string): Promise<Outcome> {
  const macro = get<Macro[]>('macros').find((m) => m.id === macroId)
  if (!macro) return { ok: false, message: str('quick_not_found') }
  const need = (id: string) => {
    const found = device(id)
    if (!found) throw new HttpError(404, str('macro_gone'))
    return found
  }
  const outcome = await attempt(async () => {
    let actions = macro.steps
    if (macro.condition) {
      const watched = need(macro.condition)
      const st = (await alexa.states([watched.applianceId]))[watched.applianceId]
      if (!st?.reachable || st.power == null) throw new HttpError(409, str('quick_unknown_state', watched.name))
      actions = st.power ? macro.steps : macro.otherwise
    }
    for (const action of actions) {
      if (action.type === ACTION_WAIT) {
        await sleep((Number(action.value) || 0) * 1000)
      } else if (action.type === ACTION_POWER) {
        await switchDevice(need(action.target), action.value === 'on')
      } else if (action.type === ACTION_REMOTE) {
        const target = remote(action.target)
        if (!target) throw new HttpError(404, str('macro_gone'))
        await sendRemote(target, action.value, 'default', action.custom === true)
      }
    }
    return { ok: true, message: str('macro_done', macro.name) }
  })
  if (outcome.ok) return outcome
  return { ...outcome, message: str('macro_failed', macro.name, outcome.message) }
}

/** Carries out what a schedule or an automation is for. */
function runAction(action: Action): Promise<Outcome> {
  switch (action.type) {
    case ACTION_POWER: return powerById(action.target, action.value === 'on')
    case ACTION_MACRO: return runMacro(action.target)
    case ACTION_REMOTE: return pressRemote(action.target, action.value, action.custom === true)
    default: return Promise.resolve({ ok: false, message: str('something_wrong') })
  }
}

// --- automations -------------------------------------------------------------------------------

const tripped = (key: string) => new Set(getSecret<string[]>(key, []))

/** An automation already holding waits for the next time, instead of going off for what was there before it. */
export function armAutomation(automation: Automation) {
  const set = tripped('automationsTripped')
  const st = get<Record<string, DeviceState>>('states')[automation.device] ?? emptyState()
  if (automation.enabled && holds(automation, st) === true) set.add(automation.id)
  else set.delete(automation.id)
  setSecret('automationsTripped', [...set])
}

export function forgetAutomation(id: string) {
  const set = tripped('automationsTripped')
  set.delete(id)
  setSecret('automationsTripped', [...set])
}

/** Runs the automations whose condition freshly read [states] have just met. */
function checkAutomations(states: Record<string, DeviceState>) {
  const automations = get<Automation[]>('automations').filter((a) => a.enabled)
  if (!automations.length) return
  const set = tripped('automationsTripped')
  for (const automation of automations) {
    const st = states[automation.device]
    if (!st) continue
    if (holds(automation, st) === true) {
      if (!set.has(automation.id)) {
        set.add(automation.id)
        runAutomation(automation)
      }
    } else if (cleared(automation, st) === true) {
      set.delete(automation.id)
    }
  }
  setSecret('automationsTripped', [...set])
}

async function runAutomation(automation: Automation) {
  const outcome = await withTimeout(runAction(automation.action), RUN_TIMEOUT_MS)
  if (outcome?.ok) notify(automation.label, str('automation_done_text', automation.title), `${automation.id}|automation`)
  else notify(str('schedule_failed_title', automation.label), outcome?.message ?? str('no_connection'), `${automation.id}|automation`)
}

// --- alerts --------------------------------------------------------------------------------------

/** With a new threshold what was already notified no longer counts. */
export function resetAlert(applianceId: string) {
  setSecret('alertsTripped', [...tripped('alertsTripped')].filter((key) => !key.startsWith(`${applianceId}|`)))
}

/** Notifies about the thresholds that freshly read [states] have just crossed. */
function checkAlerts(states: Record<string, DeviceState>) {
  const alerts = get<Record<string, { above: number | null; below: number | null }>>('alerts')
  if (!Object.keys(alerts).length) return
  const set = tripped('alertsTripped')
  for (const [id, alert] of Object.entries(alerts)) {
    const temperature = states[id]?.temperature
    const name = device(id)?.name
    if (typeof temperature !== 'number' || Number.isNaN(temperature) || !name) continue
    const title = `${name}: ${degrees(temperature)}`
    if (alert.above != null) {
      const key = `${id}|above`
      if (temperature > alert.above) {
        if (!set.has(key)) {
          set.add(key)
          notify(title, str('alert_notification_above', degrees(alert.above)), key)
        }
      } else if (temperature <= alert.above - HYSTERESIS) {
        set.delete(key)
      }
    }
    if (alert.below != null) {
      const key = `${id}|below`
      if (temperature < alert.below) {
        if (!set.has(key)) {
          set.add(key)
          notify(title, str('alert_notification_below', degrees(alert.below)), key)
        }
      } else if (temperature >= alert.below + HYSTERESIS) {
        set.delete(key)
      }
    }
  }
  setSecret('alertsTripped', [...set])
}

// --- timers and schedules ------------------------------------------------------------------------

const firing = new Set<string>()
// When each one waiting for the network tries again, by scoped id.
const retries = new Map<string, number>()

const sortSchedules = (list: Schedule[]) => [...list].sort((a, b) => a.at - b.at)
export const saveSchedules = (list: Schedule[]) => edit({ schedules: sortSchedules(list) })

/** Carries out the timers and the schedules of the current account whose time has come. */
function tick(now = Date.now()) {
  for (const [id, at] of Object.entries(get<Record<string, number>>('timers'))) {
    if (at <= now && (retries.get(scoped(id)) ?? 0) <= now) fireTimer(id, at)
  }
  for (const schedule of get<Schedule[]>('schedules')) {
    if (schedule.enabled && schedule.at <= now && (retries.get(scoped(schedule.id)) ?? 0) <= now) fireSchedule(schedule)
  }
}

async function fireTimer(id: string, due: number) {
  const key = scoped(id)
  if (firing.has(key)) return
  firing.add(key)
  const name = device(id)?.name ?? ''
  try {
    // The server was off when the time came: switching off now would come as a surprise.
    if (!retries.has(key) && Date.now() - due > RETRY_WINDOW_MS) {
      notify(str('timer_failed_title', name), str('srv_missed', clock(due)), `${id}|timer`)
      cancelTimer(id)
      return
    }
    const outcome = await withTimeout(powerById(id, false), COMMAND_TIMEOUT_MS)
    const offline = !outcome || outcome.noConnection
    if (offline && Date.now() - due < RETRY_WINDOW_MS) {
      retries.set(key, Date.now() + RETRY_DELAY_MS)
      return
    }
    if (outcome?.ok) notify(str('timer_done_title', name), str('timer_done_text', clock(Date.now())), `${id}|timer`)
    else notify(str('timer_failed_title', name), str('timer_failed_text', outcome?.message ?? str('no_connection')), `${id}|timer`)
    retries.delete(key)
    cancelTimer(id)
  } finally {
    firing.delete(key)
  }
}

async function fireSchedule(schedule: Schedule) {
  const { id } = schedule
  const key = scoped(id)
  if (firing.has(key)) return
  firing.add(key)
  try {
    // One that repeats moves to its next day; one that does not has done its job.
    const done = () => {
      retries.delete(key)
      const list = get<Schedule[]>('schedules')
      if (!schedule.days.length) saveSchedules(list.filter((it) => it.id !== id))
      else {
        const next = nextTime(schedule.hour, schedule.minute, schedule.days, Date.now())
        saveSchedules(list.map((it) => (it.id === id ? { ...it, at: next } : it)))
      }
    }
    if (!retries.has(key) && Date.now() - schedule.at > RETRY_WINDOW_MS) {
      notify(str('schedule_failed_title', schedule.label), str('srv_missed', clock(schedule.at)), `${id}|schedule`)
      done()
      return
    }
    const outcome = await withTimeout(runAction(schedule.action), RUN_TIMEOUT_MS)
    // Deleted or switched off in the meantime.
    if (!get<Schedule[]>('schedules').some((it) => it.id === id && it.enabled)) return
    const offline = !outcome || outcome.noConnection
    if (offline && Date.now() - schedule.at < RETRY_WINDOW_MS) {
      retries.set(key, Date.now() + RETRY_DELAY_MS)
      return
    }
    if (outcome?.ok) notify(schedule.label, str('schedule_done_text', clock(Date.now())), `${id}|schedule`)
    else notify(str('schedule_failed_title', schedule.label), outcome?.message ?? str('no_connection'), `${id}|schedule`)
    done()
  } finally {
    firing.delete(key)
  }
}

// --- the loop ------------------------------------------------------------------------------------

const lastPoll = new Map<number, number>()
const polling = new Set<number>()

/** How often the states of the current account are read with nobody looking; null for never. */
function watchEvery(): number | null {
  if (get<Automation[]>('automations').some((a) => a.enabled)) return WATCH_AUTOMATIONS_MS
  if (Object.keys(get<Record<string, unknown>>('alerts')).length) return WATCH_SENSORS_MS
  if (get<Device[]>('devices').some((d) => d.isSensor)) return WATCH_SENSORS_MS
  return null
}

/** Notes that the states were just read, as a client asking for them does. */
export function polled() {
  const id = currentUserId()
  if (id != null) lastPoll.set(id, Date.now())
}

function step(userId: number) {
  if (!alexa.isLoggedIn()) return
  tick()
  const every = watchEvery()
  const now = Date.now()
  if (every == null || polling.has(userId) || now - (lastPoll.get(userId) ?? 0) < every) return
  polling.add(userId)
  lastPoll.set(userId, now)
  poll().catch((e) => {
    if (!(e instanceof OfflineError) && !(e instanceof NotLoggedInError)) console.warn(`Lettura degli stati non riuscita (account ${userId}):`, (e as Error).message)
  }).finally(() => polling.delete(userId))
}

export function start() {
  const run = () => {
    for (const id of userIds()) {
      try {
        withUser(id, () => step(id))
      } catch (e) {
        console.error(`Lavoro in sottofondo non riuscito (account ${id}):`, e)
      }
    }
  }
  run()
  setInterval(run, TICK_MS)
}
