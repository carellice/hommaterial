// How the lists show in the app only: names given here, lists joined into one, and their order. Alexa
// and Notion never see it; on the server it is the same on every device. The Android app does the same
// in data/ListViews.kt: keep them alike. views = { names: { [listId]: name }, merged: [[listId, ...]], order: [shownId, ...] }

export const MERGED = 'MERGED';
const PREFIX = 'merged:';

export const EMPTY_VIEWS = { names: {}, merged: [], order: [] };

export const viewId = (group) => PREFIX + group.join('|');

/** The lists behind a list shown: itself, or those joined in it. */
export const members = (id) => (id.startsWith(PREFIX) ? id.slice(PREFIX.length).split('|') : [id]);

/** The lists as shown: renamed, those joined as one in the place of the first of them. */
export function applyViews(lists, views = EMPTY_VIEWS, nameOf = (l) => l.name) {
  const names = views?.names ?? {};
  const merged = views?.merged ?? [];
  const byId = new Map(lists.map((l) => [l.id, l]));
  const done = new Set();
  const out = [];
  for (const list of lists) {
    if (done.has(list.id)) continue;
    const group = merged.find((g) => g.includes(list.id))?.filter((id) => byId.has(id));
    if (!group || group.length < 2) {
      out.push({ ...list, name: names[list.id] ?? list.name });
      continue;
    }
    group.forEach((id) => done.add(id));
    const parts = group.map((id) => byId.get(id));
    const id = viewId(group);
    const active = parts.every((p) => p.active != null) ? parts.reduce((sum, p) => sum + p.active, 0) : null;
    out.push({ id, name: names[id] ?? parts.map(nameOf).join(' + '), type: MERGED, active });
  }
  // In the order chosen; those not in it follow, as Alexa gives them.
  const order = views?.order ?? [];
  const at = (l) => (order.includes(l.id) ? order.indexOf(l.id) : Infinity);
  return out.sort((a, b) => at(a) - at(b));
}

/** Moves the list at [from] of those [shown] (their ids) to [to]. */
export function move(views, shown, from, to) {
  if (from < 0 || to < 0 || from >= shown.length || to >= shown.length) return views;
  const order = [...shown];
  order.splice(to, 0, ...order.splice(from, 1));
  return { ...EMPTY_VIEWS, ...views, order };
}

/** Joins [ids] into one list; a list already joined leaves its old group. */
export function merge(views, ids) {
  const rest = (views?.merged ?? []).map((g) => g.filter((id) => !ids.includes(id))).filter((g) => g.length >= 2);
  return { ...EMPTY_VIEWS, ...views, merged: ids.length >= 2 ? [...rest, ids] : rest };
}

/** Parts again the lists joined as [id], forgetting the name given to them. */
export function split(views, id) {
  const names = { ...(views?.names ?? {}) };
  delete names[id];
  return { ...EMPTY_VIEWS, ...views, names, merged: (views?.merged ?? []).filter((g) => viewId(g) !== id) };
}

export function rename(views, id, name) {
  const names = { ...(views?.names ?? {}) };
  if (name.trim()) names[id] = name.trim();
  else delete names[id];
  return { ...EMPTY_VIEWS, ...views, names };
}
