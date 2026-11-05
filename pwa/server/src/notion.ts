import { str } from '../../shared/i18n.js'
import { getSecret, setSecret } from './db.ts'
import { HttpError, request } from './errors.ts'

// Notion for the account (data/Notion.kt), through the secret of an internal integration the user
// makes and shares the pages with. The secret stays on the server; the answers have the shapes the
// Android app reads.

const API = 'https://api.notion.com/v1'
const VERSION = '2022-06-28'

/** A page or a database the integration can read, chosen as a to-do list or as notes. */
export type Source = { id: string; kind: 'database' | 'page'; name: string }
export type Item = { id: string; name: string; completed: boolean; version: number }
export type Note = { id: string; title: string; edited: number; url: string }
/** A block of a note as the apps show it: h1, h2, h3, p, bullet, number, todo, quote, code, divider. */
export type Block = { kind: string; text: string; checked: boolean; depth: number }

export const linked = () => getSecret<string | null>('notion_token', null) != null
export const workspace = () => getSecret<string>('notion_workspace', '')

export function disconnect() {
  setSecret('notion_token', null)
  setSecret('notion_workspace', null)
}

/** Checks the secret with the smallest request there is and keeps it; the name of its workspace. */
export async function connect(token: string): Promise<string> {
  const me = await api('GET', '/users/me', undefined, token.trim())
  const name = String(me?.bot?.workspace_name ?? '')
  setSecret('notion_token', token.trim())
  setSecret('notion_workspace', name)
  return name
}

