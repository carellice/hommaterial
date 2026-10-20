// The state of the app and everything that changes it: the view model of the Android app, together
// with what there runs outside it (timers, schedules, automations, alerts), here run by the open page.
import { Prefs } from './prefs.js';
import { auth, api, MARKETPLACES } from './alexa.js';
import { switchBot, remoteKind } from './switchbot.js';
import { NotLoggedInError, OfflineError } from './net.js';
import { str, plural, clock, degrees, setLanguage as applyLanguage } from './i18n.js';
import { parseVoice, parseExtras } from './voice.js';
import {
  ACTION_MACRO, ACTION_POWER, ACTION_REMOTE, ACTION_WAIT, LAYOUT_GRID, REST_ITEMS,
  emptyState, holds, cleared, macroKey, remoteKey, nextTime, placed, shownRemotes,
} from './model.js';
import { APP_VERSION } from './version.js';
import { createBackup, parseBackup } from './backup.js';

const cache = new Prefs('cache');
const settings = new Prefs('settings');
const historyPrefs = new Prefs('history');

const BACKUP_APP = 'hommaterial';
const PIN_KEY = 'monitorPin';
const TILE_SLOTS = 10;
const UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;
const RETRY_DELAY_MS = 60_000;
// Past this delay a switch-off or an action would come as a surprise rather than at the time asked for.
const RETRY_WINDOW_MS = 10 * 60_000;
const COMMAND_TIMEOUT_MS = 45_000;
// A custom device may wait between its actions: it is given longer than a single command.
const RUN_TIMEOUT_MS = 5 * 60_000;
const HISTORY_KEEP_MS = 7 * 24 * 60 * 60_000;
// Readings closer than this to the previous one add nothing to a chart that spans days.
const HISTORY_SPACING_MS = 10 * 60_000;
// A temperature hovering around the threshold must not notify at every check.
const HYSTERESIS = 0.3;

export const DEFAULT_MONITOR = {
  enabled: false,
  pages: null,
  only: {},
  tileSize: 1,
  clock: true,
  keepOn: true,
  fullscreen: true,
  refreshSeconds: 60,
  rest: true,
  restMinutes: 2,
  restItems: [...REST_ITEMS],
  restSensors: null,
  restBrightness: 15,
  night: false,
  nightFrom: 23,
  nightTo: 7,
};

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

function monitorFromJson(o = {}) {
  const d = DEFAULT_MONITOR;
  const strings = (a) => (Array.isArray(a) ? a.map(String) : null);
  const only = {};
  for (const [page, ids] of Object.entries(o.only || {})) only[page] = strings(ids) || [];
  return {
    enabled: !!o.enabled,
    pages: strings(o.pages),
    only,
    tileSize: clamp(o.tileSize ?? d.tileSize, 0, 2),
    clock: o.clock ?? d.clock,
    keepOn: o.keepOn ?? d.keepOn,
    fullscreen: o.fullscreen ?? d.fullscreen,
    refreshSeconds: Math.max(30, o.refreshSeconds ?? d.refreshSeconds),
    rest: o.rest ?? d.rest,
    restMinutes: Math.max(1, o.restMinutes ?? d.restMinutes),
    restItems: strings(o.restItems) ?? d.restItems,
    restSensors: strings(o.restSensors),
    restBrightness: clamp(o.restBrightness ?? d.restBrightness, 0, 100),
    night: !!o.night,
    nightFrom: clamp(o.nightFrom ?? d.nightFrom, 0, 23),
    nightTo: clamp(o.nightTo ?? d.nightTo, 0, 23),
  };
}

const sortSchedules = (list) => [...list].sort((a, b) => a.at - b.at);

// The last known devices and states are shown instantly while the network catches up.
function loadCached() {
  return {
    onboarded: settings.get('onboarded', false),
    loggedIn: auth.isLoggedIn,
    marketplace: auth.marketplace,
    loginMessage: null,
    signingIn: false,
    devices: cache.get('devices', []),
    states: cache.get('states', {}),
    busy: [],
    hidden: cache.get('hidden', []),
    rooms: cache.get('rooms', {}),
    roomList: cache.get('roomList', []),
    groups: cache.get('groups', []),
    order: cache.get('order', {}),
    macros: cache.get('macros', []),
    shortcuts: cache.get('shortcuts', null),
    widget: cache.get('widget', null),
    tiles: cache.get('tiles', Array(TILE_SLOTS).fill(null)),
    showHidden: false,
    favorites: cache.get('favorites', []),
    timers: cache.get('timers', {}),
    schedules: sortSchedules(cache.get('schedules', [])),
    automations: cache.get('automations', []),
    alerts: cache.get('alerts', {}),
    autoUpdate: settings.get('autoUpdate', true),
    layout: settings.get('layout', LAYOUT_GRID),
    theme: settings.get('theme', 0),
    language: settings.get('language', 'auto'),
    monitor: monitorFromJson(settings.get('monitor', {})),
    switchBot: switchBot.connected,
    remotes: switchBot.remotes(),
    remoteButtons: switchBot.buttons(),
    acSettings: switchBot.acSettings(),
    hiddenRemotes: switchBot.hidden(),
    pin: settings.has(PIN_KEY),
    askMonitorRestore: false,
    refreshing: false,
    voice: null,
    updatedAt: cache.get('updatedAt', null),
    update: null,
    updatePrompt: false,
  };
}

