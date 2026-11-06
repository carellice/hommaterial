// The favorite team (ui/Team.kt): its next match at the top of the home, a page with its place,
// record, matches and the whole table, the choice of it in the settings and a line on the rest screen.
import { html, useState, useEffect } from '../lib.js';
import { Icon, IconButton, Dialog, Setting, Spinner, useBack } from './common.js';
import * as store from '../store.js';
import { str, clock, dateText, locale } from '/shared/i18n.js';
import { LEAGUES, outcome } from '/shared/football.js';

/** A smaller copy of an ESPN logo: the originals are 500 pixels wide. */
const small = (url, px = 96) => (url?.startsWith('https://a.espncdn.com/i/')
  ? `https://a.espncdn.com/combiner/i?img=${url.slice('https://a.espncdn.com'.length)}&h=${px}&w=${px}` : url);

const Logo = ({ url, size, cls = '' }) => html`
  <span class=${`team-logo ${cls}`} style=${`width:${size}px;height:${size}px`}>
    ${url && html`<img src=${small(url, size > 60 ? 192 : 96)} alt="" loading="lazy" onLoad=${(e) => e.target.classList.add('loaded')} />`}
  </span>`;

const leagueName = (id) => LEAGUES.find((l) => l.id === id)?.name ?? '';

/** The colors of the band of the team: its color, darker at the far end, with text readable on it. */
function band(team) {
  const color = team.color ?? 'var(--primary)';
  let on = '#fff';
  if (team.color) {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(team.color.slice(i, i + 2), 16) / 255);
    if (0.2126 * r + 0.7152 * g + 0.0722 * b > 0.6) on = '#15171a';
  }
  return `--team:${color};--on-team:${on}`;
}

/** The zones of the table in the language of the app; the names of the cups stay as they are. */
function zoneLabel(zone) {
  const lower = zone.toLowerCase();
  if (lower.includes('relegat')) return str('team_zone_relegation');
  if (lower.includes('playoff') || lower.includes('play-off')) return str('team_zone_playoff');
  if (lower.includes('promot')) return str('team_zone_promotion');
  return zone;
}

const sameDay = (a, b) => a.toDateString() === b.toDateString();

