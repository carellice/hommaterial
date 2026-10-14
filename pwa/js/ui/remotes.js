// The infrared remotes of SwitchBot: their tiles and their keys.
import { html, useState } from '../lib.js';
import { Icon, IconButton, Spinner, Details, Dialog, ListDialog, Field, usePress } from './common.js';
import * as store from '../store.js';
import { str } from '../i18n.js';
import { remoteKind } from '../switchbot.js';
import { ACTION_REMOTE, remoteKey, placeOf, allRooms, describe } from '../model.js';
import { TileFace, HINTS, useOpenRequest } from './home.js';
import { TimerChips } from './schedules.js';
import { RoomPicker } from './rooms.js';

const POWER_BUTTONS = [['turn_on', 'turnOn'], ['turn_off', 'turnOff']];
const AC_MIN = 16;
const AC_MAX = 30;
// The numbers SwitchBot gives the modes and the fan speeds of an air conditioner.
const AC_MODES = [[1, 'ac_auto'], [2, 'ac_cool'], [5, 'ac_heat'], [3, 'ac_dry'], [4, 'ac_fan']];
const AC_FANS = [[1, 'ac_auto'], [2, 'ac_fan_low'], [3, 'ac_fan_medium'], [4, 'ac_fan_high']];
const DEFAULT_AC = { temperature: 24, mode: 2, fan: 1 };

function iconFor(remote) {
  switch (remoteKind(remote)) {
    case 'TV': return 'tv';
    case 'AIR_CONDITIONER': return 'ac_unit';
    default: return 'settings_remote';
  }
}

function kindLabel(remote) {
  switch (remoteKind(remote)) {
    case 'TV': return str('remote_tv');
    case 'AIR_CONDITIONER': return str('remote_ac');
    default: return str('remote_generic');
  }
}

/** A remote as a tile. It has no state to show or to switch: a tap opens its buttons. */
export function RemoteTile({ remote, s, look }) {
  const [open, setOpen] = useState(false);
  // The search opens it too, as a touch would.
  useOpenRequest(remoteKey(remote.id), () => setOpen(true));
  const press = usePress(() => setOpen(true), null);
  return html`
    <button class=${`tile ${s.hiddenRemotes.includes(remote.id) ? 'dim' : ''}`} ...${press}>
      <${TileFace} look=${look} icon=${iconFor(remote)} busy=${s.busy.includes(remote.id)} name=${remote.name}
        status=${kindLabel(remote)} hints=${[HINTS.OPEN]} />
    </button>
    ${open && html`<${RemoteSheet} remote=${remote} s=${s} look=${look} onDismiss=${() => setOpen(false)} />`}`;
}

