// The music of the home server: a folder of the server (MUSIC_DIR, or /music when the installer links
// one), with a subfolder for each account. Files are served as they are, with seeking; what they say of
// themselves (title, artist, album) is read once and kept, and read again only when a file changes.
import { createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, extname, join, relative, resolve, sep } from 'node:path'
import { pipeline } from 'node:stream/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { parseFile } from 'music-metadata'
import { dataDir } from './db.ts'
import { HttpError } from './errors.ts'

/** Where the music is: the folder the installer links, else one among the data of Hommaterial. */
export const musicDir = resolve(process.env.MUSIC_DIR || (existsSync('/music') ? '/music' : join(dataDir, 'music')))

const AUDIO: Record<string, string> = {
  '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.aac': 'audio/aac', '.flac': 'audio/flac', '.ogg': 'audio/ogg',
  '.oga': 'audio/ogg', '.opus': 'audio/ogg', '.wav': 'audio/wav',
}
const PICTURES = ['cover.jpg', 'folder.jpg', 'cover.png', 'folder.png', 'front.jpg', 'album.jpg']
// Bigger than any song, to stop a stray upload from filling the disk.
const MAX_UPLOAD = 1024 * 1024 * 1024

export type Track = {
  path: string; title: string; artist: string; album: string; albumKey: string; folder: string
  disc: number; number: number; duration: number; modified: number; size: number
}

/** The folder of an account: its name, without what a folder name cannot hold. */
export function accountDir(username: string): string {
  const name = username.normalize('NFC').replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').replace(/^\.+/, '_').trim() || '_'
  const dir = join(musicDir, name)
  mkdirSync(dir, { recursive: true })
  return dir
}

/** The file at [path] under [dir], or an error when the path leads outside of it. */
export function within(dir: string, path: string): string {
  const file = resolve(dir, String(path ?? '').replace(/^[/\\]+/, ''))
  if (file !== dir && !file.startsWith(dir + sep)) throw new HttpError(400, 'Percorso non valido')
  return file
}

function audioFiles(dir: string, out: string[] = []): string[] {
  let entries: import('node:fs').Dirent[]
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return out
  }
  for (const entry of entries) {
    if (entry.name.startsWith('.')) continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) audioFiles(full, out)
    else if (entry.isFile() && AUDIO[extname(entry.name).toLowerCase()]) out.push(full)
  }
  return out
}

const cacheFile = (username: string) => join(dataDir, 'music-' + Buffer.from(username.toLowerCase()).toString('hex') + '.json')
const scans = new Map<string, Promise<Track[]>>()

/** The songs of [username], each with what its file says; a scan already going is shared. */
export function library(username: string): Promise<Track[]> {
  const going = scans.get(username)
  if (going) return going
  const scan = scanLibrary(username).finally(() => scans.delete(username))
  scans.set(username, scan)
  return scan
}

async function scanLibrary(username: string): Promise<Track[]> {
  const dir = accountDir(username)
  let known: Record<string, Track> = {}
  try {
    known = JSON.parse(readFileSync(cacheFile(username), 'utf8'))
  } catch {
    // No list yet: every file is read.
  }
  const tracks: Track[] = []
  for (const file of audioFiles(dir)) {
    const path = relative(dir, file).split(sep).join('/')
    const stat = statSync(file)
    const old = known[path]
    if (old && old.modified === Math.round(stat.mtimeMs) && old.size === stat.size) {
      tracks.push(old)
      continue
    }
    tracks.push(await readTrack(file, path, stat.mtimeMs, stat.size))
  }
  writeFileSync(cacheFile(username), JSON.stringify(Object.fromEntries(tracks.map((t) => [t.path, t]))))
  return tracks
}

