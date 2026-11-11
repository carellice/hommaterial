// The notes of Notion, a page of the bottom bar once some are chosen (ui/Notes.kt), and Notion in
// the settings: the guide to make the integration, then what of it this device shows.
import { html, useState, useEffect } from '../lib.js';
import { Icon, IconButton, Spinner, Setting, Field, Dialog, FullDialog } from './common.js';
import * as store from '../store.js';
import { str } from '/shared/i18n.js';

const NOTION_INTEGRATIONS = 'https://www.notion.so/profile/integrations';

/** The notes, kept while the app is open: read when the page first opens and on the button of the top bar. */
const model = { notes: null, loading: false, failed: false, read: null };
const listeners = new Set();
const set = (change) => {
  Object.assign(model, change);
  listeners.forEach((l) => l());
};

export async function reloadNotes() {
  if (model.loading) return;
  set({ loading: true });
  const found = await store.notionNotes();
  set({ loading: false, failed: !found, ...(found ? { notes: found } : {}) });
}

function useModel() {
  const [, tick] = useState(0);
  useEffect(() => {
    const listener = () => tick((n) => n + 1);
    listeners.add(listener);
    return () => listeners.delete(listener);
  }, []);
  return model;
}

const edited = (time) => new Date(time).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** The notes, the latest changed first; one opens on a touch, to be read. */
export function NotesPage({ s }) {
  const m = useModel();
  const [open, setOpen] = useState(null);
  const chosen = JSON.stringify(s.notionNotes);
  useEffect(() => {
    if (m.read !== chosen) {
      model.read = chosen;
      reloadNotes();
    }
  }, [chosen]);
  if (!m.notes) {
    return html`<div class="lists-center">${m.failed && !m.loading
      ? html`<button class="tonal-btn" onClick=${reloadNotes}>${str('lists_retry')}</button>`
      : html`<${Spinner} big />`}</div>`;
  }
  return html`
    <div class="notes-page">
      ${!m.notes.length && html`
        <div class="list-empty">
          <span class="list-empty-icon"><${Icon} name="description" /></span>
          <div class="title-medium" style="margin-top:16px">${str('notes_none')}</div>
          <div class="body-medium faint" style="margin-top:4px">${str('notes_none_text')}</div>
        </div>`}
      ${m.notes.map((note) => html`
        <div key=${note.id} class="note-card" role="button" tabindex="0" onClick=${() => setOpen(note)}
          onKeyDown=${(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), setOpen(note))}>
          <span class="note-icon"><${Icon} name="description" /></span>
          <span class="texts"><span class="body-large">${note.title}</span>
            ${note.edited > 0 && html`<span class="supporting body-medium">${str('notes_edited', edited(note.edited))}</span>`}</span>
        </div>`)}
      ${open && html`<${NoteView} note=${open} ai=${s.assistant.configured} onDismiss=${() => setOpen(null)} />`}
    </div>`;
}

/** The types of Notion whose text a note can change, the same as the server's (server/src/notion.ts). */
const EDITABLE_BLOCKS = new Set([
  'heading_1', 'heading_2', 'heading_3', 'paragraph', 'toggle', 'callout',
  'bulleted_list_item', 'numbered_list_item', 'to_do', 'quote', 'code',
]);
const editable = (b) => !!b.id && EDITABLE_BLOCKS.has(b.type);

/**
 * One note, read from Notion as it is now: a touch on a piece of text changes it, a touch on a box
 * ticks it, the button at the end adds text. Each change goes to Notion at once and the note is read again.
 */
