// The calendar (ui/Calendar.kt): the agenda of the coming days in its page, today and tomorrow at
// the top of the home, an editor for the events, and in the settings Google linked step by step.
import { html, useState, useEffect } from '../lib.js';
import { Icon, IconButton, Spinner, Setting, Toggle, Card, Field, FullDialog, ListDialog, ConfirmDialog, Switch } from './common.js';
import * as store from '../store.js';
import { str, clock, dateText } from '/shared/i18n.js';
import { PullToRefresh } from './home.js';

// How far the agenda reads at first, and how much more each time it is asked.
const DAYS_FIRST = 14;
const DAYS_MORE = 14;
// The events of today and tomorrow the home shows before saying how many more there are.
const HOME_EVENTS = 4;
const DAY = 86_400_000;

// ——— Days and moments ———

const pad = (n) => String(n).padStart(2, '0');
/** A day as the server writes it: "2026-11-04". */
const dateKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const timeKey = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
/** A moment with the offset of the home, as Google wants it. */
function moment(d) {
  const offset = -d.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  return `${dateKey(d)}T${timeKey(d)}:${pad(d.getSeconds())}${sign}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`;
}
/** A start or an end as the server sends it: a day for whole days, at the midnight of the home. */
const parse = (value, allDay) => {
  if (!allDay) return new Date(value);
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, m - 1, d);
};
const fromDate = (key) => {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d);
};

// ——— The model ———

/**
 * The calendars and the events of the coming days, kept while the app is open so that the home and
 * the agenda show them at once: read when either first shows, on a pull and after every change.
 */
const model = { calendars: null, events: null, loading: false, failed: false, days: DAYS_FIRST, read: null };
const listeners = new Set();
const set = (change) => {
  Object.assign(model, change);
  listeners.forEach((l) => l());
};

function useModel() {
  const [, tick] = useState(0);
  useEffect(() => {
    const listener = () => tick((n) => n + 1);
    listeners.add(listener);
    return () => listeners.delete(listener);
  }, []);
  return model;
}

/** The calendars shown: those chosen in this browser, or, until the user chooses, those Google shows. */
function visible(s) {
  const all = model.calendars ?? [];
  const chosen = s.calendarShown;
  if (!chosen) return all.filter((c) => c.shown);
  return all.filter((c) => chosen.includes(c.id));
}

const calendarOf = (id) => model.calendars?.find((c) => c.id === id);

/** Where a new event goes: the one chosen, else the main calendar, else the first that takes events. */
function defaultCalendar(s) {
  const writable = (model.calendars ?? []).filter((c) => c.writable);
  return writable.find((c) => c.id === s.calendarDefault) ?? writable.find((c) => c.primary) ?? writable[0] ?? null;
}

/** Reads the calendars and the events again, as the button of the top bar asks. */
export const reloadCalendar = (s) => reload(s);

async function reload(s) {
  if (model.loading || !s.calendar.linked) return;
  set({ loading: true });
  const found = await store.calendars();
  if (found) set({ calendars: found });
  const today = startOfDay(new Date());
  const read = found ? await store.calendarEvents(visible(s).map((c) => c.id), moment(today), moment(addDays(today, model.days))) : null;
  const events = Array.isArray(read) ? read.map((e) => ({
    ...e, from: parse(e.start, e.allDay), to: parse(e.end, e.allDay), key: `${e.calendar}|${e.id}|${e.start}`,
  })) : null;
  set({ loading: false, failed: !events, ...(events ? { events } : {}), read: JSON.stringify([s.calendarShown, s.calendar.linked]) });
}

/** Reads the calendar when it shows for the first time, and again when the calendars chosen change. */
function useCalendar(s) {
  const m = useModel();
  useEffect(() => {
    if (m.read !== JSON.stringify([s.calendarShown, s.calendar.linked])) reload(s);
  }, [s.calendarShown, s.calendar.linked]);
  return m;
}

// ——— How events read ———

/** The day an event is listed under: its first, or today for what began before and still goes on. */
const listedDay = (event, today) => (event.from < today ? today : startOfDay(event.from));

/** When an event happens, as a line: its hours, the whole day, or until the day it ends. */
function whenText(event) {
  const last = new Date(event.to.getTime() - 1);
  const oneDay = dateKey(last) === dateKey(event.from);
  if (event.allDay) return oneDay ? str('calendar_all_day') : str('calendar_until', dateText(last));
  if (!oneDay) return `${clock(event.from)} · ${str('calendar_until', `${dateText(event.to)} ${clock(event.to)}`)}`;
  return `${clock(event.from)} – ${clock(event.to)}`;
}

