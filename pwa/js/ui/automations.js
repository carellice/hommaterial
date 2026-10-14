// Automations: an action carried out when a device gets to a state.
import { html, useState } from '../lib.js';
import { Icon, IconButton, Switch, Dialog, FullDialog, ListDialog, Card, Setting } from './common.js';
import * as store from '../store.js';
import { str } from '../i18n.js';
import {
  ACTION_MACRO, ACTION_POWER, ACTION_REMOTE, HUMIDITY_TRIGGERS, THRESHOLD_TRIGGERS,
  TRIGGER_HUMIDITY_ABOVE, TRIGGER_HUMIDITY_BELOW, TRIGGER_OFF, TRIGGER_ON, TRIGGER_TEMPERATURE_ABOVE,
  TRIGGER_TEMPERATURE_BELOW, amount, conditionText, describe, placed,
} from '../model.js';
import { ActionPicker } from './macros.js';

/** What can be waited for on [device], with the words that offer it. */
function triggersOf(device, s) {
  const list = [];
  if (device.isSensor) {
    list.push([TRIGGER_TEMPERATURE_ABOVE, 'trigger_temperature_above'], [TRIGGER_TEMPERATURE_BELOW, 'trigger_temperature_below']);
    // Not every sensor measures the humidity.
    if (s.states[device.applianceId]?.humidity != null) {
      list.push([TRIGGER_HUMIDITY_ABOVE, 'trigger_humidity_above'], [TRIGGER_HUMIDITY_BELOW, 'trigger_humidity_below']);
    }
  }
  if (device.hasPower) list.push([TRIGGER_ON, 'trigger_on'], [TRIGGER_OFF, 'trigger_off']);
  return list;
}

/** The automations: those there are, and the questions that make a new one. */
export function AutomationsDialog({ s, onDismiss }) {
  // A new automation is made one answer at a time: the device, what to wait for, how much, what to do.
  const [creating, setCreating] = useState(false);
  const [device, setDevice] = useState(null);
  const [trigger, setTrigger] = useState(null);
  const [threshold, setThreshold] = useState(null);
  const reset = () => {
    setCreating(false);
    setDevice(null);
    setTrigger(null);
    setThreshold(null);
  };
  const watchable = placed(s).filter((d) => !s.hidden.includes(d.applianceId) && (d.isSensor || d.hasPower));

  let step = null;
  if (creating && !device) {
    step = html`<${ListDialog} title=${str('automation_pick_device')} items=${watchable.map((d) => [d.name, d.room ?? str('room_none')])}
      onDismiss=${reset} onPick=${(i) => setDevice(watchable[i])} />`;
  } else if (creating && !trigger) {
    const triggers = triggersOf(device, s);
    step = html`<${ListDialog} title=${device.name} items=${triggers.map(([, label]) => [str(label)])}
      onDismiss=${reset} onPick=${(i) => setTrigger(triggers[i][0])} />`;
  } else if (creating && THRESHOLD_TRIGGERS.includes(trigger) && threshold == null) {
    const st = s.states[device.applianceId];
    const current = HUMIDITY_TRIGGERS.includes(trigger) ? st?.humidity
      : typeof st?.temperature === 'number' && !Number.isNaN(st.temperature) ? st.temperature : null;
    step = html`<${ThresholdDialog} title=${str(triggersOf(device, s).find(([t]) => t === trigger)[1])}
      trigger=${trigger} current=${current} onPick=${setThreshold} onDismiss=${reset} />`;
  } else if (creating) {
    step = html`<${ActionPicker} s=${s} kinds=${[ACTION_POWER, ACTION_MACRO, ACTION_REMOTE]} onDismiss=${reset}
      onPick=${(action) => {
        // How it went is told by a notification.
        store.askNotifications();
        const limit = threshold ?? 0;
        store.addAutomation(device, trigger, limit, action, conditionText(device.name, trigger, limit), describe(action, s));
        reset();
      }} />`;
  }

  return html`
    <${FullDialog} title=${str('s_automations')} onDismiss=${onDismiss}>
      <div class="body-small faint">${str('pwa_automations_text')}</div>
      ${s.automations.map((automation) => html`
        <${Card} key=${automation.id}><${Setting} title=${automation.title} text=${automation.label} trailing=${html`
          <${Switch} checked=${automation.enabled} label=${automation.title}
            onChange=${(on) => store.setAutomationEnabled(automation, on)} />
          <${IconButton} icon="close" label=${str('automation_delete')} onClick=${() => store.deleteAutomation(automation.id)} />`} /><//>`)}
      <${Card}><${Setting} lead=${html`<${Icon} name="add" />`} title=${str('automation_new')} onClick=${() => setCreating(true)} /><//>
      ${step}
    <//>`;
}

/** Asks for a temperature or a humidity, starting near the [current] one and moving it by steps. */
function ThresholdDialog({ title, trigger, current, onPick, onDismiss }) {
  const humidity = HUMIDITY_TRIGGERS.includes(trigger);
  const above = trigger === TRIGGER_TEMPERATURE_ABOVE || trigger === TRIGGER_HUMIDITY_ABOVE;
  const step = humidity ? 5 : 0.5;
  // A little past the reading of now, on the side being waited for: otherwise it would hold at once.
  const start = (current != null ? Math.round(current) : humidity ? 50 : 22) + (humidity ? 10 : 2) * (above ? 1 : -1);
  const [value, setValue] = useState(humidity ? Math.min(100, Math.max(0, start)) : start);
  return html`
    <${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" onClick=${() => onPick(value)}>${str('automation_next')}</button>`}>
      <div class="stepper">
        <${IconButton} cls="tonal" icon="remove" label=${str('alert_lower')} disabled=${humidity && value <= 0}
          onClick=${() => setValue(value - step)} />
        <span class="headline-medium" style="padding:0 8px">${amount(trigger, value)}</span>
        <${IconButton} cls="tonal" icon="add" label=${str('alert_raise')} disabled=${humidity && value >= 100}
          onClick=${() => setValue(value + step)} />
      </div>
    <//>`;
}
