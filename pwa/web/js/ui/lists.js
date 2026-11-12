// The page of the lists of Alexa, as in its app and on the Echo devices (ui/Lists.kt): a segmented
// control to choose the list, the list under it, and a swipe sideways to move to the next one.
import { html, useState, useEffect, useRef } from '../lib.js';
import { Icon, IconButton, Spinner, Dialog, Details, Setting, Field, ConfirmDialog } from './common.js';
import { applyViews, members, merge, split, rename, move, MERGED } from '/shared/lists.js';
import * as store from '../store.js';
import { listName } from '../store.js';
import { str } from '/shared/i18n.js';

// Items added and not yet confirmed by Alexa carry an id of their own until the list is read again.
const ADDING = 'adding:';
let added = 0;

/**
 * The lists and their items, kept while the app is open so that moving between the pages shows
 * them at once: read from Alexa when the page first opens, on a pull and after every change.
 */
const model = { lists: null, items: {}, loading: false, failed: false, pending: [], removing: [] };
const listeners = new Set();
const changed = () => listeners.forEach((l) => l());
const set = (change) => {
  Object.assign(model, change);
  changed();
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

export async function reloadLists() {
  if (model.loading) return;
  set({ loading: true });
  const found = await store.alexaLists();
  if (found) {
    // Every list at once, so that moving to the next one has nothing to wait for.
    const read = await Promise.all(found.map((l) => store.alexaListItems(l.id)));
    const items = { ...model.items };
    found.forEach((l, i) => read[i] && (items[l.id] = read[i]));
    set({ lists: found, items });
  }
  set({ loading: false, failed: !found });
}

async function refresh(listId) {
  const read = await store.alexaListItems(listId);
  if (read) set({ items: { ...model.items, [listId]: read } });
}

/** The items of a list shown: of all the lists joined in it, undefined until one was read. */
export function itemsOf(listId) {
  const parts = members(listId).map((id) => model.items[id]);
  return parts.every((p) => p === undefined) ? undefined : parts.flatMap((p) => p ?? []);
}

/** The lists as shown: renamed and joined as chosen in the app (shared/lists.js). */
export const shownLists = (s) => model.lists && applyViews(model.lists, s.listViews, listName);

/** The list an item of [listId] really is in; what is added goes to the first one. */
function home(listId, item) {
  const ids = members(listId);
  return ids.find((id) => item && model.items[id]?.some((it) => it.id === item.id)) ?? ids[0];
}

function add(shownId, name) {
  const listId = home(shownId);
  const item = { id: `${ADDING}${added++}`, name, completed: false, version: 0 };
  set({ items: { ...model.items, [listId]: [item, ...(model.items[listId] ?? [])] } });
  store.addAlexaListItem(listId, name).then(async () => {
    await refresh(listId);
    // What Alexa did not take goes away with the list read again; if that failed too, now.
    set({ items: { ...model.items, [listId]: (model.items[listId] ?? []).filter((it) => it.id !== item.id) } });
  });
}

async function toggle(shownId, item) {
  const listId = home(shownId, item);
  if (item.id.startsWith(ADDING) || model.pending.includes(item.id)) return;
  set({ pending: [...model.pending, item.id] });
  await store.setAlexaListItemDone(listId, item, !item.completed);
  await refresh(listId);
  set({ pending: model.pending.filter((id) => id !== item.id) });
}

async function renameItem(shownId, item, name) {
  const listId = home(shownId, item);
  if (item.id.startsWith(ADDING) || name === item.name) return;
  // Shown at once with the new name; the list read again says how it went.
  set({ items: { ...model.items, [listId]: (model.items[listId] ?? []).map((it) => (it.id === item.id ? { ...it, name } : it)) } });
  await store.renameAlexaListItem(listId, item, name);
  await refresh(listId);
}

/** Deletes every item done of the list shown, one after the other: Alexa wants each with its version. */
async function clearDone(shownId) {
  for (const item of (itemsOf(shownId) ?? []).filter((it) => it.completed)) await remove(shownId, item);
}

async function remove(shownId, item) {
  const listId = home(shownId, item);
  if (item.id.startsWith(ADDING)) return;
  set({ removing: [...model.removing, item.id] });
  await store.deleteAlexaListItem(listId, item);
  await refresh(listId);
  set({ removing: model.removing.filter((id) => id !== item.id) });
}

/** How many items are still to do: counted from those read, or as Alexa last said. */
const active = (list) => itemsOf(list.id)?.filter((it) => !it.completed).length ?? list.active;

export function ListsPage({ s }) {
  const m = useModel();
  // The lists chosen of Notion come and go with the settings: read again when they change.
  const notion = JSON.stringify([s.notion.linked, s.notionLists]);
  const [seen, setSeen] = useState(notion);
  useEffect(() => {
    if (notion === seen) return;
    setSeen(notion);
    if (m.lists) reloadLists();
  }, [notion]);
  const [chosen, setChosen] = useState(null);
  // Which way the last change of list went, for the new one to come in from that side.
  const [direction, setDirection] = useState(0);
  useEffect(() => {
    if (!m.lists) reloadLists();
  }, []);

  if (!m.lists) {
    return html`<div class="lists-center">${m.failed && !m.loading
      ? html`<div class="body-large faint">${str('lists_failed')}</div>
        <button class="tonal-btn" style="margin-top:16px" onClick=${reloadLists}>${str('lists_retry')}</button>`
      : html`<${Spinner} />`}</div>`;
  }
  if (!m.lists.length) return html`<div class="lists-page"><${EmptyState} title=${str('lists_none')} /></div>`;

  const lists = shownLists(s);
  const index = Math.max(0, lists.findIndex((l) => l.id === chosen) >= 0
    ? lists.findIndex((l) => l.id === chosen)
    : lists.findIndex((l) => l.type === 'TO_DO' || members(l.id).some((id) => m.lists.find((x) => x.id === id)?.type === 'TO_DO')));
  const list = lists[index];
  const go = (to) => {
    if (to < 0 || to >= lists.length || to === index) return;
    setDirection(to > index ? 1 : -1);
    setChosen(lists[to].id);
  };
  return html`
    <div class="lists-page">
      ${/* A few lists fit side by side; more are chosen from a sheet. */ ''}
      ${lists.length <= 3
        ? html`<${Segments} names=${lists.map((l) => [listName(l), active(l)])} selected=${index} onSelect=${go} />`
        : html`<${ListPicker} names=${lists.map((l) => [listName(l), active(l)])} selected=${index} onSelect=${go} />`}
      <${Swipe} onNext=${() => go(index + 1)} onPrevious=${() => go(index - 1)} onPull=${reloadLists} loading=${m.loading}>
        <div class=${`list-content ${direction > 0 ? 'from-right' : direction < 0 ? 'from-left' : ''}`} key=${list.id}>
          <${ListContent} m=${m} list=${list} />
        </div>
      <//>
    </div>`;
}

/** A segmented control with a pill that slides under the chosen segment. */
function Segments({ names, selected, onSelect }) {
  const track = useRef(null);
  const [pill, setPill] = useState(null);
  useEffect(() => {
    const measure = () => {
      const node = track.current?.children[selected + 1];
      if (node) setPill({ left: node.offsetLeft, width: node.offsetWidth });
      node?.scrollIntoView?.({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
    };
    measure();
    const observer = new ResizeObserver(measure);
    if (track.current) observer.observe(track.current);
    return () => observer.disconnect();
  }, [selected, names.map((n) => n.join()).join('|')]);
  // A few lists share the width; more scroll sideways.
  return html`
    <div class="segments-wrap">
      <div class=${`segments ${names.length <= 3 ? 'fit' : ''}`} ref=${track} role="tablist">
        <span class="segment-pill" style=${pill ? `transform:translateX(${pill.left}px);width:${pill.width}px` : 'opacity:0'}></span>
        ${names.map(([name, count], i) => html`
          <button key=${i} class=${`segment ${i === selected ? 'selected' : ''}`} role="tab" aria-selected=${i === selected} onClick=${() => onSelect(i)}>
            <span class="ellipsis">${name}</span>
            ${count > 0 && html`<span class="segment-count">${count}</span>`}
          </button>`)}
      </div>
    </div>`;
}

/** The list shown, with its place among the others: a tap opens all of them to choose one. */
function ListPicker({ names, selected, onSelect }) {
  const [open, setOpen] = useState(false);
  const [name, count] = names[selected];
  return html`
    <div class="segments-wrap">
      <button class="list-picker" aria-haspopup="dialog" onClick=${() => setOpen(true)}>
        <span class="list-picker-icon"><${Icon} name="checklist" /></span>
        <span class="title-medium ellipsis grow" key=${selected}>${name}</span>
        ${count > 0 && html`<span class="segment-count">${count}</span>`}
        <span class="label-medium faint">${str('lists_position', selected + 1, names.length)}</span>
        <${Icon} name="unfold_more" />
      </button>
    </div>
    ${open && html`<${Details} onDismiss=${() => setOpen(false)}>
      <div class="title-large" style="padding:8px 16px 12px">${str('lists_choose')}</div>
      <div class="list-choices">${names.map(([n, c], i) => html`
        <button key=${i} class=${`list-choice ${i === selected ? 'selected' : ''}`} style=${`--i:${Math.min(i, 12)}`} onClick=${() => {
          onSelect(i);
          setOpen(false);
        }}>
          <${Icon} name=${i === selected ? 'check_circle' : 'format_list_bulleted'} filled=${i === selected} />
          <span class="body-large ellipsis grow">${n}</span>
          ${c > 0 && html`<span class="segment-count">${c}</span>`}
        </button>`)}
      </div>
    <//>`}`;
}

/** The list scrolls up and down; a swipe sideways moves to the next list, a pull from the top reads them again. */
function Swipe({ onNext, onPrevious, onPull, loading, children }) {
  const ref = useRef(null);
  const start = useRef(null);
  const [pull, setPull] = useState(0);
  const onTouchStart = (e) => {
    const t = e.touches[0];
    start.current = { x: t.clientX, y: t.clientY, top: ref.current.scrollTop <= 0, axis: null };
  };
  const onTouchMove = (e) => {
    const s = start.current;
    if (!s) return;
    const t = e.touches[0];
    const dx = t.clientX - s.x;
    const dy = t.clientY - s.y;
    if (!s.axis && Math.hypot(dx, dy) > 10) s.axis = Math.abs(dx) > Math.abs(dy) * 1.4 ? 'x' : 'y';
    if (s.axis === 'y' && s.top) setPull(Math.max(0, Math.min(120, dy)));
    s.dx = dx;
  };
  const onTouchEnd = () => {
    const s = start.current;
    start.current = null;
    if (s?.axis === 'x' && Math.abs(s.dx) > 60) (s.dx < 0 ? onNext : onPrevious)();
    if (pull > 70) onPull();
    setPull(0);
  };
  const shown = loading || pull > 10;
  return html`
    <div class="scroll lists-scroll" ref=${ref} onTouchStart=${onTouchStart} onTouchMove=${onTouchMove} onTouchEnd=${onTouchEnd}>
      ${shown && html`<div class="pull"><div class="ball" style=${`transform:translateY(${loading ? 0 : pull / 2 - 20}px) rotate(${pull * 3}deg)`}>
        ${loading ? html`<${Spinner} />` : html`<${Icon} name="refresh" />`}</div></div>`}
      ${children}
    </div>`;
}

/** One list: how far along it is, the field to add to it, the items to do, then those done. */
function ListContent({ m, list }) {
  const [showDone, setShowDone] = useState(false);
  const [clearing, setClearing] = useState(false);
  const items = itemsOf(list.id);
  const kept = (items ?? []).filter((it) => !m.removing.includes(it.id));
  // An item being ticked already sits where it will be.
  const done = (it) => (m.pending.includes(it.id) ? !it.completed : it.completed);
  const todo = kept.filter((it) => !done(it));
  const finished = kept.filter(done);
  const total = todo.length + finished.length;
  return html`
    <div class="list-header">
      <div class="headline-medium">${listName(list)}</div>
      ${items && html`
        <div class="body-medium faint" style="margin-top:2px">${str('list_summary', todo.length, finished.length)}</div>
        ${total > 0 && html`<div class="list-progress"><span style=${`width:${(finished.length / total) * 100}%`}></span></div>`}`}
    </div>
    <${AddField} onAdd=${(name) => add(list.id, name)} />
    ${items && !todo.length && html`<${EmptyState} title=${str('list_empty')} text=${str('list_empty_text')} />`}
    <div class="item-group">${todo.map((it) => html`<${ItemRow} key=${it.id} item=${it} m=${m} list=${list} />`)}</div>
    ${finished.length > 0 && html`
      <button class=${`done-header ${showDone ? 'open' : ''}`} onClick=${() => setShowDone(!showDone)}
        aria-label=${str(showDone ? 'list_done_hide' : 'list_done_show', finished.length)}>
        <span class="title-small primary-text">${str('list_done')}</span>
        <span class="title-small faint grow" style="white-space:pre">${` · ${finished.length}`}</span>
        <span onClick=${(e) => e.stopPropagation()}>
          <${IconButton} icon="delete_sweep" cls="faint" label=${str('list_clear_done')} onClick=${() => setClearing(true)} />
        </span>
        <${Icon} name="expand_more" cls="chevron faint" />
      </button>
      ${clearing && html`<${ConfirmDialog} title=${str('list_clear_done_title')} text=${str('list_clear_done_text', finished.length)}
        confirm=${str('list_clear_done')} onConfirm=${() => clearDone(list.id)} onDismiss=${() => setClearing(false)} />`}
      ${showDone && html`<div class="item-group appear-down">${finished.map((it) => html`<${ItemRow} key=${it.id} item=${it} m=${m} list=${list} />`)}</div>`}`}
    <div class="body-small faint lists-footnote">${str(list.type === store.LIST_NOTION ? 'lists_notion_text' : 'lists_text')}</div>`;
}

/** The field at the top of a list, with its button that comes up once there is something to add. */
function AddField({ onAdd }) {
  const [typed, setTyped] = useState('');
  const ready = typed.trim().length > 0;
  const submit = () => {
    if (!ready) return;
    onAdd(typed.trim());
    setTyped('');
  };
  return html`
    <div class="add-field">
      <${Icon} name="add" cls="faint" />
      <input type="text" enterkeyhint="done" autocomplete="off" placeholder=${str('list_add_hint')} value=${typed}
        onInput=${(e) => setTyped(e.target.value)} onKeyDown=${(e) => e.key === 'Enter' && (e.preventDefault(), submit())} />
      <button class=${`add-send ${ready ? 'ready' : ''}`} aria-label=${str('list_add')} tabindex=${ready ? 0 : -1} onClick=${submit}>
        <${Icon} name="check" />
      </button>
    </div>`;
}

/** An item: the circle ticks it, a touch elsewhere edits it, the cross deletes it. Swiping sideways moves to the next list. */
function ItemRow({ item, m, list }) {
  const [editing, setEditing] = useState(false);
  const adding = item.id.startsWith(ADDING);
  const busy = m.pending.includes(item.id) || adding;
  const completed = m.pending.includes(item.id) ? !item.completed : item.completed;
  const edit = () => !adding && setEditing(true);
  return html`
    <div class=${`item-row ${completed ? 'checked' : ''} ${adding ? 'adding' : ''}`}
      role="button" tabindex="0" aria-label=${`${str('list_item_edit')}: ${item.name}`} onClick=${edit}
      onKeyDown=${(e) => e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), edit())}>
      <button class="check-hit" aria-label=${str('list_toggle')} aria-pressed=${completed} disabled=${busy}
        onClick=${(e) => {
          e.stopPropagation();
          toggle(list.id, item);
        }}><span class="check-circle"><${Icon} name="check" /></span></button>
      <span class="item-name body-large">${item.name}</span>
      <span onClick=${(e) => e.stopPropagation()}>
        <${IconButton} icon="close" cls="faint" label=${str('list_delete')} disabled=${busy} onClick=${() => remove(list.id, item)} />
      </span>
    </div>
    ${editing && html`<${ItemEditDialog} name=${item.name} onSave=${(name) => renameItem(list.id, item, name)} onDismiss=${() => setEditing(false)} />`}`;
}

/** The name of an item, to change. */
function ItemEditDialog({ name, onSave, onDismiss }) {
  const [typed, setTyped] = useState(name);
  const ready = typed.trim().length > 0;
  const save = () => {
    if (!ready) return;
    // A name stays on one line: the newlines typed in the bigger field become spaces.
    onSave(typed.replace(/\s*\n\s*/g, ' ').trim());
    onDismiss();
  };
  return html`<${Dialog} title=${str('list_item_edit')} onDismiss=${onDismiss} actions=${html`
    <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
    <button class="text-btn" disabled=${!ready} onClick=${save}>${str('save')}</button>`}>
    ${/* Long names wrap in the field, so that all of the text shows. */ ''}
    <${Field} label=${str('list_item_name')} value=${typed} onInput=${setTyped} multiline autofocus />
  <//>`;
}

function EmptyState({ title, text }) {
  return html`
    <div class="list-empty">
      <span class="list-empty-icon"><${Icon} name="task_alt" /></span>
      <div class="title-medium" style="margin-top:16px">${title}</div>
      ${text && html`<div class="body-medium faint" style="margin-top:4px">${text}</div>`}
    </div>`;
}

/**
 * The names the lists have in the app and the lists joined into one: Alexa and Notion keep theirs
 * (ui/Lists.kt). A tap renames a list, the button joins a few, a joined list can be parted again.
 */
export function ListsEditDialog({ s, onDismiss }) {
  const m = useModel();
  const [renaming, setRenaming] = useState(null);
  const [merging, setMerging] = useState(null);
  const views = s.listViews;
  const source = m.lists ?? [];
  const shown = shownLists(s) ?? [];
  if (renaming) {
    return html`<${RenameDialog} list=${renaming} given=${views.names?.[renaming.id] != null}
      onSave=${(name) => {
        store.setListViews(rename(views, renaming.id, name));
        setRenaming(null);
      }} onDismiss=${() => setRenaming(null)} />`;
  }
  if (merging) {
    return html`<${Dialog} title=${str('list_merge')} onDismiss=${() => setMerging(null)} actions=${html`
      <button class="text-btn" onClick=${() => setMerging(null)}>${str('cancel')}</button>
      <button class="text-btn" disabled=${merging.length < 2} onClick=${() => {
        store.setListViews(merge(views, merging));
        setMerging(null);
      }}>${str('list_merge')}</button>`}>
      <p class="body-medium faint">${str('list_merge_text')}</p>
      ${source.map((list) => html`<label class="row" style="padding:8px 0" key=${list.id}>
        <input type="checkbox" checked=${merging.includes(list.id)}
          onChange=${(e) => setMerging(e.target.checked ? [...merging, list.id] : merging.filter((id) => id !== list.id))} />
        <span class="grow" style="padding-left:12px">${listName(list)}</span>
        ${merging[0] === list.id && html`<${Icon} name="star" cls="faint" />`}
      </label>`)}
    <//>`;
  }
  return html`<${Dialog} title=${str('lists_edit')} onDismiss=${onDismiss} flush
    actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('close')}</button>`}>
    <p class="body-medium faint" style="padding:0 16px">${str('lists_edit_text')}</p>
    ${shown.map((list, i) => html`<${Setting} key=${list.id} title=${listName(list)}
      text=${list.type === MERGED && views.names?.[list.id] ? members(list.id).map((id) => source.find((l) => l.id === id)).filter(Boolean).map(listName).join(' + ') : null}
      onClick=${() => setRenaming(list)} trailing=${html`
        ${list.type === MERGED && html`<${IconButton} icon="call_split" label=${str('list_split')}
          onClick=${(e) => {
            e.stopPropagation();
            store.setListViews(split(views, list.id));
          }} />`}
        ${/* The order of the pages of the lists, the first one shown first. */ ''}
        <${IconButton} icon="keyboard_arrow_up" label=${str('list_move_up')} disabled=${i === 0}
          onClick=${(e) => {
            e.stopPropagation();
            store.setListViews(move(views, shown.map((l) => l.id), i, i - 1));
          }} />
        <${IconButton} icon="keyboard_arrow_down" label=${str('list_move_down')} disabled=${i === shown.length - 1}
          onClick=${(e) => {
            e.stopPropagation();
            store.setListViews(move(views, shown.map((l) => l.id), i, i + 1));
          }} />
        <${Icon} name="edit" cls="faint" />`} />`)}
    ${source.length >= 2 && html`<div style="padding:12px 16px 0">
      <button class="tonal-btn" onClick=${() => setMerging([])}><${Icon} name="merge_type" /> ${str('list_merge')}</button></div>`}
  <//>`;
}

function RenameDialog({ list, given, onSave, onDismiss }) {
  const [name, setName] = useState(listName(list));
  return html`<${Dialog} title=${str('list_rename')} onDismiss=${onDismiss} actions=${html`
    ${given && html`<button class="text-btn" onClick=${() => onSave('')}>${str('list_name_reset')}</button>`}
    <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
    <button class="text-btn" onClick=${() => onSave(name)}>${str('save')}</button>`}>
    <${Field} label=${str('list_rename_hint')} value=${name} onInput=${setName} onEnter=${() => onSave(name)} autofocus />
  <//>`;
}

/** What is left to do in the lists on the rest screen of the monitor: the first lists with something in them. */
export function RestLists({ s }) {
  const m = useModel();
  useEffect(() => {
    if (!m.lists) reloadLists();
  }, []);
  const shown = (shownLists(s) ?? [])
    .map((list) => [list, (itemsOf(list.id) ?? []).filter((it) => !it.completed)])
    .filter(([, items]) => items.length)
    .slice(0, 3);
  if (!shown.length) return null;
  return html`<div class="rest-lists">${shown.map(([list, items]) => html`<div key=${list.id}>
    <div class="title-medium" style="opacity:.7">${listName(list)}</div>
    ${items.slice(0, 4).map((it) => html`<div class="body-large ellipsis" key=${it.id}>· ${it.name}</div>`)}
    ${items.length > 4 && html`<div class="body-medium" style="opacity:.6">+${items.length - 4}</div>`}
  </div>`)}</div>`;
}
