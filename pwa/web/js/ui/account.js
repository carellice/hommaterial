// The account on the server of the home: signing in or creating one, and managing it in the settings.
import { html, useState } from '../lib.js';
import { Icon, Setting, Dialog, Field, Spinner } from './common.js';
import * as store from '../store.js';
import { str } from '/shared/i18n.js';

/**
 * The first screen on a new device: the account on the server, which holds the Amazon sign-in,
 * the choices and the timers of one person. In the look of the onboarding of the app.
 */
export function AccountScreen({ s }) {
  const [register, setRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [invite, setInvite] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const ready = username.trim() && password && (!register || (repeat && (!s.invite || invite.trim())));
  const submit = async () => {
    if (!ready || busy) return;
    if (register && password !== repeat) {
      setError(str('srv_passwords_differ'));
      return;
    }
    setBusy(true);
    setError(null);
    const failure = await store.signIn(username.trim(), password, register, invite);
    setBusy(false);
    if (failure) setError(failure);
  };
  const enter = () => submit();
  return html`
    <div class="screen">
      <div class="scroll"><div class="onboarding account">
        <img class="account-icon" src="/icons/icon-192.png" alt="" width="72" height="72" />
        <div class="headline-medium">${str(register ? 'srv_create_title' : 'srv_welcome_back')}</div>
        <div class="body-large faint" style="margin-top:8px">${str(register ? 'srv_register_text' : 'srv_login_text')}</div>
        <form class="account-form" onSubmit=${(e) => {
          e.preventDefault();
          submit();
        }}>
          <${AccountField} label=${str('srv_username')} value=${username} onInput=${setUsername} autocomplete="username" onEnter=${enter} />
          <${AccountField} label=${str('srv_password')} value=${password} onInput=${setPassword} type="password"
            autocomplete=${register ? 'new-password' : 'current-password'} onEnter=${enter} />
          ${register && html`
            <div class="appear">
              <${AccountField} label=${str('srv_repeat_password')} value=${repeat} onInput=${setRepeat} type="password"
                autocomplete="new-password" onEnter=${enter} />
              ${s.invite && html`<${AccountField} label=${str('srv_invite')} support=${str('srv_invite_hint')} value=${invite}
                onInput=${setInvite} autocomplete="off" onEnter=${enter} />`}
            </div>`}
          ${error && html`<div class="body-medium" style="color:var(--error)">${error}</div>`}
          <button class="filled-btn" type="submit" style="width:100%;margin-top:8px" disabled=${!ready || busy}>
            ${busy ? html`<${Spinner} />` : str(register ? 'srv_register' : 'srv_login')}</button>
          <button class="text-btn" type="button" style="align-self:center" onClick=${() => {
            setRegister(!register);
            setError(null);
          }}>${str(register ? 'srv_to_login' : 'srv_to_register')}</button>
        </form>
      </div></div>
    </div>`;
}

/** A field of the account forms, with what password managers need to fill it. */
function AccountField({ label, value, onInput, type = 'text', autocomplete, support, onEnter }) {
  return html`
    <div class="field">
      <label>${label}</label>
      <input type=${type} value=${value} autocomplete=${autocomplete} autocapitalize="none" spellcheck="false"
        aria-label=${label} onInput=${(e) => onInput(e.target.value)}
        onKeyDown=${(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            onEnter();
          }
        }} />
      ${support && html`<span class="support">${support}</span>`}
    </div>`;
}

function PasswordDialog({ onDismiss }) {
  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState(null);
  const save = async () => {
    if (password !== repeat) return setError(str('srv_passwords_differ'));
    const failure = await store.changePassword(current, password);
    if (failure) setError(failure);
    else onDismiss();
  };
  return html`
    <${Dialog} title=${str('srv_change_password')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${!current || !password || !repeat} onClick=${save}>${str('save')}</button>`}>
      <div style="display:flex;flex-direction:column;gap:8px">
        <${AccountField} label=${str('srv_current_password')} type="password" autocomplete="current-password" value=${current} onInput=${setCurrent} onEnter=${save} />
        <${AccountField} label=${str('srv_new_password')} type="password" autocomplete="new-password" value=${password} onInput=${setPassword} onEnter=${save} />
        <${AccountField} label=${str('srv_repeat_password')} type="password" autocomplete="new-password" value=${repeat} onInput=${setRepeat} onEnter=${save} />
        ${error && html`<div class="body-medium" style="color:var(--error)">${error}</div>`}
      </div>
    <//>`;
}

function DeleteDialog({ onDismiss }) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const remove = async () => {
    const failure = await store.deleteAccount(password);
    if (failure) setError(failure);
  };
  return html`
    <${Dialog} title=${str('srv_delete_account')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn danger" disabled=${!password} onClick=${remove}>${str('srv_delete_confirm')}</button>`}>
      <div class="body-medium faint" style="margin-bottom:12px">${str('srv_delete_account_text')}</div>
      <${AccountField} label=${str('srv_password')} type="password" autocomplete="current-password" value=${password}
        onInput=${setPassword} onEnter=${remove} />
      ${error && html`<div class="body-medium" style="color:var(--error);margin-top:8px">${error}</div>`}
    <//>`;
}

/** The section of the settings about the account on the server. */
export function AccountSettings({ s }) {
  const [dialog, setDialog] = useState(null);
  const close = () => setDialog(null);
  return html`
    <${Setting} lead=${html`<${Icon} name="account_circle" />`} title=${s.account || ''} text=${str('srv_signed_in_as', s.account || '')} />
    <${Setting} title=${str('srv_change_password')} onClick=${() => setDialog('password')} />
    <${Setting} title=${str('srv_sign_out')} text=${str('srv_sign_out_text')} onClick=${store.signOut} />
    <${Setting} title=${str('srv_delete_account')} onClick=${() => setDialog('delete')} />
    ${dialog === 'password' && html`<${PasswordDialog} onDismiss=${close} />`}
    ${dialog === 'delete' && html`<${DeleteDialog} onDismiss=${close} />`}`;
}
