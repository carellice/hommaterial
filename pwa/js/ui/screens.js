// The first-run notice, the sign-in and the rest screen of the monitor.
import { html, useState } from '../lib.js';
import { Icon, Field, Spinner, Menu, useNow } from './common.js';
import * as store from '../store.js';
import { str, plural, clock, dateText } from '../i18n.js';
import { MARKETPLACES } from '../alexa.js';
import { proxy } from '../net.js';
import { REST_CLOCK, REST_DATE, REST_POWER, REST_SENSORS, REST_TIMERS, placed, statusText, countdown } from '../model.js';
import { ProxyDialog } from './settings.js';

const FEATURES = [
  ['meeting_room', 'feature_1'], ['touch_app', 'feature_2'], ['lightbulb', 'feature_3'],
  ['thermostat', 'feature_4'], ['bolt', 'feature_5'],
];
const WARNINGS = [
  ['warning_1_title', 'warning_1_text'], ['warning_2_title', 'warning_2_text'], ['warning_3_title', 'warning_3_text'],
  ['warning_4_title', 'pwa_warning_4_text'], ['pwa_warning_7_title', 'pwa_warning_7_text'],
  ['warning_5_title', 'warning_5_text'], ['warning_6_title', 'warning_6_text'],
];

/** Shown once, before the sign-in: what the app is for and what using it implies. */
export function OnboardingScreen() {
  const [accepted, setAccepted] = useState(false);
  return html`
    <div class="screen">
      <div class="scroll"><div class="onboarding">
        <div class="headline-medium">${str('welcome')}</div>
        <div class="body-large faint" style="margin-top:8px">${str('tagline')}</div>
        <div class="title-medium" style="color:var(--primary);margin:28px 0 12px">${str('onboarding_purpose')}</div>
        ${FEATURES.map(([icon, text]) => html`<div class="feature"><${Icon} name=${icon} /><span class="body-large">${str(text)}</span></div>`)}
        <div class="title-medium" style="color:var(--primary);margin:28px 0 12px">${str('onboarding_before')}</div>
        <div class="warning">
          <div class="row gap"><${Icon} name="warning" size=${28} /><span class="title-medium" style="font-weight:700">${str('onboarding_unofficial')}</span></div>
          ${WARNINGS.map(([title, text]) => html`
            <div class="title-small" style="margin-top:16px;font-weight:600">${str(title)}</div>
            <div class="body-medium">${str(text)}</div>`)}
        </div>
      </div></div>
      <div class="bottom-bar"><div class="inner">
        <button class="list-item" role="checkbox" aria-checked=${accepted} onClick=${() => setAccepted(!accepted)} style="padding:12px 0">
          <span class="icon" style=${accepted ? 'color:var(--primary)' : ''}>${accepted ? 'check_box' : 'check_box_outline_blank'}</span>
          <span class="texts body-medium">${str('onboarding_accept')}</span>
        </button>
        <button class="filled-btn" style="width:100%" disabled=${!accepted} onClick=${store.acceptOnboarding}>${str('continue_')}</button>
      </div></div>
    </div>`;
}

/**
 * The sign-in. Amazon's page cannot be shown inside a web app as the Android app shows it: it
 * opens in a tab of its own, and the address it lands on, which carries the code, is pasted back.
 */
