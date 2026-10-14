// Choosing rooms, the members of a room or group, and lists of things in order.
import { html, useState } from '../lib.js';
import { Dialog, Field } from './common.js';
import { str } from '../i18n.js';

const Radio = ({ label, selected, onClick }) => html`
  <button class="list-item" role="radio" aria-checked=${selected} onClick=${onClick} style="min-height:48px;padding:0 8px">
    <span class="icon" style=${selected ? 'color:var(--primary)' : ''}>${selected ? 'radio_button_checked' : 'radio_button_unchecked'}</span>
    <span class="texts">${label}</span>
  </button>`;

const Check = ({ pick, checked, enabled = true, onChange }) => html`
  <button class="list-item" role="checkbox" aria-checked=${checked} disabled=${!enabled}
    style=${`min-height:48px;padding:4px 8px;${enabled ? '' : 'opacity:.38'}`} onClick=${() => onChange(!checked)}>
    <span class="icon" style=${checked ? 'color:var(--primary)' : ''} >${checked ? 'check_box' : 'check_box_outline_blank'}</span>
    <span class="texts"><span>${pick.label}</span>${pick.hint && html`<span class="supporting body-small">${pick.hint}</span>`}</span>
  </button>`;

/**
 * Lets the user move what is called [name] to one of the [rooms], to a new one or to none.
 * [onPick] receives the room, an empty text for no room at all, or null to follow Alexa again:
 * a choice offered, worded as [follow], only to what has a room in Alexa.
 */
export function RoomPicker({ name, follow, current, rooms, onPick, onDismiss }) {
  const [created, setCreated] = useState('');
  return html`
    <${Dialog} title=${str('room_pick_title', name)} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${!created.trim()} onClick=${() => onPick(created.trim())}>${str('room_create')}</button>`}>
      ${follow != null && html`<${Radio} label=${follow} selected=${current == null} onClick=${() => onPick(null)} />`}
      ${rooms.map((room) => html`<${Radio} label=${room} selected=${current === room} onClick=${() => onPick(room)} />`)}
      <${Radio} label=${str('room_none')} selected=${current === ''} onClick=${() => onPick('')} />
      <div style="padding-top:8px"><${Field} label=${str('room_new')} value=${created} onInput=${setCreated} /></div>
    <//>`;
}

/**
 * Edits a room or a group: its name and which of the [picks] belong to it. [onDelete] is null
 * for what cannot be deleted, such as the rooms that come from Alexa.
 */
export function MembersDialog({ title, hint, name, picks, members, onSave, onDelete, onDismiss }) {
  const [typed, setTyped] = useState(name);
  const [chosen, setChosen] = useState(members);
  return html`
    <${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
      ${onDelete && html`<button class="text-btn" onClick=${onDelete}>${str('delete')}</button>`}
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" disabled=${!typed.trim()} onClick=${() => onSave(typed.trim(), chosen)}>${str('save')}</button>`}>
      <${Field} label=${str('room_name')} value=${typed} onInput=${setTyped} />
      <div class="label-large" style="padding:16px 0 4px">${hint}</div>
      ${picks.map((pick) => html`<${Check} key=${pick.key} pick=${pick} checked=${chosen.includes(pick.key)}
        onChange=${(on) => setChosen(on ? [...chosen, pick.key] : chosen.filter((k) => k !== pick.key))} />`)}
    <//>`;
}

/**
 * Chooses among the [picks], in the order they are ticked and no more than [limit] when there is
 * one. [onSave] receives null when the user takes [reset], which goes back to no choice at all.
 */
export function PicksDialog({ title, picks, initial, limit, reset, onSave, onDismiss }) {
  const [chosen, setChosen] = useState(initial.filter((key) => picks.some((p) => p.key === key)));
  return html`
    <${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${() => onSave(null)}>${reset}</button>
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" onClick=${() => onSave(chosen)}>${str('save')}</button>`}>
      ${limit != null && html`<div class="label-large">${str('shortcuts_count', chosen.length, limit)}</div>`}
      ${picks.map((pick) => {
        const checked = chosen.includes(pick.key);
        return html`<${Check} key=${pick.key} pick=${pick} checked=${checked}
          enabled=${checked || limit == null || chosen.length < limit}
          onChange=${(on) => setChosen(on ? [...chosen, pick.key] : chosen.filter((k) => k !== pick.key))} />`;
      })}
    <//>`;
}
