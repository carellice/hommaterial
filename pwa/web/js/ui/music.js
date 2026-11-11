// The music of the home server, a page of the bottom bar (ui/Music.kt): songs, albums, artists and
// folders of the folder of the account, a player that keeps playing from page to page, and the upload.
import { html, useState, useEffect, useRef } from '../lib.js';
import { Icon, IconButton, Spinner, Dialog, useBack } from './common.js';
import * as store from '../store.js';
import { get } from '../net.js';
import { str, plural } from '/shared/i18n.js';

// ——— The library and the player, kept while the app is open ———

const model = { tracks: null, loading: false, failed: false, queue: [], index: -1, playing: false, position: 0, duration: 0, shuffle: false, repeat: false, upload: null };
const listeners = new Set();
const set = (change) => {
  Object.assign(model, change);
  listeners.forEach((l) => l());
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

export async function reloadMusic() {
  if (model.loading) return;
  set({ loading: true });
  try {
    const { tracks } = await get('/api/music');
    set({ tracks: sortTracks(tracks), failed: false });
  } catch (e) {
    set({ failed: true });
    store.say(e.message);
  } finally {
    set({ loading: false });
  }
}

const collator = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true });
const sortTracks = (tracks) => [...tracks].sort((a, b) =>
  collator.compare(a.artist, b.artist) || collator.compare(a.album, b.album) || a.disc - b.disc || a.number - b.number || collator.compare(a.title, b.title));

export const fileUrl = (t) => '/api/music/file?path=' + encodeURIComponent(t.path);
export const coverUrl = (t) => '/api/music/cover?path=' + encodeURIComponent(t.path);

const audio = new Audio();
audio.preload = 'auto';
const current = () => model.queue[model.index] ?? null;

function load(index, autoplay = true) {
  const track = model.queue[index];
  if (!track) return;
  set({ index, position: 0, duration: track.duration / 1000 });
  audio.src = fileUrl(track);
  if (autoplay) audio.play().catch(() => set({ playing: false }));
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title, artist: track.artist, album: track.album,
      artwork: [{ src: coverUrl(track), sizes: '512x512' }],
    });
  }
}

