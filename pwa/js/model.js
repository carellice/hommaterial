// What the app knows and works out, without screens: the same rules as the Android app.
import { str, plural, number, clock, dateText, degrees, locale } from './i18n.js';
import { remoteKind } from './switchbot.js';

export const ACTION_POWER = 'power';
export const ACTION_REMOTE = 'remote';
export const ACTION_WAIT = 'wait';
/** Running a custom device: an action only a schedule has, since a custom device does not run another. */
export const ACTION_MACRO = 'macro';

export const TRIGGER_TEMPERATURE_ABOVE = 'temperatureAbove';
export const TRIGGER_TEMPERATURE_BELOW = 'temperatureBelow';
export const TRIGGER_HUMIDITY_ABOVE = 'humidityAbove';
export const TRIGGER_HUMIDITY_BELOW = 'humidityBelow';
export const TRIGGER_ON = 'on';
export const TRIGGER_OFF = 'off';
export const HUMIDITY_TRIGGERS = [TRIGGER_HUMIDITY_ABOVE, TRIGGER_HUMIDITY_BELOW];
export const THRESHOLD_TRIGGERS = [...HUMIDITY_TRIGGERS, TRIGGER_TEMPERATURE_ABOVE, TRIGGER_TEMPERATURE_BELOW];

export const LAYOUT_GRID = 0;
export const LAYOUT_LIST = 1;

export const PAGE_ALL = 'all';
export const PAGE_FAVORITES = 'favorites';
export const PAGE_GROUPS = 'groups';
export const PAGE_MACROS = 'macros';
export const PAGE_REMOTES = 'remotes';
export const PAGE_ROOM = 'room:';

export const REST_CLOCK = 'clock';
export const REST_DATE = 'date';
export const REST_SENSORS = 'sensors';
export const REST_TIMERS = 'timers';
export const REST_POWER = 'power';
export const REST_ITEMS = [REST_CLOCK, REST_DATE, REST_SENSORS, REST_TIMERS, REST_POWER];

export const TIMER_CHOICES = [['timer_15', 15], ['timer_30', 30], ['timer_60', 60], ['timer_120', 120]];

/** How a custom device is named where devices are named by their applianceId. */
export const macroKey = (id) => `macro:${id}`;
/** The same for a remote, which like a custom device can be put in a room. */
export const remoteKey = (id) => `remote:${id}`;

export const emptyState = () => ({
  power: null, brightness: null, temperature: null, humidity: null, colorName: null, reachable: true,
});

export const WHITE_CHOICES = [
  ['warm_white', 'color_warm_white', '#FFC58F'],
  ['soft_white', 'color_soft_white', '#FFDDB8'],
  ['white', 'color_white', '#FFF1E0'],
  ['daylight_white', 'color_daylight_white', '#F3F6FF'],
  ['cool_white', 'color_cool_white', '#DCE8FF'],
].map(([alexaName, label, rgb]) => ({ alexaName, label, rgb, white: true }));

export const COLOR_CHOICES = [
  ['red', 'color_red', '#FF0000'],
  ['orange', 'color_orange', '#FF8C00'],
  ['yellow', 'color_yellow', '#FFE600'],
  ['green', 'color_green', '#00C853'],
  ['turquoise', 'color_turquoise', '#40E0D0'],
  ['cyan', 'color_cyan', '#00E5FF'],
  ['sky_blue', 'color_sky_blue', '#87CEEB'],
  ['blue', 'color_blue', '#2962FF'],
  ['purple', 'color_purple', '#8E24AA'],
  ['magenta', 'color_magenta', '#FF00FF'],
  ['pink', 'color_pink', '#FF80AB'],
  ['lavender', 'color_lavender', '#B39DDB'],
].map(([alexaName, label, rgb]) => ({ alexaName, label, rgb, white: false }));

const validTemperature = (t) => typeof t === 'number' && !Number.isNaN(t);

