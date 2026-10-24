// The home: tiles of devices, groups, custom devices and remotes, in sections and pages.
import { html, useState, useEffect, useRef } from '../lib.js';
import {
  Icon, IconButton, Spinner, Switch, Details, Dialog, FullDialog, Menu, ConfirmDialog, PinDialog,
  Card, Chips, OrderRow, useStore, useNow, useWidth, usePress, useBack,
} from './common.js';
import * as store from '../store.js';
import { tileSpan } from '../backup.js';
import { str, plural, clock, dateText, degrees } from '../i18n.js';
import {
  PAGE_ALL, LAYOUT_LIST, ACTION_POWER, WHITE_CHOICES, COLOR_CHOICES,
  homeSections, shownPages, pageLabel, pageIcon, search, statusText, countdown, ageText, whenText, isLocked, placed, placeOf,
} from '../model.js';
import { MacroTile } from './macros.js';
import { RemoteTile } from './remotes.js';
import { HistoryCharts } from './chart.js';
import { SchedulesDialog, TimerChoices } from './schedules.js';
import { AutomationsDialog } from './automations.js';

// By size of the tiles: compact, normal, large.
const LIST_MIN_WIDTH = 320;
const ROW_HEIGHTS = [56, 64, 84];
const TILE_MIN_WIDTHS = [132, 148, 216];
const TILE_HEIGHTS = [92, 116, 156];
const WIDE = 600;
const SIDE_BAR = 840;

/**
 * What a tile tells about itself in its corner. Tiles look alike but do not answer a touch alike:
 * some switch, some run, some open, and only some do something more when held.
 */
export const HINTS = {
  TOGGLE: { icon: 'power_settings_new', text: 'hint_toggle', short: 'hint_toggle_short' },
  RUN: { icon: 'play_arrow', text: 'hint_run', short: 'hint_run_short' },
  OPEN: { icon: 'chevron_right', text: 'hint_open', short: 'hint_open_short' },
  HOLD: { icon: 'more_horiz', text: 'hint_hold', short: 'hint_hold_short' },
};
const SWITCH_HINTS = [HINTS.TOGGLE, HINTS.HOLD];

export function iconFor(device) {
  switch (device.category) {
    case 'LIGHT': return 'lightbulb';
    case 'SMARTPLUG': return 'power';
    case 'SWITCH': return 'toggle_on';
    case 'TV': return 'tv';
    case 'VACUUM_CLEANER': return 'cleaning_services';
    case 'TEMPERATURE_SENSOR': case 'THERMOSTAT': return 'thermostat';
    default: return 'devices_other';
  }
}

// ——— The search opens what it cannot switch: the tile with the id asked for opens its panel ———

export function requestOpen(id) {
  window.dispatchEvent(new CustomEvent('hommaterial-open', { detail: id }));
}

export function useOpenRequest(id, open) {
  useEffect(() => {
    const listener = (e) => e.detail === id && open();
    window.addEventListener('hommaterial-open', listener);
    return () => window.removeEventListener('hommaterial-open', listener);
  }, [id]);
}

/** How the tiles look and behave, which depends on the screen and on the monitor mode. */
function homeLook(s, wide) {
  const size = s.monitor.enabled ? s.monitor.tileSize : 1;
  const list = s.layout === LAYOUT_LIST;
  return { wide, size, list, locked: isLocked(s) };
}

function lookStyle(look) {
  const { size, list } = look;
  return [
    `--tile-min:${list ? LIST_MIN_WIDTH : TILE_MIN_WIDTHS[size]}px`,
    `--tile-height:${(list ? ROW_HEIGHTS : TILE_HEIGHTS)[size]}px`,
    `--gap:${list ? 8 : 12}px`,
    `--tile-pad:${[12, 14, 18][size]}px`,
    `--tile-icon:${(list ? [22, 24, 30] : [22, 26, 36])[size]}px`,
    `--hint-size:${[14, 16, 20][size]}px`,
    `--name-lines:${size === 0 ? 1 : 2}`,
  ].join(';');
}

