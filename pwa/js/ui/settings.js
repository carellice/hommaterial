// Everything the user can manage about the app, in one place.
import { html, useState, useRef } from '../lib.js';
import {
  Icon, IconButton, Setting, Toggle, Chips, OrderRow, Dialog, ConfirmDialog, Field, PinDialog, useStore, useBack,
} from './common.js';
import * as store from '../store.js';
import { DEFAULT_MONITOR } from '../store.js';
import { str, plural, clock, degrees } from '../i18n.js';
import { proxy, probe } from '../net.js';
import {
  LAYOUT_GRID, LAYOUT_LIST, PAGE_ROOM, REST_CLOCK, REST_DATE, REST_POWER, REST_SENSORS, REST_TIMERS,
  macroKey, remoteKey, placed, placeOf, allRooms, macroStatus, availablePages, shownPages, pageLabel, pagePicks,
} from '../model.js';
import { RoomPicker, MembersDialog, PicksDialog } from './rooms.js';
import { MacroEditor } from './macros.js';
import { SwitchBotDialog } from './remotes.js';
import { HINTS, SignOutDialog } from './home.js';

const SOURCE_URL = 'https://github.com/carellice/hommaterial';

/** A section of the settings that opens and closes on a tap of its title. */
function Section({ title, text, open, onToggle, children }) {
  return html`
    <div class=${`card accordion ${open ? 'open' : ''}`}>
      <button class="accordion-head" aria-expanded=${open} onClick=${onToggle}>
        <span class="title-medium grow">${title}</span><${Icon} name="expand_more" />
      </button>
      ${open && html`<div class="accordion-body">
        ${text && html`<div class="note body-small faint">${text}</div>`}
        ${children}
      </div>`}
    </div>`;
}

