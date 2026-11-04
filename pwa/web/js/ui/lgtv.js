// The LG TVs, which the server of the home drives on the network: pairing, the keys of their
// remote, their sources and the keyboard for their text fields (LgTvs.kt).
import { html, useState, useEffect, useRef } from '../lib.js';
import { Icon, Dialog, Field, Spinner } from './common.js';
import * as store from '../store.js';
import { str } from '/shared/i18n.js';
import { LG_KEYS, LG_MEDIA, lgLabel } from '/shared/model.js';

export { LG_KEYS, LG_MEDIA, lgLabel };

const Waiting = ({ text }) => html`
  <div class="row" style="margin-top:16px;gap:16px"><${Spinner} /><span class="body-medium">${text}</span></div>`;

const Heading = ({ text }) => html`<div class="label-large" style="color:var(--primary);margin:16px 0 4px">${text}</div>`;

const Choice = ({ text, onClick }) => html`
  <button class="list-item" onClick=${onClick}><span class="texts"><span class="body-large">${text}</span></span></button>`;

/**
 * Pairs an LG TV: the TVs that answered the search on the network of the server, or one by its
 * address, and then the wait while the TV asks the user on its screen.
 */
export function LgTvDialog({ s, onDismiss }) {
  const [address, setAddress] = useState('');
  const [searching, setSearching] = useState(true);
  const [found, setFound] = useState([]);
  const [pairing, setPairing] = useState(null);
  const [asking, setAsking] = useState(false);
  const search = async () => {
    setSearching(true);
    const list = await store.searchLgTvs();
    setFound(list.filter((tv) => !s.lgTvs.some((it) => it.host === tv.host)));
    setSearching(false);
  };
  useEffect(() => {
    search();
  }, []);
  const close = () => {
    if (pairing) store.cancelLgPairing();
    onDismiss();
  };
  const pair = async (host) => {
    const target = host.trim();
    if (!target) return;
    setPairing(target);
    setAsking(false);
    const name = found.find((tv) => tv.host === target)?.name ?? '';
    const paired = await store.pairLgTv(target, name, () => setAsking(true));
    if (paired) onDismiss();
    else setPairing(null);
  };
  return html`
    <${Dialog} title=${str('lgtv_add')} onDismiss=${close} actions=${html`
      ${!pairing && !searching && html`<button class="text-btn" onClick=${search}>${str('lgtv_search_again')}</button>`}
      <button class="text-btn" onClick=${close}>${str('cancel')}</button>
      ${!pairing && html`<button class="text-btn" disabled=${!address.trim()} onClick=${() => pair(address)}>${str('lgtv_pair')}</button>`}`}>
      ${pairing
        ? html`<${Waiting} text=${asking ? str('lgtv_asking') : pairing} />`
        : html`
          <div class="body-small">${str('lgtv_how')}</div>
          ${searching && html`<${Waiting} text=${str('lgtv_searching')} />`}
          ${!searching && !found.length && html`<div class="body-medium" style="margin-top:12px">${str('lgtv_none_found')}</div>`}
          ${found.map((tv) => html`
            <button class="list-item" onClick=${() => pair(tv.host)}>
              <span class="texts"><span class="body-large">${tv.name ?? tv.host}</span>
                ${tv.name && html`<span class="supporting">${tv.host}</span>`}</span>
            </button>`)}
          <div style="margin-top:12px"><${Field} label=${str('lgtv_address')} value=${address} type="url" inputMode="url"
            onInput=${(v) => setAddress(v.trim())} onEnter=${() => pair(address)} /></div>`}
    <//>`;
}

/** Renames an LG TV in the app, which is the name the home, the custom devices and the voice use. */
export function LgRenameDialog({ name, onRename, onDismiss }) {
  const [typed, setTyped] = useState(name);
  return html`
    <${Dialog} title=${str('room_rename_title', name)} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${!typed.trim()} onClick=${() => onRename(typed)}>${str('room_rename')}</button>`}>
      <${Field} label=${str('room_name')} value=${typed} autofocus onInput=${setTyped} onEnter=${() => typed.trim() && onRename(typed)} />
    <//>`;
}

/**
 * The keys of the LG remote that matter most, on top of those every TV has: the arrows with OK,
 * then Back, Home and the sound, then the player and the source.
 */