function shuffled(list) {
  const out = [...list];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

/** Plays [tracks] from the one at [start], or all mixed. */
export function play(tracks, start = 0, shuffle = false) {
  if (!tracks.length) return;
  const queue = shuffle ? shuffled(tracks) : tracks;
  set({ queue, shuffle });
  load(shuffle ? 0 : start);
}

export const toggle = () => (audio.paused ? audio.play().catch(() => {}) : audio.pause());
export function next() {
  if (model.index + 1 < model.queue.length) load(model.index + 1);
  else if (model.repeat && model.queue.length) load(0);
}
export function previous() {
  if (audio.currentTime > 3 || model.index === 0) audio.currentTime = 0;
  else load(model.index - 1);
}
export const seek = (seconds) => { audio.currentTime = seconds; };
export function stop() {
  audio.pause();
  audio.removeAttribute('src');
  set({ queue: [], index: -1, playing: false });
}

audio.addEventListener('play', () => set({ playing: true }));
audio.addEventListener('pause', () => set({ playing: false }));
audio.addEventListener('ended', next);
audio.addEventListener('loadedmetadata', () => Number.isFinite(audio.duration) && set({ duration: audio.duration }));
let lastTick = 0;
audio.addEventListener('timeupdate', () => {
  // Four times a second is enough for the bar, and spares the rest of the page.
  if (Math.abs(audio.currentTime - lastTick) < 0.25) return;
  lastTick = audio.currentTime;
  set({ position: audio.currentTime });
});
if ('mediaSession' in navigator) {
  const actions = { play: toggle, pause: toggle, nexttrack: next, previoustrack: previous, seekto: (d) => seek(d.seekTime) };
  for (const [action, handler] of Object.entries(actions)) {
    try {
      navigator.mediaSession.setActionHandler(action, handler);
    } catch {
      // Not every browser knows every action.
    }
  }
}

// ——— Upload ———

const AUDIO = /\.(mp3|m4a|aac|flac|ogg|oga|opus|wav)$/i;

/** Sends the audio files among [files] to the folder of the account, keeping the folders they come in. */
export async function upload(files) {
  const songs = [...files].filter((f) => AUDIO.test(f.name));
  if (!songs.length) {
    store.say(str('music_upload_none'));
    return;
  }
  let done = 0;
  const failed = [];
  for (const file of songs) {
    set({ upload: { done, total: songs.length } });
    const path = file.webkitRelativePath || file.name;
    try {
      const response = await fetch('/api/music/file?path=' + encodeURIComponent(path), { method: 'PUT', body: file, credentials: 'same-origin' });
      if (!response.ok) throw new Error((await response.json().catch(() => null))?.error || String(response.status));
      done++;
    } catch {
      failed.push(file.name);
    }
  }
  set({ upload: null });
  store.say(failed.length ? str('music_upload_failed', failed.length, songs.length) : str('music_uploaded', done));
  await reloadMusic();
}

function pick(folder) {
  const input = document.createElement('input');
  input.type = 'file';
  input.multiple = true;
  input.accept = 'audio/*,.flac,.opus,.m4a';
  if (folder) input.webkitdirectory = true;
  input.onchange = () => input.files?.length && upload(input.files);
  input.click();
}

/** The button of the top bar that uploads songs or a whole folder. */
export function UploadButton() {
  const m = useModel();
  const [asking, setAsking] = useState(false);
  if (m.upload) return html`<span class="icon-btn" aria-busy="true" title=${str('music_uploading', m.upload.done + 1, m.upload.total)}><${Spinner} /></span>`;
  return html`
    <${IconButton} icon="upload" label=${str('music_upload')} onClick=${() => setAsking(true)} />
    ${asking && html`
      <${Dialog} title=${str('music_upload')} onDismiss=${() => setAsking(false)} actions=${html`
        <button class="text-btn" onClick=${() => setAsking(false)}>${str('cancel')}</button>`}>
        <div class="body-medium faint" style="margin-bottom:12px">${str('music_upload_text')}</div>
        <button class="list-item" onClick=${() => { setAsking(false); pick(false); }}><${Icon} name="audio_file" cls="lead" /><span class="texts">${str('music_upload_files')}</span></button>
        <button class="list-item" onClick=${() => { setAsking(false); pick(true); }}><${Icon} name="folder" cls="lead" /><span class="texts">${str('music_upload_folder')}</span></button>
      <//>`}`;
}

// ——— The page ———

const VIEWS = [['songs', 'music_songs'], ['albums', 'music_albums'], ['artists', 'music_artists'], ['folders', 'music_folders']];
const fold = (text) => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
const time = (s) => (Number.isFinite(s) ? `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}` : '0:00');

function groups(tracks, view) {
  const by = new Map();
  for (const t of tracks) {
    const key = view === 'albums' ? t.albumKey : view === 'artists' ? t.artist || str('music_unknown_artist') : t.folder || '/';
    if (!by.has(key)) by.set(key, []);
    by.get(key).push(t);
  }
  return [...by.entries()].map(([key, list]) => ({
    key, tracks: list,
    title: view === 'albums' ? list[0].album || str('music_unknown_album') : key,
    sub: view === 'albums' ? list[0].artist || str('music_unknown_artist') : plural('music_tracks', list.length),
  })).sort((a, b) => collator.compare(a.title, b.title));
}

export function MusicPage() {
  const m = useModel();
  const [view, setView] = useState('songs');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(null);
  useEffect(() => {
    if (!m.tracks) reloadMusic();
  }, []);
  useBack(() => setOpen(null), open != null);
  if (!m.tracks) {
    return html`<div class="lists-center">${m.failed && !m.loading
      ? html`<button class="tonal-btn" onClick=${reloadMusic}>${str('lists_retry')}</button>`
      : html`<${Spinner} big />`}</div>`;
  }
  if (!m.tracks.length) {
    return html`
      <div class="list-empty">
        <span class="list-empty-icon"><${Icon} name="library_music" /></span>
        <div class="title-medium" style="margin-top:16px">${str('music_server_empty')}</div>
        <div class="body-medium faint" style="margin-top:4px">${str('music_upload_text')}</div>
      </div>`;
  }
  const q = fold(query.trim());
  const tracks = q ? m.tracks.filter((t) => fold(`${t.title} ${t.artist} ${t.album} ${t.folder}`).includes(q)) : m.tracks;
  const group = open && groups(m.tracks, open.view).find((g) => g.key === open.key);
  const list = group ? group.tracks : tracks;
  return html`
    <div class="music-page">
      ${group ? html`
        <div class="music-head">
          <${IconButton} icon="arrow_back" label=${str('music_back')} onClick=${() => setOpen(null)} />
          <div class="grow"><div class="title-medium">${group.title}</div><div class="body-small faint">${group.sub}</div></div>
        </div>` : html`
        <input class="music-search" type="search" placeholder=${str('music_search_hint')} value=${query} onInput=${(e) => setQuery(e.target.value)} />
        <div class="chips">${VIEWS.map(([key, label]) => html`
          <button key=${key} class=${`chip ${view === key ? 'selected' : ''}`} onClick=${() => setView(key)}>${str(label)}</button>`)}
        </div>`}
      <div class="music-actions">
        <button class="filled-btn" onClick=${() => play(list, 0)}><${Icon} name="play_arrow" />${str('music_play')}</button>
        <button class="tonal-btn" onClick=${() => play(list, 0, true)}><${Icon} name="shuffle" />${str('music_shuffle')}</button>
      </div>
      ${group || view === 'songs'
        ? list.map((t, i) => html`<${TrackRow} key=${t.path} track=${t} playing=${current()?.path === t.path} onPlay=${() => play(list, i)} />`)
        : groups(tracks, view).map((g) => html`
          <button key=${g.key} class="list-item" onClick=${() => setOpen({ view, key: g.key })}>
            ${view === 'albums' ? html`<${Cover} track=${g.tracks[0]} />` : html`<span class="music-cover icon-cover"><${Icon} name=${view === 'artists' ? 'person' : 'folder'} /></span>`}
            <span class="texts"><span class="body-large">${g.title}</span><span class="supporting">${g.sub}</span></span>
          </button>`)}
      ${!list.length && html`<div class="body-medium faint" style="padding:24px;text-align:center">${str('music_no_results')}</div>`}
    </div>`;
}

function Cover({ track, big }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [track.path]);
  return failed
    ? html`<span class=${`music-cover icon-cover ${big ? 'big' : ''}`}><${Icon} name="music_note" /></span>`
    : html`<img class=${`music-cover ${big ? 'big' : ''}`} src=${coverUrl(track)} alt="" loading="lazy" onError=${() => setFailed(true)} />`;
}

