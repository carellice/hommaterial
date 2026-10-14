// Actions carried out at a time, and the timers of what is not a device of Alexa.
import { html, useState } from '../lib.js';
import { Icon, IconButton, Switch, Dialog, FullDialog, Card, Setting, Field } from './common.js';
import * as store from '../store.js';
import { str, clock } from '../i18n.js';
import {
  ACTION_MACRO, ACTION_POWER, ACTION_REMOTE, TIMER_CHOICES, WEEK, dayName, describe, nextTime, whenText,
} from '../model.js';
import { ActionPicker } from './macros.js';

const IN_MINUTES = [5, 10, 15, 30, 60, 120];

function duration(minutes) {
  if (minutes === 60) return str('timer_60');
  if (minutes === 120) return str('timer_120');
  return str('monitor_minutes', minutes);
}

/**
 * The delays of a timer: the usual ones and one of the user's own. [canTurnOn] lets them switch on
 * instead of off; [onChoose] receives the minutes and whether to switch on.
 */
export function TimerChoices({ canTurnOn = false, onChoose }) {
  const [custom, setCustom] = useState(false);
  // What every delay below does when it runs out.
  const [on, setOn] = useState(false);
  return html`
    ${canTurnOn && html`
      <div class="chips">
        ${[[false, 'turn_off'], [true, 'turn_on']].map(([value, label]) => html`
          <button class=${`chip ${on === value ? 'selected' : ''}`} onClick=${() => setOn(value)}>
            ${on === value && html`<${Icon} name="check" />`}${str(label)}</button>`)}
      </div>`}
    <div class="chips">
      ${TIMER_CHOICES.map(([label, minutes]) => html`
        <button class="chip" onClick=${() => onChoose(minutes, on)}>${str(label)}</button>`)}
      <button class="chip" onClick=${() => setCustom(true)}>${str('timer_custom')}</button>
    </div>
    ${custom && html`<${CustomTimerDialog} onDismiss=${() => setCustom(false)}
      onChoose=${(minutes) => {
        setCustom(false);
        onChoose(minutes, on);
      }} />`}`;
}

/** Asks for a delay in hours and minutes. */
function CustomTimerDialog({ onChoose, onDismiss }) {
  const [hours, setHours] = useState('');
  const [minutes, setMinutes] = useState('');
  const total = (Number(hours) || 0) * 60 + (Number(minutes) || 0);
  const start = () => total > 0 && onChoose(total);
  const digits = (v) => v.replace(/\D/g, '').slice(0, 2);
  return html`
    <${Dialog} title=${str('timer_custom_title')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${total <= 0} onClick=${start}>${str('timer_start')}</button>`}>
      <div class="two" style="margin-top:0">
        <${Field} label=${str('timer_hours')} inputMode="numeric" autofocus value=${hours} onEnter=${start}
          onInput=${(v) => setHours(digits(v))} />
        <${Field} label=${str('timer_minutes')} inputMode="numeric" value=${minutes} onEnter=${start}
          onInput=${(v) => setMinutes(Number(digits(v)) > 59 ? '59' : digits(v))} />
      </div>
    <//>`;
}

/**
 * The timer of a remote or of a custom device, [target]: the delays to choose from, or the one
 * that is running with what cancels it. [onChoose] receives the minutes; with [onTurnOn] the
 * timer can also switch on, and that one receives them instead.
 */
export function TimerChips({ title, target, s, onChoose, onTurnOn }) {
  const running = s.schedules.find((it) => !it.days.length && it.enabled && it.action.target === target);
  return html`
    <div class="label-large">${title}</div>
    ${running
      ? html`<div class="row"><span class="grow">${str('schedule_once', running.label, clock(running.at))}</span>
          <button class="text-btn" onClick=${() => store.deleteSchedule(running.id)}>${str('cancel_timer')}</button></div>`
      : html`<${TimerChoices} canTurnOn=${!!onTurnOn} onChoose=${(minutes, on) => (on ? onTurnOn(minutes) : onChoose(minutes))} />`}`;
}