/** The buttons of a remote: those of its kind, then the ones the user added by name. */
function RemoteSheet({ remote, s, look, onDismiss }) {
  const buttons = s.remoteButtons[remote.id] || [];
  const kind = remoteKind(remote);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState(false);
  const [moving, setMoving] = useState(false);
  // The minutes of a timer, while the key it presses is being chosen.
  const [timing, setTiming] = useState(null);
  const key = remoteKey(remote.id);
  const favorite = s.favorites.includes(key);
  const hidden = s.hiddenRemotes.includes(remote.id);
  // Laid out as on a remote: rows of three keys, the one that adds a key closing the last row.
  return html`
    <${Details} onDismiss=${onDismiss}>
      <div class="sheet-head">
        <${Icon} name=${iconFor(remote)} />
        <div class="grow"><div class="title-large">${remote.name}</div>
          <div class="body-medium faint">${kindLabel(remote)}</div></div>
        ${s.busy.includes(remote.id) && html`<${Spinner} />`}
      </div>
      ${kind === 'AIR_CONDITIONER' && html`<${AcControls} remote=${remote} setting=${s.acSettings[remote.id] ?? DEFAULT_AC} />`}
      ${kind !== 'AIR_CONDITIONER' && kind !== 'CUSTOM' && html`
        <div class="two">${POWER_BUTTONS.map(([label, command]) => html`
          <button class="tonal-btn" onClick=${() => store.sendRemote(remote, command)}>${str(label)}</button>`)}</div>
        ${kind === 'TV' && html`<${TvControls} remote=${remote} />`}`}
      ${/* The timer: switching off for the remotes that can, a key to choose for those learnt by hand. */ ''}
      ${(kind !== 'CUSTOM' || buttons.length > 0) && html`
        <div style="margin-top:20px">
          ${/* The remotes that switch can also be switched on by a timer of the user's own. */ ''}
          <${TimerChips} title=${str(kind === 'CUSTOM' ? 'timer_key_in' : 'turn_off_in')} target=${remote.id} s=${s}
            onTurnOn=${kind === 'CUSTOM' ? null : (minutes) => {
              store.askNotifications();
              const action = { type: ACTION_REMOTE, target: remote.id, value: 'turnOn', custom: false };
              store.addTimer(action, describe(action, s), minutes);
            }}
            onChoose=${(minutes) => {
              store.askNotifications();
              if (kind === 'CUSTOM') setTiming(minutes);
              else {
                const action = { type: ACTION_REMOTE, target: remote.id, value: 'turnOff', custom: false };
                store.addTimer(action, describe(action, s), minutes);
              }
            }} />
        </div>`}
      ${timing != null && html`<${ListDialog} title=${str('timer_key_in')} items=${buttons.map((b) => [b])}
        onDismiss=${() => setTiming(null)} onPick=${(i) => {
          const action = { type: ACTION_REMOTE, target: remote.id, value: buttons[i], custom: true };
          store.addTimer(action, describe(action, s), timing);
          setTiming(null);
        }} />`}
      <div class="row" style="margin-top:20px">
        <span class="label-large grow">${str('remote_buttons')}</span>
        ${buttons.length > 0 && html`<button class="text-btn" onClick=${() => setEditing(!editing)}>
          ${str(editing ? 'remote_buttons_done' : 'remote_buttons_edit')}</button>`}
      </div>
      <div class="keys">
        ${buttons.map((name) => html`<${RemoteKey} key=${name} name=${name} removing=${editing} onClick=${() => {
          if (editing) store.setRemoteButtons(remote, buttons.filter((b) => b !== name));
          else store.sendRemote(remote, name, 'default', true);
        }} />`)}
        <button class="key add" aria-label=${str('remote_button_add')} title=${str('remote_button_add')}
          onClick=${() => setAdding(true)}><${Icon} name="add" /></button>
      </div>
      <div class="body-small faint" style="margin-top:8px">${str('remote_buttons_hint')}</div>
      ${/* Behind the PIN of the monitor the panel only controls: what is listed stays put. */ ''}
      ${!look.locked && html`
        <div style="display:flex;flex-direction:column;align-items:flex-start;margin-top:4px">
          <button class="text-btn" onClick=${() => store.setFavorite(key, !favorite)}>${str(favorite ? 'favorite_remove' : 'favorite_add')}</button>
          <button class="text-btn" onClick=${() => setMoving(true)}>
            ${placeOf(s, key) ? str('room_in', placeOf(s, key)) : str('room_move')}</button>
          <button class="text-btn" onClick=${() => {
            store.setRemoteHidden(remote, !hidden);
            onDismiss();
          }}>${str(hidden ? 'unhide' : 'hide')}</button>
        </div>`}
      ${moving && html`<${RoomPicker} name=${remote.name} follow=${null} current=${placeOf(s, key) ?? ''} rooms=${allRooms(s)}
        onPick=${(room) => {
          store.setPlace(key, room);
          setMoving(false);
        }} onDismiss=${() => setMoving(false)} />`}
      ${adding && html`<${ButtonDialog} onDismiss=${() => setAdding(false)} onAdd=${(names) => {
        store.setRemoteButtons(remote, [...new Set([...buttons, ...names])]);
        setAdding(false);
      }} />`}
    <//>`;
}