export function LgControls({ remote, s }) {
  const press = (key) => store.sendRemote(remote, `key:${key}`);
  const [choosingSource, setChoosingSource] = useState(false);
  const [typing, setTyping] = useState(false);
  const pad = (label, icon, key, primary = false) => html`
    <button class=${`pad-key ${primary ? 'primary' : ''}`} aria-label=${label} title=${label} onClick=${() => press(key)}>
      ${icon ? html`<${Icon} name=${icon} size=${32} />` : html`<span class="title-medium">${label}</span>`}
    </button>`;
  return html`
    <div class="pad">
      ${pad(str('lg_key_up'), 'keyboard_arrow_up', 'UP')}
      <div class="pad-row">
        ${pad(str('lg_key_left'), 'keyboard_arrow_left', 'LEFT')}
        ${pad(str('lg_key_ok'), null, 'ENTER', true)}
        ${pad(str('lg_key_right'), 'keyboard_arrow_right', 'RIGHT')}
      </div>
      ${pad(str('lg_key_down'), 'keyboard_arrow_down', 'DOWN')}
    </div>
    <div class="three" style="margin-top:16px">
      ${[['lg_key_back', 'arrow_back', 'BACK'], ['lg_key_home', 'home', 'HOME'], ['lg_key_mute', 'volume_off', 'MUTE']].map(([label, icon, key]) => html`
        <button class="tonal-btn narrow-btn" onClick=${() => press(key)}><${Icon} name=${icon} size=${18} /><span>${str(label)}</span></button>`)}
    </div>
    <div class="media-row">
      ${[['rewind', 'fast_rewind'], ['play', 'play_arrow'], ['pause', 'pause'], ['fastForward', 'fast_forward']].map(([command, icon]) => html`
        <button class="icon-btn tonal media" aria-label=${lgLabel(command)} title=${lgLabel(command)}
          onClick=${() => store.sendRemote(remote, command)}><${Icon} name=${icon} /></button>`)}
    </div>
    <div class="two" style="margin-top:12px">
      <button class="tonal-btn" onClick=${() => setChoosingSource(true)}><${Icon} name="input" size=${18} /><span>${str('lgtv_source')}</span></button>
      <button class="tonal-btn" onClick=${() => setTyping(true)}><${Icon} name="keyboard" size=${18} /><span>${str('lgtv_keyboard')}</span></button>
    </div>
    ${choosingSource && html`<${LgSourceDialog} remote=${remote} s=${s} onDismiss=${() => setChoosingSource(false)} />`}
    ${typing && html`<${LgKeyboardDialog} remote=${remote} onDismiss=${() => setTyping(false)} />`}`;
}

/** The sources of the TV, such as its HDMI ports, asked to it as the dialog opens: a tap switches to one. */
function LgSourceDialog({ remote, s, onDismiss }) {
  const tv = s.lgTvs.find((it) => it.id === remote.id);
  useEffect(() => {
    store.refreshLgApps(remote);
  }, [remote.id]);
  const inputs = Object.entries(tv?.inputs ?? {});
  return html`
    <${Dialog} title=${str('lgtv_source')} onDismiss=${onDismiss} flush
      actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>`}>
      ${inputs.length
        ? inputs.map(([id, label]) => html`<${Choice} text=${label} onClick=${() => {
          store.sendRemote(remote, `input:${id}`);
          onDismiss();
        }} />`)
        : s.busy.includes(remote.id)
          ? html`<div style="padding:0 24px"><${Waiting} text=${str('lgtv_sources_loading')} /></div>`
          : html`<div class="body-medium" style="padding:0 24px">${str('lgtv_sources_none')}</div>`}
    <//>`;
}

/**
 * The keyboard of the phone for the text fields of the TV, as its search: what is written here
 * goes to the TV as it is typed, and Enter sends it.
 */
function LgKeyboardDialog({ remote, onDismiss }) {
  const [text, setText] = useState('');
  // What the TV has, so that only the difference goes; one change at a time, the last one.
  const sent = useRef('');
  const told = useRef(false);
  const queue = useRef(Promise.resolve());
  const type = (typed) => {
    setText(typed);
    queue.current = queue.current.then(async () => {
      if (typed === sent.current) return;
      if (await store.typeOnLgTv(remote, sent.current, typed, told.current)) sent.current = typed;
      else told.current = true;
    });
  };
  const enter = () => {
    queue.current = queue.current.then(async () => {
      // The TV closes its keyboard on Enter: the next text starts anew.
      if (await store.enterOnLgTv(remote)) {
        sent.current = '';
        setText('');
      }
    });
  };
  return html`
    <${Dialog} title=${str('lgtv_keyboard')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('close')}</button>
      <button class="text-btn" onClick=${enter}>${str('lgtv_keyboard_enter')}</button>`}>
      <div class="body-small" style="margin-bottom:12px">${str('lgtv_keyboard_how')}</div>
      <${Field} label=${str('lgtv_keyboard_text')} value=${text} autofocus onInput=${type} onEnter=${enter} />
    <//>`;
}

/**
 * Adds a button to an LG TV: one of its apps or inputs, asked to the TV as the dialog opens, or a
 * key of its remote.
 */
export function LgButtonDialog({ remote, s, onAdd, onDismiss }) {
  const tv = s.lgTvs.find((it) => it.id === remote.id);
  useEffect(() => {
    store.refreshLgApps(remote);
  }, [remote.id]);
  const inputs = Object.entries(tv?.inputs ?? {});
  const apps = Object.entries(tv?.apps ?? {});
  const add = (command) => onAdd([command]);
  return html`
    <${Dialog} title=${str('remote_button_add')} onDismiss=${onDismiss}
      actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>`}>
      ${inputs.length > 0 && html`<${Heading} text=${str('lgtv_source')} />
        ${inputs.map(([id, label]) => html`<${Choice} text=${label} onClick=${() => add(`input:${id}`)} />`)}`}
      <${Heading} text=${str('lgtv_apps')} />
      ${apps.length
        ? apps.map(([id, title]) => html`<${Choice} text=${title} onClick=${() => add(`app:${id}`)} />`)
        : s.busy.includes(remote.id)
          ? html`<${Waiting} text=${str('lgtv_apps_loading')} />`
          : html`<div class="body-medium">${str('lgtv_apps_none')}</div>`}
      <${Heading} text=${str('lgtv_keys')} />
      ${LG_MEDIA.map((command) => html`<${Choice} text=${lgLabel(command)} onClick=${() => add(command)} />`)}
      ${LG_KEYS.map(([key, label]) => html`<${Choice} text=${label ? str(label) : key} onClick=${() => add(`key:${key}`)} />`)}
    <//>`;
}
