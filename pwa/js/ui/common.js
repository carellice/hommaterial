// The pieces every screen is made of, drawn as Material 3 draws them.
import { html, useState, useEffect, useRef, useCallback, useLayoutEffect } from '../lib.js';
import { getState, subscribe, onMessage } from '../store.js';
import { str } from '../i18n.js';

export { html };

/** The state of the app, drawn again whenever it changes. */
export function useStore() {
  const [state, setState] = useState(getState());
  useEffect(() => subscribe(setState), []);
  return state;
}

/** The current time, read again every [period] milliseconds for as long as something shows it. */
export function useNow(period = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), period);
    return () => clearInterval(timer);
  }, [period]);
  return now;
}

const WIDE = 600;

/** The width of the window, which decides between a phone layout and a wide one. */
export function useWidth() {
  const [width, setWidth] = useState(window.innerWidth);
  useEffect(() => {
    const resize = () => setWidth(window.innerWidth);
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  return width;
}

export const useWide = () => useWidth() >= WIDE;

// ——— The back gesture closes what is open over the screen, the topmost first ———

// One entry of the browser history stands for everything open over the screen: the back gesture
// consumes it and closes the topmost, and it is put back while something else is still open.
const backStack = [];
let armed = false;
let ignoredPops = 0;

function arm() {
  if (armed || !backStack.length) return;
  history.pushState({ hommaterial: true }, '');
  armed = true;
}

window.addEventListener('popstate', () => {
  if (ignoredPops > 0) {
    ignoredPops--;
    return;
  }
  armed = false;
  const top = backStack.pop();
  if (top) {
    top.popped = true;
    top.close();
  }
  arm();
});

window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && armed) history.back();
});

/** Makes the back gesture, and the Escape key, call [close] while the caller is shown. */
export function useBack(close, active = true) {
  const latest = useRef(close);
  latest.current = close;
  // A layout effect: what closes and what opens in the same change are counted together.
  useLayoutEffect(() => {
    if (!active) return undefined;
    const entry = { close: () => latest.current(), popped: false };
    backStack.push(entry);
    arm();
    return () => {
      const i = backStack.indexOf(entry);
      if (i >= 0) backStack.splice(i, 1);
      // Closed from the screen: the entry of the history goes too, unless something else is open.
      setTimeout(() => {
        if (!backStack.length && armed) {
          armed = false;
          ignoredPops++;
          history.back();
        }
      }, 0);
    };
  }, [active]);
}

// ——— Small pieces ———

export const Icon = ({ name, filled, size, cls = '', title }) => html`
  <span class=${`icon ${filled ? 'filled' : ''} ${cls}`} style=${size ? `font-size:${size}px` : ''}
    aria-hidden=${title ? undefined : 'true'} title=${title}>${name}</span>`;

export const IconButton = ({ icon, label, onClick, disabled, cls = '', filled }) => html`
  <button class=${`icon-btn ${cls}`} onClick=${onClick} disabled=${disabled} aria-label=${label} title=${label}>
    <${Icon} name=${icon} filled=${filled} />
  </button>`;

export const Spinner = ({ big }) => html`<span class=${`spinner ${big ? 'big' : ''}`} role="progressbar"></span>`;

export const Switch = ({ checked, onChange, label }) => html`
  <button class=${`switch ${checked ? 'on' : ''}`} role="switch" aria-checked=${checked} aria-label=${label}
    onClick=${(e) => {
      e.stopPropagation();
      onChange(!checked);
    }}></button>`;

export const Card = ({ children, cls = '' }) => html`<div class=${`card ${cls}`}>${children}</div>`;

/** A line of a list: what it is, a line under it, and what sits at its end. */
export function Setting({ title, text, onClick, trailing, lead }) {
  const body = html`
    ${lead && html`<span class="lead">${lead}</span>`}
    <span class="texts"><span class="body-large">${title}</span>
      ${text && html`<span class="supporting">${text}</span>`}</span>
    ${trailing && html`<span class="trail" onClick=${(e) => e.stopPropagation()}>${trailing}</span>`}`;
  if (!onClick) return html`<div class="list-item">${body}</div>`;
  // A div with the role of a button: the switches at its end are buttons of their own.
  return html`<div class="list-item clickable" role="button" tabindex="0" onClick=${onClick}
    onKeyDown=${(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), onClick())}>${body}</div>`;
}