const KEY_COLORS = [
  ['#FF3B30', ['rosso', 'red']],
  ['#34C759', ['verde', 'green']],
  ['#2962FF', ['blu', 'blue']],
  ['#FFD60A', ['giallo', 'yellow']],
  ['#FF9500', ['arancione', 'arancio', 'orange']],
  ['#AF52DE', ['viola', 'purple']],
  ['#FF6FB5', ['rosa', 'pink']],
  ['#32D2FF', ['azzurro', 'celeste', 'ciano', 'cyan']],
  ['#FFFFFF', ['bianco', 'white']],
];

/** Reads the usual names of the keys, in Italian and in English; null when the name says nothing known. */
function glyphFor(name) {
  const text = name.toLowerCase();
  const words = new Set(text.split(/[^\p{L}\p{N}]+/u).filter(Boolean));
  const has = (...any) => any.some((w) => words.has(w));
  const up = text.includes('+') || has('su', 'up', 'più', 'piu');
  const down = text.includes('-') || has('giù', 'giu', 'down', 'meno');
  const volume = [...words].some((w) => w.startsWith('vol'));
  const color = KEY_COLORS.find(([, names]) => names.some((n) => words.has(n)));
  if (color) return { tint: color[0] };
  let icon = null;
  if (has('mute', 'muto', 'muta', 'silenzioso')) icon = 'volume_off';
  else if (volume && up) icon = 'volume_up';
  else if (volume && down) icon = 'volume_down';
  else if (has('power', 'on', 'off', 'accendi', 'spegni', 'acceso', 'spento')) icon = 'power_settings_new';
  else if (has('play', 'riproduci')) icon = 'play_arrow';
  else if (has('pause', 'pausa')) icon = 'pause';
  else if (has('stop')) icon = 'stop';
  else if (has('ok', 'enter', 'invio', 'conferma')) icon = 'radio_button_checked';
  else if (has('home', 'casa')) icon = 'home';
  else if (has('back', 'indietro', 'return', 'esci', 'exit')) icon = 'arrow_back';
  else if (has('menu', 'menù')) icon = 'menu';
  else if (has('left', 'sinistra')) icon = 'chevron_left';
  else if (has('right', 'destra')) icon = 'chevron_right';
  else if (has('timer', 'sleep')) icon = 'timer';
  else if (has('luce', 'luci', 'light', 'lampada')) icon = 'lightbulb';
  // Last, because plus and minus also come with the volume and the like.
  else if (up) icon = 'keyboard_arrow_up';
  else if (down) icon = 'keyboard_arrow_down';
  return icon ? { icon } : null;
}

/** A key of a remote: what it is for is drawn when its name tells, and the name is always written. */
function RemoteKey({ name, removing, onClick }) {
  const glyph = glyphFor(name);
  // Alone on the key, a short name such as a number is written large.
  const alone = !glyph && !removing;
  return html`
    <button class=${`key ${removing ? 'removing' : ''}`} onClick=${onClick}
      aria-label=${removing ? `${str('remote_button_remove')}: ${name}` : name}>
      ${removing && html`<${Icon} name="close" size=${22} />`}
      ${!removing && glyph?.icon && html`<${Icon} name=${glyph.icon} />`}
      ${!removing && glyph?.tint && html`<span class="tint" style=${`background:${glyph.tint}`}></span>`}
      <span class=${`key-name ${alone && name.length <= 3 ? 'big' : 'label-medium'}`}>${name}</span>
    </button>`;
}