applyLanguage(settings.get('language', 'auto'));
let state = loadCached();
const listeners = new Set();
const messageListeners = new Set();

export const getState = () => state;

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function update(change) {
  state = { ...state, ...(typeof change === 'function' ? change(state) : change) };
  for (const listener of listeners) listener(state);
}

/** The short sentences the app says at the bottom of the screen. */
export function onMessage(listener) {
  messageListeners.add(listener);
  return () => messageListeners.delete(listener);
}

function say(text) {
  for (const listener of messageListeners) listener(text);
}

const without = (list, item) => list.filter((it) => it !== item);
const withItem = (list, item) => (list.includes(item) ? list : [...list, item]);
const omit = (map, key) => {
  const copy = { ...map };
  delete copy[key];
  return copy;
};

function saveCache() {
  cache.edit({ devices: state.devices, states: state.states });
}

// ——— Failures ———

/** Runs [block], turning failures into a message, unless [quiet], or a return to the sign-in screen. */
async function guarded(block, quiet = false) {
  try {
    await block();
    return true;
  } catch (e) {
    if (e instanceof NotLoggedInError) update({ loggedIn: false, loginMessage: e.message });
    else if (!quiet) say(e instanceof OfflineError ? str('no_connection') : e.message || str('something_wrong'));
    return false;
  }
}

/** How a command sent by the app on its own ended, with the sentence to show for it. */
async function attempt(block) {
  try {
    return await block();
  } catch (e) {
    if (e instanceof NotLoggedInError) {
      update({ loggedIn: false, loginMessage: str('login_expired') });
      return { ok: false, message: str('login_expired'), loginExpired: true };
    }
    if (e instanceof OfflineError) return { ok: false, message: str('no_connection'), noConnection: true };
    return { ok: false, message: e.message || str('something_wrong') };
  }
}

const withTimeout = (promise, ms) =>
  Promise.race([promise, new Promise((resolve) => setTimeout(() => resolve(null), ms))]);

// ——— Notifications ———

export const notificationsSupported = () => 'Notification' in window;
export const notificationsEnabled = () => notificationsSupported() && Notification.permission === 'granted';

/** Asks for the permission to notify, where the browser still has to ask. */
export async function askNotifications() {
  if (notificationsSupported() && Notification.permission === 'default') {
    try {
      await Notification.requestPermission();
    } catch {
      // Older browsers answer through a callback only; the setting stays as it is.
    }
    update({});
  }
}

/** A notification when the browser allows it, otherwise a message at the bottom of the screen. */
async function notify(title, text, tag) {
  if (!notificationsEnabled()) {
    say(`${title}: ${text}`);
    return;
  }
  const options = { body: text, tag, icon: 'icons/icon-192.png' };
  try {
    const registration = await navigator.serviceWorker?.getRegistration();
    if (registration) {
      await registration.showNotification(title, options);
      return;
    }
    new Notification(title, options);
  } catch {
    say(`${title}: ${text}`);
  }
}

// ——— First run, sign-in ———

export function acceptOnboarding() {
  settings.set('onboarded', true);
  update({ onboarded: true });
}

export function setMarketplace(domain) {
  auth.marketplace = MARKETPLACES.find((m) => m.domain === domain);
  update({ marketplace: auth.marketplace });
}

export const newLoginAttempt = () => auth.newLoginAttempt();
export const pendingLoginAttempt = () => auth.pendingAttempt;

/** Completes the sign-in with the address Amazon landed on, or with the code alone. */
export async function completeLogin(landing) {
  const attempt = auth.pendingAttempt;
  let code = landing.trim();
  try {
    code = new URL(code).searchParams.get('openid.oa2.authorization_code') || '';
  } catch {
    // Not an address: the code itself was pasted.
  }
  if (!attempt || !code) {
    update({ loginMessage: str(attempt ? 'pwa_login_no_code' : 'pwa_login_restart') });
    return;
  }
  update({ signingIn: true, loginMessage: null });
  try {
    await auth.completeLogin(attempt, code);
    update({ loggedIn: true, signingIn: false });
    refresh();
  } catch (e) {
    update({
      signingIn: false,
      loginMessage: e instanceof OfflineError ? str('no_connection') : e.message || str('login_failed'),
    });
  }
}

export function logout() {
  auth.logout();
  // The automations, timers and schedules go with the cache cleared here.
  cache.clear();
  historyPrefs.clear();
  const s = state;
  state = loadCached();
  update({
    onboarded: true,
    loggedIn: false,
    // SwitchBot is another account: signing out of Amazon leaves it linked.
    switchBot: s.switchBot,
    remotes: s.remotes,
    update: s.update,
  });
}

// ——— Devices and their states ———

let refreshing = null;

const queryable = (devices) => (state.showHidden ? devices : devices.filter((d) => !state.hidden.includes(d.applianceId)));

export function refresh() {
  if (!state.loggedIn || refreshing) return;
  // The remotes change rarely and have no state: they are fetched on the side, without a word.
  if (switchBot.connected) {
    guarded(async () => update({ remotes: await switchBot.fetchRemotes() }), true);
  }
  update({ refreshing: true });
  refreshing = guarded(async () => {
    // States of the already known devices are fetched alongside the device list.
    const known = queryable(state.devices);
    const early = known.length ? api.states(known) : null;
    early?.catch(() => {});
    const devices = await api.devices();
    update({ devices });
    const states = { ...(early ? await early : {}) };
    const missing = queryable(devices).filter((d) => !(d.applianceId in states));
    if (missing.length) Object.assign(states, await api.states(missing));
    received(states);
  }).finally(() => {
    refreshing = null;
    update({ refreshing: false });
  });
}