/** The line shown under a device name. */
export function statusText(device, state) {
  if (!state) return '…';
  if (!state.reachable) return str('unreachable');
  if (device.isSensor && validTemperature(state.temperature)) {
    return degrees(state.temperature) + (state.humidity != null ? ` · ${state.humidity}%` : '');
  }
  if (state.power === true && device.hasBrightness && state.brightness != null) {
    return str('on_brightness', state.brightness);
  }
  if (state.power === true) return str('on');
  if (state.power === false) return str('off');
  return str('unknown_state');
}

// ——— What derives from the state of the app ———

/** The devices in the rooms the user put them in, which are those of Alexa unless moved. */
export function placed(s) {
  return s.devices.map((d) => (d.applianceId in s.rooms ? { ...d, room: s.rooms[d.applianceId] || null } : d));
}

/** The room a remote or a custom device was put in, by its key; null when it is in none. */
export const placeOf = (s, key) => s.rooms[key] || null;

/** Every room there is: of the devices, made in the app, or holding only remotes and custom devices. */
export function allRooms(s) {
  const fromExtras = Object.entries(s.rooms).filter(([k]) => k.includes(':')).map(([, v]) => v);
  const all = [...placed(s).map((d) => d.room).filter(Boolean), ...s.roomList, ...fromExtras].filter(Boolean);
  return [...new Set(all)].sort(byName);
}

/** The remotes in the list: all but the hidden ones. */
export const shownRemotes = (s) => s.remotes.filter((r) => !s.hiddenRemotes.includes(r.id));

/** Whether the settings and the choices about the devices are behind the PIN of the monitor. */
export const isLocked = (s) => s.monitor.enabled && s.pin;

export const byName = (a, b) => a.toLowerCase().localeCompare(b.toLowerCase());

// ——— Custom devices, actions ———

/** The line under the name of a custom device: what it depends on, or how much it does. */
export function macroStatus(macro, s) {
  const watched = s.devices.find((d) => d.applianceId === macro.condition);
  return watched ? str('macro_depends', watched.name) : plural('macro_actions', macro.steps.length);
}

/** The keys every remote of a kind has, besides those the user added. */
export function standardCommands(remote) {
  switch (remoteKind(remote)) {
    case 'CUSTOM': return [];
    case 'TV': return ['turnOn', 'turnOff', 'volumeAdd', 'volumeSub', 'channelAdd', 'channelSub'];
    default: return ['turnOn', 'turnOff'];
  }
}

export function commandLabel(command) {
  switch (command) {
    case 'turnOn': return str('turn_on');
    case 'turnOff': return str('turn_off');
    case 'volumeAdd': return str('remote_up', str('remote_volume'));
    case 'volumeSub': return str('remote_down', str('remote_volume'));
    case 'channelAdd': return str('remote_up', str('remote_channel'));
    case 'channelSub': return str('remote_down', str('remote_channel'));
    default: return command;
  }
}

/** What an action does, as a sentence. */
export function describe(action, s) {
  switch (action.type) {
    case ACTION_MACRO:
      return str('schedule_run', s.macros.find((m) => m.id === action.target)?.name ?? str('macro_gone'));
    case ACTION_WAIT:
      return plural('macro_wait', Number(action.value) || 0);
    case ACTION_POWER: {
      const name = s.devices.find((d) => d.applianceId === action.target)?.name ?? str('macro_gone');
      return str(action.value === 'on' ? 'voice_on' : 'voice_off', name);
    }
    default: {
      const remote = s.remotes.find((r) => r.id === action.target)?.name ?? str('macro_gone');
      return str('macro_press', action.custom ? action.value : commandLabel(action.value), remote);
    }
  }
}

// ——— Automations ———

// A reading hovering around the threshold must not set the automation off again and again.
const MARGINS = {
  [TRIGGER_TEMPERATURE_ABOVE]: 0.3,
  [TRIGGER_TEMPERATURE_BELOW]: 0.3,
  [TRIGGER_HUMIDITY_ABOVE]: 2,
  [TRIGGER_HUMIDITY_BELOW]: 2,
};

