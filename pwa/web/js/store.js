// The state of the app and everything that changes it: the view model of the Android app. What
// there runs outside the screens (timers, schedules, automations, alerts, the history of the
// sensors) here runs on the server of the home, which also speaks to Alexa, SwitchBot and the TVs.
import { Prefs } from './prefs.js';
import { api, get, post, del, onSessionLost, NotLoggedInError, OfflineError, SessionError } from './net.js';
import { str, plural, lang, setLanguage as applyLanguage } from '/shared/i18n.js';
import { parseVoice, parseExtras } from './voice.js';
import {
  LAYOUT_GRID, REST_ITEMS, emptyState, macroKey, remoteKey, placed, placeOf, shownRemotes, remoteKind, isLg,
  standardCommands, commandLabel,
} from '/shared/model.js';
import { APP_VERSION } from './version.js';
import { createBackup, parseBackup, selectBackup } from '/shared/backup.js';
import { enablePush, disablePush, pushState } from './push.js';

const settings = new Prefs('settings');

const BACKUP_APP = 'hommaterial';
const PIN_KEY = 'monitorPin';
const UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;
// How often what the server knows is asked again while the app is in sight.
const SYNC_MS = 30_000;
const LG_TYPE = 'LG webOS';

/** The national Amazon sites an Alexa account can belong to. */
export const MARKETPLACES = [
  { domain: 'amazon.it', locale: 'it-IT' },
  { domain: 'amazon.de', locale: 'de-DE' },
  { domain: 'amazon.fr', locale: 'fr-FR' },
  { domain: 'amazon.es', locale: 'es-ES' },
  { domain: 'amazon.co.uk', locale: 'en-GB' },
  { domain: 'amazon.com', locale: 'en-US' },
];

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

// ——— What the server sends, and what the app shows of it ———

const EMPTY_DATA = {
  devices: [], states: {}, updatedAt: null, hidden: [], favorites: [], rooms: {}, roomList: [], groups: [], order: {},
  tileSpans: {}, macros: [], shortcuts: null, widget: null, tiles: Array(10).fill(null), timers: {}, schedules: [],
  automations: [], alerts: {}, remotes: [], remoteButtons: {}, hiddenRemotes: [], acSettings: {}, lgTvs: [],
  assistantNotes: [], assistantHouse: '', assistantTaught: [], autoUpdate: true, haptics: true, hapticLevel: 70,
};

const EMPTY_STATUS = {
  alexa: { loggedIn: false, marketplace: 'amazon.it', attempt: false },
  switchBot: false,
  assistant: { configured: false, accountId: '', model: '' },
};

const marketOf = (domain) => MARKETPLACES.find((m) => m.domain === domain) ?? MARKETPLACES[0];

/** The LG TVs among the remotes, next to those of SwitchBot, as the Android app lists them. */
const lgRemote = (tv) => ({ id: tv.id, name: tv.name, type: LG_TYPE });

/** The state of the app for what the server sent: [data] in the format of the Android app. */
function fromServer(data, status) {
  return {
    ...data,
    switchBotRemotes: data.remotes,
    remotes: [...data.remotes, ...data.lgTvs.map(lgRemote)],
    loggedIn: status.alexa.loggedIn,
    marketplace: marketOf(status.alexa.marketplace),
    loginPending: status.alexa.attempt,
    switchBot: status.switchBot,
    assistant: {
      ...status.assistant,
      notes: data.assistantNotes,
      house: data.assistantHouse,
      taught: data.assistantTaught,
    },
  };
}

function initialState() {
  return {
    // The account on the server: null until the session is checked, then a name or false.
    account: null,
    serverVersion: null,
    invite: false,
    onboarded: settings.get('onboarded', false),
    loginMessage: null,
    signingIn: false,
    ...fromServer(EMPTY_DATA, EMPTY_STATUS),
    busy: [],
    showHidden: false,
    layout: settings.get('layout', LAYOUT_GRID),
    theme: settings.get('theme', 0),
    accent: settings.get('accent', 'auto'),
    language: settings.get('language', 'auto'),
    monitor: monitorFromJson(settings.get('monitor', {})),
    pin: settings.has(PIN_KEY),
    askBackupRestore: false,
    refreshing: false,
    voice: null,
    correcting: false,
    update: null,
    updatePrompt: false,
    push: pushState(),
    synced: false,
  };
}

applyLanguage(settings.get('language', 'auto'));
let state = initialState();
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

// ——— The copy kept in the browser, by account, to open at once and without a network ———

let cache = null;
let revision = null;

