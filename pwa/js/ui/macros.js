// Custom devices: tiles that run a series of actions, and their editor.
import { html, useState } from '../lib.js';
import { Icon, IconButton, FullDialog, ListDialog, ConfirmDialog, Card, Field, Setting, usePress } from './common.js';
import * as store from '../store.js';
import { str, plural } from '../i18n.js';
import {
  ACTION_MACRO, ACTION_POWER, ACTION_REMOTE, ACTION_WAIT, macroKey, macroStatus, describe, placed, placeOf,
  allRooms, standardCommands, commandLabel,
} from '../model.js';
import { TileFace, HINTS } from './home.js';
import { TimerChips } from './schedules.js';
import { RoomPicker } from './rooms.js';

const WAIT_SECONDS = [1, 2, 3, 5, 10, 30, 60];

/** A custom device as a tile: a tap runs its actions, holding it opens them. */
export function MacroTile({ macro, s, look }) {
  const [editing, setEditing] = useState(false);
  // Behind the PIN of the monitor the panel only runs them.
  const press = usePress(() => store.runMacro(macro), look.locked ? null : () => setEditing(true));
  return html`
    <button class="tile" ...${press}>
      <${TileFace} look=${look} icon="auto_awesome" busy=${s.busy.includes(macro.id)} name=${macro.name}
        status=${macroStatus(macro, s)} hints=${look.locked ? [HINTS.RUN] : [HINTS.RUN, HINTS.HOLD]} />
    </button>
    ${editing && html`<${MacroEditor} macro=${macro} s=${s} onDismiss=${() => setEditing(false)} />`}`;
}