async function readTrack(file: string, path: string, mtime: number, size: number): Promise<Track> {
  const folder = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''
  const name = path.slice(path.lastIndexOf('/') + 1).replace(/\.[^.]+$/, '')
  let tags: Awaited<ReturnType<typeof parseFile>> | null = null
  try {
    tags = await parseFile(file, { duration: true, skipCovers: true })
  } catch {
    // A file it cannot read still plays, by its name.
  }
  const common = tags?.common
  const artist = common?.artist?.trim() || common?.albumartist?.trim() || ''
  const album = common?.album?.trim() || (folder ? folder.slice(folder.lastIndexOf('/') + 1) : '')
  return {
    path,
    title: common?.title?.trim() || name,
    artist,
    album,
    // The same album name by another artist, or in another folder, is another album.
    albumKey: `${(common?.albumartist || artist).toLowerCase()}|${album.toLowerCase()}|${folder}`,
    folder,
    disc: common?.disk?.no ?? 0,
    number: common?.track?.no ?? 0,
    duration: Math.round((tags?.format?.duration ?? 0) * 1000),
    modified: Math.round(mtime),
    size,
  }
}

/** Sends the song at [path], or the part of it asked for: players seek with ranges. */
export function stream(req: IncomingMessage, res: ServerResponse, username: string, path: string) {
  const file = within(accountDir(username), path)
  const type = AUDIO[extname(file).toLowerCase()]
  if (!type || !existsSync(file)) throw new HttpError(404, 'Brano non trovato')
  const size = statSync(file).size
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range ?? '')
  const headers = { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Cache-Control': 'private, max-age=86400' }
  if (range && (range[1] || range[2])) {
    let start = range[1] ? Number(range[1]) : size - Number(range[2])
    let end = range[1] && range[2] ? Number(range[2]) : size - 1
    start = Math.max(0, start)
    end = Math.min(end, size - 1)
    if (start > end) {
      res.writeHead(416, { 'Content-Range': `bytes */${size}` })
      res.end()
      return
    }
    res.writeHead(206, { ...headers, 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 })
    if (req.method === 'HEAD') res.end()
    else createReadStream(file, { start, end }).pipe(res)
    return
  }
  res.writeHead(200, { ...headers, 'Content-Length': size })
  if (req.method === 'HEAD') res.end()
  else createReadStream(file).pipe(res)
}

/** The picture of the song at [path]: the one inside the file, else a cover image of its folder. */
export async function cover(res: ServerResponse, username: string, path: string) {
  const file = within(accountDir(username), path)
  if (!existsSync(file)) throw new HttpError(404, 'Brano non trovato')
  let picture: { data: Uint8Array; format: string } | undefined
  try {
    picture = (await parseFile(file, { duration: false })).common.picture?.[0]
  } catch {
    // Then the folder may have one.
  }
  if (!picture) {
    const near = PICTURES.map((name) => join(dirname(file), name)).find((it) => existsSync(it))
    if (!near) throw new HttpError(404, 'Nessuna copertina')
    picture = { data: readFileSync(near), format: near.endsWith('.png') ? 'image/png' : 'image/jpeg' }
  }
  res.writeHead(200, { 'Content-Type': picture.format, 'Content-Length': picture.data.length, 'Cache-Control': 'private, max-age=604800' })
  res.end(Buffer.from(picture.data))
}

/** Writes the song sent as the body to [path] of the folder of [username], first aside so that half a file never shows. */
export async function upload(req: IncomingMessage, username: string, path: string): Promise<string> {
  const dir = accountDir(username)
  const file = within(dir, path)
  if (!AUDIO[extname(file).toLowerCase()]) throw new HttpError(400, 'Formato audio non supportato')
  if (Number(req.headers['content-length'] ?? 0) > MAX_UPLOAD) throw new HttpError(413, 'File troppo grande')
  mkdirSync(dirname(file), { recursive: true })
  const part = `${file}.${process.pid}.part`
  let size = 0
  try {
    await pipeline(
      req,
      async function* (source) {
        for await (const chunk of source) {
          size += (chunk as Buffer).length
          if (size > MAX_UPLOAD) throw new HttpError(413, 'File troppo grande')
          yield chunk
        }
      },
      createWriteStream(part),
    )
    renameSync(part, file)
  } catch (e) {
    rmSync(part, { force: true })
    throw e
  }
  return relative(dir, file).split(sep).join('/')
}
