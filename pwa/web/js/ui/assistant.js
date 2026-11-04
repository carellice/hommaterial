// The assistant of Workers AI, which puts right the names in spoken commands: its guided set-up,
// its questions about the home and what it was taught (Assistant.kt). It runs on the server.
import { html, useState, useEffect } from '../lib.js';
import { IconButton, Setting, Dialog, ConfirmDialog, Field, Spinner, Chips } from './common.js';
import * as store from '../store.js';
import { str, plural } from '/shared/i18n.js';
import { macroKey, remoteKey, placed, shownRemotes } from '/shared/model.js';

const SIGN_UP_URL = 'https://dash.cloudflare.com/sign-up';
const WORKERS_AI_URL = 'https://dash.cloudflare.com/?to=/:account/ai/workers-ai';
const ACCOUNT_ID = /^[0-9a-fA-F]{32}$/;
const MODELS = [['@cf/meta/llama-3.3-70b-instruct-fp8-fast', 'assistant_model_big'], ['@cf/meta/llama-3.1-8b-instruct', 'assistant_model_small']];

const open = (url) => window.open(url, '_blank', 'noopener');

const Progress = ({ value }) => html`
  <div class="progress" role="progressbar" aria-valuenow=${Math.round(value * 100)}><span style=${`width:${value * 100}%`}></span></div>`;

/** The keys of everything the assistant can be taught about, to tell what it was never asked about. */
function teachable(s) {
  return [
    ...placed(s).filter((d) => !s.hidden.includes(d.applianceId)).map((d) => d.applianceId),
    ...s.groups.map((g) => `group:${g.id}`),
    ...s.macros.map((m) => macroKey(m.id)),
    ...shownRemotes(s).map((r) => remoteKey(r.id)),
  ];
}

/** The guided set-up: an account, its id, a token, then a request to try them. */
function SetupDialog({ s, onDismiss }) {
  const [step, setStep] = useState(0);
  const [account, setAccount] = useState(s.assistant.accountId);
  const [token, setToken] = useState('');
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState(null);
  const steps = 4;
  const validAccount = ACCOUNT_ID.test(account.trim());
  const ready = step === 1 ? validAccount : step === 2 ? token.trim() !== '' : true;
  const test = async () => {
    setTesting(true);
    setError(null);
    const failure = await store.connectAssistant(account.trim(), token.trim());
    setTesting(false);
    if (failure) setError(failure);
    else onDismiss(true);
  };
  const title = html`
    <div class="label-large" style="color:var(--primary)">${str('assistant_step', step + 1, steps)}</div>
    <div>${str(`assistant_step${step + 1}_title`)}</div>`;
  return html`
    <${Dialog} title=${title} onDismiss=${() => onDismiss(false)} actions=${html`
      <button class="text-btn" onClick=${() => (step === 0 ? onDismiss(false) : setStep(step - 1))}>${str(step === 0 ? 'cancel' : 'back')}</button>
      ${step < steps - 1
        ? html`<button class="text-btn" disabled=${!ready} onClick=${() => setStep(step + 1)}>${str('assistant_next')}</button>`
        : html`<button class="text-btn" disabled=${testing} onClick=${test}>${str('assistant_test')}</button>`}`}>
      <${Progress} value=${(step + 1) / steps} />
      ${step === 0 && html`
        <div class="body-medium">${str('assistant_step1_text')}</div>
        <button class="tonal-btn" style="margin-top:16px" onClick=${() => open(SIGN_UP_URL)}>${str('assistant_open_signup')}</button>`}
      ${step === 1 && html`
        <div class="body-medium">${str('assistant_step2_text')}</div>
        <button class="tonal-btn" style="margin:16px 0" onClick=${() => open(WORKERS_AI_URL)}>${str('assistant_open_dashboard')}</button>
        <${Field} label=${str('assistant_account_id')} value=${account} onInput=${(v) => setAccount(v.trim())}
          error=${account && !validAccount ? str('assistant_bad_account') : null} />`}
      ${step === 2 && html`
        <div class="body-medium">${str('assistant_step3_text')}</div>
        <button class="tonal-btn" style="margin:16px 0" onClick=${() => open(WORKERS_AI_URL)}>${str('assistant_open_dashboard')}</button>
        <${Field} label=${str('assistant_token')} type="password" value=${token} onInput=${(v) => setToken(v.trim())} />`}
      ${step === 3 && html`
        <div class="body-medium">${str('assistant_step4_text')}</div>
        ${testing && html`<div class="row" style="margin-top:16px;gap:12px"><${Spinner} /><span>${str('assistant_testing')}</span></div>`}
        ${error && html`<div class="body-medium" style="color:var(--error);margin-top:16px">${error}</div>`}`}
    <//>`;
}

