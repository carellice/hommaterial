// Portable v1 contract shared with Android and the native Windows client.
// Optional additions keep old backups readable. No Amazon login or monitor PIN is portable.
export function createBackup(s, switchBot = null) {
  const backup = {
    app: 'hommaterial', version: 1,
    favorites: s.favorites ?? [], hidden: s.hidden ?? [], rooms: s.rooms ?? {}, roomList: s.roomList ?? [],
    groups: s.groups ?? [], macros: s.macros ?? [], order: s.order ?? {},
    schedules: s.schedules ?? [], automations: s.automations ?? [], layout: s.layout ?? 0, language: s.language ?? 'auto',
    shortcuts: s.shortcuts ?? null, widget: s.widget ?? {}, tiles: s.tiles ?? Array(10).fill(null),
    alerts: s.alerts ?? {}, autoUpdate: s.autoUpdate ?? true, theme: s.theme ?? 0,
    remoteButtons: s.remoteButtons ?? {}, hiddenRemotes: s.hiddenRemotes ?? [], monitor: s.monitor ?? {},
  };
  // Work with a detached object, so sanitizing never changes the running app state.
  const portable = JSON.parse(JSON.stringify(backup));
  delete portable.monitor.pin;
  delete portable.monitor.monitorPin;
  if (switchBot?.token) portable.switchBot = { token: switchBot.token, secret: switchBot.secret || '' };
  return portable;
}

export function parseBackup(text) {
  if (text.length > 5_000_000) throw new Error('Backup too large');
  const backup = JSON.parse(text);
  if (!backup || backup.app !== 'hommaterial' || backup.version !== 1) throw new Error('Invalid Hommaterial backup');
  const action = (a, allowMacro = true) => {
    if (!a || typeof a.target !== 'string' || typeof a.value !== 'string') throw new Error('Invalid action');
    if (a.type === 'power' && a.target && ['on', 'off'].includes(a.value)) return;
    if (a.type === 'remote' && a.target && a.value) return;
    if (a.type === 'macro' && allowMacro && a.target) return;
    if (a.type === 'wait' && /^\d+$/.test(a.value) && Number(a.value) <= 300) return;
    throw new Error('Invalid action');
  };
  for (const s of backup.schedules || []) {
    if (!s || !s.id || !Number.isFinite(s.at) || !Number.isInteger(s.hour) || s.hour < 0 || s.hour > 23 || !Number.isInteger(s.minute) || s.minute < 0 || s.minute > 59 || !Array.isArray(s.days) || s.days.some((d) => !Number.isInteger(d) || d < 1 || d > 7)) throw new Error('Invalid schedule');
    action(s.action);
  }
  for (const a of backup.automations || []) {
    if (!a || !a.id || !a.device || !['on', 'off', 'temperatureAbove', 'temperatureBelow', 'humidityAbove', 'humidityBelow'].includes(a.trigger) || !Number.isFinite(a.threshold ?? 0)) throw new Error('Invalid automation');
    action(a.action);
  }
  for (const m of backup.macros || []) for (const step of [...(m.steps || []), ...(m.otherwise || [])]) action(step, false);
  return backup;
}
