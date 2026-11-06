import { str } from '../../shared/i18n.js'
import * as football from '../../shared/football.js'
import { HttpError } from './errors.ts'

// The favorite team: ESPN, read here because a browser cannot (shared/football.js). The pages are
// shared by every account: what one asked for another finds ready.

const ESPN = 'https://site.api.espn.com/apis'
const cache = new Map<string, { at: number; value: unknown }>()

/** A page of ESPN, kept for [ttl] ms; the last copy when ESPN does not answer. */
async function espn(path: string, ttl: number): Promise<unknown> {
  const hit = cache.get(path)
  if (hit && Date.now() - hit.at < ttl) return hit.value
  try {
    const response = await fetch(ESPN + path, { signal: AbortSignal.timeout(10_000) })
    if (!response.ok) throw new Error(String(response.status))
    const value = await response.json()
    cache.set(path, { at: Date.now(), value })
    return value
  } catch {
    if (hit) return hit.value
    throw new HttpError(502, str('team_unavailable'))
  }
}

function league(id: string) {
  if (!football.LEAGUES.some((l) => l.id === id)) throw new HttpError(404, str('team_unavailable'))
  return id
}

export const teams = () => Promise.all(football.LEAGUES.map(async (l) =>
  ({ ...l, teams: football.parseTeams(await espn(`/site/v2/sports/soccer/${l.id}/teams`, 12 * 3_600_000)) })))

/** The table and the matches of team [id] of [leagueId], as shared/football.js sums them up. */
export async function team(leagueId: string, id: string) {
  if (!/^\d{1,8}$/.test(id)) throw new HttpError(404, str('team_unavailable'))
  const minutes = 5 * 60_000
  const [table, fixtures, results] = await Promise.all([
    espn(`/v2/sports/soccer/${league(leagueId)}/standings`, minutes).then(football.parseTable),
    espn(`/site/v2/sports/soccer/all/teams/${id}/schedule?fixture=true`, minutes).then(football.parseMatches),
    espn(`/site/v2/sports/soccer/all/teams/${id}/schedule`, minutes).then(football.parseMatches),
  ])
  return football.teamSummary(id, { table, fixtures, results })
}
