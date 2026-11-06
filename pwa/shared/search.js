// The search of the settings: every word typed must start, or be found in, a word of what a section
// is about, with one typo forgiven once four letters are typed. The Android app does the same in
// data/SettingsSearch.kt: keep them alike.

/** Lowercase and without accents: "luminosita" finds "luminosità". */
export const fold = (text) => text.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();

const words = (text) => fold(text).split(/[^a-z0-9]+/).filter(Boolean);

function distance(a, b) {
  let row = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const next = [i];
    for (let j = 1; j <= b.length; j++) {
      next[j] = Math.min(row[j] + 1, next[j - 1] + 1, row[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    row = next;
  }
  return row[b.length];
}

function near(word, key) {
  if (key.startsWith(word) || (word.length >= 3 && key.includes(word))) return true;
  if (word.length < 4) return false;
  return [word.length - 1, word.length, word.length + 1].some((n) => n <= key.length && distance(word, key.slice(0, n)) <= 1);
}

/** Whether [query] finds [text]; an empty query finds everything. */
export function matches(query, text) {
  const all = words(query);
  // Short words such as "di" or "la" only count when nothing else was typed.
  const long = all.filter((w) => w.length >= 3);
  const wanted = long.length ? long : all;
  if (!wanted.length) return true;
  const keys = words(text);
  return wanted.every((word) => keys.some((key) => near(word, key)));
}