/**
 * Fetches the states alone and says nothing when it cannot: for a screen that stays open and
 * asks again shortly. The list of devices rarely changes and waits for a real [refresh].
 */
export function poll() {
  if (!state.loggedIn || refreshing) return;
  const known = queryable(state.devices);
  // What an automation watches is needed too, whatever it is.
  const watched = state.automations.filter((a) => a.enabled).map((a) => a.device);
  const extra = state.devices.filter((d) => watched.includes(d.applianceId) && !known.includes(d));
  if (!known.length && !extra.length) return refresh();
  refreshing = guarded(async () => received(await api.states([...known, ...extra])), true).finally(() => {
    refreshing = null;
  });
}

function received(states) {
  recordHistory(states);
  checkAlerts(states);
  checkAutomations(states);
  // Devices with a command in flight keep their optimistic state.
  const now = Date.now();
  const fresh = Object.fromEntries(Object.entries(states).filter(([id]) => !state.busy.includes(id)));
  update((s) => ({ states: { ...s.states, ...fresh }, updatedAt: now }));
  cache.set('updatedAt', now);
  saveCache();
}

/** Applies [optimistic] right away and rolls it back if Alexa refuses the command. */
async function command(device, optimistic, send) {
  const id = device.applianceId;
  const before = state.states[id];
  update((s) => ({ states: { ...s.states, [id]: optimistic(before ?? emptyState()) }, busy: withItem(s.busy, id) }));
  const ok = await guarded(send);
  update((s) => {
    let states = s.states;
    if (!ok) states = before ? { ...states, [id]: before } : omit(states, id);
    return { states, busy: without(s.busy, id) };
  });
  if (ok) saveCache();
  return ok;
}

export function setPower(device, on) {
  // A device switched off by hand no longer needs its timer.
  if (!on) cancelTimer(device.applianceId);
  return command(device, (st) => ({ ...st, power: on }), () => api.setPower(device, on));
}

/** Sets a color or a shade of white, by the name Alexa gives it. Lamps switch on when asked for one. */
export function setColor(device, choice) {
  return command(device, (st) => ({ ...st, colorName: choice.alexaName, power: true }), () =>
    choice.white ? api.setColorTemperature(device, choice.alexaName) : api.setColor(device, choice.alexaName));
}

export function setBrightness(device, percent) {
  return command(device, (st) => ({ ...st, brightness: percent }), () => api.setBrightness(device, percent));
}

/** Switches every device of a room or group that is not already in the requested state. */
export function setRoomPower(devices, on) {
  devices
    .filter((d) => d.hasPower && !state.busy.includes(d.applianceId) && state.states[d.applianceId]?.power !== on)
    .forEach((d) => setPower(d, on));
}

/** Switches a group off if any of its devices is on, on otherwise. */
export function toggleGroup(group) {
  const devices = state.devices.filter((d) => d.hasPower && group.devices.includes(d.applianceId) && !state.hidden.includes(d.applianceId));
  setRoomPower(devices, !devices.some((d) => state.states[d.applianceId]?.power === true));
}

export function setHidden(device, hidden) {
  const list = hidden ? withItem(state.hidden, device.applianceId) : without(state.hidden, device.applianceId);
  cache.set('hidden', list);
  update({ hidden: list });
}

/** Adds to the favorites, or takes away, a device by its applianceId, or a remote or custom device by its key. */
export function setFavorite(key, favorite) {
  const list = favorite ? withItem(state.favorites, key) : without(state.favorites, key);
  cache.set('favorites', list);
  update({ favorites: list });
}

export function toggleShowHidden() {
  update({ showHidden: !state.showHidden });
  if (state.showHidden) refresh();
}

/** Copies to the clipboard everything Alexa says about [device], for writing support for it. */
export async function copyDiagnostics(device) {
  const copied = await guarded(async () => navigator.clipboard.writeText(await api.diagnostics(device)));
  if (copied) say(str('diagnostics_copied'));
}

// ——— Timers ———

/** Switches [device] off in [minutes] minutes. */
export function setTimer(device, minutes) {
  const timers = { ...state.timers, [device.applianceId]: Date.now() + minutes * 60_000 };
  cache.set('timers', timers);
  update({ timers });
}

export function cancelTimer(applianceId) {
  if (!(applianceId in state.timers)) return;
  const timers = omit(state.timers, applianceId);
  cache.set('timers', timers);
  update({ timers });
}

// ——— Schedules ———

function saveSchedules(list) {
  const schedules = sortSchedules(list);
  cache.set('schedules', schedules);
  update({ schedules });
}

/** Sets [action] to be carried out [at] a moment, and again on each of the [days] if there are any. */
export function addSchedule(action, label, at, days) {
  const time = new Date(at);
  saveSchedules([...state.schedules, {
    id: crypto.randomUUID(),
    action,
    label,
    at,
    hour: time.getHours(),
    minute: time.getMinutes(),
    days,
    enabled: true,
  }]);
}