function useAccount(username) {
  cache = new Prefs(`cache.${username.toLowerCase()}`);
  const saved = cache.get('snapshot', null);
  revision = null;
  if (saved?.data) update(fromServer({ ...EMPTY_DATA, ...saved.data, favorites: ownFavorites(saved.data.favorites) }, { ...EMPTY_STATUS, ...saved.status }));
}

/**
 * The favorites of this browser: each device keeps its own, so they are never sent to the server.
 * The first time they are those the account had, so that nothing is lost on the way.
 */
function ownFavorites(fromAccount) {
  if (!cache) return [];
  if (!cache.has('favorites')) cache.set('favorites', Array.isArray(fromAccount) ? fromAccount : []);
  return cache.get('favorites', []);
}

// Values written by the app and not yet confirmed by the server keep what the user just chose.
const pendingValues = new Map();

function applySnapshot(answer) {
  revision = answer.revision;
  const data = { ...EMPTY_DATA, ...answer.data };
  for (const [key, value] of pendingValues) data[key] = value;
  data.favorites = ownFavorites(answer.data?.favorites);
  const next = fromServer(data, { ...EMPTY_STATUS, ...answer.status });
  // Devices with a command in flight keep their optimistic state.
  const states = { ...next.states };
  for (const id of state.busy) if (state.states[id]) states[id] = state.states[id];
  update({ ...next, states, synced: true });
  cache?.set('snapshot', { data: answer.data, status: answer.status });
}

let syncing = null;

/** Asks the server what changed since the last time; [force] asks for everything. */
export function sync(force = false) {
  if (!state.account) return Promise.resolve();
  if (syncing && !force) return syncing;
  const since = force || revision == null ? '' : `?since=${revision}`;
  const run = get(`/api/state${since}`).then((answer) => {
    if (!answer.unchanged) applySnapshot(answer);
  }).catch(() => {
    // Without the server the app shows what it saw last.
  }).finally(() => {
    if (syncing === run) syncing = null;
  });
  syncing = run;
  return run;
}

let writing = Promise.resolve();

/** Writes choices of the user on the server, showing them at once. */
function put(values) {
  for (const [key, value] of Object.entries(values)) pendingValues.set(key, value);
  update(values);
  writing = writing.then(async () => {
    try {
      await post('/api/data', { values });
    } catch (e) {
      if (!(e instanceof SessionError)) say(e instanceof OfflineError ? str('srv_not_saved') : e.message);
    } finally {
      for (const [key, value] of Object.entries(values)) if (pendingValues.get(key) === value) pendingValues.delete(key);
    }
  });
  return writing.then(() => sync());
}

// ——— Failures ———

/** Runs [block], turning failures into a message, unless [quiet], or a return to the sign-in screen. */
async function guarded(block, quiet = false) {
  try {
    await block();
    return true;
  } catch (e) {
    if (e instanceof NotLoggedInError) update({ loggedIn: false, loginMessage: e.message });
    else if (e instanceof SessionError) return false;
    else if (!quiet) say(e instanceof OfflineError ? e.message || str('no_connection') : e.message || str('something_wrong'));
    return false;
  }
}

// ——— The account on the server ———

/** Looks at the session with the server: who is signed in, if anybody. */
export async function checkSession() {
  try {
    const answer = await get('/api/session');
    update({ serverVersion: answer.version, invite: answer.invite === true });
    if (answer.loggedIn) {
      if (state.account !== answer.username) useAccount(answer.username);
      update({ account: answer.username });
      sendLanguage();
      await sync(true);
    } else {
      update({ account: false });
    }
  } catch {
    // Offline: the last account opens with what the browser kept.
    const last = settings.get('lastAccount', null);
    if (last && state.account == null) {
      useAccount(last);
      update({ account: last });
    } else if (state.account == null) update({ account: false });
  }
}

onSessionLost(() => {
  if (state.account) update({ account: false });
});

/** Signs in to the server, or creates the account when [register]. Returns an error message or null. */
export async function signIn(username, password, register = false, invite = '') {
  try {
    const answer = await post(register ? '/api/register' : '/api/login', { username, password, invite });
    settings.set('lastAccount', answer.username);
    useAccount(answer.username);
    update({ account: answer.username });
    sendLanguage();
    await sync(true);
    refresh();
    return null;
  } catch (e) {
    return e.message || str('something_wrong');
  }
}

/** Leaves the account on this device: it stops getting its notifications first. */
export async function signOut() {
  await disablePush().catch(() => {});
  await post('/api/logout').catch(() => {});
  settings.remove('lastAccount');
  location.reload();
}