const capital = (text) => text.charAt(0).toUpperCase() + text.slice(1);

function dayTitle(day, today) {
  const date = dateText(day);
  if (day.getTime() === today.getTime()) return [str('calendar_today'), date];
  if (day.getTime() === addDays(today, 1).getTime()) return [str('calendar_tomorrow'), date];
  return [capital(date), null];
}

const dot = (calendar) => html`<span class="cal-dot" style=${`background:${calendar?.color ?? '#4285F4'}`}></span>`;

// ——— The page ———

/** The page of the agenda: the events of the coming days by day, a + to add one, a tap to change one. */
export function CalendarPage({ s, onSettings }) {
  const m = useCalendar(s);
  const [editing, setEditing] = useState(null);
  if (!s.calendar.linked) {
    return html`<${Message} icon="calendar_month" title=${str('calendar_server_title')} text=${str('calendar_server_text')}
      action=${html`<button class="tonal-btn" onClick=${onSettings}>${str('calendar_open_settings')}</button>`} />`;
  }
  let body;
  if (!m.events && m.failed) {
    body = html`<${Message} icon="event_busy" title=${str('calendar_failed')}
      action=${html`<button class="tonal-btn" onClick=${() => reload(s)}>${str('calendar_retry')}</button>`} />`;
  } else if (!m.events) {
    body = html`<div class="cal-center"><${Spinner} big /></div>`;
  } else if (!visible(s).length) {
    body = html`<${Message} icon="calendar_month" title=${str('calendar_none_chosen')}
      action=${html`<button class="tonal-btn" onClick=${onSettings}>${str('calendar_open_settings')}</button>`} />`;
  } else {
    body = html`<${Agenda} s=${s} events=${m.events} onOpen=${setEditing} />`;
  }
  return html`
    <div class="cal-page">
      <${PullToRefresh} refreshing=${m.loading && m.events != null} onRefresh=${() => reload(s)}>${body}<//>
      ${defaultCalendar(s) && html`
        <button class="fab extended" onClick=${() => setEditing('new')}><${Icon} name="add" /><span class="label-large">${str('calendar_new')}</span></button>`}
      ${editing && html`<${EventEditor} s=${s} event=${editing === 'new' ? null : editing} onDismiss=${() => setEditing(null)} />`}
    </div>`;
}

function Agenda({ s, events, onOpen }) {
  const today = startOfDay(new Date());
  const days = new Map();
  for (const event of events) {
    const day = listedDay(event, today).getTime();
    if (!days.has(day)) days.set(day, []);
    days.get(day).push(event);
  }
  const sorted = [...days.entries()].sort((a, b) => a[0] - b[0]);
  return html`
    <div class="cal-agenda">
      ${!sorted.length && html`<${Message} icon="calendar_month" title=${str('calendar_empty')} text=${str('calendar_empty_text')} />`}
      ${sorted.map(([day, list]) => {
        const [title, date] = dayTitle(new Date(day), today);
        return html`
          <div class="cal-day" key=${day}>
            <div class=${`cal-day-title ${day === today.getTime() ? 'today' : ''}`}>
              <span class="title-medium">${title}</span>${date && html`<span class="body-medium supporting">${date}</span>`}
            </div>
            ${list.map((event) => html`<${EventRow} key=${event.key} event=${event} onClick=${() => onOpen(event)} />`)}
          </div>`;
      })}
      <div class="cal-more">
        <button class="outlined-btn" disabled=${model.loading} onClick=${() => {
          set({ days: model.days + DAYS_MORE });
          reload(s);
        }}>${str('calendar_load_more')}</button>
      </div>
    </div>`;
}

/** An event in the agenda: the color of its calendar, when, what and where. */
function EventRow({ event, onClick }) {
  return html`
    <button class="cal-event" onClick=${onClick}>
      <span class="cal-bar" style=${`background:${calendarOf(event.calendar)?.color ?? '#4285F4'}`}></span>
      <span class="texts">
        <span class="title-small">${event.title || str('calendar_untitled')}</span>
        <span class="body-medium supporting">${whenText(event)}</span>
        ${event.location && html`<span class="body-small supporting cal-place"><${Icon} name="place" />${event.location}</span>`}
      </span>
    </button>`;
}

