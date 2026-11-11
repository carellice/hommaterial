// Understands a spoken sentence: which devices to switch, or which custom device or remote is meant.

// Italian and English are both understood, whatever the language of the app.
const ON_WORDS = new Set(['accendi', 'accendere', 'accendimi', 'accenda', 'attiva', 'attivare', 'on']);
const OFF_WORDS = new Set(['spegni', 'spegnere', 'spegnimi', 'spenga', 'spengi', 'disattiva', 'disattivare', 'off']);
const ALL_WORDS = new Set(['tutto', 'tutta', 'tutti', 'tutte', 'all', 'everything', 'every']);
const FILLER_WORDS = new Set([
  'il', 'lo', 'la', 'i', 'gli', 'le', 'l', 'un', 'uno', 'una',
  'di', 'del', 'dello', 'della', 'dei', 'degli', 'delle', 'in', 'nel', 'nello', 'nella', 'a', 'al', 'alla',
  'per', 'favore', 'piacere', 'grazie', 'ora', 'adesso', 'subito', 'e',
  'turn', 'switch', 'the', 'an', 'of', 'at', 'to', 'my', 'please', 'now', 'thanks',
]);
const RUN_WORDS = new Set(['esegui', 'avvia', 'lancia', 'fai', 'run', 'start', 'launch']);

// Below this nothing is offered; from here up to a perfect match the user is asked.
const MIN_SCORE = 0.4;
const MAX_OPTIONS = 3;

function words(text) {
  return text.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').split(/[^a-z0-9]+/).filter(Boolean);
}

/** Same word, give or take the ending: "luce" and "luci", "presa" and "prese". */
function alike(a, b) {
  if (a === b) return true;
  if (a.length < 4 || b.length < 4 || a.length !== b.length) return false;
  return a.slice(0, -1) === b.slice(0, -1);
}

/**
 * How well the [said] words name a device: 1 when they are exactly its name, less when words are
 * missing or extra. The words of its room may be said too and do not count against it.
 */
function score(said, name, room) {
  if (!said.length || !name.length) return 0;
  const named = name.filter((word) => said.some((it) => alike(it, word))).length;
  const explained = said.filter((word) => [...name, ...room].some((it) => alike(it, word))).length;
  return (named / name.length) * explained / said.length;
}

/**
 * Looks in what was [heard] for a custom device to run, or a remote or group to switch on or off. All are
 * given as [id, name], and the name must be said whole: these are tried only when no device of
 * Alexa was meant, and a loose match would steal sentences from them.
 */
export function parseExtras(heard, macros, remotes, groups = []) {
  for (const sentence of heard) {
    const said = words(sentence);
    const verb = said.find((it) => RUN_WORDS.has(it) || ON_WORDS.has(it) || OFF_WORDS.has(it));
    if (!verb) continue;
    const rest = said.filter((it) => it !== verb && !FILLER_WORDS.has(it));
    const named = (name) => {
      const wanted = words(name).filter((it) => !FILLER_WORDS.has(it));
      return wanted.length > 0 && wanted.length === rest.length && wanted.every((w) => rest.some((it) => alike(it, w)));
    };
    // "Accendi" starts a custom device as well as "esegui" does; "spegni" never does.
    if (!OFF_WORDS.has(verb)) {
      const macro = macros.find(([, name]) => named(name));
      if (macro) return { type: 'run', macroId: macro[0] };
    }
    if (!RUN_WORDS.has(verb)) {
      const remote = remotes.find(([, name]) => named(name));
      if (remote) return { type: 'remote', remoteId: remote[0], on: ON_WORDS.has(verb) };
      const group = groups.find(([, name]) => named(name));
      if (group) return { type: 'group', groupId: group[0], on: ON_WORDS.has(verb) };
    }
  }
  return null;
}

/**
 * Looks in what was [heard] for one of the voice phrases of the custom devices, given as [id,
 * phrases]. A phrase must be said whole and nothing else, give or take the small words and "Alexa":
 * "buonanotte" is not "buonanotte a tutti".
 */
export function parsePhrase(heard, phrases) {
  const meaningful = (text) => words(text).filter((it) => !FILLER_WORDS.has(it) && it !== 'alexa');
  for (const sentence of heard) {
    const said = meaningful(sentence);
    if (!said.length) continue;
    for (const [id, list] of phrases) {
      if (list.some((phrase) => {
        const wanted = meaningful(phrase);
        return wanted.length === said.length && wanted.every((w, i) => alike(said[i], w));
      })) return id;
    }
  }
  return null;
}