export const Toggle = ({ title, text, checked, onChange }) => html`
  <${Setting} title=${title} text=${text} onClick=${() => onChange(!checked)}
    trailing=${html`<${Switch} checked=${checked} onChange=${onChange} label=${title} />`} />`;

/** A setting with a handful of values, all in sight. */
export const Chips = ({ title, choices, selected, onSelect }) => html`
  <div class="setting-block">
    ${title && html`<div class="body-large">${title}</div>`}
    <div class="chips">
      ${choices.map(([value, label]) => html`
        <button class=${`chip ${value === selected ? 'selected' : ''}`} onClick=${() => onSelect(value)}>
          ${value === selected && html`<${Icon} name="check" />`}${label}
        </button>`)}
    </div>
  </div>`;

/** An entry of an ordered list, with the arrows that move it one place up or down. */
export const OrderRow = ({ name, text, first, last, onClick, onMove }) => html`
  <${Setting} title=${name} text=${text} onClick=${onClick} trailing=${html`
    <${IconButton} icon="keyboard_arrow_up" label=${str('move_up')} disabled=${first} onClick=${() => onMove(-1)} />
    <${IconButton} icon="keyboard_arrow_down" label=${str('move_down')} disabled=${last} onClick=${() => onMove(1)} />`} />`;

export function Field({ label, value, onInput, type = 'text', support, error, multiline, autofocus, inputMode, onEnter, maxLength }) {
  const ref = useRef(null);
  useEffect(() => {
    if (autofocus) setTimeout(() => ref.current?.focus(), 50);
  }, []);
  const props = {
    ref,
    value,
    onInput: (e) => onInput(e.target.value),
    'aria-label': label,
    inputMode,
    maxLength,
    onKeyDown: (e) => {
      if (e.key === 'Enter' && !multiline && onEnter) {
        e.preventDefault();
        onEnter();
      }
    },
  };
  return html`
    <div class=${`field ${error ? 'error' : ''}`}>
      ${label && html`<label>${label}</label>`}
      ${multiline ? html`<textarea rows="3" ...${props}></textarea>` : html`<input type=${type} ...${props} />`}
      ${(error || support) && html`<span class="support">${error || support}</span>`}
    </div>`;
}

// ——— Over the screen ———

export function Dialog({ title, onDismiss, actions, children, flush }) {
  useBack(onDismiss);
  return html`
    <div class="scrim" onClick=${onDismiss}></div>
    <div class="dialog" role="dialog" aria-modal="true" aria-label=${title}>
      ${title && html`<div class="head headline-small">${title}</div>`}
      <div class=${`content ${flush ? 'flush' : ''}`}>${children}</div>
      ${actions && html`<div class="actions">${actions}</div>`}
    </div>`;
}

/** A sheet from the bottom on a phone, a panel on the side where the screen is wide. */
export function Details({ onDismiss, children }) {
  useBack(onDismiss);
  const wide = useWide();
  return html`
    <div class="scrim" onClick=${onDismiss}></div>
    ${wide
      ? html`<div class="side-panel" role="dialog" aria-modal="true"><div class="content">${children}</div></div>`
      : html`<div class="sheet" role="dialog" aria-modal="true"><div class="handle"></div><div class="content">${children}</div></div>`}`;
}

/** A dialog that takes the whole screen, with its own bar at the top. */
export function FullDialog({ title, onDismiss, actions, children, closeLabel = str('back') }) {
  useBack(onDismiss);
  return html`
    <div class="full-dialog" role="dialog" aria-modal="true" aria-label=${title}>
      <div class="top-bar">
        <${IconButton} icon="close" label=${closeLabel} onClick=${onDismiss} />
        <div class="title"><div class="title-large">${title}</div></div>
        ${actions}
      </div>
      <div class="scroll"><div class="narrow">${children}</div></div>
    </div>`;
}

/** A list to pick one entry from; each has a label and may have a line under it. */
export const ListDialog = ({ title, items, onPick, onDismiss, empty }) => html`
  <${Dialog} title=${title} onDismiss=${onDismiss} flush
    actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>`}>
    ${!items.length && empty && html`<div class="body-medium" style="padding:0 16px">${empty}</div>`}
    ${items.map(([label, hint], i) => html`
      <button class="list-item" onClick=${() => onPick(i)}>
        <span class="texts"><span class="body-large">${label}</span>
          ${hint && html`<span class="supporting">${hint}</span>`}</span>
      </button>`)}
  <//>`;