function Message({ icon, title, text, action }) {
  return html`
    <div class="cal-center">
      <span class="cal-badge"><${Icon} name=${icon} /></span>
      <div class="title-medium">${title}</div>
      ${text && html`<div class="body-medium supporting">${text}</div>`}
      ${action && html`<div style="margin-top:16px">${action}</div>`}
    </div>`;
}

// ——— At the top of the home ———

/**
 * The events of today and tomorrow at the top of the home, a few of them and how many more: a
 * tap opens the agenda. Nothing shows without a calendar to read.
 */
export function HomeAgenda({ s, onOpen }) {
  const m = useCalendar(s);
  if (!m.events) return null;
  const today = startOfDay(new Date());
  const until = addDays(today, 2);
  const now = new Date();
  const soon = m.events.filter((e) => e.from < until && e.to > now);
  return html`
    <button class="span cal-home" onClick=${onOpen}>
      <span class="cal-home-head"><${Icon} name="calendar_month" /><span class="title-small grow">${str('calendar_home_title')}</span><${Icon} name="chevron_right" /></span>
      ${!soon.length && html`<span class="body-medium supporting">${str('calendar_nothing')}</span>`}
      ${soon.slice(0, HOME_EVENTS).map((e) => html`
        <span class="cal-home-row" key=${e.key}>
          ${dot(calendarOf(e.calendar))}
          <span class=${`label-large supporting cal-home-time ${e.allDay ? 'wide' : ''}`}>${e.allDay ? str('calendar_all_day') : clock(e.from)}</span>
          <span class="body-large grow cal-home-title">${e.title || str('calendar_untitled')}</span>
          ${listedDay(e, today).getTime() !== today.getTime() && html`<span class="label-medium cal-tomorrow">${str('calendar_tomorrow')}</span>`}
        </span>`)}
      ${soon.length > HOME_EVENTS && html`<span class="label-large cal-home-more">${str('calendar_more', soon.length - HOME_EVENTS)}</span>`}
    </button>`;
}

// ——— The editor ———

/**
 * Creates an event, when [event] is null, or shows and changes one: what, when, where, in which
 * calendar. What cannot be changed from here is shown as it is.
 */