/** How far past the condition the state is: above zero when it holds; null when it says nothing. */
function distance(automation, state) {
  if (!state.reachable) return null;
  const t = validTemperature(state.temperature) ? state.temperature : null;
  const h = state.humidity;
  const { trigger, threshold } = automation;
  switch (trigger) {
    case TRIGGER_TEMPERATURE_ABOVE: return t == null ? null : t - threshold;
    case TRIGGER_TEMPERATURE_BELOW: return t == null ? null : threshold - t;
    case TRIGGER_HUMIDITY_ABOVE: return h == null ? null : h - threshold;
    case TRIGGER_HUMIDITY_BELOW: return h == null ? null : threshold - h;
    case TRIGGER_ON: return state.power == null ? null : state.power ? 1 : -1;
    case TRIGGER_OFF: return state.power == null ? null : state.power ? -1 : 1;
    default: return null;
  }
}

/** Whether [state] is what the automation waits for; null when it says nothing about it. */
export function holds(automation, state) {
  const d = distance(automation, state);
  return d == null ? null : d > 0;
}

/** Whether [state] is far enough from the condition for the automation to be ready again. */
export function cleared(automation, state) {
  const d = distance(automation, state);
  return d == null ? null : d <= -(MARGINS[automation.trigger] ?? 0);
}

export function amount(trigger, threshold) {
  return HUMIDITY_TRIGGERS.includes(trigger) ? `${Math.round(threshold)}%` : degrees(threshold);
}

/** The condition of an automation as a sentence, about the device called [name]. */
export function conditionText(name, trigger, threshold) {
  switch (trigger) {
    case TRIGGER_TEMPERATURE_ABOVE: return str('automation_temperature_above', name, amount(trigger, threshold));
    case TRIGGER_TEMPERATURE_BELOW: return str('automation_temperature_below', name, amount(trigger, threshold));
    case TRIGGER_HUMIDITY_ABOVE: return str('automation_humidity_above', name, amount(trigger, threshold));
    case TRIGGER_HUMIDITY_BELOW: return str('automation_humidity_below', name, amount(trigger, threshold));
    case TRIGGER_ON: return str('automation_on', name);
    default: return str('automation_off', name);
  }
}

// ——— Schedules ———

// The days are those of java.util.Calendar, Sunday 1 to Saturday 7, as in the backups of the app.
export const WEEK = [2, 3, 4, 5, 6, 7, 1];
const calendarDay = (date) => date.getDay() + 1;

/** The first time it is [hour]:[minute] after [after], on one of the [days] when there are any. */
export function nextTime(hour, minute, days, after) {
  const date = new Date(after);
  date.setHours(hour, minute, 0, 0);
  // A week is enough to meet any day; the eighth step only guards against a set of days gone wrong.
  for (let i = 0; i < 8; i++) {
    if (date.getTime() > after && (days.length === 0 || days.includes(calendarDay(date)))) return date.getTime();
    date.setDate(date.getDate() + 1);
    date.setHours(hour, minute, 0, 0);
  }
  return date.getTime();
}

export function dayName(day) {
  // 2024-01-07 was a Sunday: day 1 of the calendar.
  return new Date(2024, 0, 6 + day).toLocaleDateString(locale(), { weekday: 'short' });
}

/** When a schedule runs next, or on which days, as a line of text. */
export function whenText(schedule) {
  let text;
  if (schedule.days.length === 0) {
    const now = new Date();
    const then = new Date(schedule.at);
    const midnight = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    const ahead = Math.round((midnight(then) - midnight(now)) / 86_400_000);
    const day = ahead === 0 ? str('schedule_today') : ahead === 1 ? str('schedule_tomorrow') : dateText(schedule.at);
    text = str('schedule_once', day, clock(schedule.at));
  } else if (schedule.days.length === WEEK.length) {
    text = str('schedule_daily', clock(schedule.at));
  } else {
    const days = WEEK.filter((d) => schedule.days.includes(d)).map(dayName).join(', ');
    text = str('schedule_once', days, clock(schedule.at));
  }
  return schedule.enabled ? text : str('schedule_paused', text);
}

