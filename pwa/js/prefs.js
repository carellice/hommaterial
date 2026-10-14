/**
 * A named set of values kept in the browser, as the Android app keeps its SharedPreferences: the
 * cache, the settings, the login, SwitchBot and the history each have their own.
 */
export class Prefs {
  constructor(name) {
    this.key = `hommaterial.${name}`;
  }

  all() {
    try {
      return JSON.parse(localStorage.getItem(this.key) || '{}') || {};
    } catch {
      return {};
    }
  }

  get(name, fallback = null) {
    const value = this.all()[name];
    return value === undefined ? fallback : value;
  }

  has(name) {
    return name in this.all();
  }

  set(name, value) {
    this.edit({ [name]: value });
  }

  /** Writes several values at once; undefined removes one. */
  edit(values) {
    const all = this.all();
    for (const [name, value] of Object.entries(values)) {
      if (value === undefined) delete all[name];
      else all[name] = value;
    }
    this.save(all);
  }

  remove(...names) {
    const all = this.all();
    for (const name of names) delete all[name];
    this.save(all);
  }

  clear() {
    this.save({});
  }

  save(all) {
    try {
      localStorage.setItem(this.key, JSON.stringify(all));
    } catch {
      // A full or blocked storage loses the value; the app goes on with what it has in memory.
    }
  }
}