export async function changePassword(current, password) {
  try {
    await post('/api/account/password', { current, password });
    say(str('srv_password_changed'));
    return null;
  } catch (e) {
    return e.message;
  }
}

export async function deleteAccount(password) {
  try {
    await disablePush().catch(() => {});
    await post('/api/account/delete', { password });
    cache?.clear();
    settings.remove('lastAccount');
    location.reload();
    return null;
  } catch (e) {
    return e.message;
  }
}

/** Tells the server the language of the app, which its notifications speak. */
function sendLanguage() {
  post('/api/language', { language: lang() }).catch(() => {});
}

// ——— Notifications, sent by the server ———

export const notificationsSupported = () => pushState().supported;
export const notificationsEnabled = () => state.push.enabled;

/** Asks for the permission to notify, where the browser still has to ask, and tells the server where to send them. */
export async function askNotifications() {
  if (state.push.enabled) return;
  const error = await enablePush();
  update({ push: pushState() });
  if (error) say(error);
}

export async function stopNotifications() {
  await disablePush().catch(() => {});
  update({ push: pushState() });
}

export async function testNotification() {
  await guarded(() => post('/api/push/test'));
}

// ——— First run, sign-in to Amazon ———

export function acceptOnboarding() {
  settings.set('onboarded', true);
  update({ onboarded: true });
}

export function setMarketplace(domain) {
  update({ marketplace: marketOf(domain) });
  guarded(() => post('/api/alexa/marketplace', { domain }));
}

/** Prepares a new sign-in on the server, returning the address of Amazon's page. */
export async function newLoginAttempt() {
  try {
    const { url } = await post('/api/alexa/attempt');
    update({ loginPending: true, loginMessage: null });
    return url;
  } catch (e) {
    update({ loginMessage: e.message });
    return null;
  }
}

export const pendingLoginAttempt = () => state.loginPending;

/** Completes the sign-in with the address Amazon landed on, or with the code alone. */
export async function completeLogin(landing) {
  update({ signingIn: true, loginMessage: null });
  try {
    await post('/api/alexa/login', { landing });
    update({ signingIn: false });
    await sync(true);
  } catch (e) {
    update({ signingIn: false, loginMessage: e.message || str('login_failed') });
  }
}

/** Signs out of Amazon: the home it gave goes, with its timers, schedules and automations. */
export async function logout() {
  await guarded(() => post('/api/alexa/logout'));
  await sync(true);
}

// ——— Devices and their states ———

let refreshing = null;