/** Time left as 1:05:09 or 05:09; past due, waiting for the network, it stays at zero. */
export function countdown(millis) {
  const seconds = Math.floor(Math.max(0, millis) / 1000);
  const h = Math.floor(seconds / 3600);
  const m = Math.floor(seconds / 60) % 60;
  const s = seconds % 60;
  const two = (n) => String(n).padStart(2, '0');
  // Days away, the seconds would only flicker: days and hours say enough.
  if (h >= 24) return str('countdown_days', Math.floor(h / 24), h % 24);
  if (h > 0) return `${h}:${two(m)}:${two(s)}`;
  return `${two(m)}:${two(s)}`;
}

export function ageText(updatedAt, now) {
  const minutes = Math.floor((now - updatedAt) / 60_000);
  if (minutes < 1) return str('updated_now');
  if (minutes < 60) return plural('updated_minutes', minutes);
  if (minutes < 24 * 60) return plural('updated_hours', Math.floor(minutes / 60));
  return str('updated_long_ago');
}

// ——— Pages and sections of the home ———

/** Every page there is something to show in, in the order the side bar lists them by default. */
export function availablePages(s) {
  const visible = placed(s).filter((d) => !s.hidden.includes(d.applianceId));
  // A room may hold nothing but remotes and custom devices.
  const extras = [...s.macros.map((m) => macroKey(m.id)), ...shownRemotes(s).map((r) => remoteKey(r.id))];
  const rooms = [...new Set([...visible.map((d) => d.room).filter(Boolean), ...extras.map((k) => placeOf(s, k)).filter(Boolean)])]
    .sort(byName);
  const pages = [PAGE_ALL];
  if (visible.some((d) => s.favorites.includes(d.applianceId)) || extras.some((k) => s.favorites.includes(k)) || s.groups.some((g) => s.favorites.includes(`group:${g.id}`))) {
    pages.push(PAGE_FAVORITES);
  }
  if (s.groups.length) pages.push(PAGE_GROUPS);
  if (s.macros.length) pages.push(PAGE_MACROS);
  if (shownRemotes(s).length) pages.push(PAGE_REMOTES);
  for (const room of rooms) pages.push(PAGE_ROOM + room);
  // An empty name stands for the devices without a room.
  if (rooms.length && visible.some((d) => d.room == null)) pages.push(PAGE_ROOM);
  return pages;
}

/** The pages to move between: those chosen for the monitor, all of them on a wide screen, one on a phone. */
export function shownPages(s, wide) {
  const available = availablePages(s);
  if (s.monitor.enabled) {
    const chosen = s.monitor.pages?.filter((p) => available.includes(p));
    return chosen?.length ? chosen : available;
  }
  return wide ? available : [PAGE_ALL];
}

export function pageLabel(page) {
  switch (page) {
    case PAGE_ALL: return str('page_all');
    case PAGE_FAVORITES: return str('favorites');
    case PAGE_GROUPS: return str('s_groups');
    case PAGE_MACROS: return str('s_macros');
    case PAGE_REMOTES: return str('s_remotes');
    default: return page.slice(PAGE_ROOM.length) || str('no_room');
  }
}

export function pageIcon(page) {
  switch (page) {
    case PAGE_ALL: return 'home';
    case PAGE_FAVORITES: return 'star';
    case PAGE_GROUPS: return 'layers';
    case PAGE_MACROS: return 'auto_awesome';
    case PAGE_REMOTES: return 'settings_remote';
    default: return 'meeting_room';
  }
}

/** Something that has a tile on the home: a device, a custom device, a remote or a group. */
const entry = {
  dev: (device) => ({ kind: 'dev', id: device.applianceId, name: device.name, device }),
  mac: (macro) => ({ kind: 'mac', id: macroKey(macro.id), name: macro.name, macro }),
  rem: (remote) => ({ kind: 'rem', id: remoteKey(remote.id), name: remote.name, remote }),
  grp: (group, devices) => ({ kind: 'grp', id: `group:${group.id}`, name: group.name, group, devices }),
};