function EventEditor({ s, event, onDismiss }) {
  const writable = (model.calendars ?? []).filter((c) => c.writable);
  const canEdit = !event || calendarOf(event.calendar)?.writable === true;
  // A new event starts at the next full hour, for one hour.
  const first = event?.from ?? (() => {
    const d = new Date();
    d.setHours(d.getHours() + 1, 0, 0, 0);
    return d;
  })();
  const last = event ? (event.allDay ? new Date(event.to.getTime() - DAY / 2) : event.to) : new Date(first.getTime() + 3_600_000);
  const [title, setTitle] = useState(event?.title ?? '');
  const [location, setLocation] = useState(event?.location ?? '');
  const [notes, setNotes] = useState(event?.notes ?? '');
  const [allDay, setAllDay] = useState(event?.allDay ?? false);
  const [startDate, setStartDate] = useState(dateKey(first));
  const [startTime, setStartTime] = useState(timeKey(first));
  // A whole day ends at the midnight after it: shown, it is the day before.
  const [endDate, setEndDate] = useState(dateKey(last));
  const [endTime, setEndTime] = useState(timeKey(last));
  const [calendar, setCalendar] = useState(event?.calendar ?? defaultCalendar(s)?.id ?? '');
  const [choosing, setChoosing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [saving, setSaving] = useState(false);

  const start = allDay ? fromDate(startDate) : new Date(`${startDate}T${startTime}`);
  const end = allDay ? addDays(fromDate(endDate), 1) : new Date(`${endDate}T${endTime}`);
  const valid = title.trim() && calendar && !Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()) && end >= start;

  // The end keeps the length of the event when the start moves.
  const moveStart = (date, time) => {
    const before = allDay ? fromDate(startDate) : new Date(`${startDate}T${startTime}`);
    const after = allDay ? fromDate(date) : new Date(`${date}T${time}`);
    if (Number.isNaN(after.getTime())) return;
    const shifted = new Date(end.getTime() + (after - before) - (allDay ? DAY : 0));
    setStartDate(date);
    setStartTime(time);
    if (!Number.isNaN(shifted.getTime())) {
      setEndDate(dateKey(shifted));
      setEndTime(timeKey(shifted));
    }
  };

  const save = async () => {
    setSaving(true);
    const input = {
      title: title.trim(), location: location.trim(), notes: notes.trim(), allDay,
      start: allDay ? startDate : moment(start), end: allDay ? dateKey(end) : moment(end),
    };
    const done = await store.saveEvent(event, calendar, input);
    setSaving(false);
    if (done) {
      onDismiss();
      reload(s);
    }
  };

  const note = !event ? null : !canEdit ? str('calendar_readonly') : event.recurring ? str('calendar_recurring') : null;
  const chosen = calendarOf(calendar);
  return html`
    <${FullDialog} title=${str(event ? 'calendar_edit' : 'calendar_new')} onDismiss=${onDismiss} closeLabel=${str('cancel')}
      actions=${canEdit && html`
        ${event && html`<button class="text-btn" onClick=${() => setConfirmDelete(true)}>${str('delete')}</button>`}
        <button class="text-btn" disabled=${!valid || saving} onClick=${save}>${str('save')}</button>`}>
      ${note && html`<p class="body-medium supporting">${note}</p>`}
      <fieldset class="plain" disabled=${!canEdit}>
        <${Field} label=${str('calendar_title')} value=${title} onInput=${setTitle} autofocus=${!event} />
        <${Card}><${Setting} lead=${dot(chosen)} title=${chosen?.name ?? str('s_calendar')}
          onClick=${canEdit && writable.length > 1 ? () => setChoosing(true) : null} /><//>
        <${Card}>
          <${Setting} title=${str('calendar_all_day')} onClick=${canEdit ? () => setAllDay(!allDay) : null}
            trailing=${html`<${Switch} checked=${allDay} onChange=${setAllDay} label=${str('calendar_all_day')} />`} />
          <div class="cal-when">
            <span class="body-large">${str('calendar_start')}</span>
            <input type="date" value=${startDate} onInput=${(e) => e.target.value && moveStart(e.target.value, startTime)} />
            ${!allDay && html`<input type="time" value=${startTime} onInput=${(e) => e.target.value && moveStart(startDate, e.target.value)} />`}
          </div>
          <div class="cal-when">
            <span class="body-large">${str('calendar_end')}</span>
            <input type="date" value=${endDate} onInput=${(e) => e.target.value && setEndDate(e.target.value)} />
            ${!allDay && html`<input type="time" value=${endTime} onInput=${(e) => e.target.value && setEndTime(e.target.value)} />`}
          </div>
          ${end < start && html`<div class="body-small cal-error">${str('calendar_bad_times')}</div>`}
        <//>
        <${Field} label=${str('calendar_location')} value=${location} onInput=${setLocation} />
        <${Field} label=${str('calendar_notes')} value=${notes} onInput=${setNotes} multiline />
      </fieldset>
      ${choosing && html`<${ListDialog} title=${str('s_calendar')} items=${writable.map((c) => [c.name])}
        onDismiss=${() => setChoosing(false)} onPick=${(i) => {
          setCalendar(writable[i].id);
          setChoosing(false);
        }} />`}
      ${confirmDelete && html`<${ConfirmDialog} title=${str('calendar_delete_title', event.title || str('calendar_untitled'))}
        text=${str('calendar_delete_text')} confirm=${str('delete')} onDismiss=${() => setConfirmDelete(false)}
        onConfirm=${async () => {
          setConfirmDelete(false);
          if (await store.deleteEvent(event)) {
            set({ events: model.events.filter((e) => e.key !== event.key) });
            onDismiss();
            reload(s);
          }
        }} />`}
    <//>`;
}

// ——— The settings ———

const GOOGLE_PROJECT = 'https://console.cloud.google.com/projectcreate';
const GOOGLE_API = 'https://console.cloud.google.com/apis/library/calendar-json.googleapis.com';
const GOOGLE_CONSENT = 'https://console.cloud.google.com/auth/overview';
const GOOGLE_AUDIENCE = 'https://console.cloud.google.com/auth/audience';
const GOOGLE_CLIENTS = 'https://console.cloud.google.com/auth/clients';