/** The actions set to happen at a time: what is waiting, and how to add one. */
export function SchedulesDialog({ s, onDismiss }) {
  const [picking, setPicking] = useState(false);
  // The action chosen, while its time is being asked.
  const [chosen, setChosen] = useState(null);
  return html`
    <${FullDialog} title=${str('s_schedules')} onDismiss=${onDismiss}>
      <div class="body-small faint">${str('schedules_text')}</div>
      <div class="body-small faint">${str('pwa_open_needed')}</div>
      ${s.schedules.map((schedule) => html`
        <${Card} key=${schedule.id}><${Setting} title=${schedule.label} text=${whenText(schedule)} trailing=${html`
          ${/* One that runs once is either waiting or deleted. */ ''}
          ${schedule.days.length > 0 && html`<${Switch} checked=${schedule.enabled} label=${schedule.label}
            onChange=${(on) => store.setScheduleEnabled(schedule, on)} />`}
          <${IconButton} icon="close" label=${str('schedule_delete')} onClick=${() => store.deleteSchedule(schedule.id)} />`} /><//>`)}
      <${Card}><${Setting} lead=${html`<${Icon} name="add" />`} title=${str('schedule_new')} onClick=${() => setPicking(true)} /><//>
      ${picking && html`<${ActionPicker} s=${s} kinds=${[ACTION_POWER, ACTION_MACRO, ACTION_REMOTE]}
        onPick=${(action) => {
          setChosen(action);
          setPicking(false);
        }} onDismiss=${() => setPicking(false)} />`}
      ${chosen && html`<${WhenDialog} onDismiss=${() => setChosen(null)} onPick=${(at, days) => {
        // How it went is told by a notification.
        store.askNotifications();
        store.addSchedule(chosen, describe(chosen, s), at, days);
        setChosen(null);
      }} />`}
    <//>`;
}

/** Asks when: at a time, once or on some days of the week, or in a while from now. */
function WhenDialog({ onPick, onDismiss }) {
  const now = new Date();
  const [timed, setTimed] = useState(true);
  const [minutes, setMinutes] = useState(30);
  const [days, setDays] = useState([]);
  const two = (n) => String(n).padStart(2, '0');
  const [time, setTime] = useState(`${two(now.getHours())}:${two(now.getMinutes())}`);
  const chip = (selected, label, onClick) => html`
    <button class=${`chip ${selected ? 'selected' : ''}`} onClick=${onClick}>${selected && html`<${Icon} name="check" />`}${label}</button>`;
  const confirm = () => {
    const from = Date.now();
    if (timed) {
      const [hour, minute] = time.split(':').map(Number);
      onPick(nextTime(hour, minute, days, from), days);
    } else {
      onPick(from + minutes * 60_000, []);
    }
  };
  return html`
    <${Dialog} title=${str('schedule_when')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${timed && !/^\d\d:\d\d$/.test(time)} onClick=${confirm}>${str('schedule_confirm')}</button>`}>
      <div class="chips">
        ${chip(timed, str('schedule_at_time'), () => setTimed(true))}
        ${chip(!timed, str('schedule_in_a_while'), () => setTimed(false))}
      </div>
      ${timed
        ? html`
          <div class="field" style="margin:16px 0 12px">
            <input type="time" value=${time} onInput=${(e) => setTime(e.target.value)} aria-label=${str('schedule_at_time')}
              style="font-size:32px;padding:12px 16px" />
          </div>
          <div class="label-large">${str('schedule_repeat')}</div>
          <div class="chips">${WEEK.map((day) => chip(days.includes(day), dayName(day),
            () => setDays(days.includes(day) ? days.filter((d) => d !== day) : [...days, day])))}</div>
          ${!days.length && html`<div class="body-small faint">${str('schedule_repeat_hint')}</div>`}`
        : html`<div class="chips" style="margin-top:8px">${IN_MINUTES.map((choice) => chip(choice === minutes, duration(choice), () => setMinutes(choice)))}</div>`}
    <//>`;
}