export function SettingsScreen({ onBack }) {
  const s = useStore();
  useBack(onBack);
  const [open, setOpen] = useState([]);
  const [dialog, setDialog] = useState(null);
  const importInput = useRef(null);
  const close = () => setDialog(null);
  const section = (key, title, text, children) => html`
    <${Section} title=${str(title)} text=${text && str(text)} open=${open.includes(key)}
      onToggle=${() => setOpen(open.includes(key) ? open.filter((k) => k !== key) : [...open, key])}>${children}<//>`;

  const all = placed(s);
  const noRoom = str('room_none');
  const rooms = allRooms(s);
  // Remotes and custom devices, as what can be put in a room next to the devices.
  const extras = [
    ...s.macros.map((m) => ({ key: macroKey(m.id), label: m.name, hint: str('macro_edit') })),
    ...s.remotes.map((r) => ({ key: remoteKey(r.id), label: r.name, hint: str('remote_generic') })),
  ];
  const switchable = all.filter((d) => d.hasPower);
  const names = Object.fromEntries(s.devices.map((d) => [d.applianceId, d.name]));
  const now = Date.now();
  const timers = Object.entries(s.timers).filter(([id, at]) => at > now && names[id]);
  const alerts = Object.entries(s.alerts).filter(([id]) => names[id]);

  let shownDialog = null;
  if (dialog?.type === 'move') {
    const device = dialog.device;
    shownDialog = html`<${RoomPicker} name=${device.name} follow=${str('room_as_alexa', device.room ?? noRoom)}
      current=${device.applianceId in s.rooms ? s.rooms[device.applianceId] : null} rooms=${rooms}
      onPick=${(room) => {
        store.setRoom(device, room);
        close();
      }} onDismiss=${close} />`;
  } else if (dialog?.type === 'room') {
    const old = dialog.room || null;
    shownDialog = html`<${MembersDialog} title=${str(old ? 'room_edit_title' : 'room_new')} hint=${str('room_devices_hint')}
      name=${dialog.room} picks=${[...all.map((d) => ({ key: d.applianceId, label: d.name, hint: d.room ?? noRoom })), ...extras]}
      members=${[...all.filter((d) => old && d.room === old).map((d) => d.applianceId), ...extras.filter((e) => old && placeOf(s, e.key) === old).map((e) => e.key)]}
      onSave=${(name, members) => {
        store.saveRoom(old, name, members);
        close();
      }}
      onDelete=${s.roomList.includes(dialog.room) ? () => {
        store.deleteRoom(dialog.room);
        close();
      } : null}
      onDismiss=${close} />`;
  } else if (dialog?.type === 'group') {
    const group = dialog.group;
    shownDialog = html`<${MembersDialog} title=${str(group ? 'group_edit_title' : 'group_new')} hint=${str('group_devices_hint')}
      name=${group?.name ?? ''} picks=${switchable.map((d) => ({ key: d.applianceId, label: d.name, hint: d.room ?? noRoom }))}
      members=${group?.devices ?? []}
      onSave=${(name, members) => {
        store.saveGroup(group?.id ?? null, name, members);
        close();
      }}
      onDelete=${group ? () => {
        store.deleteGroup(group.id);
        close();
      } : null}
      onDismiss=${close} />`;
  } else if (dialog?.type === 'macro') {
    shownDialog = html`<${MacroEditor} macro=${dialog.macro} s=${s} onDismiss=${close} />`;
  } else if (dialog?.type === 'resetRooms') {
    shownDialog = html`<${ConfirmDialog} title=${str('rooms_reset_title')} text=${str('rooms_reset_text')}
      confirm=${str('rooms_reset_confirm')} onConfirm=${store.resetRooms} onDismiss=${close} />`;
  } else if (dialog?.type === 'clearHistory') {
    shownDialog = html`<${ConfirmDialog} title=${str('history_clear_title')} text=${str('history_clear_text')}
      confirm=${str('history_clear_confirm')} onConfirm=${store.clearHistory} onDismiss=${close} />`;
  } else if (dialog?.type === 'signOut') {
    shownDialog = html`<${SignOutDialog} onDismiss=${close} />`;
  } else if (dialog?.type === 'switchBot') {
    shownDialog = html`<${SwitchBotDialog} onDismiss=${close} onConnect=${(token, secret) => {
      store.connectSwitchBot(token, secret);
      close();
    }} />`;
  } else if (dialog?.type === 'unlinkSwitchBot') {
    shownDialog = html`<${ConfirmDialog} title=${str('switchbot_unlink_title')} text=${str('switchbot_unlink_text')}
      confirm=${str('switchbot_unlink')} onConfirm=${store.disconnectSwitchBot} onDismiss=${close} />`;
  } else if (dialog?.type === 'proxy') {
    shownDialog = html`<${ProxyDialog} onDismiss=${close} />`;
  }

  const notifications = store.notificationsEnabled();
  const denied = store.notificationsSupported() && Notification.permission === 'denied';

  return html`
    <div class="screen">
      <div class="top-bar">
        <${IconButton} icon="arrow_back" label=${str('back')} onClick=${onBack} />
        <div class="title"><div class="title-large">${str('settings')}</div></div>
      </div>
      <div class="scroll"><div class="narrow">
        ${section('appearance', 's_appearance', null, html`
          <${Chips} title=${str('theme')} choices=${[[0, str('theme_auto')], [1, str('theme_light')], [2, str('theme_dark')]]}
            selected=${s.theme} onSelect=${store.setTheme} />
          <${Chips} title=${str('layout')} choices=${[[LAYOUT_GRID, str('layout_grid')], [LAYOUT_LIST, str('layout_list')]]}
            selected=${s.layout} onSelect=${store.setLayout} />
          <${Chips} title=${str('language_choose')} choices=${[['auto', str('pwa_language_auto')], ['it', 'Italiano'], ['en', 'English']]}
            selected=${s.language} onSelect=${store.setLanguage} />`)}

        ${section('notifications', 's_notifications', 'pwa_notifications_text', html`
          <${Setting} title=${str(notifications ? 'notifications_on' : 'notifications_off')}
            text=${!store.notificationsSupported() ? str('pwa_notifications_unsupported') : denied ? str('pwa_notifications_denied') : notifications ? null : str('pwa_notifications_ask')}
            onClick=${!notifications && !denied && store.notificationsSupported() ? store.askNotifications : null} />`)}

        ${section('proxy', 'pwa_s_proxy', 'pwa_proxy_text', html`
          <${Setting} title=${str('pwa_proxy_address')} text=${proxy.url || str('pwa_proxy_none')} onClick=${() => setDialog({ type: 'proxy' })} />`)}

        ${section('switchbot', 's_switchbot', 'switchbot_text', s.switchBot
          ? html`
            <${Setting} title=${str('switchbot_linked')} text=${plural('remotes_found', s.remotes.length)} />
            ${s.remotes.map((remote) => {
              const hidden = s.hiddenRemotes.includes(remote.id);
              return html`<${Setting} key=${remote.id} title=${remote.name} text=${remote.type} trailing=${html`
                <${IconButton} icon=${hidden ? 'visibility_off' : 'visibility'} label=${str(hidden ? 'unhide' : 'hide')}
                  onClick=${() => store.setRemoteHidden(remote, !hidden)} />`} />`;
            })}
            <${Setting} title=${str('switchbot_unlink')} onClick=${() => setDialog({ type: 'unlinkSwitchBot' })} />`
          : html`<${Setting} title=${str('switchbot_connect')} text=${str('switchbot_connect_text')} onClick=${() => setDialog({ type: 'switchBot' })} />`)}

        ${section('updates', 's_updates', null, html`
          <${Toggle} title=${str('auto_update')} text=${str('pwa_auto_update_text')} checked=${s.autoUpdate} onChange=${store.setAutoUpdate} />
          <${Setting} title=${str('check_updates')} text=${str('installed_version', store.installedVersion)} onClick=${() => store.checkForUpdate(true)} />`)}

        ${s.devices.length > 0 && html`
          ${section('rooms', 's_rooms', 'rooms_text', html`
            ${rooms.map((room) => html`<${Setting} key=${room} title=${room}
              text=${plural('devices', all.filter((d) => d.room === room).length + extras.filter((e) => placeOf(s, e.key) === room).length)}
              onClick=${() => setDialog({ type: 'room', room })} />`)}
            <${Setting} title=${str('room_new')} onClick=${() => setDialog({ type: 'room', room: '' })} trailing=${html`<${Icon} name="add" cls="faint" />`} />
            ${(Object.keys(s.rooms).length > 0 || s.roomList.length > 0) && html`
              <${Setting} title=${str('rooms_reset')} onClick=${() => setDialog({ type: 'resetRooms' })} />`}`)}
          ${section('devices', 's_devices', 'devices_text', html`
            ${/* Listed in their rooms as shown in the app, but moved as the devices Alexa knows. */ ''}
            ${s.devices.map((device) => {
              const id = device.applianceId;
              const favorite = s.favorites.includes(id);
              const hidden = s.hidden.includes(id);
              const room = all.find((d) => d.applianceId === id)?.room;
              return html`<${Setting} key=${id} title=${device.name} text=${room ?? noRoom}
                onClick=${() => setDialog({ type: 'move', device })} trailing=${html`
                <${IconButton} icon="star" filled=${favorite} cls=${favorite ? '' : 'faint'} label=${str(favorite ? 'favorite_remove' : 'favorite_add')}
                  onClick=${() => store.setFavorite(id, !favorite)} />
                <${IconButton} icon=${hidden ? 'visibility_off' : 'visibility'} label=${str(hidden ? 'unhide' : 'hide')}
                  onClick=${() => store.setHidden(device, !hidden)} />`} />`;
            })}`)}`}

        ${switchable.length > 0 && html`
          ${section('groups', 's_groups', 'groups_text', html`
            ${s.groups.map((group) => html`<${Setting} key=${group.id} title=${group.name}
              text=${plural('devices', switchable.filter((d) => group.devices.includes(d.applianceId)).length)}
              onClick=${() => setDialog({ type: 'group', group })} />`)}
            <${Setting} title=${str('group_new')} onClick=${() => setDialog({ type: 'group', group: null })} trailing=${html`<${Icon} name="add" cls="faint" />`} />`)}
          ${section('macros', 's_macros_settings', 'pwa_macros_text', html`
            ${s.macros.map((macro) => html`<${Setting} key=${macro.id} title=${macro.name} text=${macroStatus(macro, s)}
              onClick=${() => setDialog({ type: 'macro', macro })} />`)}
            <${Setting} title=${str('macro_new')} onClick=${() => setDialog({ type: 'macro', macro: null })} trailing=${html`<${Icon} name="add" cls="faint" />`} />`)}`}

        ${s.devices.length > 0 && section('monitor', 's_monitor', 'pwa_monitor_text', html`<${MonitorSettings} s=${s} />`)}

        ${timers.length > 0 && section('timers', 's_timers', null, timers.map(([id, at]) => html`
          <${Setting} key=${id} title=${names[id]} text=${str('turns_off_at', clock(at))} trailing=${html`
            <${IconButton} icon="close" label=${str('cancel_timer')} onClick=${() => store.cancelTimer(id)} />`} />`))}

        ${alerts.length > 0 && section('alerts', 's_alerts', null, alerts.map(([id, alert]) => html`
          <${Setting} key=${id} title=${names[id]}
            text=${[alert.above != null && str('alert_above', degrees(alert.above)), alert.below != null && str('alert_below', degrees(alert.below))].filter(Boolean).join(' · ')}
            trailing=${html`<${IconButton} icon="close" label=${str('alert_remove')} onClick=${() => store.clearAlert(id)} />`} />`))}

        ${section('backup', 's_backup', 'pwa_backup_text', html`
          <${Setting} title=${str('backup_export')} onClick=${store.exportBackup} />
          <${Setting} title=${str('backup_import')} onClick=${() => importInput.current.click()} />
          <input type="file" accept="application/json,.json" ref=${importInput} style="display:none"
            onChange=${(e) => {
              const file = e.target.files[0];
              e.target.value = '';
              if (file) store.importBackup(file);
            }} />`)}

        ${section('data', 's_data', null, html`<${Setting} title=${str('history_clear')} onClick=${() => setDialog({ type: 'clearHistory' })} />`)}

        ${section('account', 's_account', null, html`
          <${Setting} title=${str('sign_out')} text=${str('account_marketplace', s.marketplace.domain)} onClick=${() => setDialog({ type: 'signOut' })} />`)}

        ${section('hints', 's_hints', 'hints_text', Object.values(HINTS).map((hint) => html`
          <${Setting} key=${hint.icon} lead=${html`<${Icon} name=${hint.icon} />`} title=${str(hint.text)} />`))}

        ${section('about', 's_about', null, html`
          <${Setting} title=${str('about_source')} text=${str('installed_version', store.installedVersion)}
            onClick=${() => window.open(SOURCE_URL, '_blank', 'noopener')} />`)}
      </div></div>
      ${s.askMonitorRestore && html`
        <${Dialog} title=${str('backup_monitor_title')} onDismiss=${() => store.finishImport(null)} actions=${html`
          <button class="text-btn" onClick=${() => store.finishImport(false)}>${str('backup_monitor_no')}</button>
          <button class="text-btn" onClick=${() => store.finishImport(true)}>${str('backup_monitor_yes')}</button>`}>
          <div class="body-medium faint">${str('backup_monitor_text')}</div>
        <//>`}
      ${shownDialog}
    </div>`;
}

/** The address of the proxy and the word it may want, checked before they are kept. */
export function ProxyDialog({ onDismiss, onSaved }) {
  const [url, setUrl] = useState(proxy.url);
  const [key, setKey] = useState(proxy.key);
  const [error, setError] = useState(null);
  const [checking, setChecking] = useState(false);
  const save = async () => {
    setChecking(true);
    const ok = await probe(url);
    setChecking(false);
    if (!ok) {
      setError(str('pwa_proxy_unreachable'));
      return;
    }
    proxy.set(url, key);
    onSaved?.();
    onDismiss();
  };
  return html`
    <${Dialog} title=${str('pwa_s_proxy')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${!url.trim() || checking} onClick=${save}>${str('save')}</button>`}>
      <div style="display:flex;flex-direction:column;gap:12px">
        <div class="body-small faint">${str('pwa_proxy_text')}</div>
        <${Field} label=${str('pwa_proxy_address')} value=${url} type="url" error=${error}
          onInput=${(v) => {
            setUrl(v);
            setError(null);
          }} onEnter=${save} />
        <${Field} label=${str('pwa_proxy_key')} value=${key} support=${str('pwa_proxy_key_hint')} onInput=${setKey} />
      </div>
    <//>`;
}

const REFRESH_CHOICES = [30, 60, 120, 300];
const REST_CHOICES = [1, 2, 5, 10];
const REST_LABELS = [
  [REST_CLOCK, 'rest_item_clock'], [REST_DATE, 'rest_item_date'], [REST_SENSORS, 'rest_item_sensors'],
  [REST_POWER, 'rest_item_power'], [REST_TIMERS, 'rest_item_timers'],
];
// In percent; 0 leaves the screen as it is.
const BRIGHTNESS_CHOICES = [[1, 'brightness_lowest'], [15, 'brightness_low'], [40, 'brightness_medium'], [0, 'brightness_same']];
const duration = (seconds) => (seconds < 60 ? str('monitor_seconds', seconds) : str('monitor_minutes', seconds / 60));
const sameMonitor = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/** Everything about the tablet used as the panel of the home: what it shows and how it behaves. */
function MonitorSettings({ s }) {
  const monitor = s.monitor;
  const set = (change) => store.setMonitor({ ...monitor, ...change });
  const available = availablePages(s);
  const [dialog, setDialog] = useState(null);
  const close = () => setDialog(null);
  let shown = null;
  if (dialog === 'preset') {
    shown = html`<${PresetDialog} rooms=${available.filter((p) => p.startsWith(PAGE_ROOM) && p !== PAGE_ROOM)}
      onPick=${(pages) => {
        store.setMonitor({ ...monitor, enabled: true, pages });
        close();
      }} onDismiss=${close} />`;
  } else if (dialog === 'pages') {
    shown = html`<${PicksDialog} title=${str('monitor_pages')} picks=${available.map((p) => ({ key: p, label: pageLabel(p) }))}
      initial=${monitor.pages ?? available} limit=${null} reset=${str('monitor_pages_all')}
      onSave=${(pages) => {
        set({ pages });
        close();
      }} onDismiss=${close} />`;
  } else if (dialog?.page) {
    const page = dialog.page;
    const picks = pagePicks(s, page);
    shown = html`<${PicksDialog} title=${pageLabel(page)} picks=${picks} initial=${monitor.only[page] ?? picks.map((p) => p.key)}
      limit=${null} reset=${str('monitor_show_all')} onSave=${(chosen) => {
        // Everything ticked is no choice at all: what is added later shows up by itself.
        const some = chosen && chosen.length < picks.length ? chosen : null;
        const only = { ...monitor.only };
        if (some) only[page] = some;
        else delete only[page];
        set({ only });
        close();
      }} onDismiss=${close} />`;
  } else if (dialog === 'pin') {
    shown = html`<${PinDialog} title=${str('pin_new_title')} hint=${str('pin_new_hint')} onDismiss=${close}
      onSubmit=${async (pin) => {
        await store.setPin(pin);
        close();
        return true;
      }} />`;
  } else if (dialog === 'sensors') {
    const sensors = placed(s).filter((d) => d.isSensor && !s.hidden.includes(d.applianceId));
    shown = html`<${PicksDialog} title=${str('monitor_rest_sensors')}
      picks=${sensors.map((d) => ({ key: d.applianceId, label: d.name, hint: d.room ?? str('room_none') }))}
      initial=${monitor.restSensors ?? sensors.map((d) => d.applianceId)} limit=${null} reset=${str('monitor_all_sensors')}
      onSave=${(restSensors) => {
        set({ restSensors });
        close();
      }} onDismiss=${close} />`;
  }

  const toggle = html`<${Toggle} title=${str('monitor_enable')} text=${str('monitor_enable_text')} checked=${monitor.enabled}
    onChange=${(on) => {
      // The first time it starts from a ready-made panel, to be adjusted afterwards.
      if (on && sameMonitor(monitor, DEFAULT_MONITOR)) setDialog('preset');
      else set({ enabled: on });
    }} />`;
  if (!monitor.enabled) return html`${toggle}${shown}`;

  const pages = shownPages(s, true);
  const sensors = placed(s).filter((d) => d.isSensor && !s.hidden.includes(d.applianceId));
  return html`
    ${toggle}
    <${Setting} title=${str('monitor_pages')} text=${monitor.pages == null ? str('monitor_pages_default') : pages.map(pageLabel).join(', ')}
      onClick=${() => setDialog('pages')} />
    <div class="note body-small faint" style="padding-top:8px">${str('monitor_pages_order')}</div>
    ${pages.map((page, index) => {
      const only = monitor.only[page];
      const total = pagePicks(s, page);
      return html`<${OrderRow} key=${page} name=${pageLabel(page)}
        text=${only ? str('monitor_page_some', total.filter((p) => only.includes(p.key)).length, total.length) : str('monitor_page_all')}
        first=${index === 0} last=${index === pages.length - 1} onClick=${() => setDialog({ page })}
        onMove=${(by) => {
          const moved = [...pages];
          moved.splice(index + by, 0, moved.splice(index, 1)[0]);
          set({ pages: moved });
        }} />`;
    })}
    <${Chips} title=${str('widget_size')} choices=${[[0, str('widget_compact')], [1, str('widget_normal')], [2, str('widget_large')]]}
      selected=${monitor.tileSize} onSelect=${(tileSize) => set({ tileSize })} />
    <${Toggle} title=${str('monitor_clock')} text=${str('monitor_clock_text')} checked=${monitor.clock} onChange=${(clock) => set({ clock })} />
    <${Toggle} title=${str('monitor_keep_on')} text=${str('pwa_keep_on_text')} checked=${monitor.keepOn} onChange=${(keepOn) => set({ keepOn })} />
    <${Toggle} title=${str('monitor_fullscreen')} text=${str('pwa_fullscreen_text')} checked=${monitor.fullscreen} onChange=${(fullscreen) => set({ fullscreen })} />
    <${Chips} title=${str('monitor_refresh')} choices=${REFRESH_CHOICES.map((v) => [v, duration(v)])} selected=${monitor.refreshSeconds}
      onSelect=${(refreshSeconds) => set({ refreshSeconds })} />
    <${Setting} title=${str(s.pin ? 'monitor_pin_on' : 'monitor_pin_set')} text=${str(s.pin ? 'monitor_pin_on_text' : 'monitor_pin_set_text')}
      onClick=${() => setDialog('pin')}
      trailing=${s.pin && html`<${IconButton} icon="close" label=${str('pin_remove')} onClick=${() => store.setPin(null)} />`} />
    <${Toggle} title=${str('monitor_rest')} text=${str('monitor_rest_text')} checked=${monitor.rest} onChange=${(rest) => set({ rest })} />
    ${monitor.rest && html`
      <${Chips} title=${str('monitor_rest_after')} choices=${REST_CHOICES.map((v) => [v, duration(v * 60)])} selected=${monitor.restMinutes}
        onSelect=${(restMinutes) => set({ restMinutes })} />
      <div class="setting-block">
        <div class="body-large">${str('monitor_rest_items')}</div>
        <div class="chips">${REST_LABELS.map(([item, label]) => {
          const on = monitor.restItems.includes(item);
          return html`<button class=${`chip ${on ? 'selected' : ''}`}
            onClick=${() => set({ restItems: on ? monitor.restItems.filter((i) => i !== item) : [...monitor.restItems, item] })}>
            ${on && html`<${Icon} name="check" />`}${str(label)}</button>`;
        })}</div>
      </div>
      ${monitor.restItems.includes(REST_SENSORS) && sensors.length > 1 && html`
        <${Setting} title=${str('monitor_rest_sensors')}
          text=${monitor.restSensors == null ? str('monitor_rest_sensors_all') : sensors.filter((d) => monitor.restSensors.includes(d.applianceId)).map((d) => d.name).join(', ')}
          onClick=${() => setDialog('sensors')} />`}
      <${Chips} title=${str('pwa_rest_brightness')} choices=${BRIGHTNESS_CHOICES.map(([v, label]) => [v, str(label)])}
        selected=${monitor.restBrightness} onSelect=${(restBrightness) => set({ restBrightness })} />
      <${Toggle} title=${str('monitor_night')} text=${str('monitor_night_text')} checked=${monitor.night} onChange=${(night) => set({ night })} />
      ${monitor.night && html`
        <${HourRow} label="monitor_night_from" hour=${monitor.nightFrom} onChange=${(nightFrom) => set({ nightFrom })} />
        <${HourRow} label="monitor_night_to" hour=${monitor.nightTo} onChange=${(nightTo) => set({ nightTo })} />`}`}
    ${shown}`;
}

/** An hour of the day, moved one hour at a time and around midnight. */
const HourRow = ({ label, hour, onChange }) => html`
  <${Setting} title=${str(label, `${String(hour).padStart(2, '0')}:00`)} trailing=${html`
    <${IconButton} icon="remove" label=${str('hour_earlier')} onClick=${() => onChange((hour + 23) % 24)} />
    <${IconButton} icon="add" label=${str('hour_later')} onClick=${() => onChange((hour + 1) % 24)} />`} />`;

/** The two ways a panel usually starts: the whole home, or the room the tablet hangs in. */
const PresetDialog = ({ rooms, onPick, onDismiss }) => html`
  <${Dialog} title=${str('monitor_preset_title')} onDismiss=${onDismiss} flush
    actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>`}>
    <${Setting} title=${str('monitor_preset_all')} text=${str('monitor_preset_all_text')} onClick=${() => onPick(null)} />
    ${rooms.map((room) => html`<${Setting} key=${room} title=${pageLabel(room)} text=${str('monitor_preset_room_text')} onClick=${() => onPick([room])} />`)}
    <div class="body-small" style="padding:8px 16px 0">${str('monitor_preset_note')}</div>
  <//>`;
