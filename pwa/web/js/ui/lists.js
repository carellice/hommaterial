// The lists of Alexa, as in its app and on the Echo devices: asked to Alexa through the server as
// the screen opens and after every change, never kept (ui/Lists.kt).
import { html, useState, useEffect } from '../lib.js';
import { Icon, IconButton, FullDialog, Field, Spinner, Chips } from './common.js';
import * as store from '../store.js';
import { str } from '/shared/i18n.js';

/** What a list is called: the two of Alexa by their name in the app, the others by the one the user gave them. */
function listName(list) {
  if (list.type === 'TO_DO' && !list.name) return str('list_todo');
  if (list.type === 'SHOPPING_LIST' && !list.name) return str('list_shopping');
  return list.name || list.type;
}

export function ListsDialog({ onDismiss }) {
  const [lists, setLists] = useState(null);
  const [chosen, setChosen] = useState(null);
  const [items, setItems] = useState(null);
  const [loading, setLoading] = useState(true);
  const [typed, setTyped] = useState('');
  const [showDone, setShowDone] = useState(false);
  // Items being ticked, shown at once as they will be, and those being deleted, gone at once.
  const [pending, setPending] = useState([]);
  const [removing, setRemoving] = useState([]);

  const reload = async (keep = chosen) => {
    setLoading(true);
    const found = await store.alexaLists();
    if (found) {
      setLists(found);
      const current = found.find((l) => l.id === keep) ?? found.find((l) => l.type === 'TO_DO') ?? found[0];
      setChosen(current?.id ?? null);
      setItems(current ? (await store.alexaListItems(current.id)) ?? [] : []);
    }
    setLoading(false);
  };
  useEffect(() => {
    reload(null);
  }, []);

  const choose = async (id) => {
    if (id === chosen) return;
    setChosen(id);
    setItems(null);
    setLoading(true);
    setItems((await store.alexaListItems(id)) ?? []);
    setLoading(false);
  };

  const add = async () => {
    const name = typed.trim();
    if (!name || !chosen) return;
    setTyped('');
    if (await store.addAlexaListItem(chosen, name)) setItems((await store.alexaListItems(chosen)) ?? items);
  };

  const change = async (item, remove) => {
    if (remove) setRemoving((r) => [...r, item.id]);
    else setPending((p) => [...p, item.id]);
    if (remove) await store.deleteAlexaListItem(chosen, item);
    else await store.setAlexaListItemDone(chosen, item, !item.completed);
    const fresh = await store.alexaListItems(chosen);
    if (fresh) setItems(fresh);
    setPending((p) => p.filter((id) => id !== item.id));
    setRemoving((r) => r.filter((id) => id !== item.id));
  };

  const row = (item) => {
    const busy = pending.includes(item.id);
    const completed = busy ? !item.completed : item.completed;
    return html`
      <div key=${item.id} class="list-item clickable list-row" role="button" tabindex="0" onClick=${() => !busy && change(item, false)}>
        <span class="lead"><${Icon} name=${completed ? 'check_box' : 'check_box_outline_blank'} cls=${completed ? 'primary' : ''} /></span>
        <span class="texts"><span class=${`body-large ${completed ? 'done' : ''}`}>${item.name}</span></span>
        <span class="trail" onClick=${(e) => e.stopPropagation()}>
          <${IconButton} icon="close" cls="faint" label=${str('list_delete')} disabled=${busy} onClick=${() => change(item, true)} />
        </span>
      </div>`;
  };

  const kept = (items ?? []).filter((it) => !removing.includes(it.id));
  const todo = kept.filter((it) => !it.completed);
  const done = kept.filter((it) => it.completed);
  return html`
    <${FullDialog} title=${str('s_lists')} onDismiss=${onDismiss} actions=${loading
      ? html`<span style="padding:12px"><${Spinner} /></span>`
      : html`<${IconButton} icon="refresh" label=${str('refresh')} onClick=${() => reload()} />`}>
      <div class="body-small faint" style="margin-bottom:12px">${str('lists_text')}</div>
      ${lists && !lists.length && html`<div class="body-medium">${str('lists_none')}</div>`}
      ${lists?.length > 0 && html`
        <${Chips} choices=${lists.map((l) => [l.id, listName(l) + (l.active != null ? ` · ${l.active}` : '')])}
          selected=${chosen} onSelect=${choose} />
        <div class="row gap" style="align-items:flex-end;margin:8px 0 12px">
          <div class="grow"><${Field} label=${str('list_add_hint')} value=${typed} onInput=${setTyped} onEnter=${add} /></div>
          <${IconButton} icon="add" cls="tonal" label=${str('list_add')} disabled=${!typed.trim() || !chosen} onClick=${add} />
        </div>`}
      ${items && html`
        <div class="card">
          ${!todo.length && html`<div class="body-medium faint" style="padding:16px">${str('list_empty')}</div>`}
          ${todo.map(row)}
        </div>
        ${done.length > 0 && html`
          <button class="text-btn" style="margin-top:8px" onClick=${() => setShowDone(!showDone)}>
            ${str(showDone ? 'list_done_hide' : 'list_done_show', done.length)}</button>
          ${showDone && html`<div class="card">${done.map(row)}</div>`}`}`}
    <//>`;
}