export function refresh() {
  if (!state.account || !state.loggedIn || refreshing) return;
  // The remotes change rarely and have no state: they are fetched on the side, without a word.
  if (state.switchBot) guarded(() => post('/api/switchbot/remotes'), true).then(() => sync());
  update({ refreshing: true });
  refreshing = guarded(async () => {
    await post('/api/alexa/refresh', { showHidden: state.showHidden });
    await sync();
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
  if (!state.account || !state.loggedIn || refreshing) return;
  if (!state.devices.length) return refresh();
  refreshing = guarded(async () => {
    await post('/api/alexa/poll', {});
    await sync();
  }, true).finally(() => {
    refreshing = null;
  });
}

/** Applies [optimistic] right away and rolls it back if Alexa refuses the command. */
async function command(device, optimistic, body) {
  const id = device.applianceId;
  const before = state.states[id];
  update((s) => ({ states: { ...s.states, [id]: optimistic(before ?? emptyState()) }, busy: withItem(s.busy, id) }));
  const ok = await guarded(() => post('/api/alexa/control', { applianceId: id, ...body }));
  update((s) => {
    let states = s.states;
    if (!ok) {
      states = { ...states };
      if (before) states[id] = before;
      else delete states[id];
    }
    return { states, busy: without(s.busy, id) };
  });
  sync();
  return ok;
}

export function setPower(device, on) {
  // A device switched off by hand no longer needs its timer: the server drops it.
  if (!on && device.applianceId in state.timers) {
    const timers = { ...state.timers };
    delete timers[device.applianceId];
    update({ timers });
  }
  return command(device, (st) => ({ ...st, power: on }), { action: 'power', on });
}

/** Sets a color or a shade of white, by the name Alexa gives it. Lamps switch on when asked for one. */
export function setColor(device, choice) {
  return command(device, (st) => ({ ...st, colorName: choice.alexaName, power: true }),
    { action: 'color', color: choice.alexaName, white: choice.white });
}

export function setBrightness(device, percent) {
  return command(device, (st) => ({ ...st, brightness: percent }), { action: 'brightness', brightness: percent });
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
  put({ hidden: hidden ? withItem(state.hidden, device.applianceId) : without(state.hidden, device.applianceId) });
}

export function setTileSpan(key, columns) {
  put({ tileSpans: { ...state.tileSpans, [key]: columns === 2 ? 2 : 1 } });
}

/** Adds to the favorites, or takes away, a device by its applianceId, or a remote or custom device by its key. */
export function setFavorite(key, favorite) {
  const favorites = favorite ? withItem(state.favorites, key) : without(state.favorites, key);
  cache?.set('favorites', favorites);
  update({ favorites });
}

export function toggleShowHidden() {
  update({ showHidden: !state.showHidden });
  if (state.showHidden) refresh();
}

/** Copies to the clipboard everything Alexa says about [device], for writing support for it. */
export async function copyDiagnostics(device) {
  const copied = await guarded(async () => {
    const { text } = await get(`/api/alexa/diagnostics/${encodeURIComponent(device.applianceId)}`);
    await navigator.clipboard.writeText(text);
  });
  if (copied) say(str('diagnostics_copied'));
}

// ——— Timers, schedules, automations, alerts: the server carries them out ———

/** Switches [device] off in [minutes] minutes. */
export async function setTimer(device, minutes) {
  update({ timers: { ...state.timers, [device.applianceId]: Date.now() + minutes * 60_000 } });
  await guarded(() => post('/api/timers', { applianceId: device.applianceId, minutes }));
  sync();
}

export async function cancelTimer(applianceId) {
  if (!(applianceId in state.timers)) return;
  const timers = { ...state.timers };
  delete timers[applianceId];
  update({ timers });
  await guarded(() => del(`/api/timers/${encodeURIComponent(applianceId)}`));
  sync();
}

/** Sets [action] to be carried out [at] a moment, and again on each of the [days] if there are any. */
export async function addSchedule(action, label, at, days) {
  await guarded(() => post('/api/schedules', { action, label, at, days }));
  sync();
}

/** Sets [action] to happen once, in [minutes] minutes: the timer of what is not a device of Alexa. */
export const addTimer = (action, label, minutes) => addSchedule(action, label, Date.now() + minutes * 60_000, []);

/** Switches a repeating schedule off, or on again from its next time. */
export async function setScheduleEnabled(schedule, enabled) {
  update({ schedules: state.schedules.map((it) => (it.id === schedule.id ? { ...it, enabled } : it)) });
  await guarded(() => post(`/api/schedules/${schedule.id}/enabled`, { enabled }));
  sync();
}

export async function deleteSchedule(id) {
  update({ schedules: state.schedules.filter((it) => it.id !== id) });
  await guarded(() => del(`/api/schedules/${id}`));
  sync();
}

/** Creates an automation: [action] when [device] gets to what [trigger] and [threshold] say. */
export async function addAutomation(device, trigger, threshold, action, title, label) {
  await guarded(() => post('/api/automations', { device: device.applianceId, trigger, threshold, action, title, label }));
  sync();
}

export async function setAutomationEnabled(automation, enabled) {
  update({ automations: state.automations.map((a) => (a.id === automation.id ? { ...a, enabled } : a)) });
  await guarded(() => post(`/api/automations/${automation.id}/enabled`, { enabled }));
  sync();
}

export async function deleteAutomation(id) {
  update({ automations: state.automations.filter((a) => a.id !== id) });
  await guarded(() => del(`/api/automations/${id}`));
  sync();
}

/** Sets the temperatures a sensor notifies about: {above, below}, each null for none. */
export async function setAlert(applianceId, alert) {
  const alerts = { ...state.alerts };
  if (alert.above == null && alert.below == null) delete alerts[applianceId];
  else alerts[applianceId] = alert;
  update({ alerts });
  await guarded(() => post(`/api/alerts/${encodeURIComponent(applianceId)}`, alert));
  sync();
}

export const clearAlert = (applianceId) => setAlert(applianceId, { above: null, below: null });

// ——— History ———

/** The readings of the last week of a sensor: {at, temperature, humidity}. */
export async function readings(applianceId) {
  try {
    return (await get(`/api/history/${encodeURIComponent(applianceId)}`)).readings;
  } catch {
    return [];
  }
}

export async function clearHistory() {
  if (await guarded(() => del('/api/history'))) say(str('history_cleared'));
}

// ——— The lists of Alexa, asked every time: they change from the Echo devices and the Alexa app too ———

/** Runs [block] for the lists screen, telling a failure as the rest of the app does; null when it failed. */
async function onList(block) {
  let result = null;
  const ok = await guarded(async () => {
    result = await block();
  });
  return ok ? result ?? true : null;
}

export const alexaLists = () => onList(async () => (await get('/api/lists')).lists);

export const alexaListItems = (listId) =>
  onList(async () => (await get(`/api/lists/${encodeURIComponent(listId)}/items`)).items);

export async function addAlexaListItem(listId, name) {
  navigator.vibrate?.(12);
  return (await onList(() => post(`/api/lists/${encodeURIComponent(listId)}/items`, { name }))) != null;
}

export async function setAlexaListItemDone(listId, item, completed) {
  navigator.vibrate?.(12);
  const path = `/api/lists/${encodeURIComponent(listId)}/items/${encodeURIComponent(item.id)}`;
  return (await onList(() => api('PUT', path, { version: item.version, completed }))) != null;
}

export async function deleteAlexaListItem(listId, item) {
  const path = `/api/lists/${encodeURIComponent(listId)}/items/${encodeURIComponent(item.id)}?version=${item.version}`;
  return (await onList(() => del(path))) != null;
}

// ——— Rooms and groups ———

const omit = (map, key) => {
  const copy = { ...map };
  delete copy[key];
  return copy;
};

/** Moves a device to [room]: a name, an empty text for no room, or null to follow Alexa again. */
export function setRoom(device, room) {
  put({ rooms: room == null ? omit(state.rooms, device.applianceId) : { ...state.rooms, [device.applianceId]: room } });
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
  const roomList = [...new Set([...state.roomList.map((r) => (r === old ? name : r)), name])];
  put({ rooms, roomList });
}

/** Puts a remote or a custom device, by its key, in [room]; null or an empty text takes it out. */
export function setPlace(key, room) {
  put({ rooms: room ? { ...state.rooms, [key]: room } : omit(state.rooms, key) });
}

/** Removes a room created in the app; its devices go back to their Alexa room. */
export function deleteRoom(room) {
  put({ rooms: Object.fromEntries(Object.entries(state.rooms).filter(([, v]) => v !== room)), roomList: without(state.roomList, room) });
}

export const resetRooms = () => put({ rooms: {}, roomList: [] });

/** Creates a group, when [id] is null, or changes its name and devices. */
export function saveGroup(id, name, members) {
  const group = { id: id ?? crypto.randomUUID(), name, devices: members };
  put({ groups: id == null ? [...state.groups, group] : state.groups.map((g) => (g.id === id ? group : g)) });
}

export const deleteGroup = (id) => put({ groups: state.groups.filter((g) => g.id !== id) });

export const setOrder = (order) => put({ order });

// ——— Custom devices ———

/** Creates a custom device, when its id is empty, or replaces the one with the same id. */
export function saveMacro(macro) {
  put({ macros: macro.id ? state.macros.map((m) => (m.id === macro.id ? macro : m)) : [...state.macros, { ...macro, id: crypto.randomUUID() }] });
}

export function deleteMacro(id) {
  put({ rooms: omit(state.rooms, macroKey(id)), macros: state.macros.filter((m) => m.id !== id) });
}

/** Runs a custom device on the server, and says how it went. */
export async function runMacro(macro) {
  if (state.busy.includes(macro.id)) return;
  update((s) => ({ busy: withItem(s.busy, macro.id) }));
  let outcome = null;
  await guarded(async () => {
    outcome = await post(`/api/macros/${macro.id}/run`);
  });
  if (outcome && !outcome.loginExpired) say(outcome.message);
  if (outcome?.loginExpired) update({ loggedIn: false, loginMessage: str('login_expired') });
  update((s) => ({ busy: without(s.busy, macro.id) }));
  sync();
}

// ——— Voice ———

const voiceDevices = () => placed(state).filter((d) => !state.hidden.includes(d.applianceId));

/** Does what was [heard] when it is understood without doubt; false when it is not. */
function act(heard) {
  const result = parseVoice(heard, voiceDevices());
  if (result.type === 'run') {
    runVoice(result.command);
    return true;
  }
  // What Alexa does not know is tried when no device of hers was clearly meant.
  // A remote learnt key by key has no power key to be told about.
  const remotes = shownRemotes(state).filter((r) => remoteKind(r) !== 'CUSTOM');
  const extra = parseExtras(heard, state.macros.map((m) => [m.id, m.name]), remotes.map((r) => [r.id, r.name]));
  if (extra?.type === 'run') {
    const macro = state.macros.find((m) => m.id === extra.macroId);
    say(str('schedule_run', macro.name));
    runMacro(macro);
    return true;
  }
  if (extra?.type === 'remote') {
    const remote = remotes.find((r) => r.id === extra.remoteId);
    say(str(extra.on ? 'voice_on' : 'voice_off', remote.name));
    sendRemote(remote, extra.on ? 'turnOn' : 'turnOff');
    return true;
  }
  return false;
}

/** Tells what was understood only in part, or not at all. */
function answer(result) {
  if (result.type === 'ask') update({ voice: result });
  else if (result.type !== 'run') say(result.heard.trim() ? str('voice_not_understood', result.heard) : str('voice_nothing_heard'));
}

/**
 * Acts on what the speech recognizer [heard], its guesses best first. What is not clearly
 * understood goes to the assistant, when there is one, to have the names put right first.
 */
export async function onSpeech(heard) {
  if (act(heard)) return;
  const result = parseVoice(heard, voiceDevices());
  if (!state.assistant.configured || heard.every((it) => !it.trim())) return answer(result);
  update({ correcting: true });
  let corrected = '';
  try {
    ({ text: corrected } = await post('/api/assistant/correct', { heard: heard.filter((it) => it.trim()), home: assistantHome() }));
  } catch {
    // Without the assistant the sentence is still read as it was heard.
  } finally {
    update({ correcting: false });
  }
  if (!corrected || !act([corrected])) {
    // What was put right is offered first, should it still be understood only in part.
    const again = corrected ? parseVoice([corrected, ...heard], voiceDevices()) : result;
    answer(again.type === 'unknown' ? result : again);
  }
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

// ——— Remotes: SwitchBot and the LG TVs ———

/** Links the SwitchBot account, keeping the keys only if SwitchBot accepts them. */
export async function connectSwitchBot(token, secret) {
  await guarded(async () => {
    const { count } = await post('/api/switchbot/connect', { token: token.trim(), secret: secret.trim() });
    await sync();
    say(plural('remotes_found', count));
  });
}

export async function disconnectSwitchBot() {
  await guarded(() => post('/api/switchbot/disconnect'));
  sync();
}

/** Presses a button of [remote]. Nothing comes back from an appliance: only a failure is told. */
export async function sendRemote(remote, command, parameter = 'default', custom = false) {
  update((s) => ({ busy: withItem(s.busy, remote.id) }));
  const sent = await guarded(() => post('/api/remotes/send', { id: remote.id, command, parameter, custom }));
  if (!sent) navigator.vibrate?.([30, 60, 30]);
  update((s) => ({ busy: without(s.busy, remote.id) }));
  return sent;
}

/** Sends an air conditioner everything at once, as its remote would, and remembers it. */
export function sendAc(remote, setting, on) {
  setAc(remote, setting);
  return sendRemote(remote, 'setAll', `${setting.temperature},${setting.mode},${setting.fan},${on ? 'on' : 'off'}`);
}

export function setAc(remote, setting) {
  put({ acSettings: { ...state.acSettings, [remote.id]: setting } });
}

export function setRemoteHidden(remote, hidden) {
  put({ hiddenRemotes: hidden ? withItem(state.hiddenRemotes, remote.id) : without(state.hiddenRemotes, remote.id) });
}

export function setRemoteButtons(remote, names) {
  put({ remoteButtons: { ...state.remoteButtons, [remote.id]: names } });
}

/** Looks for LG TVs on the network of the server. */
export async function searchLgTvs() {
  try {
    return (await post('/api/lg/discover')).found;
  } catch {
    return [];
  }
}

/**
 * Pairs the TV at [host], which asks the user on its screen. [onAsking] is called once the TV
 * shows the request; the promise ends true when paired.
 */
export async function pairLgTv(host, name, onAsking) {
  if (!await guarded(() => post('/api/lg/pair', { host, name }))) return false;
  for (;;) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    let pairing;
    try {
      pairing = await get('/api/lg/pair');
    } catch {
      continue;
    }
    if (!pairing) return false;
    if (pairing.asking) onAsking();
    if (pairing.error) {
      say(pairing.error);
      return false;
    }
    if (pairing.done) {
      say(str('lgtv_paired', pairing.name));
      await sync();
      return true;
    }
  }
}

export const cancelLgPairing = () => del('/api/lg/pair').catch(() => {});

/** Gives the LG TV [id] the [name] it goes by in the app; the TV itself keeps its own. */
export async function renameLgTv(id, name) {
  if (!name.trim()) return;
  await guarded(() => post(`/api/lg/${encodeURIComponent(id)}/rename`, { name }));
  sync();
}

export async function forgetLgTv(id) {
  await guarded(() => del(`/api/lg/${encodeURIComponent(id)}`));
  sync();
}

/** Asks [remote], an LG TV, for its apps and its sources, to offer them as buttons and to switch. */
export async function refreshLgApps(remote) {
  update((s) => ({ busy: withItem(s.busy, remote.id) }));
  await guarded(() => post(`/api/lg/${encodeURIComponent(remote.id)}/apps`), true);
  await sync();
  update((s) => ({ busy: without(s.busy, remote.id) }));
}

/** Writes in the text field open on the TV, turning what was [sent] into [typed]; false when it did not take it. */
export const typeOnLgTv = (remote, sent, typed, quiet = false) =>
  guarded(() => post(`/api/lg/${encodeURIComponent(remote.id)}/type`, { sent, typed }), quiet);

export const enterOnLgTv = (remote) => guarded(() => post(`/api/lg/${encodeURIComponent(remote.id)}/enter`));

/** Shows a message on the TV, to see that it answers. */
export async function testLgTv(id) {
  update((s) => ({ busy: withItem(s.busy, id) }));
  await guarded(() => post(`/api/lg/${encodeURIComponent(id)}/test`));
  update((s) => ({ busy: without(s.busy, id) }));
}

// ——— The assistant ———

/** The keys of a remote as the assistant sees them: what is sent, whether the user added it, how it is called. */
function assistantKeys(remote) {
  const keys = standardCommands(remote).map((command) => ({ command, label: commandLabel(command) }));
  for (const name of state.remoteButtons[remote.id] || []) keys.push({ command: name, label: name });
  return keys;
}

/** Everything the assistant may act on, as it is now: the hidden things are left out. */
function assistantHome() {
  const devices = voiceDevices();
  return {
    devices,
    states: state.states,
    groups: state.groups,
    macros: state.macros.map((m) => ({ key: macroKey(m.id), name: m.name, room: placeOf(state, macroKey(m.id)) })),
    remotes: shownRemotes(state).map((r) => ({
      id: r.id, key: remoteKey(r.id), name: r.name, type: r.type, room: placeOf(state, remoteKey(r.id)), keys: assistantKeys(r),
    })),
  };
}

/** Tries the keys typed in the guide and keeps them if Cloudflare takes them; null when it went well. */
export async function connectAssistant(accountId, token) {
  try {
    await post('/api/assistant/connect', { accountId, token });
    await sync();
    say(str('assistant_test_ok'));
    return null;
  } catch (e) {
    return e instanceof OfflineError ? str('no_connection') : e.message;
  }
}

export async function disconnectAssistant() {
  await guarded(() => post('/api/assistant/disconnect'));
  sync();
}

export async function setAssistantModel(model) {
  await guarded(() => post('/api/assistant/model', { model }));
  sync();
}

export function saveAssistantNotes(house, notes) {
  put({ assistantHouse: house.trim(), assistantNotes: notes.map((n) => ({ ...n, answer: n.answer.trim() })) });
}

/** Has the assistant look at the home and ask about what it does not understand: {questions, seen} or {error}. */
export async function assistantQuestions() {
  try {
    return await post('/api/assistant/questions', { home: assistantHome() });
  } catch (e) {
    return { error: e instanceof OfflineError ? str('no_connection') : e.message || str('something_wrong') };
  }
}

/** Keeps the answer to a question about [target]. */
export function addAssistantNote(target, question, answer) {
  put({ assistantNotes: [...state.assistant.notes, { target, question, answer: answer.trim() }] });
}

/** Notes that the assistant has seen these things, so that it asks only about what is new. */
export function markTaught(keys) {
  put({ assistantTaught: [...new Set([...state.assistant.taught, ...keys])] });
}

// ——— Settings of this device ———

export function setAutoUpdate(on) {
  put({ autoUpdate: on });
}

export function setLayout(layout) {
  settings.set('layout', layout);
  update({ layout });
}

export function setTheme(theme) {
  settings.set('theme', theme);
  update({ theme });
}

export function setAccent(accent) {
  settings.set('accent', accent);
  update({ accent });
}

export function setLanguage(language) {
  settings.set('language', language);
  applyLanguage(language);
  update({ language });
  sendLanguage();
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

/** Everything the backup holds, as now: the choices here, the keys from the server. */
async function currentBackup() {
  const keys = await get('/api/backup/keys');
  const s = state;
  return createBackup({
    ...s,
    remotes: s.switchBotRemotes,
    lgTvs: keys.lgTvs,
    assistant: { notes: s.assistant.notes, house: s.assistant.house, taught: s.assistant.taught },
  }, keys.switchBot, keys.assistantKeys);
}

export async function exportBackup() {
  await guarded(async () => {
    const backup = await currentBackup();
    const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `hommaterial-backup-${new Date().toISOString().slice(0, 10)}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
    say(str('backup_exported'));
  });
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
  pendingBackup = backup;
  update({ askBackupRestore: true });
}

export async function finishImport(selected) {
  const imported = pendingBackup;
  pendingBackup = null;
  update({ askBackupRestore: false });
  if (!imported || !selected?.length) return;
  await guarded(async () => {
    const backup = selectBackup(await currentBackup(), imported, selected);
    await restore(backup, selected);
  });
}

async function restore(backup, selected) {
  const list = (v) => (Array.isArray(v) ? v.map(String) : []);
  const alerts = {};
  for (const [id, a] of Object.entries(backup.alerts || {})) alerts[id] = { above: a.above ?? null, below: a.below ?? null };
  if (selected.includes('favorites')) {
    cache?.set('favorites', list(backup.favorites));
    update({ favorites: list(backup.favorites) });
  }
  const values = {
    hidden: list(backup.hidden),
    rooms: backup.rooms || {},
    roomList: list(backup.roomList),
    groups: (backup.groups || []).map((g) => ({ id: g.id, name: g.name, devices: list(g.devices) })),
    macros: (backup.macros || []).map((m) => ({
      id: m.id, name: m.name, condition: m.condition ?? null, steps: m.steps || [], otherwise: m.otherwise || [],
    })),
    order: backup.order || {},
    tileSpans: backup.tileSpans || {},
    schedules: (backup.schedules || []).map((s) => ({ ...s, days: list(s.days).map(Number) })),
    automations: (backup.automations || []).map((a) => ({ ...a })),
    shortcuts: backup.shortcuts ?? null,
    widget: backup.widget ?? null,
    tiles: Array.from({ length: 10 }, (_, i) => backup.tiles?.[i] || null),
    alerts,
    remoteButtons: backup.remoteButtons || {},
    hiddenRemotes: list(backup.hiddenRemotes),
    autoUpdate: backup.autoUpdate ?? true,
    haptics: backup.haptics ?? true,
    hapticLevel: clamp(Number(backup.hapticLevel ?? 70), 10, 100),
    assistantNotes: Array.isArray(backup.assistant?.notes) ? backup.assistant.notes : [],
    assistantHouse: String(backup.assistant?.house ?? ''),
    assistantTaught: list(backup.assistant?.taught),
  };
  const answer = await post('/api/backup/restore', {
    values,
    selected,
    switchBot: backup.switchBot ?? null,
    assistantKeys: backup.assistantKeys ?? null,
    lgTvs: Array.isArray(backup.lgTvs) ? backup.lgTvs : null,
  });
  const theme = clamp(Number(backup.theme ?? 0), 0, 2);
  const layout = clamp(Number(backup.layout ?? 0), 0, 1);
  const language = backup.language || 'auto';
  const accent = typeof backup.accent === 'string' && backup.accent ? backup.accent : 'auto';
  settings.edit({ theme, accent, layout, language });
  applyLanguage(language);
  update({ theme, accent, layout, language });
  if (selected.includes('monitor') && backup.monitor) setMonitor(monitorFromJson(backup.monitor));
  await sync(true);
  if (answer.switchBot) refresh();
  say(str('backup_imported'));
}

// ——— Updates of the web app ———

export const installedVersion = APP_VERSION;

/**
 * Looks for a newer version of the web app on the server. The automatic check runs at most once
 * a day and stays silent unless it finds something; the [manual] one always reports its outcome.
 */
export async function checkForUpdate(manual, quiet = false) {
  if (!state.onboarded) return;
  const now = Date.now();
  const last = settings.get('updateCheckedAt', 0);
  if (!manual && (!state.autoUpdate || (now - last >= 0 && now - last < UPDATE_CHECK_INTERVAL_MS))) return;
  try {
    const response = await fetch(`/js/version.js?at=${now}`, { cache: 'no-store' });
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

// ——— Keeping in step with the server ———

let syncTimer = null;

/** Asks the server again every little while, while the app is in sight. */
export function startSync() {
  if (syncTimer) return;
  syncTimer = setInterval(() => {
    if (document.visibilityState === 'visible') sync();
  }, SYNC_MS);
  // A notification of the server comes with fresh states and timers to show.
  navigator.serviceWorker?.addEventListener('message', (event) => {
    if (event.data?.type === 'pushed') sync();
  });
}

export { isLg };