/** Sets [action] to happen once, in [minutes] minutes: the timer of what is not a device of Alexa. */
export const addTimer = (action, label, minutes) => addSchedule(action, label, Date.now() + minutes * 60_000, []);

/** Switches a repeating schedule off, or on again from its next time. */
export function setScheduleEnabled(schedule, enabled) {
  const at = enabled ? nextTime(schedule.hour, schedule.minute, schedule.days, Date.now()) : schedule.at;
  saveSchedules(state.schedules.map((it) => (it.id === schedule.id ? { ...it, enabled, at } : it)));
}

export function deleteSchedule(id) {
  saveSchedules(state.schedules.filter((it) => it.id !== id));
}

// ——— Automations ———

function saveAutomations(automations, tripped) {
  cache.edit({ automations, automationsTripped: tripped });
  update({ automations });
}

const automationsTripped = () => cache.get('automationsTripped', []);

/** Creates an automation: [action] when [device] gets to what [trigger] and [threshold] say. */
export function addAutomation(device, trigger, threshold, action, title, label) {
  const automation = { id: crypto.randomUUID(), device: device.applianceId, trigger, threshold, action, title, label, enabled: true };
  // Already so: it waits for the next time, instead of going off for what was there before it.
  const already = holds(automation, state.states[device.applianceId] ?? emptyState()) === true;
  const tripped = already ? withItem(automationsTripped(), automation.id) : automationsTripped();
  saveAutomations([...state.automations, automation], tripped);
}

export function setAutomationEnabled(automation, enabled) {
  const already = holds(automation, state.states[automation.device] ?? emptyState()) === true;
  const tripped = already ? withItem(automationsTripped(), automation.id) : without(automationsTripped(), automation.id);
  saveAutomations(state.automations.map((a) => (a.id === automation.id ? { ...a, enabled } : a)), tripped);
}

export function deleteAutomation(id) {
  saveAutomations(state.automations.filter((a) => a.id !== id), without(automationsTripped(), id));
}

/** Runs the automations whose condition freshly fetched [states] have just met. */
function checkAutomations(states) {
  const automations = state.automations.filter((a) => a.enabled);
  if (!automations.length) return;
  const tripped = new Set(automationsTripped());
  for (const automation of automations) {
    const st = states[automation.device];
    if (!st) continue;
    if (holds(automation, st) === true) {
      if (!tripped.has(automation.id)) {
        tripped.add(automation.id);
        runAutomation(automation);
      }
    } else if (cleared(automation, st) === true) {
      tripped.delete(automation.id);
    }
  }
  cache.set('automationsTripped', [...tripped]);
}

async function runAutomation(automation) {
  const outcome = await withTimeout(runAction(automation.action), RUN_TIMEOUT_MS);
  if (outcome?.ok) notify(automation.label, str('automation_done_text', automation.title), `${automation.id}|automation`);
  else notify(str('schedule_failed_title', automation.label), outcome?.message ?? str('no_connection'), `${automation.id}|automation`);
}

// ——— Alerts ———

/** Sets the temperatures a sensor notifies about: {above, below}, each null for none. */
export function setAlert(applianceId, alert) {
  const alerts = alert.above == null && alert.below == null ? omit(state.alerts, applianceId) : { ...state.alerts, [applianceId]: alert };
  // With a new threshold what was already notified no longer counts.
  const tripped = cache.get('alertsTripped', []).filter((key) => !key.startsWith(`${applianceId}|`));
  cache.edit({ alerts, alertsTripped: tripped });
  update({ alerts });
}

export const clearAlert = (applianceId) => setAlert(applianceId, { above: null, below: null });

/** Notifies about the thresholds that freshly fetched [states] have just crossed. */
function checkAlerts(states) {
  if (!Object.keys(state.alerts).length) return;
  const tripped = new Set(cache.get('alertsTripped', []));
  for (const [id, alert] of Object.entries(state.alerts)) {
    const temperature = states[id]?.temperature;
    const name = state.devices.find((d) => d.applianceId === id)?.name;
    if (typeof temperature !== 'number' || Number.isNaN(temperature) || !name) continue;
    const title = `${name}: ${degrees(temperature)}`;
    if (alert.above != null) {
      const key = `${id}|above`;
      if (temperature > alert.above) {
        if (!tripped.has(key)) {
          tripped.add(key);
          notify(title, str('alert_notification_above', degrees(alert.above)), key);
        }
      } else if (temperature <= alert.above - HYSTERESIS) {
        tripped.delete(key);
      }
    }
    if (alert.below != null) {
      const key = `${id}|below`;
      if (temperature < alert.below) {
        if (!tripped.has(key)) {
          tripped.add(key);
          notify(title, str('alert_notification_below', degrees(alert.below)), key);
        }
      } else if (temperature >= alert.below + HYSTERESIS) {
        tripped.delete(key);
      }
    }
  }
  cache.set('alertsTripped', [...tripped]);
}

// ——— History ———

/** The readings of the last week of a sensor: {at, temperature, humidity}. */
export function readings(applianceId) {
  return (historyPrefs.get(applianceId, '') || '').split(';').map((entry) => {
    const [at, temperature, humidity] = entry.split(',');
    if (!at || temperature === undefined || temperature === '') return null;
    return { at: Number(at), temperature: Number(temperature), humidity: humidity ? Number(humidity) : null };
  }).filter(Boolean);
}

