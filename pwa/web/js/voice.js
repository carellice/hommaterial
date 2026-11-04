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
 * Looks in what was [heard] for a custom device to run, or a remote to switch on or off. Both are
 * given as [id, name], and the name must be said whole: these are tried only when no device of
 * Alexa was meant, and a loose match would steal sentences from them.
 */
export function parseExtras(heard, macros, remotes) {
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
