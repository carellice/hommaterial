// The app: which screen is shown, the theme, and the panel behaviour of the monitor mode.
import { html, useState, useEffect, useRef } from '../lib.js';
import { Dialog, Snackbar, useStore, useNow } from './common.js';
import * as store from '../store.js';
import { str } from '/shared/i18n.js';
import { accentCss } from '/shared/accents.js';
import { HomeScreen } from './home.js';
import { SettingsScreen } from './settings.js';
import { OnboardingScreen, LoginScreen, RestScreen } from './screens.js';
import { AccountScreen } from './account.js';


/** Whether [at] falls in the hours from [from] to [to] o'clock, which usually cross midnight. */
function isNight(at, from, to) {
  const hour = new Date(at).getHours();
  return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
}

function useTheme(theme, accent) {
  useEffect(() => {
    const root = document.documentElement;
    if (theme === 1) root.dataset.theme = 'light';
    else if (theme === 2) root.dataset.theme = 'dark';
    else delete root.dataset.theme;
    // The colors of the accent, after app.css so that they win over its own.
    let style = document.getElementById('accent');
    if (!style) {
      style = document.createElement('style');
      style.id = 'accent';
      document.head.appendChild(style);
    }
    style.textContent = accentCss(accent);
    // The bar of the browser takes the color of the background chosen in the app.
    const background = getComputedStyle(root).getPropertyValue('--background').trim();
    document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => meta.setAttribute('content', background));
  }, [theme, accent]);
}

/** Keeps the screen lit while [on], taking the lock again whenever the page comes back in sight. */
function useWakeLock(on) {
  useEffect(() => {
    if (!on || !('wakeLock' in navigator)) return undefined;
    let lock = null;
    const take = async () => {
      if (document.visibilityState !== 'visible') return;
      try {
        lock = await navigator.wakeLock.request('screen');
      } catch {
        // Refused, as with the battery saver on: the screen goes off as it normally would.
      }
    };
    take();
    document.addEventListener('visibilitychange', take);
    return () => {
      document.removeEventListener('visibilitychange', take);
      lock?.release().catch(() => {});
    };
  }, [on]);
}

/** Every return to the app shows fresh states, as on the phone. */
function useResume(account, loggedIn) {
  useEffect(() => {
    if (!account) return undefined;
    const resume = () => {
      if (document.visibilityState !== 'visible') return;
      store.sync();
      store.refresh();
      store.checkForUpdate(false);
    };
    resume();
    store.startSync();
    document.addEventListener('visibilitychange', resume);
    return () => document.removeEventListener('visibilitychange', resume);
  }, [account, loggedIn]);
}

export function App() {
  const s = useStore();
  const [settings, setSettings] = useState(false);
  const [resting, setResting] = useState(false);
  const touches = useRef(0);
  const [touched, setTouched] = useState(0);
  useTheme(s.theme, s.accent);
  useResume(s.account, s.loggedIn);
  useEffect(() => {
    store.checkSession();
  }, []);

  // A panel on a wall stays lit, uses the whole screen and asks for the states by itself.
  const monitor = s.monitor.enabled && s.loggedIn ? s.monitor : null;
  useWakeLock(monitor?.keepOn === true);
  // Without a monitor the server reads the states by itself; a panel on a wall wants them fresher.
  const every = monitor ? monitor.refreshSeconds * 1000 : null;
  useEffect(() => {
    if (!every || !s.loggedIn) return undefined;
    const timer = setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      store.poll();
      if (monitor) store.checkForUpdate(false);
    }, every);
    return () => clearInterval(timer);
  }, [every, s.loggedIn]);

  // Fullscreen needs a touch to be granted: the first one on the panel asks for it.
  useEffect(() => {
    const onTouch = () => {
      touches.current++;
      setTouched(touches.current);
      if (monitor?.fullscreen && !document.fullscreenElement && document.documentElement.requestFullscreen) {
        document.documentElement.requestFullscreen({ navigationUI: 'hide' }).catch(() => {});
      }
    };
    window.addEventListener('pointerdown', onTouch, true);
    window.addEventListener('keydown', onTouch, true);
    return () => {
      window.removeEventListener('pointerdown', onTouch, true);
      window.removeEventListener('keydown', onTouch, true);
    };
  }, [monitor?.fullscreen]);
  useEffect(() => {
    if (!monitor?.fullscreen && document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
  }, [monitor?.fullscreen]);

  // A panel rests after a while without a touch; whoever wakes it finds the first page.
  const rests = monitor?.rest === true;
  useEffect(() => {
    setResting(false);
    if (!rests) return undefined;
    const timer = setTimeout(() => {
      setSettings(false);
      setResting(true);
    }, monitor.restMinutes * 60_000);
    return () => clearTimeout(timer);
  }, [touched, rests, monitor?.restMinutes]);
  const now = useNow(30_000);
  const night = monitor?.night === true && isNight(now, monitor.nightFrom, monitor.nightTo);

  // A new sign-in starts from the list, not from where the previous one left.
  useEffect(() => {
    if (!s.loggedIn) setSettings(false);
  }, [s.loggedIn]);

  let screen;
  if (s.account == null) screen = html`<div class="screen"></div>`;
  else if (s.account === false) screen = html`<${AccountScreen} s=${s} />`;
  else if (!s.onboarded) screen = html`<${OnboardingScreen} />`;
  else if (!s.loggedIn) screen = html`<${LoginScreen} s=${s} />`;
  else if (settings) screen = html`<${SettingsScreen} onBack=${() => setSettings(false)} />`;
  else screen = html`<${HomeScreen} onSettings=${() => setSettings(true)} />`;

  return html`
    ${screen}
    ${resting && monitor && html`<${RestScreen} s=${s} night=${night} onWake=${() => setResting(false)} />`}
    ${s.update && s.updatePrompt && html`<${UpdateDialog} version=${s.update.version} />`}
    <${Snackbar} />`;
}

function UpdateDialog({ version }) {
  return html`
    <${Dialog} title=${str('update_title')} onDismiss=${store.dismissUpdate} actions=${html`
      <button class="text-btn" onClick=${store.dismissUpdate}>${str('update_later')}</button>
      <button class="text-btn" onClick=${store.installUpdate}>${str('update_now')}</button>`}>
      <div class="body-medium faint">${str('update_text', version, '', store.installedVersion)}</div>
    <//>`;
}