/** Notes down the sensor readings found in freshly fetched [states]. */
function recordHistory(states, now = Date.now()) {
  const changes = {};
  for (const [id, st] of Object.entries(states)) {
    if (typeof st.temperature !== 'number' || Number.isNaN(st.temperature)) continue;
    const known = readings(id);
    if (known.length && now - known[known.length - 1].at < HISTORY_SPACING_MS) continue;
    const kept = [...known.filter((r) => now - r.at < HISTORY_KEEP_MS), { at: now, temperature: st.temperature, humidity: st.humidity }];
    changes[id] = kept.map((r) => `${r.at},${r.temperature},${r.humidity ?? ''}`).join(';');
  }
  if (Object.keys(changes).length) historyPrefs.edit(changes);
}

export function clearHistory() {
  historyPrefs.clear();
  say(str('history_cleared'));
}

// ——— Rooms and groups ———

function saveRooms(rooms, list = state.roomList) {
  cache.edit({ rooms, roomList: list });
  update({ rooms, roomList: list });
}

/** Moves a device to [room]: a name, an empty text for no room, or null to follow Alexa again. */
export function setRoom(device, room) {
  saveRooms(room == null ? omit(state.rooms, device.applianceId) : { ...state.rooms, [device.applianceId]: room });
}

/**
 * Creates the room [name] or edits the room [old]: gives it the name [name] and puts in it
 * exactly the devices in [members]. Those taken out go back to their Alexa room.
 */
export function saveRoom(old, name, members) {
  const rooms = { ...state.rooms };
  for (const device of state.devices) {
    const id = device.applianceId;
    const wasIn = old != null && (id in rooms ? rooms[id] : device.room) === old;
    if (members.includes(id) && device.room === name) delete rooms[id];
    else if (members.includes(id)) rooms[id] = name;
    // Out of its own Alexa room a device can only go to no room at all.
    else if (wasIn && device.room === old) rooms[id] = '';
    else if (wasIn) delete rooms[id];
  }
  // Remotes and custom devices have no room of their own to go back to.
  for (const key of [...state.macros.map((m) => macroKey(m.id)), ...state.remotes.map((r) => remoteKey(r.id))]) {
    if (members.includes(key)) rooms[key] = name;
    else if (old != null && rooms[key] === old) delete rooms[key];
  }
  const list = [...new Set([...state.roomList.map((r) => (r === old ? name : r)), name])];
  saveRooms(rooms, list);
}

/** Puts a remote or a custom device, by its key, in [room]; null or an empty text takes it out. */
export function setPlace(key, room) {
  saveRooms(room ? { ...state.rooms, [key]: room } : omit(state.rooms, key));
}

/** Removes a room created in the app; its devices go back to their Alexa room. */
export function deleteRoom(room) {
  saveRooms(Object.fromEntries(Object.entries(state.rooms).filter(([, v]) => v !== room)), without(state.roomList, room));
}

export const resetRooms = () => saveRooms({}, []);

function saveGroups(groups) {
  cache.set('groups', groups);
  update({ groups });
}

/** Creates a group, when [id] is null, or changes its name and devices. */
export function saveGroup(id, name, members) {
  const group = { id: id ?? crypto.randomUUID(), name, devices: members };
  saveGroups(id == null ? [...state.groups, group] : state.groups.map((g) => (g.id === id ? group : g)));
}

export const deleteGroup = (id) => saveGroups(state.groups.filter((g) => g.id !== id));

export function setOrder(order) {
  cache.set('order', order);
  update({ order });
}

// ——— Custom devices ———

function saveMacros(macros) {
  cache.set('macros', macros);
  update({ macros });
}

/** Creates a custom device, when its id is empty, or replaces the one with the same id. */
export function saveMacro(macro) {
  saveMacros(macro.id ? state.macros.map((m) => (m.id === macro.id ? macro : m)) : [...state.macros, { ...macro, id: crypto.randomUUID() }]);
}

export function deleteMacro(id) {
  setPlace(macroKey(id), null);
  saveMacros(state.macros.filter((m) => m.id !== id));
}

