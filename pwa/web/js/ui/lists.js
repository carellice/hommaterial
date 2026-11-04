// The page of the lists of Alexa, as in its app and on the Echo devices (ui/Lists.kt): a segmented
// control to choose the list, the list under it, and a swipe sideways to move to the next one.
import { html, useState, useEffect, useRef } from '../lib.js';
import { Icon, IconButton, Spinner } from './common.js';
import * as store from '../store.js';
import { str } from '/shared/i18n.js';

/** What a list is called: the two of Alexa by their name in the app, the others by the one the user gave them. */
function listName(list) {
  if (list.type === 'TO_DO' && !list.name) return str('list_todo');
  if (list.type === 'SHOPPING_LIST' && !list.name) return str('list_shopping');
  return list.name || list.type;
}

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

function add(listId, name) {
  const item = { id: `${ADDING}${added++}`, name, completed: false, version: 0 };
  set({ items: { ...model.items, [listId]: [item, ...(model.items[listId] ?? [])] } });
  store.addAlexaListItem(listId, name).then(async () => {
    await refresh(listId);
    // What Alexa did not take goes away with the list read again; if that failed too, now.
    set({ items: { ...model.items, [listId]: (model.items[listId] ?? []).filter((it) => it.id !== item.id) } });
  });
}

async function toggle(listId, item) {
  if (item.id.startsWith(ADDING) || model.pending.includes(item.id)) return;
  set({ pending: [...model.pending, item.id] });
  await store.setAlexaListItemDone(listId, item, !item.completed);
  await refresh(listId);
  set({ pending: model.pending.filter((id) => id !== item.id) });
}

async function remove(listId, item) {
  if (item.id.startsWith(ADDING)) return;
  set({ removing: [...model.removing, item.id] });
  await store.deleteAlexaListItem(listId, item);
  await refresh(listId);
  set({ removing: model.removing.filter((id) => id !== item.id) });
}

/** How many items are still to do: counted from those read, or as Alexa last said. */
const active = (list) => model.items[list.id]?.filter((it) => !it.completed).length ?? list.active;

export function ListsPage() {
  const m = useModel();
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

  const index = Math.max(0, m.lists.findIndex((l) => l.id === chosen) >= 0
    ? m.lists.findIndex((l) => l.id === chosen)
    : m.lists.findIndex((l) => l.type === 'TO_DO'));
  const list = m.lists[index];
  const go = (to) => {
    if (to < 0 || to >= m.lists.length || to === index) return;
    setDirection(to > index ? 1 : -1);
    setChosen(m.lists[to].id);
  };
  return html`
    <div class="lists-page">
      <${Segments} names=${m.lists.map((l) => [listName(l), active(l)])} selected=${index} onSelect=${go} />
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
  const items = m.items[list.id];
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
        <${Icon} name="expand_more" cls="chevron faint" />
      </button>
      ${showDone && html`<div class="item-group appear-down">${finished.map((it) => html`<${ItemRow} key=${it.id} item=${it} m=${m} list=${list} />`)}</div>`}`}
    <div class="body-small faint lists-footnote">${str('lists_text')}</div>`;
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

/** An item: a touch ticks it, the cross deletes it. Swiping sideways moves to the next list. */
function ItemRow({ item, m, list }) {
  const adding = item.id.startsWith(ADDING);
  const busy = m.pending.includes(item.id) || adding;
  const completed = m.pending.includes(item.id) ? !item.completed : item.completed;
  return html`
    <div class=${`item-row ${completed ? 'checked' : ''} ${adding ? 'adding' : ''}`}
      role="button" tabindex="0" onClick=${() => !busy && toggle(list.id, item)}
      onKeyDown=${(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), !busy && toggle(list.id, item))}>
      <span class="check-circle"><${Icon} name="check" /></span>
      <span class="item-name body-large">${item.name}</span>
      <span onClick=${(e) => e.stopPropagation()}>
        <${IconButton} icon="close" cls="faint" label=${str('list_delete')} disabled=${busy} onClick=${() => remove(list.id, item)} />
      </span>
    </div>`;
}

function EmptyState({ title, text }) {
  return html`
    <div class="list-empty">
      <span class="list-empty-icon"><${Icon} name="task_alt" /></span>
      <div class="title-medium" style="margin-top:16px">${title}</div>
      ${text && html`<div class="body-medium faint" style="margin-top:4px">${text}</div>`}
    </div>`;
}