/**
 * The sections of a [page], each with its tiles. The order of each section is the one the user
 * gave it; what was never moved keeps its usual place, after what was.
 */
export function homeSections(page, s) {
  const visible = placed(s).filter((d) => s.showHidden || !s.hidden.includes(d.applianceId));
  const room = page.startsWith(PAGE_ROOM) ? page.slice(PAGE_ROOM.length) || null : null;
  // On a monitor a page may be narrowed to some of its devices, or of its groups.
  const only = s.monitor.enabled ? s.monitor.only?.[page] ?? null : null;
  const inOnly = (id) => only == null || only.includes(id);
  let shown;
  if (page === PAGE_ALL) shown = visible;
  else if (page === PAGE_FAVORITES) shown = visible.filter((d) => s.favorites.includes(d.applianceId));
  else if (page === PAGE_GROUPS || page === PAGE_MACROS || page === PAGE_REMOTES) shown = [];
  else shown = visible.filter((d) => d.room === room);
  shown = shown.filter((d) => inOnly(d.applianceId));

  // Named rooms first, alphabetically; devices without a room close the list.
  const noRoom = str('no_room');
  const rooms = {};
  if (page !== PAGE_FAVORITES) for (const d of shown) (rooms[d.room ?? noRoom] ||= []).push(d);

  // Hidden devices do not count for a group, as they do not for a room.
  const groupList = (page === PAGE_ALL || page === PAGE_FAVORITES) ? s.groups : page === PAGE_GROUPS ? s.groups.filter((g) => inOnly(g.id)) : [];
  const groups = groupList
    .map((g) => entry.grp(g, s.devices.filter((d) => g.devices.includes(d.applianceId) && !s.hidden.includes(d.applianceId) && d.hasPower)))
    .filter((e) => e.devices.length);

  // Remotes and custom devices put in a room show there; the others keep their own sections.
  const inList = s.showHidden ? s.remotes : shownRemotes(s);
  const group = (list, key) => {
    const out = new Map();
    for (const it of list) {
      const place = placeOf(s, key(it.id));
      if (!out.has(place)) out.set(place, []);
      out.get(place).push(it);
    }
    return out;
  };
  const roomRemotes = group(inList, remoteKey);
  const roomMacros = group(s.macros, macroKey);
  const remotes = page === PAGE_ALL ? roomRemotes.get(null) ?? []
    : page === PAGE_REMOTES ? inList.filter((r) => inOnly(r.id)) : [];
  const macros = page === PAGE_ALL ? roomMacros.get(null) ?? []
    : page === PAGE_MACROS ? s.macros.filter((m) => inOnly(m.id)) : [];
  // A room may hold nothing but them.
  const extraRooms = [...roomRemotes.keys(), ...roomMacros.keys()].filter((r) => r != null && (page === PAGE_ALL || r === room));
  const names = [...new Set([...Object.keys(rooms), ...extraRooms])]
    .sort((a, b) => (a === noRoom) - (b === noRoom) || byName(a, b));
  // On a monitor a room narrowed to some of its things leaves the others out, whatever they are.
  const narrowed = only != null && page !== PAGE_ALL;

  // Favorites are repeated at the top and stay in their rooms too; remotes and custom devices as the devices.
  const starred = page === PAGE_ALL || page === PAGE_FAVORITES;
  const favorites = !starred ? [] : [
    ...shown.filter((d) => s.favorites.includes(d.applianceId)).sort((a, b) => byName(a.name, b.name)).map(entry.dev),
    ...s.macros.filter((m) => s.favorites.includes(macroKey(m.id))).map(entry.mac),
    ...inList.filter((r) => s.favorites.includes(remoteKey(r.id))).map(entry.rem),
    ...groups.filter((e) => s.favorites.includes(e.id) && inOnly(e.group.id)),
  ];

  const section = (key, title, entries, switchable = []) => {
    const saved = new Map((s.order[key] || []).map((id, i) => [id, i]));
    const rank = (e) => saved.get(e.id) ?? Number.MAX_SAFE_INTEGER;
    // A stable sort: what was never moved keeps its place among its peers.
    const sorted = entries.map((e, i) => [e, i]).sort((a, b) => rank(a[0]) - rank(b[0]) || a[1] - b[1]).map((p) => p[0]);
    return { key, title, entries: sorted, switchable };
  };
  const list = [
    section('favorites', str('favorites'), favorites),
    section('groups', str('s_groups'), page === PAGE_FAVORITES ? [] : groups),
    section('macros', str('s_macros'), macros.map(entry.mac)),
    section('remotes', str('s_remotes'), remotes.map(entry.rem)),
  ];
  for (const name of names) {
    const devices = rooms[name] || [];
    // The devices without a room are unrelated: switching them together makes no sense.
    const switchable = name === noRoom ? [] : devices.filter((d) => d.hasPower && !s.hidden.includes(d.applianceId));
    const entries = [
      ...devices.map(entry.dev),
      ...(roomMacros.get(name) || []).filter((m) => !narrowed || only.includes(m.id)).map(entry.mac),
      ...(roomRemotes.get(name) || []).filter((r) => !narrowed || only.includes(r.id)).map(entry.rem),
    ];
    list.push(section(`room:${name}`, name, entries, switchable.length > 1 ? switchable : []));
  }
  return list.filter((sec) => sec.entries.length);
}