/** Runs a custom device, and says how it went. */
export async function runMacro(macro) {
  if (state.busy.includes(macro.id)) return;
  update((s) => ({ busy: withItem(s.busy, macro.id) }));
  const outcome = await runMacroById(macro.id);
  if (!outcome.loginExpired) say(outcome.message);
  update((s) => ({ busy: without(s.busy, macro.id) }));
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Notes a device as switched, as Alexa was told, without waiting for the next fetch. */
function noteSwitched(applianceId, on) {
  const st = { ...(state.states[applianceId] ?? emptyState()), power: on, reachable: true };
  update((s) => ({ states: { ...s.states, [applianceId]: st } }));
  saveCache();
}

/**
 * Runs the actions of a custom device, one after the other, and stops at the first that fails.
 * The device it depends on is asked to Alexa first: what the browser remembers may be old.
 */
async function runMacroById(macroId) {
  const macro = state.macros.find((m) => m.id === macroId);
  if (!macro) return { ok: false, message: str('quick_not_found') };
  const device = (id) => {
    const found = state.devices.find((d) => d.applianceId === id);
    if (!found) throw new Error(str('macro_gone'));
    return found;
  };
  const outcome = await attempt(async () => {
    let actions = macro.steps;
    if (macro.condition) {
      const watched = device(macro.condition);
      const st = (await api.states([watched]))[watched.applianceId];
      if (!st?.reachable || st.power == null) throw new Error(str('quick_unknown_state', watched.name));
      actions = st.power ? macro.steps : macro.otherwise;
    }
    for (const action of actions) {
      if (action.type === ACTION_WAIT) {
        await sleep((Number(action.value) || 0) * 1000);
      } else if (action.type === ACTION_POWER) {
        const target = device(action.target);
        const on = action.value === 'on';
        // A device switched off no longer needs its timer.
        if (!on) cancelTimer(target.applianceId);
        await api.setPower(target, on);
        noteSwitched(target.applianceId, on);
      } else if (action.type === ACTION_REMOTE) {
        const remote = state.remotes.find((r) => r.id === action.target);
        if (!remote) throw new Error(str('macro_gone'));
        await switchBot.send(remote, action.value, 'default', action.custom);
      }
    }
    return { ok: true, message: str('macro_done', macro.name) };
  });
  if (outcome.ok) return outcome;
  return { ...outcome, message: str('macro_failed', macro.name, outcome.message) };
}

/** Switches a device by its applianceId, as a timer or a schedule does. */
async function powerById(applianceId, on) {
  const device = state.devices.find((d) => d.applianceId === applianceId && d.hasPower);
  if (!device) return { ok: false, message: str('quick_not_found') };
  return attempt(async () => {
    await api.setPower(device, on);
    noteSwitched(applianceId, on);
    return { ok: true, message: str(on ? 'quick_on' : 'quick_off', device.name) };
  });
}

/** Presses a key of a remote, one of those the user added by name when [custom]. */
async function pressRemote(remoteId, command, custom) {
  const remote = state.remotes.find((r) => r.id === remoteId);
  if (!remote) return { ok: false, message: str('quick_not_found') };
  return attempt(async () => {
    await switchBot.send(remote, command, 'default', custom);
    return { ok: true, message: remote.name };
  });
}

/** Carries out what a schedule or an automation is for. */
function runAction(action) {
  switch (action.type) {
    case ACTION_POWER: return powerById(action.target, action.value === 'on');
    case ACTION_MACRO: return runMacroById(action.target);
    case ACTION_REMOTE: return pressRemote(action.target, action.value, action.custom);
    default: return Promise.resolve({ ok: false, message: str('something_wrong') });
  }
}

// ——— What comes due while the page is open ———

const firing = new Set();
// When each one waiting for the network tries again, by id.
const retries = new Map();

/** Carries out the timers and the schedules whose time has come. Called every second by the app. */
export function tick(now = Date.now()) {
  if (!state.loggedIn) return;
  for (const [id, at] of Object.entries(state.timers)) {
    if (at <= now && (retries.get(id) ?? 0) <= now) fireTimer(id, at);
  }
  for (const schedule of state.schedules) {
    if (schedule.enabled && schedule.at <= now && (retries.get(schedule.id) ?? 0) <= now) fireSchedule(schedule);
  }
}

async function fireTimer(id, due) {
  if (firing.has(id)) return;
  firing.add(id);
  const name = state.devices.find((d) => d.applianceId === id)?.name ?? '';
  try {
    // The page was closed when the time came: switching off now would come as a surprise.
    if (!retries.has(id) && Date.now() - due > RETRY_WINDOW_MS) {
      notify(str('timer_failed_title', name), str('pwa_missed', clock(due)), `${id}|timer`);
      cancelTimer(id);
      return;
    }
    const outcome = await withTimeout(powerById(id, false), COMMAND_TIMEOUT_MS);
    const offline = !outcome || outcome.noConnection;
    if (offline && Date.now() - due < RETRY_WINDOW_MS) {
      retries.set(id, Date.now() + RETRY_DELAY_MS);
      return;
    }
    if (outcome?.ok) notify(str('timer_done_title', name), str('timer_done_text', clock(Date.now())), `${id}|timer`);
    else notify(str('timer_failed_title', name), str('timer_failed_text', outcome?.message ?? str('no_connection')), `${id}|timer`);
    retries.delete(id);
    cancelTimer(id);
  } finally {
    firing.delete(id);
  }
}

async function fireSchedule(schedule) {
  const { id } = schedule;
  if (firing.has(id)) return;
  firing.add(id);
  try {
    // One that repeats moves to its next day; one that does not has done its job.
    const done = () => {
      retries.delete(id);
      if (!schedule.days.length) deleteSchedule(id);
      else {
        const next = nextTime(schedule.hour, schedule.minute, schedule.days, Date.now());
        saveSchedules(state.schedules.map((it) => (it.id === id ? { ...it, at: next } : it)));
      }
    };
    if (!retries.has(id) && Date.now() - schedule.at > RETRY_WINDOW_MS) {
      notify(str('schedule_failed_title', schedule.label), str('pwa_missed', clock(schedule.at)), `${id}|schedule`);
      done();
      return;
    }
    const outcome = await withTimeout(runAction(schedule.action), RUN_TIMEOUT_MS);
    // Deleted or switched off in the meantime.
    if (!state.schedules.some((it) => it.id === id && it.enabled)) return;
    const offline = !outcome || outcome.noConnection;
    if (offline && Date.now() - schedule.at < RETRY_WINDOW_MS) {
      retries.set(id, Date.now() + RETRY_DELAY_MS);
      return;
    }
    if (outcome?.ok) notify(schedule.label, str('schedule_done_text', clock(Date.now())), `${id}|schedule`);
    else notify(str('schedule_failed_title', schedule.label), outcome?.message ?? str('no_connection'), `${id}|schedule`);
    done();
  } finally {
    firing.delete(id);
  }
}

// ——— Voice ———

/** Acts on what the speech recognizer [heard], its guesses best first. */
export function onSpeech(heard) {
  const visible = placed(state).filter((d) => !state.hidden.includes(d.applianceId));
  const result = parseVoice(heard, visible);
  // What Alexa does not know is tried when no device of hers was clearly meant.
  if (result.type !== 'run') {
    // A remote learnt key by key has no power key to be told about.
    const remotes = shownRemotes(state).filter((r) => remoteKind(r) !== 'CUSTOM');
    const extra = parseExtras(heard, state.macros.map((m) => [m.id, m.name]), remotes.map((r) => [r.id, r.name]));
    if (extra?.type === 'run') {
      const macro = state.macros.find((m) => m.id === extra.macroId);
      say(str('schedule_run', macro.name));
      return runMacro(macro);
    }
    if (extra?.type === 'remote') {
      const remote = remotes.find((r) => r.id === extra.remoteId);
      say(str(extra.on ? 'voice_on' : 'voice_off', remote.name));
      return sendRemote(remote, extra.on ? 'turnOn' : 'turnOff');
    }
  }
  if (result.type === 'run') runVoice(result.command);
  else if (result.type === 'ask') update({ voice: result });
  else say(result.heard.trim() ? str('voice_not_understood', result.heard) : str('voice_nothing_heard'));
}

/** The command as a sentence, to offer it or to say it is being carried out. */
export function voiceLabel(command) {
  if (command.room != null) return str(command.on ? 'voice_room_on' : 'voice_room_off', command.room);
  return str(command.on ? 'voice_on' : 'voice_off', command.devices[0].name);
}

export function runVoice(command) {
  dismissVoice();
  say(voiceLabel(command));
  if (command.room != null) setRoomPower(command.devices, command.on);
  else setPower(command.devices[0], command.on);
}

export const dismissVoice = () => update({ voice: null });
export const speechUnavailable = () => say(str('voice_unavailable'));

// ——— SwitchBot ———

/** Links the SwitchBot account, keeping the keys only if SwitchBot accepts them. */
export async function connectSwitchBot(token, secret) {
  switchBot.connect(token.trim(), secret.trim());
  const linked = await guarded(async () => {
    const remotes = await switchBot.fetchRemotes();
    update({ switchBot: true, remotes });
    say(plural('remotes_found', remotes.length));
  });
  if (!linked) switchBot.disconnect();
}

export function disconnectSwitchBot() {
  switchBot.disconnect();
  update({ switchBot: false, remotes: [], remoteButtons: {}, acSettings: {}, hiddenRemotes: [] });
}

/** Presses a button of [remote]. Nothing comes back from an appliance: only a failure is told. */
export async function sendRemote(remote, command, parameter = 'default', custom = false) {
  update((s) => ({ busy: withItem(s.busy, remote.id) }));
  await guarded(() => switchBot.send(remote, command, parameter, custom));
  update((s) => ({ busy: without(s.busy, remote.id) }));
}

/** Sends an air conditioner everything at once, as its remote would, and remembers it. */
export function sendAc(remote, setting, on) {
  setAc(remote, setting);
  return sendRemote(remote, 'setAll', `${setting.temperature},${setting.mode},${setting.fan},${on ? 'on' : 'off'}`);
}

export function setAc(remote, setting) {
  switchBot.putAcSetting(remote.id, setting);
  update({ acSettings: switchBot.acSettings() });
}

export function setRemoteHidden(remote, hidden) {
  const all = hidden ? withItem(state.hiddenRemotes, remote.id) : without(state.hiddenRemotes, remote.id);
  switchBot.putHidden(all);
  update({ hiddenRemotes: all });
}

export function setRemoteButtons(remote, names) {
  const buttons = { ...state.remoteButtons, [remote.id]: names };
  switchBot.putButtons(buttons);
  update({ remoteButtons: buttons });
}

// ——— Settings ———

export function setAutoUpdate(on) {
  settings.set('autoUpdate', on);
  update({ autoUpdate: on });
}

export function setLayout(layout) {
  settings.set('layout', layout);
  update({ layout });
}

export function setTheme(theme) {
  settings.set('theme', theme);
  update({ theme });
}

export function setLanguage(language) {
  settings.set('language', language);
  applyLanguage(language);
  update({ language });
}

export function setMonitor(monitor) {
  settings.set('monitor', monitor);
  update({ monitor });
}

async function hash(salt, pin) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(salt + pin));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Sets the PIN of the monitor mode, or removes it with null. It keeps guests and children out
 * of the settings; it is not meant to resist someone who can read the storage of the browser.
 */