/** When a match is: today, tomorrow or its day, and its hour once fixed. */
function dayOf(match) {
  const at = new Date(match.date);
  const today = new Date();
  const tomorrow = new Date();
  tomorrow.setDate(today.getDate() + 1);
  if (sameDay(at, today)) return str('calendar_today');
  if (sameDay(at, tomorrow)) return str('calendar_tomorrow');
  const text = dateText(at);
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export const matchWhen = (match) => `${dayOf(match)} · ${match.timeValid ? clock(match.date) : str('team_time_tbd')}`;

const RESULT = { W: 'team_result_w', D: 'team_result_d', L: 'team_result_l' };

/** Reads the team when it first shows, and again when it is older than five minutes. */
function useTeam(s) {
  useEffect(() => {
    store.loadTeam();
  }, [s.team?.id]);
}

// ——— At the top of the home ———

/** The next match of the favorite team at the top of the home, in its colors: a tap opens the team. */
export function TeamHomeCard({ s, onOpen }) {
  useTeam(s);
  const team = s.team;
  const info = s.teamInfo;
  const next = info?.next;
  return html`
    <button class="span team-card" style=${band(team)} onClick=${onOpen}>
      <${Logo} url=${team.logo} size=${150} cls="team-watermark" />
      <span class="team-card-head">
        <${Icon} name="sports_soccer" />
        <span class="title-small grow">${next ? str(next.state === 'in' ? 'team_live' : 'team_next') : team.name}</span>
        ${info?.standing && html`<span class="label-large dim">${str('team_rank', info.standing.rank, leagueName(team.league))}</span>`}
        <${Icon} name="chevron_right" />
      </span>
      ${!info && !s.teamFailed ? html`<span class="team-loading"><span></span></span>`
        : !next ? html`<span class="body-large dim">${str(info ? 'team_no_match' : 'team_unavailable')}</span>`
        : html`<${Versus} match=${next} size=${52} />`}
    </button>`;
}

/** The two teams face to face, sliding in from their sides, with the time or the score between them. */
function Versus({ match, size }) {
  return html`
    <span class="versus" key=${match.id}>
      <span class="versus-side from-left"><${Logo} url=${match.home.logo} size=${size} /><span class="label-large ellipsis">${match.home.name}</span></span>
      <span class="versus-mid">
        <span class="label-medium dim ellipsis">${match.competition}</span>
        ${match.state === 'in' ? html`
          <span class="display-small tnum versus-score">${match.home.score ?? 0} – ${match.away.score ?? 0}</span>
          <span class="label-large live"><span class="live-dot"></span>${[str('team_live'), match.detail].filter(Boolean).join(' · ')}</span>`
        : html`
          <span class="headline-medium versus-time">${match.timeValid ? clock(match.date) : '–'}</span>
          <span class="label-large">${dayOf(match)}</span>`}
      </span>
      <span class="versus-side from-right"><${Logo} url=${match.away.logo} size=${size} /><span class="label-large ellipsis">${match.away.name}</span></span>
    </span>`;
}

// ——— The page of the team ———

/** A number counted up from 0 when it first shows. */
function Count({ value }) {
  const [shown, setShown] = useState(0);
  useEffect(() => {
    const start = performance.now();
    let frame;
    const step = (now) => {
      const t = Math.min(1, (now - start) / 900);
      setShown(Math.round(value * (1 - (1 - t) ** 3)));
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [value]);
  return shown;
}

/** Everything about the favorite team: its place, its record, its matches and the whole table. */
export function TeamScreen({ s, onBack }) {
  useBack(onBack);
  useTeam(s);
  const team = s.team;
  const info = s.teamInfo;
  const standing = info?.standing;
  return html`
    <div class="full-dialog team-page">
      <div class="scroll">
        <div class="team-hero" style=${band(team)}>
          <${Logo} url=${team.logo} size=${260} cls="team-watermark" />
          <div class="team-hero-bar">
            <${IconButton} icon="arrow_back" label=${str('back')} onClick=${onBack} />
            <span class="grow"></span>
            <${IconButton} icon="refresh" label=${str('refresh_now')} onClick=${() => store.loadTeam(true)} />
          </div>
          <div class="team-hero-body">
            <${Logo} url=${team.logo} size=${112} cls="team-hero-logo" />
            <div class="headline-medium team-hero-name">${team.name}</div>
            <div class="title-medium dim team-hero-rank">${standing
              ? `${str('team_rank', standing.rank, leagueName(team.league))} · ${str('team_points', standing.points)}`
              : leagueName(team.league)}</div>
          </div>
        </div>
        <div class="narrow team-body">
          ${!info ? html`<div class="team-center">${s.teamFailed ? html`<span class="faint">${str('team_unavailable')}</span>` : html`<${Spinner} />`}</div>` : html`
            ${standing && html`<${Record} row=${standing} />`}
            ${info.next && html`
              <div class="title-medium team-title">${str(info.next.state === 'in' ? 'team_live' : 'team_next')}</div>
              <div class="team-box rise" style="--i:2"><${Versus} match=${info.next} size=${56} />
                ${info.next.venue && html`<div class="body-small faint" style="text-align:center;margin-top:12px">${info.next.venue}</div>`}</div>`}
            ${info.recent.length > 0 && html`
              <div class="title-medium team-title">${str('team_form')}</div>
              <div class="form-row">${[...info.recent].reverse().map((m, i) => {
                const r = outcome(m, team.id);
                return html`<span key=${m.id} class=${`form-dot r-${r ?? 'x'}`} style=${`--i:${i}`}>${r ? str(RESULT[r]) : '–'}</span>`;
              })}</div>
              ${info.recent.map((m) => html`<${MatchLine} key=${m.id} match=${m} teamId=${team.id} />`)}`}
            ${info.upcoming.length > 0 && html`
              <div class="title-medium team-title">${str('team_upcoming')}</div>
              ${info.upcoming.map((m) => html`<${MatchLine} key=${m.id} match=${m} teamId=${team.id} />`)}`}
            ${info.table.length > 0 && html`<${Table} table=${info.table} teamId=${team.id} />`}`}
          <div class="label-small faint" style="text-align:center;padding:16px 0 32px">${str('team_source')}</div>
        </div>
      </div>
    </div>`;
}

/** Won, drawn and lost: counted up, with a bar that fills in their proportions. */
function Record({ row }) {
  const played = Math.max(1, row.wins + row.draws + row.losses);
  return html`
    <div class="team-box rise record" style="--i:1">
      <div class="record-row">
        ${[['team_wins', row.wins, 'W'], ['team_draws', row.draws, 'D'], ['team_losses', row.losses, 'L']].map(([label, value, r]) => html`
          <div key=${label}><div class=${`display-small tnum record-${r}`}><${Count} value=${value} /></div>
            <div class="label-large faint">${str(label)}</div></div>`)}
      </div>
      <div class="record-bar"><span class="record-fill">
        ${row.wins > 0 && html`<span class="record-W" style=${`flex:${row.wins / played}`}></span>`}
        ${row.draws > 0 && html`<span class="record-D" style=${`flex:${row.draws / played}`}></span>`}
        ${row.losses > 0 && html`<span class="record-L" style=${`flex:${row.losses / played}`}></span>`}
      </span></div>
      <div class="record-row small">
        ${[['team_played', row.played], ['team_goals_for', row.goalsFor], ['team_goals_against', row.goalsAgainst]].map(([label, value]) => html`
          <div key=${label}><div class="title-large tnum"><${Count} value=${value} /></div><div class="label-medium faint">${str(label)}</div></div>`)}
      </div>
    </div>`;
}

/** A match in a line: when, the two teams and, once played, the score with how it went. */
function MatchLine({ match, teamId }) {
  const r = outcome(match, teamId);
  const score = match.state === 'pre' ? (match.timeValid ? clock(match.date) : '–') : `${match.home.score ?? '-'} – ${match.away.score ?? '-'}`;
  return html`
    <div class="match-line">
      <div class="match-when"><div class="label-medium ellipsis">${new Date(match.date).toLocaleDateString(locale(), { weekday: 'short', day: 'numeric', month: 'short' })}</div>
        <div class="label-small faint ellipsis">${match.competition}</div></div>
      <span class="body-medium ellipsis grow" style="text-align:right">${match.home.name}</span>
      <${Logo} url=${match.home.logo} size=${22} />
      <span class=${`match-score label-large tnum ${r ? `r-${r}` : ''}`}>${score}</span>
      <${Logo} url=${match.away.logo} size=${22} />
      <span class="body-medium ellipsis grow">${match.away.name}</span>
    </div>`;
}

function Table({ table, teamId }) {
  const zones = table.filter((r) => r.zone).filter((r, i, all) => all.findIndex((x) => x.zone === r.zone) === i);
  return html`
    <div class="title-medium team-title">${str('team_table')}</div>
    <div class="table-line head label-medium faint"><span class="zone"></span><span class="rank">#</span><span class="logo-gap"></span>
      <span class="grow"></span><span class="num">${str('team_col_played')}</span><span class="num wide">${str('team_col_diff')}</span><span class="num">${str('team_col_points')}</span></div>
    ${table.map((row, i) => {
      const diff = row.goalsFor - row.goalsAgainst;
      return html`<div key=${row.id} class=${`table-line body-medium tnum ${row.id === teamId ? 'mine' : ''}`} style=${`--i:${Math.min(i, 20)}`}>
        <span class="zone" style=${row.zoneColor ? `background:${row.zoneColor}` : ''}></span>
        <span class="rank">${row.rank}</span><${Logo} url=${row.logo} size=${22} />
        <span class="grow ellipsis name">${row.name}</span><span class="num">${row.played}</span>
        <span class="num wide">${diff > 0 ? `+${diff}` : diff}</span><span class="num points">${row.points}</span></div>`;
    })}
    <div class="zones">${zones.map((r) => html`<span key=${r.zone} class="label-medium faint"><span class="cal-dot" style=${`background:${r.zoneColor ?? 'gray'}`}></span>${zoneLabel(r.zone)}</span>`)}</div>`;
}

// ——— The settings ———

/** The section of the settings: the team chosen, to change or remove, or the choice of one. */
export function TeamSettings({ s }) {
  const [picking, setPicking] = useState(false);
  const team = s.team;
  return html`
    ${team ? html`
      <${Setting} lead=${html`<${Logo} url=${team.logo} size=${40} />`} title=${team.name} text=${leagueName(team.league)}
        onClick=${() => setPicking(true)} trailing=${html`<span class="label-large primary-text" onClick=${() => setPicking(true)}>${str('team_change')}</span>`} />
      <${Setting} title=${str('team_remove')} onClick=${() => store.setTeam(null)} />`
    : html`<${Setting} title=${str('team_choose')} text=${str('team_none')} onClick=${() => setPicking(true)} />`}
    ${picking && html`<${TeamPicker} current=${team} onDismiss=${() => setPicking(false)} onPick=${(picked) => {
      store.setTeam(picked);
      setPicking(false);
    }} />`}`;
}

/** The teams of each league as a grid of their logos. */
function TeamPicker({ current, onPick, onDismiss }) {
  const [leagues, setLeagues] = useState(null);
  const [league, setLeague] = useState(current?.league ?? LEAGUES[0].id);
  useEffect(() => {
    store.footballTeams().then((found) => setLeagues(found ?? []));
  }, []);
  const teams = leagues?.find((l) => l.id === league)?.teams;
  return html`<${Dialog} title=${str('team_choose')} onDismiss=${onDismiss}
    actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>`}>
    <div class="chips">${LEAGUES.map((l) => html`<button key=${l.id} class=${`chip ${l.id === league ? 'selected' : ''}`} onClick=${() => setLeague(l.id)}>
      ${l.id === league && html`<${Icon} name="check" />`}${l.name}</button>`)}</div>
    <div class="team-grid">
      ${!leagues ? html`<div class="team-center"><${Spinner} /><div class="body-medium" style="margin-top:12px">${str('team_loading')}</div></div>`
        : !teams?.length ? html`<div class="team-center">${str('team_unavailable')}</div>`
        : teams.map((t, i) => html`<button key=${t.id} class=${`team-pick ${t.id === current?.id ? 'selected' : ''}`} style=${`--i:${Math.min(i, 20)}`}
            onClick=${() => onPick({ league, id: t.id, name: t.name, logo: t.logo, color: t.color })}>
            <${Logo} url=${t.logo} size=${44} /><span class="label-medium">${t.name}</span></button>`)}
    </div>
  <//>`;
}

// ——— The rest screen of the monitor ———

/** The next match in one line on the rest screen: the two logos, the teams and when. */
export function RestTeam({ s }) {
  useTeam(s);
  const next = s.teamInfo?.next;
  if (!s.team || !next) return null;
  return html`
    <div class="rest-team">
      <${Logo} url=${next.home.logo} size=${36} />
      <div><div class="title-large">${next.home.name} – ${next.away.name}</div>
        <div class="body-large" style="opacity:.7">${next.state === 'in' ? `${next.home.score ?? 0} – ${next.away.score ?? 0} · ${next.detail}` : matchWhen(next)}</div></div>
      <${Logo} url=${next.away.logo} size=${36} />
    </div>`;
}