/**
 * What [query] matches among the tiles of the [sections], best first: names that start with it,
 * then names with a word that does, then names that only contain it. Each tile comes once.
 */
export function search(query, sections) {
  const wanted = query.trim().toLowerCase();
  if (!wanted) return [];
  const rank = (name) => {
    const lower = name.toLowerCase();
    if (lower === wanted) return 0;
    if (lower.startsWith(wanted)) return 1;
    if (lower.split(' ').some((w) => w.startsWith(wanted))) return 2;
    if (lower.includes(wanted)) return 3;
    return null;
  };
  const seen = new Set();
  return sections.flatMap((sec) => sec.entries)
    .filter((e) => !seen.has(e.id) && seen.add(e.id))
    .map((e) => [e, rank(e.name)])
    .filter(([, r]) => r != null)
    .sort((a, b) => a[1] - b[1] || a[0].name.length - b[0].name.length)
    .map(([e]) => e);
}

/** What a page can be narrowed to on a monitor: its devices, or the groups for the page that lists them. */
export function pagePicks(s, page) {
  if (page === PAGE_GROUPS) return s.groups.map((g) => ({ key: g.id, label: g.name }));
  if (page === PAGE_MACROS) return s.macros.map((m) => ({ key: m.id, label: m.name }));
  if (page === PAGE_REMOTES) return shownRemotes(s).map((r) => ({ key: r.id, label: r.name }));
  const visible = placed(s).filter((d) => !s.hidden.includes(d.applianceId));
  const room = page.startsWith(PAGE_ROOM) ? page.slice(PAGE_ROOM.length) || null : null;
  let devices;
  if (page === PAGE_ALL) devices = visible;
  else if (page === PAGE_FAVORITES) devices = visible.filter((d) => s.favorites.includes(d.applianceId));
  else devices = visible.filter((d) => d.room === room);
  const picks = devices.map((d) => ({ key: d.applianceId, label: d.name, hint: d.room ?? str('room_none') }));
  if (page === PAGE_FAVORITES) picks.push(...s.groups.filter((g) => s.favorites.includes(`group:${g.id}`)).map((g) => ({ key: g.id, label: g.name })));
  if (room == null || !page.startsWith(PAGE_ROOM)) return picks;
  // What else was put in the room, to keep or to leave out as its devices.
  return [
    ...picks,
    ...s.macros.filter((m) => placeOf(s, macroKey(m.id)) === room).map((m) => ({ key: m.id, label: m.name })),
    ...shownRemotes(s).filter((r) => placeOf(s, remoteKey(r.id)) === room).map((r) => ({ key: r.id, label: r.name })),
  ];
}

export { number };