export async function setPin(pin) {
  if (pin == null) settings.remove(PIN_KEY);
  else {
    const salt = crypto.randomUUID();
    settings.set(PIN_KEY, `${salt}:${await hash(salt, pin)}`);
  }
  update({ pin: pin != null });
}

export async function checkPin(pin) {
  const [salt, hashed] = (settings.get(PIN_KEY, '') || '').split(':');
  if (!salt || !hashed) return true;
  return (await hash(salt, pin)) === hashed;
}

// ——— Backup, in the format of the Android app: a file goes from one to the other ———

export function exportBackup() {
  const s = state;
  const backup = createBackup(s, switchBot.keys());
  const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = 'hommaterial-backup.json';
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
  say(str('backup_exported'));
}

let pendingBackup = null;

/** Replaces the choices made in the app with those of a file written by [exportBackup]. */
export async function importBackup(file) {
  let backup = null;
  try {
    backup = parseBackup(await file.text());
  } catch {
    // Told below.
  }
  if (!backup || backup.app !== BACKUP_APP) {
    say(str(backup ? 'backup_invalid' : 'backup_failed'));
    return;
  }
  // The panel of a tablet may not suit the device the backup lands on: that is asked.
  if (backup.monitor) {
    pendingBackup = backup;
    update({ askMonitorRestore: true });
  } else {
    restore(backup, false);
  }
}