function NoteView({ note, ai, onDismiss }) {
  const [blocks, setBlocks] = useState(null);
  const [failed, setFailed] = useState(false);
  const [editing, setEditing] = useState(null);
  const [adding, setAdding] = useState(false);
  const read = () => store.notionNote(note.id).then((found) => (found ? (setBlocks(found), setFailed(false)) : setFailed(true)));
  useEffect(() => { read(); }, [note.id]);
  const tick = async (block) => {
    setBlocks(blocks.map((it) => (it.id === block.id ? { ...it, checked: !block.checked } : it)));
    if (!(await store.tickNoteBlock(block, !block.checked))) read();
  };
  // A piece of text opens to be changed on a touch, or on Enter.
  const edit = (b) => (editable(b)
    ? { role: 'button', tabindex: 0, onClick: () => setEditing(b),
      onKeyDown: (e) => e.key === 'Enter' && (e.preventDefault(), setEditing(b)) }
    : {});
  const addButton = html`<button class="tonal-btn" style="margin-top:16px" onClick=${() => setAdding(true)}>${str('notes_add')}</button>`;
  let number = 0;
  return html`
    <${FullDialog} title=${note.title} onDismiss=${onDismiss} closeLabel=${str('close')}
      actions=${html`${ai && blocks?.length > 0 && html`<${IconButton} icon="summarize" label=${str('ai_summary')}
        onClick=${() => store.summarizeNote(note.title, blocks.map((b) => b.text).join(' / '))} />`}
      ${note.url && html`<a class="icon-btn" href=${note.url} target="_blank" rel="noopener" aria-label=${str('notes_open_notion')}
        title=${str('notes_open_notion')}><${Icon} name="open_in_new" /></a>`}`}>
      ${!blocks && !failed && html`<div style="padding:32px;text-align:center"><${Spinner} big /></div>`}
      ${failed && html`<p class="body-large faint" style="padding:32px;text-align:center">${str('notes_failed')}</p>`}
      ${blocks && !blocks.length && html`<div style="padding:32px;text-align:center">
        <p class="body-large faint">${str('notes_empty')}</p>${addButton}</div>`}
      ${blocks && html`<div class="note-text">${blocks.map((b, i) => {
        number = b.kind === 'number' ? number + 1 : 0;
        const indent = `margin-left:${b.depth * 20}px`;
        if (b.kind === 'h1') return html`<h2 key=${i} class="headline-small" style=${indent} ...${edit(b)}>${b.text}</h2>`;
        if (b.kind === 'h2') return html`<h3 key=${i} class="title-large" style=${indent} ...${edit(b)}>${b.text}</h3>`;
        if (b.kind === 'h3') return html`<h4 key=${i} class="title-medium" style=${indent} ...${edit(b)}>${b.text}</h4>`;
        if (b.kind === 'divider') return html`<hr key=${i} />`;
        if (b.kind === 'bullet' || b.kind === 'number') {
          return html`<div key=${i} class="note-list body-large" style=${indent}><span>${b.kind === 'bullet' ? '•' : `${number}.`}</span><span ...${edit(b)}>${b.text}</span></div>`;
        }
        if (b.kind === 'todo') {
          return html`<div key=${i} class=${`note-list body-large ${b.checked ? 'checked' : ''}`} style=${indent}>
            ${editable(b)
              ? html`<button class="note-tick" role="checkbox" aria-checked=${b.checked} aria-label=${b.text} onClick=${() => tick(b)}>
                  <${Icon} name=${b.checked ? 'check_box' : 'check_box_outline_blank'} /></button>`
              : html`<${Icon} name=${b.checked ? 'check_box' : 'check_box_outline_blank'} />`}<span ...${edit(b)}>${b.text}</span></div>`;
        }
        if (b.kind === 'quote') return html`<blockquote key=${i} class="body-large" style=${indent} ...${edit(b)}>${b.text}</blockquote>`;
        if (b.kind === 'code') return html`<pre key=${i} class="body-medium" style=${indent} ...${edit(b)}>${b.text}</pre>`;
        return b.text.trim() ? html`<p key=${i} class="body-large" style=${indent} ...${edit(b)}>${b.text}</p>` : null;
      })}
      ${blocks.length > 0 && blocks.every((b) => b.id) && addButton}</div>`}
      ${editing && html`<${NoteTextDialog} title=${str('notes_block_edit')} text=${editing.text} hint=${str('notes_block_hint')}
        onDismiss=${() => setEditing(null)} onSave=${async (text) => { if (await store.editNoteBlock(editing, text)) read(); }} />`}
      ${adding && html`<${NoteTextDialog} title=${str('notes_add')} text="" onDismiss=${() => setAdding(false)}
        onSave=${async (text) => { if (text && await store.addToNote(note.id, text)) read(); }} />`}
    <//>`;
}