function TrackRow({ track, playing, onPlay }) {
  return html`
    <button class=${`list-item ${playing ? 'music-current' : ''}`} onClick=${onPlay}>
      <${Cover} track=${track} />
      <span class="texts"><span class="body-large">${track.title}</span><span class="supporting">${track.artist || str('music_unknown_artist')}${track.album ? ' · ' + track.album : ''}</span></span>
      <span class="supporting faint">${time(track.duration / 1000)}</span>
    </button>`;
}

/** The song that plays, above the bottom bar; a touch opens the whole player. */
export function PlayerBar() {
  const m = useModel();
  const [full, setFull] = useState(false);
  const track = current();
  if (!track) return null;
  return html`
    <div class="music-bar" onClick=${() => setFull(true)}>
      <div class="music-progress" style=${`width:${m.duration ? (m.position / m.duration) * 100 : 0}%`}></div>
      <${Cover} track=${track} />
      <span class="texts grow"><span class="body-medium">${track.title}</span><span class="body-small faint">${track.artist}</span></span>
      <span onClick=${(e) => e.stopPropagation()} style="display:flex">
        <${IconButton} icon=${m.playing ? 'pause' : 'play_arrow'} label=${str(m.playing ? 'music_pause' : 'music_play')} onClick=${toggle} />
        <${IconButton} icon="skip_next" label=${str('music_next')} onClick=${next} />
      </span>
    </div>
    ${full && html`<${FullPlayer} onClose=${() => setFull(false)} />`}`;
}

function FullPlayer({ onClose }) {
  const m = useModel();
  const track = current();
  useBack(onClose);
  useEffect(() => {
    if (!track) onClose();
  }, [track]);
  if (!track) return null;
  return html`
    <div class="music-full" role="dialog" aria-modal="true" aria-label=${str('music_now_playing')}>
      <div class="music-full-top">
        <${IconButton} icon="expand_more" label=${str('music_close')} onClick=${onClose} />
        <span class="grow label-large" style="text-align:center">${str('music_now_playing')}</span>
        <${IconButton} icon="stop" label=${str('music_stop')} onClick=${stop} />
      </div>
      <${Cover} track=${track} big />
      <div class="music-full-texts"><div class="headline-small">${track.title}</div><div class="body-large faint">${track.artist}${track.album ? ' · ' + track.album : ''}</div></div>
      <input class="music-seek" type="range" min="0" max=${m.duration || 0} step="1" value=${m.position} onInput=${(e) => seek(Number(e.target.value))} aria-label=${str('music_now_playing')} />
      <div class="music-times body-small faint"><span>${time(m.position)}</span><span>${time(m.duration)}</span></div>
      <div class="music-controls">
        <${IconButton} icon="shuffle" label=${str('music_shuffle')} cls=${m.shuffle ? 'music-on' : ''}
          onClick=${() => (m.shuffle ? set({ shuffle: false }) : (set({ queue: [track, ...shuffled(m.queue.filter((t) => t !== track))], index: 0, shuffle: true })))} />
        <${IconButton} icon="skip_previous" label=${str('music_previous')} onClick=${previous} />
        <button class="music-play" onClick=${toggle} aria-label=${str(m.playing ? 'music_pause' : 'music_play')}><${Icon} name=${m.playing ? 'pause' : 'play_arrow'} filled /></button>
        <${IconButton} icon="skip_next" label=${str('music_next')} onClick=${next} />
        <${IconButton} icon="repeat" label=${str('music_repeat')} cls=${m.repeat ? 'music-on' : ''} onClick=${() => set({ repeat: !m.repeat })} />
      </div>
    </div>`;
}
