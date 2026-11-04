import { STRINGS } from './strings.js';
import { EXTRA } from './extra-strings.js';

// 'auto' follows the browser; Italian and English are the languages the app speaks.
let chosen = 'en';
// The server speaks to several accounts at once: it tells the language of the one at hand.
let provider = null;

const browserLanguage = () => (typeof navigator !== 'undefined' && navigator.language) || 'en-US';

/** The language for [choice]: 'it', 'en' or 'auto', which follows [preferred] or the browser. */
export function resolveLanguage(choice, preferred = browserLanguage()) {
  const browser = (preferred || 'en').slice(0, 2).toLowerCase();
  return choice === 'it' || choice === 'en' ? choice : browser === 'it' ? 'it' : 'en';
}

export function setLanguage(choice) {
  chosen = resolveLanguage(choice);
  if (typeof document !== 'undefined') document.documentElement.lang = chosen;
}

/** On the server: [get] returns the language of the account being served, null for the default. */
export function setLanguageProvider(get) {
  provider = get;
}

const current = () => provider?.() || chosen;

export const lang = () => current();

/** The locale for dates and numbers: the language of the app, in the country of the browser when it matches. */
export function locale() {
  const language = current();
  const browser = provider ? '' : browserLanguage();
  return browser.slice(0, 2).toLowerCase() === language ? browser : language === 'it' ? 'it-IT' : 'en-US';
}

function lookup(key) {
  const language = current();
  return EXTRA[language]?.[key] ?? STRINGS[language]?.[key] ?? EXTRA.en[key] ?? STRINGS.en[key] ?? key;
}

/** Formats a number as the language writes it, with exactly [decimals] digits after the point. */
export function number(value, decimals = 0) {
  return Number(value).toLocaleString(locale(), {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
    useGrouping: false,
  });
}

// The format of the Android strings: %1$s, %2$d, %1$.1f, %d, %s and %% for the sign itself.
function format(template, args) {
  let next = 0;
  return template.replace(/%(?:(\d+)\$)?(\.\d+)?([sdf%])/g, (match, position, precision, type) => {
    if (type === '%') return '%';
    const value = args[position ? Number(position) - 1 : next++];
    if (type === 'd') return number(Math.trunc(Number(value)));
    if (type === 'f') return number(value, precision ? Number(precision.slice(1)) : 6);
    return String(value ?? '');
  });
}

export function str(key, ...args) {
  const template = lookup(key);
  return typeof template === 'string' ? format(template, args) : key;
}

export function plural(key, count, ...args) {
  const forms = lookup(key);
  if (typeof forms !== 'object') return key;
  const rule = new Intl.PluralRules(current()).select(count);
  return format(forms[rule] ?? forms.other, [count, ...args]);
}

/** A time of day as the language writes it, such as 21:05. */
export function clock(at) {
  return new Date(at).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' });
}

/** The day as "giovedì 8 ottobre". */
export function dateText(at) {
  return new Date(at).toLocaleDateString(locale(), { weekday: 'long', day: 'numeric', month: 'long' });
}

export function degrees(temperature) {
  return number(temperature, 1) + '°';
}