/**
 * Turns what the speech recognizer [heard], its guesses best first, into a command for one of the
 * [devices] or for a whole room: {type: 'run', command}, {type: 'ask', heard, options} or
 * {type: 'unknown', heard}. A command is {on, devices, room}. Only switching on and off is understood.
 */
export function parseVoice(heard, devices) {
  const switchable = devices.filter((d) => d.hasPower);
  let best = null;
  for (const sentence of heard) {
    const reading = interpret(sentence, switchable);
    // An earlier guess wins over a later one that is equally convincing.
    if (!best || reading[0] > best[0]) best = reading;
  }
  return best ? best[1] : { type: 'unknown', heard: '' };
}

function interpret(sentence, devices) {
  const all = words(sentence);
  let on;
  if (all.some((it) => ON_WORDS.has(it))) on = true;
  else if (all.some((it) => OFF_WORDS.has(it))) on = false;
  else return [0, { type: 'unknown', heard: sentence }];
  const wholeRoom = all.some((it) => ALL_WORDS.has(it));
  const said = all.filter((it) => !ON_WORDS.has(it) && !OFF_WORDS.has(it) && !ALL_WORDS.has(it) && !FILLER_WORDS.has(it));
  if (!said.length) return [0, { type: 'unknown', heard: sentence }];

  const byRoom = new Map();
  for (const d of devices) {
    if (d.room == null) continue;
    if (!byRoom.has(d.room)) byRoom.set(d.room, []);
    byRoom.get(d.room).push(d);
  }
  const rooms = [...byRoom].map(([room, members]) => [{ on, devices: members, room }, score(said, words(room), [])])
    .filter(([, s]) => s >= MIN_SCORE);
  const singles = devices.map((d) => [{ on, devices: [d], room: null }, score(said, words(d.name), words(d.room || ''))])
    .filter(([, s]) => s >= MIN_SCORE);

  // "Tutto in salotto" means the room; "salotto" alone means it only if no device goes by that name.
  const ranked = (wholeRoom ? [...rooms, ...singles] : [...singles, ...rooms]).sort((a, b) => b[1] - a[1]);
  const top = ranked[0];
  if (!top) return [0, { type: 'unknown', heard: sentence }];
  const certain = top[1] === 1 && ranked.filter(([, s]) => s === 1).length === 1 && top[0].room == null;
  // A whole room is always confirmed: it is a lot to switch on a misheard word.
  const result = certain
    ? { type: 'run', command: top[0] }
    : { type: 'ask', heard: sentence, options: ranked.slice(0, MAX_OPTIONS).map(([c]) => c) };
  return [top[1], result];
}

// Kept the same as completeCommand in voice/Autocomplete.kt.
const LIST_NAMES = new Set(['lista', 'liste', 'list', 'lists']);
const LIST_WORDS = new Set([...LIST_NAMES, 'aggiungi', 'aggiungere', 'aggiungimi', 'add']);
const TAIL_WORDS = 4;

const nameWords = (text) =>
  text.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').split(/[^\p{L}\p{N}]+/u).filter(Boolean).join(' ');

/**
 * Suggestions for what is being [typed] in the chat, as [{label, text}]: the name of a thing of the
 * home, a room or a list that the last words begin (or of which they begin a word), chosen by the
 * verb already typed; after a verb and a space, the things it can act on; while the first word is
 * typed, the verbs and the voice phrases. [words] holds power, run, rooms, lists, phrases, verbs.
 */