export function HomeScreen({ onSettings }) {
  const s = useStore();
  const width = useWidth();
  const wide = width >= WIDE;
  const pages = shownPages(s, wide);
  const [page, setPage] = useState(null);
  const current = pages.includes(page) ? page : pages[0];
  const showClock = s.monitor.enabled && s.monitor.clock;
  const [arranging, setArranging] = useState(false);
  // The search of the top bar: null while it is closed.
  const [query, setQuery] = useState(null);
  useBack(() => setQuery(null), query != null);
  // Ctrl+K, or Cmd+K on a Mac, opens the search from the keyboard, as in many apps on a computer.
  useEffect(() => {
    const onKey = (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'k') return;
      // Not under something open over the home: the search would end up behind it.
      if (document.querySelector('.dialog, .sheet, .side-panel, .full-dialog, .menu, .rest')) return;
      e.preventDefault();
      setQuery((typed) => typed ?? '');
      document.querySelector('input.search')?.select();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  const found = query != null ? search(query, homeSections(PAGE_ALL, s)) : [];
  const look = homeLook(s, wide);

  /** What a touch on the tile of [entry] does: the search runs it for the one it found first. */
  function activate(entry) {
    let keepOpen = true;
    if (entry.kind === 'dev') {
      const power = entry.device.hasPower ? s.states[entry.device.applianceId]?.power : null;
      if (power != null) store.setPower(entry.device, !power);
      else requestOpen(entry.id);
      keepOpen = power != null;
    } else if (entry.kind === 'grp') store.toggleGroup(entry.group);
    else if (entry.kind === 'mac') store.runMacro(entry.macro);
    else {
      requestOpen(entry.id);
      keepOpen = false;
    }
    // What switched or ran is done with; a panel opens over the results, which must stay.
    if (keepOpen) setQuery(null);
  }

  return html`
    <div class="screen">
      <div class="top-bar">
        ${query == null
          ? html`<${Title} updatedAt=${s.updatedAt} />`
          : html`<${SearchField} query=${query} onChange=${setQuery} onEnter=${() => found[0] && activate(found[0])} />`}
        ${query != null
          ? html`<${IconButton} icon="close" label=${str('search_close')} onClick=${() => setQuery(null)} />`
          : html`
            ${showClock && pages.length === 1 && html`<${ClockText} cls="title-large" style="padding:0 12px" />`}
            <${IconButton} icon="search" label=${str('pwa_search_shortcut')} onClick=${() => setQuery('')} />
            <${VoiceButton} />
            <${OverflowMenu} s=${s} onSettings=${onSettings} onArrange=${() => setArranging(true)} />`}
      </div>
      <div class="body-row">
        ${pages.length > 1 && query == null && html`
          <${SideBar} pages=${pages} current=${current} expanded=${width >= SIDE_BAR} clock=${showClock} onSelect=${setPage} />`}
        <${PullToRefresh} refreshing=${s.refreshing} onRefresh=${store.refresh} key=${current}>
          <div class=${look.list ? 'list-layout' : ''} style=${lookStyle(look)}>
            ${query != null
              ? html`<${SearchResults} query=${query} found=${found} s=${s} look=${look} />`
              : html`<${PageGrid} page=${current} s=${s} look=${look} />`}
          </div>
        <//>
      </div>
      ${arranging && html`<${ArrangeDialog} s=${s} onDismiss=${() => setArranging(false)} />`}
      ${s.voice && html`<${VoiceDialog} ask=${s.voice} />`}
    </div>`;
}

function ClockText({ cls, style }) {
  const now = useNow();
  return html`<span class=${cls} style=${style}>${clock(now)}</span>`;
}

function Title({ updatedAt }) {
  // Recomputed every half minute so that the age keeps growing while the screen stays open.
  const now = useNow(30_000);
  return html`
    <div class="title">
      <div class="title-large">${str('app_name')}</div>
      ${updatedAt && html`<div class="body-small faint">${ageText(updatedAt, now)}</div>`}
    </div>`;
}

/** The search of the top bar: typing finds, the Enter key acts on what was found first. */
function SearchField({ query, onChange, onEnter }) {
  const ref = useRef(null);
  useEffect(() => ref.current?.focus(), []);
  return html`<input ref=${ref} class="search" type="text" enterkeyhint="go" autocomplete="off" placeholder=${str('search_hint')}
    value=${query} onInput=${(e) => onChange(e.target.value)}
    onKeyDown=${(e) => e.key === 'Enter' && (e.preventDefault(), onEnter())} />`;
}

/** What the Enter key will do with [entry], as a verb. */
function enterText(entry, s) {
  if (entry.kind === 'dev') {
    const power = s.states[entry.device.applianceId]?.power;
    if (!entry.device.hasPower || power == null) return str('search_open');
    return power ? str('turn_off') : str('turn_on');
  }
  if (entry.kind === 'grp') return str('search_switch');
  if (entry.kind === 'mac') return str('search_run');
  return str('search_open');
}

function useGridColumns(look, active = true) {
  const grid = useRef(null);
  const [columns, setColumns] = useState(1);
  useEffect(() => {
    const measure = () => {
      const node = grid.current;
      if (node) setColumns(getComputedStyle(node).gridTemplateColumns.split(' ').length);
    };
    const observer = new ResizeObserver(measure);
    if (grid.current) observer.observe(grid.current);
    measure();
    return () => observer.disconnect();
  }, [look.list, look.size, active]);
  return [grid, columns];
}

/** What the search found, the first one marked: it is the one the Enter key acts on. */
function SearchResults({ query, found, s, look }) {
  const [grid, columns] = useGridColumns(look, found.length > 0);
  if (!query.trim()) return null;
  if (!found.length) return html`<div class="empty">${str('search_nothing', query)}</div>`;
  return html`
    <div class="grid" ref=${grid}>
      <div class="span section-header"><span class="title-small">${str('search_enter', enterText(found[0], s), found[0].name)}</span></div>
      ${found.map((entry, i) => html`
        <div key=${`found|${entry.id}`} class=${i === 0 ? 'marked' : ''} style=${`grid-column:span ${look.list ? 1 : tileSpan(s.tileSpans, entry.id, columns)};min-width:0`}><${Tile} entry=${entry} s=${s} look=${look} /></div>`)}
    </div>`;
}

/** The pages to move between, with their names where the screen has room for them. */
function SideBar({ pages, current, expanded, clock: showClock, onSelect }) {
  const now = useNow();
  if (expanded) {
    return html`
      <nav class="side-bar expanded">
        ${showClock && html`<div style="padding:0 16px 12px">
          <div class="display-small">${clock(now)}</div><div class="body-medium faint">${dateText(now)}</div></div>`}
        ${pages.map((page) => html`
          <button class=${`side-item ${page === current ? 'selected' : ''}`} onClick=${() => onSelect(page)}>
            <${Icon} name=${pageIcon(page)} /><span class="ellipsis">${pageLabel(page)}</span>
          </button>`)}
      </nav>`;
  }
  return html`
    <nav class="side-bar rail">
      ${showClock && html`<div class="title-medium" style="padding-bottom:12px">${clock(now)}</div>`}
      ${pages.map((page) => html`
        <button class=${`rail-item ${page === current ? 'selected' : ''}`} onClick=${() => onSelect(page)}>
          <span class="pill"><${Icon} name=${pageIcon(page)} /></span>
          <span class="label-medium">${pageLabel(page)}</span>
        </button>`)}
    </nav>`;
}

/** Pulling the list down from its top fetches everything again, as on the phone. */
function PullToRefresh({ refreshing, onRefresh, children }) {
  const ref = useRef(null);
  const [pull, setPull] = useState(0);
  const start = useRef(null);
  const onTouchStart = (e) => {
    start.current = ref.current.scrollTop <= 0 ? e.touches[0].clientY : null;
  };
  const onTouchMove = (e) => {
    if (start.current == null) return;
    setPull(Math.max(0, Math.min(120, e.touches[0].clientY - start.current)));
  };
  const onTouchEnd = () => {
    if (pull > 70) onRefresh();
    start.current = null;
    setPull(0);
  };
  const shown = refreshing || pull > 10;
  return html`
    <div class="scroll" ref=${ref} onTouchStart=${onTouchStart} onTouchMove=${onTouchMove} onTouchEnd=${onTouchEnd}>
      ${shown && html`<div class="pull"><div class="ball" style=${`transform:translateY(${refreshing ? 0 : pull / 2 - 20}px) rotate(${pull * 3}deg)`}>
        ${refreshing ? html`<${Spinner} />` : html`<${Icon} name="refresh" />`}</div></div>`}
      ${children}
    </div>`;
}

/** The tiles of a page: the whole home in sections, or only the favorites, the groups or a room. */
function PageGrid({ page, s, look }) {
  const [grid, columns] = useGridColumns(look);
  const sections = homeSections(page, s);
  const shown = sections.flatMap((sec) => sec.entries).filter((e) => e.kind === 'dev').map((e) => e.device);
  const now = Date.now();
  // Running timers come first, the one closest to switching off at the top.
  const timers = Object.entries(s.timers).sort((a, b) => a[1] - b[1])
    .map(([id, at]) => [shown.find((d) => d.applianceId === id), at]).filter(([d]) => d);
  // With them, what was set to happen once: the timers of remotes and custom devices among it.
  const all = page === PAGE_ALL;
  const due = all ? s.schedules.filter((it) => !it.days.length && it.enabled) : [];
  const repeating = all ? s.schedules.filter((it) => it.days.length && it.enabled) : [];
  const automations = all ? s.automations.filter((a) => a.enabled) : [];
  const running = timers.length || due.length || repeating.length || automations.length;
  if (!sections.length && !running && !s.refreshing) return html`<div class="empty">${str('no_devices')}</div>`;
  return html`
    <div class="grid" ref=${grid}>
      ${(timers.length > 0 || due.length > 0) && html`
        <div class="span section-header"><span class="title-small">${str('s_timers')}</span></div>
        ${timers.map(([device, at]) => html`
          <div class="span" key=${`timer:${device.applianceId}`}>
            <${TimerRow} icon=${iconFor(device)} name=${device.name} text=${str('turns_off_at', clock(at))} at=${at}
              onStop=${() => store.cancelTimer(device.applianceId)} /></div>`)}
        ${due.map((it) => html`
          <div class="span" key=${`schedule:${it.id}`}>
            <${TimerRow} icon="schedule" name=${it.label} text=${str('schedule_due', clock(it.at))} at=${it.at}
              onStop=${() => store.deleteSchedule(it.id)} /></div>`)}`}
      ${repeating.length > 0 && html`
        <div class="span section-header"><span class="title-small">${str('s_schedules')}</span></div>
        ${repeating.map((it) => html`
          <div class="span" key=${`repeating:${it.id}`}>
            <${TimerRow} icon="event_repeat" name=${it.label} text=${whenText(it)} at=${it.at} stop="pause_circle"
              stopLabel=${str('pause')} onStop=${() => store.setScheduleEnabled(it, false)} /></div>`)}`}
      ${automations.length > 0 && html`
        <div class="span section-header"><span class="title-small">${str('s_automations')}</span></div>
        ${automations.map((it) => html`
          <div class="span" key=${`automation:${it.id}`}>
            <${TimerRow} icon="bolt" name=${it.title} text=${it.label} stop="pause_circle"
              stopLabel=${str('pause')} onStop=${() => store.setAutomationEnabled(it, false)} /></div>`)}`}
      ${sections.map((section) => html`
        <div class="span section-header" key=${section.key}>
          <span class="title-small">${section.title}</span>
          ${section.switchable.length > 0 && html`<${RoomPower} name=${section.title} devices=${section.switchable} />`}
        </div>
        ${section.entries.map((entry) => html`<div class="tile-slot" key=${`${section.key}|${entry.id}`} style=${`grid-column:span ${look.list ? 1 : tileSpan(s.tileSpans, entry.id, columns)};min-width:0`}>
          <${Tile} entry=${entry} s=${s} look=${section.key === 'favorites' ? { ...look, room: entryRoom(s, entry) } : look} /></div>`)}`)}
      ${sections.length > 0 && html`<div class="span"><${Legend} /></div>`}
    </div>`;
}

/** A menu rather than a toggle: a whole room should not switch on a stray tap. */
function RoomPower({ name, devices }) {
  const [anchor, setAnchor] = useState(null);
  return html`
    <button class="icon-btn small" aria-label=${str('room_power', name)} title=${str('room_power', name)}
      onClick=${(e) => setAnchor(e.currentTarget.getBoundingClientRect())}>
      <${Icon} name="power_settings_new" size=${20} />
    </button>
    ${anchor && html`<${Menu} anchor=${anchor} onDismiss=${() => setAnchor(null)} items=${[
      [str('all_on'), () => store.setRoomPower(devices, true)],
      [str('all_off'), () => store.setRoomPower(devices, false)],
    ]} />`}`;
}

/** What the symbols in the corners mean, in a few words: it closes the list, small, for who wonders. */
function Legend() {
  return html`<div class="legend label-small">
    ${Object.values(HINTS).map((hint) => html`<span><${Icon} name=${hint.icon} />${str(hint.short)}</span>`)}
  </div>`;
}

/** Something due at a time, such as a device with a switch-off timer, with the time left counted down. */
function TimerRow({ icon, name, text, at, stop = 'close', stopLabel = str('cancel_timer'), onStop }) {
  const now = useNow();
  return html`
    <div class="timer-row">
      <${Icon} name=${icon} size=${26} />
      <div class="grow">
        <div class="title-small ellipsis" style="font-weight:600">${name}</div>
        <div class="body-small" style="opacity:.75">${text}</div>
      </div>
      ${at != null && html`<span class="count">${countdown(at - now)}</span>`}
      <${IconButton} icon=${stop} label=${stopLabel} onClick=${onStop} />
    </div>`;
}

export function Tile({ entry, s, look }) {
  switch (entry.kind) {
    case 'dev': return html`<${DeviceTile} device=${entry.device} s=${s} look=${look} />`;
    case 'mac': return html`<${MacroTile} macro=${entry.macro} s=${s} look=${look} />`;
    case 'rem': return html`<${RemoteTile} remote=${entry.remote} s=${s} look=${look} />`;
    default: return html`<${GroupTile} group=${entry.group} devices=${entry.devices} s=${s} look=${look} />`;
  }
}

/** What every tile shows, sized as the screen it is on asks. */
export function TileBody({ icon, busy, name, status, hints, size }) {
  const corner = html`<span class="corner">${busy
    ? html`<${Spinner} />`
    : hints.map((hint) => html`<${Icon} name=${hint.icon} title=${str(hint.text)} />`)}</span>`;
  const nameClass = size === 2 ? 'title-large' : 'title-small';
  const statusClass = size === 2 ? 'body-large' : 'body-small';
  return html`
    <span class="top"><${Icon} name=${icon} cls="main-icon" />${corner}</span>
    <span class=${`name ${nameClass}`}>${name}</span>
    <span class=${`status ${statusClass}`}>${status}</span>`;
}

/** The same body laid out as a row of a list. */
export function TileRow({ icon, busy, name, status, hints, size }) {
  return html`
    <${Icon} name=${icon} cls="main-icon" />
    <span class="texts">
      <span class=${`name ${size === 2 ? 'title-medium' : 'title-small'}`}>${name}</span>
      <span class=${`status ${size === 2 ? 'body-medium' : 'body-small'}`}>${status}</span>
    </span>
    <span class="corner">${busy ? html`<${Spinner} />` : hints.map((hint) => html`<${Icon} name=${hint.icon} title=${str(hint.text)} />`)}</span>`;
}

export function TileFace({ look, status, ...props }) {
  // Out of their rooms, the favorites say which room they are in.
  const told = look.room ? `${look.room} · ${status}` : status;
  return look.list
    ? html`<${TileRow} size=${look.size} status=${told} ...${props} />`
    : html`<${TileBody} size=${look.size} status=${told} ...${props} />`;
}

/** The room the user sees a tile in, null for a group or for what is in none. */
function entryRoom(s, entry) {
  if (entry.kind === 'dev') return placed(s).find((d) => d.applianceId === entry.id)?.room || null;
  if (entry.kind === 'mac' || entry.kind === 'rem') return placeOf(s, entry.id);
  return null;
}

function DeviceTile({ device, s, look }) {
  const id = device.applianceId;
  const st = s.states[id];
  const on = st?.power === true;
  const powerKnown = st?.power != null;
  const [details, setDetails] = useState(false);
  useOpenRequest(id, () => setDetails(true));
  const timer = s.timers[id] > Date.now() ? s.timers[id] : null;
  const press = usePress(() => {
    // A sensor has nothing to switch, and without a reported state a toggle would be a guess: a touch opens it.
    if (device.hasPower && powerKnown) store.setPower(device, !on);
    else setDetails(true);
  }, () => setDetails(true));
  const status = statusText(device, st);
  return html`
    <button class=${`tile ${on ? 'on' : ''} ${s.hidden.includes(id) ? 'dim' : ''}`} ...${press}>
      <${TileFace} look=${look} icon=${iconFor(device)} busy=${s.busy.includes(id)} name=${device.name}
        status=${timer ? str('status_until', status, clock(timer)) : status}
        hints=${device.hasPower && powerKnown ? SWITCH_HINTS : [HINTS.OPEN]} />
    </button>
    ${details && html`<${DeviceSheet} device=${device} s=${s} look=${look} onDismiss=${() => setDetails(false)} />`}`;
}

// A timer that switches on is a scheduled action, since the timers of the devices only switch off.
const switchOn = (device) => ({ type: ACTION_POWER, target: device.applianceId, value: 'on', custom: false });

const switchingOn = (device, s) => s.schedules.filter((it) => !it.days.length && it.enabled
  && it.action.type === ACTION_POWER && it.action.target === device.applianceId && it.action.value === 'on');

/** Everything beyond the tap-to-toggle: explicit on/off, timer, brightness, color, favorites, hiding. */
function DeviceSheet({ device, s, look, onDismiss }) {
  const id = device.applianceId;
  const st = s.states[id];
  const timer = s.timers[id] > Date.now() ? s.timers[id] : null;
  const favorite = s.favorites.includes(id);
  const hidden = s.hidden.includes(id);
  const hold = usePress(null, () => store.copyDiagnostics(device));
  const choices = [...(device.hasColorTemperature ? WHITE_CHOICES : []), ...(device.hasColor ? COLOR_CHOICES : [])];
  return html`
    <${Details} onDismiss=${onDismiss}>
      <div class="sheet-head">
        <${Icon} name=${iconFor(device)} />
        <div class="grow">
          ${/* Hidden on purpose: holding the name copies the technical data of the device. */ ''}
          <div class="title-large" ...${hold}>${device.name}</div>
          <div class="body-medium faint">${statusText(device, st)}</div>
        </div>
      </div>
      ${device.hasPower && html`
        <div class="two">
          <button class="tonal-btn" onClick=${() => store.setPower(device, true)}>${str('turn_on')}</button>
          <button class="tonal-btn" onClick=${() => store.setPower(device, false)}>${str('turn_off')}</button>
        </div>
        <span class="label label-large">${str('timer')}</span>
        ${timer
          ? html`<div class="row"><span class="grow">${str('turns_off_at', clock(timer))}</span>
              <button class="text-btn" onClick=${() => store.cancelTimer(id)}>${str('cancel_timer')}</button></div>`
          : html`<${TimerChoices} canTurnOn onChoose=${(minutes, on) => {
              store.askNotifications();
              if (on) store.addTimer(switchOn(device), str('voice_on', device.name), minutes);
              else store.setTimer(device, minutes);
            }} />`}
        ${/* Switching on is a scheduled action: the one waiting, and what cancels it. */ ''}
        ${switchingOn(device, s).map((waiting) => html`
          <div class="row" key=${waiting.id}><span class="grow">${str('schedule_once', waiting.label, clock(waiting.at))}</span>
            <button class="text-btn" onClick=${() => store.deleteSchedule(waiting.id)}>${str('cancel_timer')}</button></div>`)}`}
      ${device.isSensor && html`
        <${HistoryCharts} readings=${store.readings(id)} />
        <${AlertSettings} alert=${s.alerts[id] ?? { above: null, below: null }} temperature=${st?.temperature}
          onChange=${(alert) => store.setAlert(id, alert)} />`}
      ${device.hasBrightness && html`
        <span class="label label-large">${str('brightness')}</span>
        <${BrightnessSlider} brightness=${st?.brightness ?? 100} onChange=${(v) => store.setBrightness(device, v)} />`}
      ${choices.length > 0 && html`
        <span class="label label-large" style="margin-bottom:8px">${str('color')}</span>
        <div class="swatches">
          ${choices.map((choice) => html`
            <button class=${`swatch ${st?.colorName === choice.alexaName ? 'selected' : ''}`} style=${`background:${choice.rgb}`}
              aria-label=${str(choice.label)} title=${str(choice.label)} onClick=${() => store.setColor(device, choice)}></button>`)}
        </div>`}
      ${/* Behind the PIN of the monitor the panel only controls: how the home is arranged stays put. */ ''}
      ${!look.locked && html`
        <div style="display:flex;flex-direction:column;align-items:flex-start;margin-top:12px">
          <button class="text-btn" onClick=${() => store.setFavorite(id, !favorite)}>${str(favorite ? 'favorite_remove' : 'favorite_add')}</button>
          <button class="text-btn" onClick=${() => {
            store.setHidden(device, !hidden);
            onDismiss();
          }}>${str(hidden ? 'unhide' : 'hide')}</button>
        </div>`}
    <//>`;
}

function BrightnessSlider({ brightness, onChange }) {
  // Local while dragging; the command goes out once, when the finger lifts.
  const [value, setValue] = useState(brightness);
  useEffect(() => setValue(brightness), [brightness]);
  return html`<input type="range" min="1" max="100" value=${value} aria-label=${str('brightness')}
    onInput=${(e) => setValue(Number(e.target.value))} onChange=${(e) => onChange(Number(e.target.value))} />`;
}

/** The temperatures above and below which a sensor notifies. */
function AlertSettings({ alert, temperature, onChange }) {
  const current = typeof temperature === 'number' && !Number.isNaN(temperature) ? Math.round(temperature) : 22;
  const change = (next) => {
    if ((next.above != null && alert.above == null) || (next.below != null && alert.below == null)) store.askNotifications();
    onChange(next);
  };
  const row = (label, value, fallback, key) => html`
    <div class="row" style="min-height:48px">
      <${Switch} checked=${value != null} label=${str(label, degrees(value ?? fallback))}
        onChange=${(on) => change({ ...alert, [key]: on ? fallback : null })} />
      <span class="grow" style="padding-left:12px">${str(label, degrees(value ?? fallback))}</span>
      ${value != null && html`
        <${IconButton} icon="remove" label=${str('alert_lower')} onClick=${() => change({ ...alert, [key]: value - 0.5 })} />
        <${IconButton} icon="add" label=${str('alert_raise')} onClick=${() => change({ ...alert, [key]: value + 0.5 })} />`}
    </div>`;
  const any = alert.above != null || alert.below != null;
  const enabled = store.notificationsEnabled();
  return html`
    <span class="label label-large">${str('alerts_title')}</span>
    ${row('alert_above', alert.above, current + 2, 'above')}
    ${row('alert_below', alert.below, current - 2, 'below')}
    ${any && html`<div class=${`body-small ${enabled ? 'faint' : 'error-text'}`}>${str(enabled ? 'pwa_alerts_note' : 'pwa_alerts_disabled')}</div>`}`;
}

function groupStatus(lit, size) {
  if (lit === 0) return str('off');
  if (lit === size) return str('on');
  return str('group_some_on', lit, size);
}

/** A group as one tile: lit while any of its devices is on, and a tap switches them all. Holding it opens them. */
function GroupTile({ group, devices, s, look }) {
  const lit = devices.filter((d) => s.states[d.applianceId]?.power === true).length;
  const [details, setDetails] = useState(false);
  const press = usePress(() => store.toggleGroup(group), () => setDetails(true));
  return html`
    <button class=${`tile ${lit > 0 ? 'on' : ''}`} ...${press}>
      <${TileFace} look=${look} icon="layers" busy=${devices.some((d) => s.busy.includes(d.applianceId))}
        name=${group.name} status=${groupStatus(lit, devices.length)} hints=${SWITCH_HINTS} />
    </button>
    ${details && html`<${GroupSheet} group=${group} devices=${devices} s=${s} onDismiss=${() => setDetails(false)} />`}`;
}

/** A group opened up: everything on or off, a switch-off timer for all, and each device by itself. */
function GroupSheet({ group, devices, s, onDismiss }) {
  const now = Date.now();
  const timers = devices.map((d) => s.timers[d.applianceId]).filter((at) => at > now);
  const waiting = devices.flatMap((d) => switchingOn(d, s));
  const lit = devices.filter((d) => s.states[d.applianceId]?.power === true).length;
  return html`
    <${Details} onDismiss=${onDismiss}>
      <div class="sheet-head">
        <${Icon} name="layers" />
        <div class="grow"><div class="title-large">${group.name}</div>
          <div class="body-medium faint">${groupStatus(lit, devices.length)}</div></div>
      </div>
      ${!isLocked(s) && html`<button class="text-btn" onClick=${() => store.setFavorite(`group:${group.id}`, !s.favorites.includes(`group:${group.id}`))}>${str(s.favorites.includes(`group:${group.id}`) ? 'favorite_remove' : 'favorite_add')}</button>`}
      <div class="two">
        <button class="tonal-btn" onClick=${() => store.setRoomPower(devices, true)}>${str('all_on')}</button>
        <button class="tonal-btn" onClick=${() => store.setRoomPower(devices, false)}>${str('all_off')}</button>
      </div>
      <span class="label label-large">${str('timer')}</span>
      ${timers.length > 0 && html`
        <div class="row">
          ${/* The devices may carry timers of their own: the group says when the last one goes off. */ ''}
          <span class="grow">${timers.length === devices.length
            ? str('turns_off_at', clock(Math.max(...timers)))
            : str('group_timers_some', timers.length, devices.length)}</span>
          <button class="text-btn" onClick=${() => devices.forEach((d) => store.cancelTimer(d.applianceId))}>${str('cancel_timer')}</button>
        </div>`}
      ${waiting.length > 0 && html`
        <div class="row">
          <span class="grow">${str('schedule_once', str('voice_on', group.name), clock(Math.max(...waiting.map((it) => it.at))))}</span>
          <button class="text-btn" onClick=${() => waiting.forEach((it) => store.deleteSchedule(it.id))}>${str('cancel_timer')}</button>
        </div>`}
      ${/* A timer can switch the group on too, one scheduled action for each device. */ ''}
      <${TimerChoices} canTurnOn onChoose=${(minutes, on) => {
        store.askNotifications();
        devices.forEach((d) => (on ? store.addTimer(switchOn(d), str('voice_on', d.name), minutes) : store.setTimer(d, minutes)));
      }} />
      <span class="label label-large" style="margin-bottom:4px">${str('s_devices')}</span>
      ${[...devices].sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase())).map((device) => {
        const id = device.applianceId;
        const timer = s.timers[id] > now ? s.timers[id] : null;
        const status = statusText(device, s.states[id]);
        return html`
          <div class="row" style="padding:4px 0" key=${id}>
            <${Icon} name=${iconFor(device)} />
            <div class="grow" style="padding-left:16px">
              <div class="ellipsis">${device.name}</div>
              <div class="body-small faint">${timer ? str('status_until', status, clock(timer)) : status}</div>
            </div>
            ${s.busy.includes(id) && html`<${Spinner} />`}
            <span style="padding-left:12px"><${Switch} checked=${s.states[id]?.power === true} label=${device.name}
              onChange=${(on) => store.setPower(device, on)} /></span>
          </div>`;
      })}
    <//>`;
}

/** The tiles of the home as lists, one per section, to put in order with the arrows. */
function ArrangeDialog({ s, onDismiss }) {
  const sections = homeSections(PAGE_ALL, s);
  return html`
    <${FullDialog} title=${str('arrange')} onDismiss=${onDismiss}
      actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('arrange_done')}</button>`}>
      <div class="body-small faint">${str('arrange_text')}</div>
      ${sections.map((section) => {
        const ids = section.entries.map((e) => e.id);
        return html`
          <${Card} key=${section.key}>
            <div class="card-title title-medium">${section.title}</div>
            <div style="padding-bottom:8px">
              ${section.entries.map((entry, index) => html`
                <div key=${entry.id}><${OrderRow} name=${entry.name} first=${index === 0} last=${index === ids.length - 1}
                  onMove=${(by) => {
                    const moved = [...ids];
                    moved.splice(index + by, 0, moved.splice(index, 1)[0]);
                    store.setOrder({ ...s.order, [section.key]: moved });
                  }} />
                <${Chips} title=${str('tile_width')} choices=${[[1, str('tile_one_column')], [2, str('tile_two_columns')]]}
                  selected=${tileSpan(s.tileSpans, entry.id)} onSelect=${(value) => store.setTileSpan(entry.id, value)} /></div>`)}
            </div>
          <//>`;
      })}
    <//>`;
}

/** Asks the browser's speech recognizer for a sentence and passes on what it heard. */
function VoiceButton() {
  const [listening, setListening] = useState(false);
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  const listen = () => {
    if (!Recognition) {
      store.speechUnavailable();
      return;
    }
    const recognition = new Recognition();
    recognition.lang = str('voice_language');
    recognition.maxAlternatives = 5;
    recognition.interimResults = false;
    let heard = false;
    recognition.onresult = (e) => {
      heard = true;
      store.onSpeech(Array.from(e.results[0], (alternative) => alternative.transcript));
    };
    recognition.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') store.speechUnavailable();
    };
    recognition.onend = () => {
      setListening(false);
      if (!heard) store.onSpeech([]);
    };
    try {
      recognition.start();
      setListening(true);
    } catch {
      store.speechUnavailable();
    }
  };
  return html`<button class="icon-btn" aria-label=${str('voice_button')} title=${listening ? str('voice_prompt') : str('voice_button')}
    onClick=${listen} style=${listening ? 'color:var(--primary);background:var(--primary-container)' : ''}>
    <${Icon} name="mic" filled=${listening} /></button>`;
}

