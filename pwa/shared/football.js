// The favorite team: what ESPN publishes about Italian football, read into the few things the app
// shows. The server reads it for the web app (server/src/main.ts); the Android app does the same in
// data/Football.kt. ESPN asks for no key, but gives no CORS headers: a browser cannot read it alone.

/** The leagues a favorite team can be chosen from, by ESPN's id. */
export const LEAGUES = [
  { id: 'ita.1', name: 'Serie A' },
  { id: 'ita.2', name: 'Serie B' },
];

const logoOf = (t = {}) => t.logo || t.logos?.[0]?.href || '';
const hex = (color) => (/^[0-9a-f]{6}$/i.test(color ?? '') ? `#${color}` : null);

/** The teams of a league, by name. */
export function parseTeams(json) {
  return (json?.sports?.[0]?.leagues?.[0]?.teams ?? [])
    .map(({ team: t }) => ({ id: String(t.id), name: t.displayName, logo: logoOf(t), color: hex(t.color) }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

function side(c) {
  const t = c.team ?? {};
  const score = c.score == null ? null : typeof c.score === 'object' ? c.score.displayValue ?? String(c.score.value) : String(c.score);
  return { id: String(t.id ?? c.id ?? ''), name: t.shortDisplayName || t.displayName || '', logo: logoOf(t), score, winner: c.winner === true };
}

/** The matches of a schedule: when, which competition, the two teams and, once played or playing, the score. */
export function parseMatches(json) {
  return (json?.events ?? []).map((ev) => {
    const c = ev.competitions?.[0] ?? {};
    const status = c.status?.type ?? ev.status?.type ?? {};
    const teams = c.competitors ?? [];
    const home = teams.find((x) => x.homeAway === 'home') ?? teams[0];
    const away = teams.find((x) => x.homeAway === 'away') ?? teams[1];
    if (!home || !away || !ev.date) return null;
    return {
      id: String(ev.id),
      date: ev.date,
      // False while the hour is not fixed yet: only the day is known.
      timeValid: ev.timeValid !== false,
      competition: ev.league?.abbreviation || ev.league?.shortName || ev.league?.name || '',
      state: ['pre', 'in', 'post'].includes(status.state) ? status.state : 'pre',
      detail: status.shortDetail || '',
      venue: c.venue?.fullName || '',
      home: side(home),
      away: side(away),
    };
  }).filter(Boolean);
}

/** The league table, first place first. */
export function parseTable(json) {
  const entries = json?.children?.[0]?.standings?.entries ?? json?.standings?.entries ?? [];
  return entries.map((e) => {
    const s = Object.fromEntries((e.stats ?? []).map((x) => [x.name, x.value]));
    const t = e.team ?? {};
    return {
      rank: s.rank ?? 0,
      id: String(t.id),
      name: t.shortDisplayName || t.displayName || '',
      logo: logoOf(t),
      played: s.gamesPlayed ?? 0,
      wins: s.wins ?? 0,
      draws: s.ties ?? 0,
      losses: s.losses ?? 0,
      goalsFor: s.pointsFor ?? 0,
      goalsAgainst: s.pointsAgainst ?? 0,
      points: s.points ?? 0,
      // What the place is worth at the end: Champions League, relegation... with the color ESPN gives it.
      zone: e.note?.description ?? null,
      zoneColor: e.note?.color ?? null,
    };
  }).sort((a, b) => a.rank - b.rank);
}

const time = (m) => Date.parse(m.date);

/**
 * What the app shows of [teamId]: its place in the table, the match to come (or the one being played),
 * the few after it and the last five played, newest first.
 */
export function teamSummary(teamId, { table = [], fixtures = [], results = [] }, now = Date.now()) {
  const coming = fixtures
    .filter((m) => m.state === 'in' || (m.state === 'pre' && time(m) > now - 3 * 3_600_000))
    .sort((a, b) => time(a) - time(b));
  const played = results.filter((m) => m.state === 'post').sort((a, b) => time(b) - time(a));
  return {
    table,
    standing: table.find((r) => r.id === teamId) ?? null,
    next: coming[0] ?? null,
    upcoming: coming.slice(1, 4),
    recent: played.slice(0, 5),
  };
}

/** How a played match went for [teamId]: 'W', 'D' or 'L'; null before the end. */
export function outcome(match, teamId) {
  if (match.state !== 'post') return null;
  const mine = match.home.id === teamId ? match.home : match.away;
  const other = mine === match.home ? match.away : match.home;
  // Penalties leave the score even: the winner says who went through.
  if (mine.winner) return 'W';
  if (other.winner) return 'L';
  const a = Number(mine.score);
  const b = Number(other.score);
  if (Number.isNaN(a) || Number.isNaN(b)) return null;
  return a > b ? 'W' : a < b ? 'L' : 'D';
}