/** The calendar in the settings: Google linked step by step, then which calendars to show and where new events go. */
export function CalendarSettings({ s }) {
  const [list, setList] = useState(null);
  useEffect(() => {
    if (s.calendar.linked) store.calendars().then((found) => found && setList(found));
    else setList(null);
  }, [s.calendar.linked]);
  const [choosing, setChoosing] = useState(false);
  if (!s.calendar.linked) return html`<${GoogleSetup} s=${s} />`;
  const chosen = s.calendarShown ?? (list ?? []).filter((c) => c.shown).map((c) => c.id);
  const toggle = (id) => store.setCalendarShown(chosen.includes(id) ? chosen.filter((it) => it !== id) : [...chosen, id]);
  const writable = (list ?? []).filter((c) => c.writable);
  const current = writable.find((c) => c.id === s.calendarDefault) ?? writable.find((c) => c.primary) ?? writable[0];
  return html`
    <${Toggle} title=${str('calendar_home_toggle')} text=${str('calendar_home_toggle_text')} checked=${s.calendarHome} onChange=${store.setCalendarHome} />
    <div class="title-small cal-settings-title">${str('calendar_shown')}</div>
    ${!list && html`<div style="padding:16px;text-align:center"><${Spinner} /></div>`}
    ${(list ?? []).map((c) => html`
      <${Setting} key=${c.id} lead=${dot(c)} title=${c.name} onClick=${() => toggle(c.id)}
        trailing=${html`<input type="checkbox" class="checkbox" checked=${chosen.includes(c.id)} onChange=${() => toggle(c.id)} aria-label=${c.name} />`} />`)}
    ${current && html`<${Setting} title=${str('calendar_default')} text=${current.name} onClick=${() => setChoosing(true)} />`}
    <${Setting} title=${str('calendar_unlink')} text=${str('calendar_linked', s.calendar.account)} onClick=${store.unlinkGoogle} />
    ${choosing && html`<${ListDialog} title=${str('calendar_default')} items=${writable.map((c) => [c.name])}
      onDismiss=${() => setChoosing(false)} onPick=${(i) => {
        store.setCalendarDefault(writable[i].id);
        setChoosing(false);
      }} />`}`;
}

/** Google Calendar on the home server: six steps, each with the page of Google Cloud it happens on. */
function GoogleSetup({ s }) {
  const [changingKeys, setChangingKeys] = useState(false);
  const [clientId, setClientId] = useState('');
  const [secret, setSecret] = useState('');
  const [address, setAddress] = useState('');
  const [busy, setBusy] = useState(false);
  const keys = !s.calendar.client || changingKeys;
  const step = (n, link, done) => html`
    <div class=${`cal-step ${done ? 'done' : ''}`}>
      <div class="texts"><div class="title-small">${str(`gcal_step${n}_title`)}</div><div class="body-medium supporting">${str(`gcal_step${n}_text`)}</div></div>
      <a class="text-btn" href=${link} target="_blank" rel="noopener">${str('gcal_open')}</a>
    </div>`;
  return html`
    <div class="cal-setup">
      <p class="body-medium supporting">${str('gcal_intro')}</p>
      ${step(1, GOOGLE_PROJECT)}
      ${step(2, GOOGLE_API)}
      ${step(3, GOOGLE_CONSENT)}
      ${step(4, GOOGLE_AUDIENCE)}
      ${step(5, GOOGLE_CLIENTS, !keys)}
      ${keys && html`
        <${Field} label=${str('gcal_client_id')} value=${clientId} onInput=${(v) => setClientId(v.trim())} />
        <${Field} label=${str('gcal_client_secret')} type="password" value=${secret} onInput=${(v) => setSecret(v.trim())} />
        <button class="tonal-btn" disabled=${!clientId || !secret || busy} onClick=${async () => {
          setBusy(true);
          if (await store.setGoogleClient(clientId, secret)) setChangingKeys(false);
          setBusy(false);
        }}>${str('gcal_save_keys')}</button>`}
      ${!keys && html`
        <div class="title-small">${str('gcal_step6_title')}</div>
        <div class="body-medium supporting">${str('gcal_step6_text')}</div>
        <button class="tonal-btn" onClick=${async () => {
          // Opened at once, before the address arrives: a window opened later is blocked as a pop-up.
          const opened = window.open('', '_blank');
          const url = await store.googleAuthUrl();
          if (url && opened) opened.location.href = url;
          else if (url) window.location.href = url;
          else opened?.close();
        }}><${Icon} name="open_in_new" /> ${str('gcal_sign_in')}</button>
        <${Field} label=${str('gcal_paste')} value=${address} onInput=${(v) => setAddress(v.trim())} />
        <button class="tonal-btn" disabled=${!address || busy} onClick=${async () => {
          setBusy(true);
          await store.linkGoogle(address);
          setBusy(false);
        }}>${str('gcal_link')}</button>
        <button class="text-btn" onClick=${() => setChangingKeys(true)}>${str('calendar_change_keys')}</button>`}
    </div>`;
}
