// The color of the app (ui/Accent.kt): the schemes are worked out by tools/accents.mjs. A browser
// has no wallpaper to take colors from, so the automatic accent is the first, the blue.
import { ACCENT_SCHEMES } from './accent-schemes.js';

export const ACCENT_AUTO = 'auto';
export const ACCENTS = Object.keys(ACCENT_SCHEMES);
export const accentSeed = (key) => ACCENT_SCHEMES[key]?.seed;

/** The accent shown as chosen: the automatic one stands for the first. */
export const shownAccent = (key) => (ACCENT_SCHEMES[key] ? key : ACCENTS[0]);

/** The rules that give the page the colors of [key], light and dark, over those of app.css. */
export function accentCss(key) {
  const scheme = ACCENT_SCHEMES[shownAccent(key)];
  const vars = (roles) => Object.entries(roles).map(([name, value]) => `--${name}:${value};`).join('');
  return `:root{${vars(scheme.light)}}`
    + `@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){${vars(scheme.dark)}}}`
    + `:root[data-theme="dark"]{${vars(scheme.dark)}}`;
}