function TvControls({ remote }) {
  const [channel, setChannel] = useState('');
  const rocker = (label, onPress) => html`
    <div class="rocker">
      <button aria-label=${str('remote_up', str(label))} onClick=${() => onPress(true)}><${Icon} name="add" /></button>
      <span class="label-large">${str(label)}</span>
      <button aria-label=${str('remote_down', str(label))} onClick=${() => onPress(false)}><${Icon} name="remove" /></button>
    </div>`;
  return html`
    <div class="row" style="gap:10px;margin-top:20px">
      ${rocker('remote_volume', (up) => store.sendRemote(remote, up ? 'volumeAdd' : 'volumeSub'))}
      ${rocker('remote_channel', (up) => store.sendRemote(remote, up ? 'channelAdd' : 'channelSub'))}
    </div>
    <div class="row gap" style="margin-top:12px;align-items:flex-end">
      <div class="grow"><${Field} label=${str('remote_channel_number')} inputMode="numeric" value=${channel}
        onInput=${(v) => setChannel(v.replace(/\D/g, '').slice(0, 4))}
        onEnter=${() => channel && store.sendRemote(remote, 'SetChannel', channel)} /></div>
      <button class="tonal-btn" style="margin-bottom:4px" disabled=${!channel}
        onClick=${() => store.sendRemote(remote, 'SetChannel', channel)}>${str('remote_channel_go')}</button>
    </div>`;
}

/**
 * Temperature, mode and fan are chosen first and sent together by the power buttons: an air
 * conditioner takes everything in one signal, and beeps at each one.
 */
function AcControls({ remote, setting }) {
  const set = (change) => store.setAc(remote, { ...setting, ...change });
  const choices = (title, list, selected, key) => html`
    <span class="label label-large" style="margin-top:12px">${str(title)}</span>
    <div class="chips">${list.map(([value, label]) => html`
      <button class=${`chip ${value === selected ? 'selected' : ''}`} onClick=${() => set({ [key]: value })}>
        ${value === selected && html`<${Icon} name="check" />`}${str(label)}</button>`)}</div>`;
  return html`
    <div class="row" style="margin-top:20px">
      <span class="label-large grow">${str('temperature')}</span>
      <${IconButton} cls="tonal" icon="remove" label=${str('alert_lower')} disabled=${setting.temperature <= AC_MIN}
        onClick=${() => set({ temperature: setting.temperature - 1 })} />
      <span class="headline-small" style="padding:0 16px">${setting.temperature}°</span>
      <${IconButton} cls="tonal" icon="add" label=${str('alert_raise')} disabled=${setting.temperature >= AC_MAX}
        onClick=${() => set({ temperature: setting.temperature + 1 })} />
    </div>
    ${choices('ac_mode', AC_MODES, Math.max(1, setting.mode), 'mode')}
    ${choices('ac_fan_speed', AC_FANS, setting.fan, 'fan')}
    <div class="two" style="margin-top:16px">
      <button class="tonal-btn" onClick=${() => store.sendAc(remote, setting, true)}>${str('ac_send')}</button>
      <button class="tonal-btn" onClick=${() => store.sendAc(remote, setting, false)}>${str('turn_off')}</button>
    </div>`;
}

function ButtonDialog({ onAdd, onDismiss }) {
  const [typed, setTyped] = useState('');
  // Several names at once, one per line or with commas between them.
  const names = typed.split(/[,;\n]/).map((n) => n.trim()).filter(Boolean);
  return html`
    <${Dialog} title=${str('remote_button_add')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${!names.length} onClick=${() => onAdd(names)}>${str('save')}</button>`}>
      <${Field} label=${str('remote_button_name')} support=${str('remote_buttons_many')} multiline autofocus
        value=${typed} onInput=${setTyped} />
    <//>`;
}

/** Asks for the two keys of the SwitchBot account, saying where the SwitchBot app shows them. */
export function SwitchBotDialog({ onConnect, onDismiss }) {
  const [token, setToken] = useState('');
  const [secret, setSecret] = useState('');
  return html`
    <${Dialog} title=${str('switchbot_connect')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${!token.trim() || !secret.trim()} onClick=${() => onConnect(token, secret)}>
        ${str('switchbot_link')}</button>`}>
      <div class="body-small" style="margin-bottom:12px">${str('switchbot_how')}</div>
      <div style="display:flex;flex-direction:column;gap:8px">
        <${Field} label=${str('switchbot_token')} value=${token} onInput=${setToken} />
        <${Field} label=${str('switchbot_secret')} value=${secret} onInput=${setSecret} />
      </div>
    <//>`;
}