/** A piece of text to change, or to add when [text] is empty; [hint] says what an empty text does. */
function NoteTextDialog({ title, text, hint, onSave, onDismiss }) {
  const [typed, setTyped] = useState(text);
  const changed = typed.trim() !== text.trim();
  const save = () => {
    if (!changed) return;
    onSave(typed.trim());
    onDismiss();
  };
  return html`<${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
    <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
    <button class="text-btn" disabled=${!changed} onClick=${save}>${str('save')}</button>`}>
    <${Field} label=${str('notes_block_text')} value=${typed} onInput=${setTyped} support=${hint} multiline autofocus />
  <//>`;
}

/**
 * Notion in the settings: the guide to make the integration and give its secret; once linked, the
 * pages and databases shown as lists and as notes on this device.
 */
export function NotionSettings({ s }) {
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [picking, setPicking] = useState(null);
  if (!s.notion.linked) {
    const step = (n, link) => html`
      <div class="cal-step">
        <div class="texts"><div class="title-small">${str(`notion_step${n}_title`)}</div><div class="body-medium supporting">${str(`notion_step${n}_text`)}</div></div>
        ${link && html`<a class="text-btn" href=${link} target="_blank" rel="noopener">${str('gcal_open')}</a>`}
      </div>`;
    return html`
      <div class="cal-setup">
        <p class="body-medium supporting">${str('notion_intro')}</p>
        ${step(1, NOTION_INTEGRATIONS)}
        ${step(2)}
        ${step(3)}
        <${Field} label=${str('notion_secret')} type="password" value=${token} error=${error}
          onInput=${(v) => { setToken(v.trim()); setError(null); }} />
        <button class="tonal-btn" disabled=${!token || busy} onClick=${async () => {
          setBusy(true);
          setError(await store.connectNotion(token));
          setBusy(false);
        }}>${str('notion_link')}</button>
      </div>`;
  }
  const names = (sources) => sources.map((it) => it.name).join(', ') || str('notion_none_chosen');
  return html`
    <${Setting} title=${str('notion_lists')} text=${names(s.notionLists)} onClick=${() => setPicking('lists')} />
    <${Setting} title=${str('notion_notes')} text=${names(s.notionNotes)} onClick=${() => setPicking('notes')} />
    <${Setting} title=${str('notion_unlink')} text=${s.notion.workspace ? str('notion_linked', s.notion.workspace) : null}
      onClick=${store.disconnectNotion} />
    ${picking && html`<${SourcesDialog} title=${str(picking === 'lists' ? 'notion_lists' : 'notion_notes')}
      chosen=${picking === 'lists' ? s.notionLists : s.notionNotes} onDismiss=${() => setPicking(null)}
      onDone=${(sources) => {
        if (picking === 'lists') store.setNotionLists(sources);
        else store.setNotionNotes(sources);
        setPicking(null);
      }} />`}`;
}

/** The pages and databases shared with the integration, ticked when chosen. */
function SourcesDialog({ title, chosen, onDone, onDismiss }) {
  const [sources, setSources] = useState(null);
  const [failed, setFailed] = useState(false);
  const [ticked, setTicked] = useState(chosen.map((it) => it.id));
  useEffect(() => {
    store.notionSources().then((found) => (found ? setSources(found) : setFailed(true)));
  }, []);
  const toggle = (id) => setTicked(ticked.includes(id) ? ticked.filter((it) => it !== id) : [...ticked, id]);
  const save = () => {
    // What could not be read again is kept as it was chosen.
    const all = sources ?? chosen;
    onDone([...all.filter((it) => ticked.includes(it.id)), ...chosen.filter((old) => ticked.includes(old.id) && !all.some((it) => it.id === old.id))]);
  };
  return html`
    <${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" onClick=${save}>${str('save')}</button>`}>
      ${!sources && !failed && html`<div style="padding:16px;text-align:center"><${Spinner} /></div>`}
      ${failed && html`<p class="body-medium">${str('notes_failed')}</p>`}
      ${sources && !sources.length && html`<p class="body-medium supporting">${str('notion_none_shared')}</p>`}
      ${(sources ?? []).map((it) => html`
        <${Setting} key=${it.id} title=${it.name} text=${str(it.kind === 'database' ? 'notion_database' : 'notion_page')}
          onClick=${() => toggle(it.id)}
          trailing=${html`<input type="checkbox" class="checkbox" checked=${ticked.includes(it.id)} onChange=${() => toggle(it.id)} aria-label=${it.name} />`} />`)}
    <//>`;
}