/** Asks before doing something that cannot be undone. */
export const ConfirmDialog = ({ title, text, confirm, onConfirm, onDismiss }) => html`
  <${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
    <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
    <button class="text-btn" onClick=${() => {
      onDismiss();
      onConfirm();
    }}>${confirm}</button>`}>
    <div class="body-medium faint">${text}</div>
  <//>`;

const PIN_MIN = 4;
const PIN_MAX = 8;

/**
 * Asks for a PIN, to check it or to choose a new one. [onSubmit] says whether the digits were
 * taken; when they were not, the dialog stays open and says the PIN is wrong.
 */
export function PinDialog({ title, hint, onSubmit, onDismiss }) {
  const [typed, setTyped] = useState('');
  const [wrong, setWrong] = useState(false);
  const submit = async () => {
    if (typed.length >= PIN_MIN && !(await onSubmit(typed))) {
      setWrong(true);
      setTyped('');
    }
  };
  return html`
    <${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${typed.length < PIN_MIN} onClick=${submit}>${str('pin_confirm')}</button>`}>
      <${Field} label=${str('pin_label')} type="password" inputMode="numeric" autofocus value=${typed}
        error=${wrong ? str('pin_wrong') : null} support=${hint} onEnter=${submit}
        onInput=${(v) => {
          setTyped(v.replace(/\D/g, '').slice(0, PIN_MAX));
          setWrong(false);
        }} />
    <//>`;
}

/** A menu under the button that opened it, at [anchor]. */
export function Menu({ anchor, items, onDismiss }) {
  useBack(onDismiss);
  const right = Math.max(8, window.innerWidth - anchor.right);
  return html`
    <div class="menu-scrim" onClick=${onDismiss}></div>
    <div class="menu" role="menu" style=${`top:${anchor.bottom}px;right:${right}px`}>
      ${items.filter(Boolean).map(([label, action]) => html`
        <button role="menuitem" onClick=${() => {
          onDismiss();
          setTimeout(action, 0);
        }}>${label}</button>`)}
    </div>`;
}

/** The sentences of the app, one at a time at the bottom of the screen. */
export function Snackbar() {
  const [queue, setQueue] = useState([]);
  useEffect(() => onMessage((text) => setQueue((q) => [...q, text])), []);
  useEffect(() => {
    if (!queue.length) return undefined;
    const timer = setTimeout(() => setQueue((q) => q.slice(1)), 4000);
    return () => clearTimeout(timer);
  }, [queue]);
  return queue.length ? html`<div class="snackbar" role="status" onClick=${() => setQueue((q) => q.slice(1))}>${queue[0]}</div>` : null;
}

/**
 * The handlers of something that does one thing when touched and another when held, as a tile:
 * a long press, a right click or the context menu key all count as holding.
 */
export function usePress(onClick, onLong) {
  // [long] swallows the click that ends a long press; [heldAt] is when the long press went off.
  const press = useRef({ timer: null, long: false, heldAt: 0, x: 0, y: 0 });
  const latest = useRef({ onClick, onLong });
  latest.current = { onClick, onLong };
  const clear = () => {
    clearTimeout(press.current.timer);
    press.current.timer = null;
  };
  const hold = () => {
    clear();
    press.current.long = true;
    press.current.heldAt = Date.now();
    navigator.vibrate?.(12);
    latest.current.onLong?.();
  };
  return {
    onPointerDown: useCallback((e) => {
      // Every new press starts clean, whatever the button: a right click must not leave a click swallowed.
      press.current.long = false;
      clear();
      if (e.button !== 0 || !latest.current.onLong) return;
      press.current.x = e.clientX;
      press.current.y = e.clientY;
      press.current.timer = setTimeout(hold, 450);
    }, []),
    onPointerMove: useCallback((e) => {
      if (press.current.timer && Math.hypot(e.clientX - press.current.x, e.clientY - press.current.y) > 10) clear();
    }, []),
    onPointerUp: clear,
    onPointerCancel: clear,
    onPointerLeave: clear,
    onClick: useCallback((e) => {
      if (press.current.long) {
        press.current.long = false;
        e.preventDefault();
        return;
      }
      latest.current.onClick?.();
    }, []),
    onContextMenu: useCallback((e) => {
      e.preventDefault();
      if (!latest.current.onLong) return;
      // A finger held on a phone brings the context menu too, once the long press has already opened.
      if (Date.now() - press.current.heldAt < 1000) return;
      clear();
      latest.current.onLong();
    }, []),
  };
}