async function api(method: string, path: string, body?: unknown, token = getSecret<string | null>('notion_token', null)): Promise<any> {
  if (!token) throw new HttpError(400, str('notion_not_linked'))
  const response = await request(`${API}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Notion-Version': VERSION,
      ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const answer: any = await response.json().catch(() => null)
  if (response.status === 401) throw new HttpError(400, str('notion_unauthorized'))
  if (response.status === 404) throw new HttpError(404, str('notion_not_shared'))
  if (response.status === 429) throw new HttpError(429, str('notion_limit'))
  if (!response.ok || answer == null) throw new HttpError(502, str('notion_error', answer?.message || String(response.status)))
  return answer
}

const plain = (rich: any): string => (Array.isArray(rich) ? rich.map((it) => String(it?.plain_text ?? '')).join('') : '')

/** The title of a page, wherever the database keeps it. */
function pageTitle(page: any): string {
  for (const property of Object.values<any>(page?.properties ?? {})) {
    if (property?.type === 'title') return plain(property.title)
  }
  return ''
}

const richText = (text: string) => [{ type: 'text', text: { content: text.slice(0, 2000) } }]

/** Every page of [path], following the cursor up to a few hundred. */
async function paged(method: string, path: string, body: Record<string, unknown> = {}): Promise<any[]> {
  const all: any[] = []
  let cursor: string | null = null
  do {
    const answer: any = method === 'GET'
      ? await api('GET', `${path}${path.includes('?') ? '&' : '?'}page_size=100${cursor ? `&start_cursor=${cursor}` : ''}`)
      : await api(method, path, { ...body, page_size: 100, ...(cursor ? { start_cursor: cursor } : {}) })
    all.push(...(Array.isArray(answer.results) ? answer.results : []))
    cursor = answer.has_more && answer.next_cursor ? String(answer.next_cursor) : null
  } while (cursor && all.length < 400)
  return all
}

/** Every page and database shared with the integration, the databases first. */
export async function sources(): Promise<Source[]> {
  const found: Source[] = []
  for (const o of await paged('POST', '/search')) {
    if (o.archived || o.in_trash) continue
    if (o.object === 'database') found.push({ id: o.id, kind: 'database', name: plain(o.title) })
    else if (o.object === 'page') found.push({ id: o.id, kind: 'page', name: pageTitle(o) })
  }
  return found
    .map((it) => ({ ...it, name: it.name.trim() || str('notion_untitled') }))
    .sort((a, b) => (a.kind === b.kind ? a.name.localeCompare(b.name) : a.kind === 'database' ? -1 : 1))
}

// --- to-do lists --------------------------------------------------------------------------------
// A database is a list of its pages, done by a checkbox or a status; a page is a list of its to-dos.

type Schema = { title: string; checkbox: string | null; status: string | null; doneOptions: string[]; doneOption: string | null; todoOption: string | null }
const schemas = new Map<string, Schema>()

async function schema(database: string): Promise<Schema> {
  const known = schemas.get(database)
  if (known) return known
  const properties: Record<string, any> = (await api('GET', `/databases/${database}`)).properties ?? {}
  let title = 'Name'
  let checkbox: string | null = null
  let status: string | null = null
  for (const [name, property] of Object.entries(properties)) {
    if (property?.type === 'title') title = name
    else if (property?.type === 'checkbox' && checkbox == null) checkbox = name
    else if (property?.type === 'status' && status == null) status = name
  }
  // The options of a status are done when they are in the group Notion calls "Complete".
  const config = status ? properties[status].status ?? {} : {}
  const options = new Map<string, string>((config.options ?? []).map((it: any) => [String(it.id), String(it.name)]))
  const group = (name: string): string[] => {
    const found = (config.groups ?? []).find((it: any) => String(it.name).toLowerCase() === name.toLowerCase())
    return (found?.option_ids ?? []).map((id: string) => options.get(id)).filter(Boolean)
  }
  const complete = group('Complete')
  const todo = group('To-do').length ? group('To-do') : [...options.values()].filter((it) => !complete.includes(it))
  const read = { title, checkbox, status, doneOptions: complete, doneOption: complete[0] ?? null, todoOption: todo[0] ?? null }
  schemas.set(database, read)
  return read
}

function isDone(s: Schema, properties: any): boolean {
  if (s.checkbox) return properties?.[s.checkbox]?.checkbox === true
  if (s.status) return s.doneOptions.includes(properties?.[s.status]?.status?.name)
  return false
}

const query = (database: string) => paged('POST', `/databases/${database}/query`, { sorts: [{ timestamp: 'created_time', direction: 'ascending' }] })

export async function items(kind: string, id: string): Promise<Item[]> {
  if (kind !== 'database') {
    return (await paged('GET', `/blocks/${id}/children`))
      .filter((it) => it.type === 'to_do')
      .map((it) => ({ id: it.id, name: plain(it.to_do?.rich_text), completed: it.to_do?.checked === true, version: 1 }))
  }
  const s = await schema(id)
  return (await query(id)).map((page) => ({ id: page.id, name: pageTitle(page), completed: isDone(s, page.properties), version: 1 }))
}

export async function add(kind: string, id: string, name: string) {
  if (kind !== 'database') {
    await api('PATCH', `/blocks/${id}/children`, { children: [{ object: 'block', type: 'to_do', to_do: { rich_text: richText(name), checked: false } }] })
    return
  }
  const s = await schema(id)
  await api('POST', '/pages', { parent: { database_id: id }, properties: { [s.title]: { title: richText(name) } } })
}

export async function setDone(kind: string, id: string, item: string, done: boolean) {
  if (kind !== 'database') {
    await api('PATCH', `/blocks/${item}`, { to_do: { checked: done } })
    return
  }
  const s = await schema(id)
  let change: Record<string, unknown> | null = null
  if (s.checkbox) change = { [s.checkbox]: { checkbox: done } }
  else if (s.status && (done ? s.doneOption : s.todoOption)) change = { [s.status]: { status: { name: done ? s.doneOption : s.todoOption } } }
  if (!change) throw new HttpError(400, str('notion_no_checkbox'))
  await api('PATCH', `/pages/${item}`, { properties: change })
}

export async function remove(kind: string, item: string) {
  if (kind === 'database') await api('PATCH', `/pages/${item}`, { archived: true })
  else await api('DELETE', `/blocks/${item}`)
}

// --- notes --------------------------------------------------------------------------------------

const note = (page: any): Note => ({
  id: page.id,
  title: pageTitle(page).trim() || str('notion_untitled'),
  edited: Date.parse(page.last_edited_time) || 0,
  url: String(page.url ?? ''),
})

/** The notes of [chosen], the latest changed first: the pages of a database, or a page itself. */
export async function notes(chosen: { kind: string; id: string }[]): Promise<Note[]> {
  const all: Note[] = []
  for (const source of chosen) {
    if (source.kind === 'database') all.push(...(await query(source.id)).map(note))
    else all.push(note(await api('GET', `/pages/${source.id}`)))
  }
  return all.filter((it, i) => all.findIndex((other) => other.id === it.id) === i).sort((a, b) => b.edited - a.edited)
}

const KINDS: Record<string, string> = {
  heading_1: 'h1', heading_2: 'h2', heading_3: 'h3', paragraph: 'p', toggle: 'p', callout: 'p',
  bulleted_list_item: 'bullet', numbered_list_item: 'number', to_do: 'todo', quote: 'quote', code: 'code', divider: 'divider', child_page: 'p',
}

/** The text of the note [id], block by block, with the lists nested up to a few levels. */
export async function noteBlocks(id: string): Promise<Block[]> {
  const blocks: Block[] = []
  const read = async (parent: string, depth: number) => {
    for (const block of await paged('GET', `/blocks/${parent}/children`)) {
      if (blocks.length >= 400) return
      const kind = KINDS[block.type]
      if (kind) {
        const content = block[block.type] ?? {}
        const text = block.type === 'child_page' ? String(content.title ?? '') : plain(content.rich_text)
        blocks.push({ kind, text, checked: block.type === 'to_do' && content.checked === true, depth })
      }
      if (block.has_children && depth < 2 && block.type !== 'child_page') await read(block.id, depth + 1)
    }
  }
  await read(id, 0)
  return blocks
}