function VoiceDialog({ ask }) {
  return html`
    <${Dialog} title=${str('voice_ask_title')} onDismiss=${store.dismissVoice}
      actions=${html`<button class="text-btn" onClick=${store.dismissVoice}>${str('cancel')}</button>`}>
      <div style="padding-bottom:8px">${str('voice_heard', ask.heard)}</div>
      ${ask.options.map((option) => {
        const label = option.room == null ? store.voiceLabel(option)
          : str('voice_room_option', store.voiceLabel(option), plural('devices', option.devices.length));
        return html`<div><button class="text-btn" style="white-space:normal;text-align:left" onClick=${() => store.runVoice(option)}>${label}</button></div>`;
      })}
    <//>`;
}

function OverflowMenu({ s, onSettings, onArrange }) {
  const [anchor, setAnchor] = useState(null);
  const [dialog, setDialog] = useState(null);
  // What waits for the PIN of the monitor before it happens.
  const [behindPin, setBehindPin] = useState(null);
  const guarded = (action) => (isLocked(s) ? setBehindPin(() => action) : action());
  const items = [
    [str('refresh'), store.refresh],
    (s.hidden.length > 0 || s.hiddenRemotes.length > 0) && [str(s.showHidden ? 'hide_hidden' : 'show_hidden'), store.toggleShowHidden],
    [str('check_updates'), () => store.checkForUpdate(true)],
    [str('arrange'), () => guarded(onArrange)],
    [str('s_automations'), () => guarded(() => setDialog('automations'))],
    [str('s_schedules'), () => guarded(() => setDialog('schedules'))],
    [str('settings'), () => guarded(onSettings)],
    [str('sign_out'), () => guarded(() => setDialog('signOut'))],
  ];
  const close = () => setDialog(null);
  return html`
    <${IconButton} icon="more_vert" label=${str('menu')} onClick=${(e) => setAnchor(e.currentTarget.getBoundingClientRect())} />
    ${anchor && html`<${Menu} anchor=${anchor} items=${items} onDismiss=${() => setAnchor(null)} />`}
    ${dialog === 'automations' && html`<${AutomationsDialog} s=${s} onDismiss=${close} />`}
    ${dialog === 'schedules' && html`<${SchedulesDialog} s=${s} onDismiss=${close} />`}
    ${dialog === 'signOut' && html`<${SignOutDialog} onDismiss=${close} />`}
    ${behindPin && html`<${PinDialog} title=${str('pin_ask_title')} onDismiss=${() => setBehindPin(null)}
      onSubmit=${async (pin) => {
        const ok = await store.checkPin(pin);
        if (ok) {
          const action = behindPin;
          setBehindPin(null);
          setTimeout(action, 0);
        }
        return ok;
      }} />`}`;
}

export const SignOutDialog = ({ onDismiss }) => html`
  <${ConfirmDialog} title=${str('sign_out_title')} text=${str('sign_out_text')} confirm=${str('sign_out')}
    onConfirm=${store.logout} onDismiss=${onDismiss} />`;

export { TimerChoices };