export function completeCommand(typed, words, max = 6) {
  if (!typed.trim()) return [];
  const tokens = [...typed.matchAll(/[\p{L}\p{N}]+/gu)].map((m) => ({ start: m.index, word: nameWords(m[0]) }));
  if (!tokens.length) return [];
  const open = /[\p{L}\p{N}]$/u.test(typed);
  const said = tokens.map((t) => t.word);
  const verbAt = said.findIndex((it) => ON_WORDS.has(it) || OFF_WORDS.has(it) || RUN_WORDS.has(it) || LIST_WORDS.has(it));
  const verb = said[verbAt];
  const toList = said.some((it) => LIST_WORDS.has(it));
  // A list is named only after "lista": what comes before it is what to add.
  const listAt = said.findLastIndex((it) => LIST_NAMES.has(it));
  const { power = [], run = [], rooms = [], lists = [], phrases = [], verbs = [] } = words;
  const seen = new Set();
  const pool = (toList ? lists
    : ON_WORDS.has(verb) ? [...power, ...run, ...rooms]
    : OFF_WORDS.has(verb) ? [...power, ...rooms]
    : RUN_WORDS.has(verb) ? run
    : [...power, ...run, ...rooms, ...lists])
    .filter((n) => n.trim() && !seen.has(nameWords(n)) && seen.add(nameWords(n)));

  const found = [];
  const whole = said.join(' ');
  if (open && verbAt < 0) {
    for (const phrase of phrases) {
      const p = nameWords(phrase);
      if (p.startsWith(whole) && p !== whole) found.push([100, { label: phrase, text: phrase }]);
    }
  }
  if (open && tokens.length === 1) {
    for (const v of verbs) {
      if (nameWords(v).startsWith(said[0]) && nameWords(v) !== said[0]) found.push([90, { label: v, text: `${v} ` }]);
    }
  }
  if (open && (!toList || listAt >= 0)) {
    // The longest run of last words that begins a name, or a word inside it, wins.
    const from = Math.max(toList ? listAt + 1 : verbAt + 1, tokens.length - TAIL_WORDS);
    for (const name of pool) {
      const n = nameWords(name);
      let best = null;
      for (let first = from; first < tokens.length; first++) {
        const tail = said.slice(first).join(' ');
        if (n === tail) { best = null; break; }
        const k = tokens.length - first;
        const score = n.startsWith(tail) ? 3 * k + 2 : ` ${n}`.includes(` ${tail}`) ? 3 * k + 1 : 0;
        if (score && (!best || score > best[0])) best = [score, tokens[first].start];
      }
      if (best) found.push([best[0], { label: name, text: `${typed.slice(0, best[1])}${name} ` }]);
    }
  } else if (!open && (toList ? LIST_NAMES.has(said.findLast((it) => !FILLER_WORDS.has(it)))
    : verb != null && said.slice(verbAt + 1).every((it) => FILLER_WORDS.has(it) || ALL_WORDS.has(it)))) {
    // "Accendi " with nothing after it yet: what it can act on.
    for (const name of pool) found.push([0, { label: name, text: `${typed}${name} ` }]);
  }
  const labels = new Set();
  return found.sort((a, b) => b[0] - a[0]).map((it) => it[1])
    .filter((c) => c.text.trim() !== typed.trim() && !labels.has(c.label.toLowerCase()) && labels.add(c.label.toLowerCase()))
    .slice(0, max);
}

// ——— Web search (voice/VoiceParser.kt parseWebSearch) ———
const POLITE = /\s+(?:per favore|per piacere|grazie|please|thanks)$/i;
const ON_WEB = String.raw`(?:su\s+internet|in\s+internet|online|on\s*line|sul\s+web|nel\s+web|in\s+rete|su\s+google|con\s+google|on\s+the\s+web|on\s+google)`;
const SEARCH_VERB = String.raw`(?:cerca(?:mi|re)?|ricerca|trova(?:mi)?|search(?:\s+for)?|look\s+up)`;
const SEARCH_FIRST = new RegExp(String.raw`^\s*${SEARCH_VERB}\s+${ON_WEB}\s+(?:per\s+|for\s+)?(.+?)[\s.!?]*$`, 'i');
const SEARCH_LAST = new RegExp(String.raw`^\s*${SEARCH_VERB}\s+(.+?)\s+${ON_WEB}[\s.!?]*$`, 'i');
const SEARCH_EN = /^\s*(?:search\s+the\s+web\s+for|google)\s+(.+?)[\s.!?]*$/i;

/** What to look up on the web, when asked in so many words ("cerca su internet chi ha vinto Sanremo"); null otherwise. */
export function parseWebSearch(heard) {
  for (const sentence of heard) {
    const text = sentence.trim().replace(/[.!?]+$/, '').replace(POLITE, '');
    const found = SEARCH_FIRST.exec(text) ?? SEARCH_LAST.exec(text) ?? SEARCH_EN.exec(text);
    const query = found?.[1]?.trim();
    if (query) return query;
  }
  return null;
}