/** The questions of the assistant about what it does not understand, one at a time. */
function TeachDialog({ onDismiss }) {
  const [teach, setTeach] = useState({ loading: true, error: null, questions: [], index: 0, learnt: 0, seen: [] });
  const [answer, setAnswer] = useState('');
  useEffect(() => {
    let alive = true;
    store.assistantQuestions().then((result) => {
      if (!alive) return;
      if (result.error) setTeach((t) => ({ ...t, loading: false, error: result.error }));
      else {
        setTeach((t) => ({ ...t, loading: false, questions: result.questions, seen: result.seen }));
        if (!result.questions.length) store.markTaught(result.seen);
      }
    });
    return () => {
      alive = false;
    };
  }, []);
  const question = teach.questions[teach.index];
  const done = !teach.loading && !teach.error && !question;
  const next = (said) => {
    const text = (said ?? '').trim();
    if (text) store.addAssistantNote(question.target, question.question, text);
    const index = teach.index + 1;
    setTeach({ ...teach, index, learnt: teach.learnt + (text ? 1 : 0) });
    setAnswer('');
    if (index >= teach.questions.length) store.markTaught(teach.seen);
  };
  const title = done ? str('assistant_teach_done_title')
    : question ? str('assistant_teach_question', teach.index + 1, teach.questions.length) : str('assistant_teach');
  return html`
    <${Dialog} title=${title} onDismiss=${onDismiss} actions=${html`
      ${question && html`<button class="text-btn" onClick=${() => next(null)}>${str('assistant_skip')}</button>`}
      ${teach.loading && html`<button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>`}
      ${question
        ? html`<button class="text-btn" disabled=${!answer.trim()} onClick=${() => next(answer)}>${str('assistant_next')}</button>`
        : !teach.loading && html`<button class="text-btn" onClick=${onDismiss}>${str('close')}</button>`}`}>
      ${teach.loading && html`<div class="row" style="gap:16px"><${Spinner} /><span>${str('assistant_teach_loading')}</span></div>`}
      ${teach.error && html`<div class="body-medium" style="color:var(--error)">${teach.error}</div>`}
      ${question && html`
        <${Progress} value=${teach.index / teach.questions.length} />
        <div class="title-medium">${question.name}</div>
        <div class="body-medium" style="margin:8px 0 12px">${question.question}</div>
        <${Field} label=${str('assistant_answer')} value=${answer} multiline autofocus onInput=${setAnswer} />`}
      ${done && html`<div class="body-medium">${teach.questions.length ? plural('assistant_learnt', teach.learnt) : str('assistant_teach_nothing')}</div>`}
    <//>`;
}

/** What the assistant was taught, to correct or forget: the home in general and each answer. */
function NotesDialog({ s, onDismiss }) {
  const [house, setHouse] = useState(s.assistant.house);
  const [notes, setNotes] = useState(s.assistant.notes);
  const nameOf = (key) => (key.startsWith('group:') ? s.groups.find((g) => `group:${g.id}` === key)?.name
    : s.devices.find((d) => d.applianceId === key)?.name
      ?? s.macros.find((m) => macroKey(m.id) === key)?.name
      ?? s.remotes.find((r) => remoteKey(r.id) === key)?.name) ?? str('macro_gone');
  return html`
    <${Dialog} title=${str('assistant_notes')} onDismiss=${onDismiss} actions=${html`
      <button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>
      <button class="text-btn" onClick=${() => {
        store.saveAssistantNotes(house, notes.filter((n) => n.answer.trim()));
        onDismiss();
      }}>${str('save')}</button>`}>
      <div style="display:flex;flex-direction:column;gap:12px">
        <${Field} label=${str('assistant_house')} support=${str('assistant_house_hint')} multiline value=${house} onInput=${setHouse} />
        ${!notes.length && html`<div class="body-medium faint">${str('assistant_notes_none')}</div>`}
        ${notes.map((note, index) => html`
          <div class="note-card">
            <div class="row"><span class="title-small grow">${nameOf(note.target)}</span>
              <${IconButton} icon="close" cls="faint" label=${str('assistant_note_delete')}
                onClick=${() => setNotes(notes.filter((_, i) => i !== index))} /></div>
            ${note.question.trim() && html`<div class="body-small faint" style="margin-bottom:8px">${note.question}</div>`}
            <${Field} value=${note.answer} multiline onInput=${(v) => setNotes(notes.map((n, i) => (i === index ? { ...n, answer: v } : n)))} />
          </div>`)}
      </div>
    <//>`;
}

/** The section of the settings: the guided set-up, then the model, the teaching and the notes. */
export function AssistantSettings({ s }) {
  const assistant = s.assistant;
  const [dialog, setDialog] = useState(null);
  const close = () => setDialog(null);
  let shown = null;
  if (dialog === 'setup') {
    // Once linked the guide is done with, and the assistant goes on to its questions by itself.
    shown = html`<${SetupDialog} s=${s} onDismiss=${(linked) => setDialog(linked && !assistant.taught.length ? 'teach' : null)} />`;
  } else if (dialog === 'teach') shown = html`<${TeachDialog} onDismiss=${close} />`;
  else if (dialog === 'notes') shown = html`<${NotesDialog} s=${s} onDismiss=${close} />`;
  else if (dialog === 'unlink') {
    shown = html`<${ConfirmDialog} title=${str('assistant_unlink_title')} text=${str('assistant_unlink_text')}
      confirm=${str('assistant_unlink')} onConfirm=${store.disconnectAssistant} onDismiss=${close} />`;
  }
  if (!assistant.configured) {
    return html`<${Setting} title=${str('assistant_setup')} text=${str('assistant_setup_text')} onClick=${() => setDialog('setup')} />${shown}`;
  }
  const fresh = teachable(s).some((key) => !assistant.taught.includes(key));
  return html`
    <${Setting} title=${str('assistant_linked')} text=${str('assistant_linked_text', assistant.accountId.slice(0, 6))} />
    <${Chips} title=${str('assistant_model')} choices=${MODELS.map(([model, label]) => [model, str(label)])}
      selected=${assistant.model} onSelect=${store.setAssistantModel} />
    <${Setting} title=${str('assistant_teach')}
      text=${str(fresh && assistant.taught.length ? 'assistant_teach_new' : 'assistant_teach_text')} onClick=${() => setDialog('teach')} />
    <${Setting} title=${str('assistant_notes')} text=${plural('assistant_notes_count', assistant.notes.length)} onClick=${() => setDialog('notes')} />
    <${Setting} title=${str('assistant_unlink')} onClick=${() => setDialog('unlink')} />
    ${shown}`;
}