/** Creates a custom device, when [macro] is null, or edits one: its name, what it depends on, its actions. */
export function MacroEditor({ macro, s, onDismiss }) {
  const [name, setName] = useState(macro?.name ?? '');
  const [condition, setCondition] = useState(macro?.condition ?? null);
  const [steps, setSteps] = useState(macro?.steps ?? []);
  const [otherwise, setOtherwise] = useState(macro?.otherwise ?? []);
  const [choosingCondition, setChoosingCondition] = useState(false);
  const [moving, setMoving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const switchable = placed(s).filter((d) => d.hasPower);
  const watched = switchable.find((d) => d.applianceId === condition);
  const save = () => {
    // Without the device it depended on, what was done when it was off is dropped.
    store.saveMacro({
      id: macro?.id ?? '',
      name: name.trim(),
      condition: watched?.applianceId ?? null,
      steps,
      otherwise: watched ? otherwise : [],
    });
    onDismiss();
  };
  const key = macro ? macroKey(macro.id) : null;
  const favorite = key && s.favorites.includes(key);
  return html`
    <${FullDialog} title=${str(macro ? 'macro_edit' : 'macro_new')} onDismiss=${onDismiss} closeLabel=${str('cancel')}
      actions=${html`
        ${macro && html`<button class="text-btn" onClick=${() => setConfirmDelete(true)}>${str('delete')}</button>`}
        <button class="text-btn" disabled=${!name.trim() || (!steps.length && !otherwise.length)} onClick=${save}>${str('save')}</button>`}>
      <${Field} label=${str('room_name')} value=${name} onInput=${setName} />
      <${Card}><${Setting} title=${str('macro_condition')} text=${watched?.name ?? str('macro_condition_none')}
        onClick=${() => setChoosingCondition(true)} /><//>
      ${/* Only once it exists: a timer runs what is saved. */ ''}
      ${macro && html`
        <${Card}><div style="padding:16px">
          <${TimerChips} title=${str('macro_run_in')} target=${macro.id} s=${s} onChoose=${(minutes) => {
            store.askNotifications();
            const action = { type: ACTION_MACRO, target: macro.id, value: '', custom: false };
            store.addTimer(action, describe(action, s), minutes);
          }} />
        </div><//>
        <${Card}><${Setting} title=${str(favorite ? 'favorite_remove' : 'favorite_add')}
          onClick=${() => store.setFavorite(key, !favorite)} /><//>
        <${Card}><${Setting} title=${str('room_label')} text=${placeOf(s, key) ?? str('room_none')}
          onClick=${() => setMoving(true)} /><//>`}
      ${watched
        ? html`
          <${ActionList} title=${str('macro_when_on', watched.name)} actions=${steps} s=${s} onChange=${setSteps} />
          <${ActionList} title=${str('macro_when_off', watched.name)} actions=${otherwise} s=${s} onChange=${setOtherwise} />`
        : html`<${ActionList} title=${str('macro_steps')} actions=${steps} s=${s} onChange=${setSteps} />`}
      ${choosingCondition && html`<${ListDialog} title=${str('macro_condition')}
        items=${[[str('macro_condition_none')], ...switchable.map((d) => [d.name, d.room ?? str('room_none')])]}
        onDismiss=${() => setChoosingCondition(false)} onPick=${(i) => {
          setCondition(i === 0 ? null : switchable[i - 1].applianceId);
          setChoosingCondition(false);
        }} />`}
      ${moving && html`<${RoomPicker} name=${macro.name} follow=${null} current=${placeOf(s, key) ?? ''} rooms=${allRooms(s)}
        onPick=${(room) => {
          store.setPlace(key, room);
          setMoving(false);
        }} onDismiss=${() => setMoving(false)} />`}
      ${confirmDelete && html`<${ConfirmDialog} title=${str('macro_delete_title', macro.name)} text=${str('macro_delete_text')}
        confirm=${str('delete')} onConfirm=${() => {
          store.deleteMacro(macro.id);
          onDismiss();
        }} onDismiss=${() => setConfirmDelete(false)} />`}
    <//>`;
}

/** The actions of one branch, in the order they run, with what moves one up, removes it or adds one. */
function ActionList({ title, actions, s, onChange }) {
  const [adding, setAdding] = useState(false);
  return html`
    <${Card}>
      <div class="card-title title-medium">${title}</div>
      <div style="padding-bottom:8px">
        ${actions.map((action, index) => html`
          <${Setting} key=${index} lead=${html`<span>${index + 1}</span>`} title=${describe(action, s)} trailing=${html`
            <${IconButton} icon="keyboard_arrow_up" label=${str('move_up')} disabled=${index === 0} onClick=${() => {
              const moved = [...actions];
              moved.splice(index - 1, 0, moved.splice(index, 1)[0]);
              onChange(moved);
            }} />
            <${IconButton} icon="close" label=${str('macro_remove_action')} onClick=${() => onChange(actions.filter((_, i) => i !== index))} />`} />`)}
        <${Setting} lead=${html`<${Icon} name="add" />`} title=${str('macro_add_action')} onClick=${() => setAdding(true)} />
      </div>
    <//>
    ${adding && html`<${ActionPicker} s=${s} onDismiss=${() => setAdding(false)} onPick=${(action) => {
      onChange([...actions, action]);
      setAdding(false);
    }} />`}`;
}

/** Builds an action of one of the [kinds], one question at a time: what kind, on what, and what exactly. */
export function ActionPicker({ s, kinds = [ACTION_POWER, ACTION_REMOTE, ACTION_WAIT], onPick, onDismiss }) {
  const [kind, setKind] = useState(null);
  const [target, setTarget] = useState(null);
  const switchable = placed(s).filter((d) => d.hasPower);
  const remote = s.remotes.find((r) => r.id === target);
  const action = (type, target = '', value = '', custom = false) => ({ type, target, value, custom });
  if (kind == null) {
    // Remotes and custom devices are offered only to who has some.
    const offered = [
      [ACTION_POWER, 'macro_kind_power'],
      s.macros.length > 0 && [ACTION_MACRO, 'schedule_kind_macro'],
      s.remotes.length > 0 && [ACTION_REMOTE, 'macro_kind_remote'],
      [ACTION_WAIT, 'macro_kind_wait'],
    ].filter((it) => it && kinds.includes(it[0]));
    return html`<${ListDialog} title=${str('macro_add_action')} items=${offered.map(([, label]) => [str(label)])}
      onDismiss=${onDismiss} onPick=${(i) => setKind(offered[i][0])} />`;
  }
  if (kind === ACTION_MACRO) {
    return html`<${ListDialog} title=${str('schedule_pick_macro')} items=${s.macros.map((m) => [m.name, macroStatus(m, s)])}
      onDismiss=${onDismiss} onPick=${(i) => onPick(action(ACTION_MACRO, s.macros[i].id))} />`;
  }
  if (kind === ACTION_WAIT) {
    return html`<${ListDialog} title=${str('macro_kind_wait')} items=${WAIT_SECONDS.map((n) => [plural('macro_wait', n)])}
      onDismiss=${onDismiss} onPick=${(i) => onPick(action(ACTION_WAIT, '', String(WAIT_SECONDS[i])))} />`;
  }
  if (kind === ACTION_POWER && target == null) {
    return html`<${ListDialog} title=${str('macro_pick_device')} items=${switchable.map((d) => [d.name, d.room ?? str('room_none')])}
      onDismiss=${onDismiss} onPick=${(i) => setTarget(switchable[i].applianceId)} />`;
  }
  if (kind === ACTION_POWER) {
    return html`<${ListDialog} title=${switchable.find((d) => d.applianceId === target)?.name ?? ''}
      items=${[[str('turn_on')], [str('turn_off')]]} onDismiss=${onDismiss}
      onPick=${(i) => onPick(action(ACTION_POWER, target, i === 0 ? 'on' : 'off'))} />`;
  }
  if (!remote) {
    return html`<${ListDialog} title=${str('macro_pick_remote')} items=${s.remotes.map((r) => [r.name, r.type])}
      onDismiss=${onDismiss} onPick=${(i) => setTarget(s.remotes[i].id)} />`;
  }
  const standard = standardCommands(remote);
  const keys = s.remoteButtons[remote.id] || [];
  return html`<${ListDialog} title=${remote.name} empty=${str('macro_no_keys')}
    items=${[...standard.map((c) => [commandLabel(c)]), ...keys.map((k) => [k])]} onDismiss=${onDismiss}
    onPick=${(i) => {
      const custom = i >= standard.length;
      onPick(action(ACTION_REMOTE, remote.id, custom ? keys[i - standard.length] : standard[i], custom));
    }} />`;
}
