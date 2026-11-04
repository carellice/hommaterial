// Writes the colors of the app for each accent the user can choose, the same on the phone and on
// the web: the whole Material 3 scheme, light and dark, worked out from one seed color with the
// "content" scheme, which keeps the color of the swatch the user picked.
//
// From the root of the repository:
//   npm i --no-save --prefix pwa/tools @material/material-color-utilities@0.3.0
//   node pwa/tools/accents.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { argbFromHex, hexFromArgb, Hct, SchemeContent, MaterialDynamicColors } from '@material/material-color-utilities';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));

// The key saved in the settings, and the seed. The blue is the one of the web version until now.
const SEEDS = [
  ['blue', '#3D5BA9'], ['sky', '#0288D1'], ['teal', '#00897B'], ['green', '#43A047'], ['yellow', '#F9A825'],
  ['orange', '#F57C00'], ['red', '#D32F2F'], ['pink', '#D81B60'], ['purple', '#7E57C2'],
];

// The roles of a scheme, by the names of Compose; the web takes them in kebab case.
const ROLES = ['primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'inversePrimary',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant', 'surfaceTint',
  'inverseSurface', 'inverseOnSurface', 'error', 'onError', 'errorContainer', 'onErrorContainer',
  'outline', 'outlineVariant', 'surfaceBright', 'surfaceDim', 'surfaceContainerLowest', 'surfaceContainerLow',
  'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest'];

const scheme = (seed, dark) => {
  const s = new SchemeContent(Hct.fromInt(argbFromHex(seed)), dark, 0);
  return Object.fromEntries(ROLES.map((role) => [role, hexFromArgb(MaterialDynamicColors[role].getArgb(s)).toUpperCase()]));
};
const accents = SEEDS.map(([key, seed]) => ({ key, seed, light: scheme(seed, false), dark: scheme(seed, true) }));

// --- Kotlin ---
const pascal = (key) => key[0].toUpperCase() + key.slice(1);
const color = (hex) => `Color(0xFF${hex.slice(1)})`;
const kotlinScheme = (fn, roles) => `${fn}(\n${ROLES.map((r) => `        ${r} = ${color(roles[r])},`).join('\n')}\n    )`;
let kotlin = `package app.hommaterial.ui

// Written by pwa/tools/accents.mjs: do not edit.
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.hommaterial.R

`;
for (const a of accents) {
  kotlin += `private val ${pascal(a.key)}Light = ${kotlinScheme('lightColorScheme', a.light)}\n\n`;
  kotlin += `private val ${pascal(a.key)}Dark = ${kotlinScheme('darkColorScheme', a.dark)}\n\n`;
}
kotlin += `/** The accents to choose from, in the order of the settings. */\ninternal val ACCENTS = listOf(\n${accents
  .map((a) => `    Accent("${a.key}", R.string.accent_${a.key}, ${color(a.seed)}, ${pascal(a.key)}Light, ${pascal(a.key)}Dark),`)
  .join('\n')}\n)\n`;
writeFileSync(`${ROOT}app/src/main/java/app/hommaterial/ui/AccentSchemes.kt`, kotlin);

// --- the web ---
const kebab = (role) => role.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
const web = Object.fromEntries(accents.map((a) => [a.key, {
  seed: a.seed,
  light: Object.fromEntries(ROLES.map((r) => [kebab(r), a.light[r]])),
  dark: Object.fromEntries(ROLES.map((r) => [kebab(r), a.dark[r]])),
}]));
writeFileSync(`${ROOT}pwa/shared/accent-schemes.js`,
  `// Written by tools/accents.mjs: do not edit.\nexport const ACCENT_SCHEMES = ${JSON.stringify(web, null, 1)};\n`);
console.log(`${accents.length} accents written`);