/** Answers whether the backup being read brings its monitor mode along; null gives the import up. */
export function finishImport(monitor) {
  const backup = pendingBackup;
  pendingBackup = null;
  update({ askMonitorRestore: false });
  if (backup && monitor != null) restore(backup, monitor);
}

function restore(backup, monitor) {
  const list = (v) => (Array.isArray(v) ? v.map(String) : []);
  const alerts = {};
  for (const [id, a] of Object.entries(backup.alerts || {})) alerts[id] = { above: a.above ?? null, below: a.below ?? null };
  const tiles = Array.from({ length: TILE_SLOTS }, (_, i) => backup.tiles?.[i] || null);
  const values = {
    favorites: list(backup.favorites),
    hidden: list(backup.hidden),
    rooms: backup.rooms || {},
    roomList: list(backup.roomList),
    groups: (backup.groups || []).map((g) => ({ id: g.id, name: g.name, devices: list(g.devices) })),
    macros: (backup.macros || []).map((m) => ({
      id: m.id, name: m.name, condition: m.condition ?? null, steps: m.steps || [], otherwise: m.otherwise || [],
    })),
    order: backup.order || {},
    schedules: (backup.schedules || []).map((s) => ({ ...s, days: list(s.days).map(Number) })),
    automations: (backup.automations || []).map((a) => ({ ...a })),
    shortcuts: backup.shortcuts ?? null,
    widget: backup.widget ?? null,
    tiles,
    alerts,
  };
  // Restored automations wait for a new crossing when their condition already holds.
  const automationsTripped = values.automations.filter((a) => holds(a, state.states[a.device] ?? emptyState()) === true).map((a) => a.id);
  cache.edit({ ...values, alertsTripped: [], automationsTripped });
  const theme = clamp(Number(backup.theme ?? 0), 0, 2);
  const layout = clamp(Number(backup.layout ?? 0), 0, 1);
  const language = backup.language || 'auto';
  settings.edit({ autoUpdate: backup.autoUpdate ?? true, theme, layout, language });
  applyLanguage(language);
  update({ ...values, autoUpdate: backup.autoUpdate ?? true, theme, layout, language });
  if (backup.remoteButtons) {
    switchBot.putButtons(backup.remoteButtons);
    update({ remoteButtons: backup.remoteButtons });
  }
  if (backup.hiddenRemotes) {
    switchBot.putHidden(list(backup.hiddenRemotes));
    update({ hiddenRemotes: switchBot.hidden() });
  }
  if (monitor && backup.monitor) setMonitor(monitorFromJson(backup.monitor));
  if (backup.switchBot?.token) {
    switchBot.connect(backup.switchBot.token, backup.switchBot.secret || '');
    update({ switchBot: true });
    guarded(async () => update({ remotes: await switchBot.fetchRemotes() }), true);
  }
  say(str('backup_imported'));
}

// ——— Updates of the web app ———

export const installedVersion = APP_VERSION;

/**
 * Looks for a newer version of the web app on its site. The automatic check runs at most once a
 * day and stays silent unless it finds something; the [manual] one always reports its outcome.
 */
export async function checkForUpdate(manual, quiet = false) {
  if (!state.onboarded) return;
  const now = Date.now();
  const last = settings.get('updateCheckedAt', 0);
  if (!manual && (!state.autoUpdate || (now - last >= 0 && now - last < UPDATE_CHECK_INTERVAL_MS))) return;
  try {
    const response = await fetch(`js/version.js?at=${now}`, { cache: 'no-store' });
    const version = (await response.text()).match(/APP_VERSION = '([^']+)'/)?.[1];
    settings.set('updateCheckedAt', now);
    if (version && version !== APP_VERSION) update((s) => ({ update: { version }, updatePrompt: s.updatePrompt || !quiet }));
    else if (manual && !quiet) say(str('update_latest', APP_VERSION));
  } catch {
    if (manual && !quiet) say(str('no_connection'));
  }
}

/** Takes the newer version: the service worker gets out of the way and the page loads again. */
export async function installUpdate() {
  const registration = await navigator.serviceWorker?.getRegistration();
  await registration?.update().catch(() => {});
  registration?.waiting?.postMessage('skipWaiting');
  location.reload();
}

export const dismissUpdate = () => update({ updatePrompt: false });