export function LoginScreen({ s }) {
  const [anchor, setAnchor] = useState(null);
  const [landing, setLanding] = useState('');
  const [opened, setOpened] = useState(!!store.pendingLoginAttempt());
  const [settingProxy, setSettingProxy] = useState(false);
  const [, setProxyVersion] = useState(0);
  const open = async () => {
    const attempt = await store.newLoginAttempt();
    window.open(attempt.url, '_blank', 'noopener');
    setOpened(true);
  };
  return html`
    <div class="screen">
      <div class="top-bar">
        <div class="title"><div class="title-large">${str('login_title')}</div></div>
        ${!s.signingIn && html`<button class="text-btn" onClick=${(e) => setAnchor(e.currentTarget.getBoundingClientRect())}>${s.marketplace.domain}</button>`}
      </div>
      ${anchor && html`<${Menu} anchor=${anchor} onDismiss=${() => setAnchor(null)}
        items=${MARKETPLACES.map((m) => [m.domain, () => store.setMarketplace(m.domain)])} />`}
      ${s.loginMessage && html`<div class="banner">${s.loginMessage}</div>`}
      ${s.signingIn
        ? html`<div class="empty" style="flex-direction:column;gap:16px;height:100%"><${Spinner} big /><span>${str('login_connecting')}</span></div>`
        : html`
          <div class="scroll"><div class="narrow">
            <div class="steps">
              <div class="step"><div>
                <div class="title-medium">${str('pwa_s_proxy')}</div>
                <div class="body-medium faint">${proxy.url ? proxy.url : str('pwa_proxy_text')}</div>
                <div><button class=${proxy.url ? 'text-btn' : 'filled-btn'} style=${proxy.url ? 'margin-left:-12px' : ''}
                  onClick=${() => setSettingProxy(true)}>${str(proxy.url ? 'pwa_proxy_change' : 'pwa_proxy_set')}</button></div>
              </div></div>
              <div class="step"><div>
                <div class="title-medium">${str('pwa_login_open_title')}</div>
                <div class="body-medium faint">${str('pwa_login_open_text', s.marketplace.domain)}</div>
                <div><button class=${opened ? 'tonal-btn' : 'filled-btn'} disabled=${!proxy.url} onClick=${open}>${str('pwa_login_open')}</button></div>
              </div></div>
              <div class="step"><div>
                <div class="title-medium">${str('pwa_login_paste_title')}</div>
                <div class="body-medium faint">${str('pwa_login_paste_text')}</div>
                <${Field} label=${str('pwa_login_paste_label')} value=${landing} onInput=${setLanding} type="url"
                  onEnter=${() => landing.trim() && store.completeLogin(landing)} />
                <div><button class="filled-btn" disabled=${!landing.trim() || !proxy.url}
                  onClick=${() => store.completeLogin(landing)}>${str('pwa_login_finish')}</button></div>
              </div></div>
            </div>
            <div class="body-small faint" style="margin-top:8px">${str('pwa_login_note')}</div>
          </div></div>`}
      ${settingProxy && html`<${ProxyDialog} onDismiss=${() => setSettingProxy(false)} onSaved=${() => setProxyVersion((v) => v + 1)} />`}
    </div>`;
}

// How bright what the rest screen writes is, for each choice of brightness: a page cannot dim the screen.
const REST_LEVELS = { 0: 0.87, 1: 0.3, 15: 0.55, 40: 0.75 };

/**
 * What a panel shows while nobody uses it: the clock and a glance at the home, on black. At
 * [night] only the clock is left, faint. Any touch calls [onWake].
 */
export function RestScreen({ s, night, onWake }) {
  const now = useNow();
  const monitor = s.monitor;
  const items = night ? [REST_CLOCK] : monitor.restItems;
  const devices = placed(s).filter((d) => !s.hidden.includes(d.applianceId));
  // Moved a little every minute, so that nothing stays lit on the same pixels for days.
  const minute = Math.floor(now / 60_000);
  const shift = `translate(${((minute % 5) - 2) * 8}px, ${((Math.floor(minute / 5) % 5) - 2) * 8}px)`;
  const opacity = night ? 0.25 : REST_LEVELS[monitor.restBrightness] ?? 0.87;
  const sensors = devices.filter((d) => d.isSensor && (monitor.restSensors == null || monitor.restSensors.includes(d.applianceId))
    && typeof s.states[d.applianceId]?.temperature === 'number');
  const on = devices.filter((d) => d.hasPower && s.states[d.applianceId]?.power === true).length;
  const timers = Object.entries(s.timers).filter(([, at]) => at > now).sort((a, b) => a[1] - b[1]);
  return html`
    <div class="rest" onPointerDown=${(e) => {
      e.preventDefault();
      onWake();
    }}>
      <div class="inner" style=${`transform:${shift};opacity:${opacity}`}>
        ${items.includes(REST_CLOCK) && html`<div class="clock">${clock(now)}</div>`}
        ${items.includes(REST_DATE) && html`<div class="headline-small">${dateText(now)}</div>`}
        ${items.includes(REST_SENSORS) && html`<div class="sensors">${sensors.map((sensor) => html`
          <div><div class="headline-medium">${statusText(sensor, s.states[sensor.applianceId])}</div>
            ${/* The room tells apart sensors with the same name. */ ''}
            <div class="body-medium" style="opacity:.7">${[sensor.name, sensor.room].filter(Boolean).join(' · ')}</div></div>`)}</div>`}
        ${items.includes(REST_POWER) && html`<div class="title-medium" style="margin-top:16px;opacity:.7">
          ${on === 0 ? str('rest_all_off') : plural('rest_on', on)}</div>`}
        ${items.includes(REST_TIMERS) && html`
          ${timers.map(([id, at]) => {
            const name = devices.find((d) => d.applianceId === id)?.name;
            return name && html`<div class="title-medium tnum">${str('rest_timer', name, countdown(at - now))}</div>`;
          })}
          ${/* What else is about to happen once, such as the timer of a remote. */ ''}
          ${s.schedules.filter((it) => !it.days.length && it.enabled && it.at > now).map((it) => html`
            <div class="title-medium tnum">${str('rest_schedule', it.label, countdown(it.at - now))}</div>`)}`}
      </div>
    </div>`;
}
