// Temperature and humidity of a sensor over the last day or week.
import { html, useState, useRef, useLayoutEffect } from '../lib.js';
import { Icon } from './common.js';
import { str, clock, number, locale } from '../i18n.js';

const HOUR = 60 * 60_000;
// Readings further apart than this are not joined: the line would invent what happened in between.
const MAX_JOIN = 2 * HOUR;
// How far apart the lines of the scale can be, in degrees or in percent.
const SCALE_STEPS = [0.5, 1, 2, 5, 10, 20];
const SPANS = [['span_day', 24 * HOUR], ['span_week', 7 * 24 * HOUR]];
const HEIGHT = 160;
const LABEL_HEIGHT = 18;
// About the width of a digit of the small labels, to leave the scale the room it needs.
const CHAR_WIDTH = 7.4;

export function HistoryCharts({ readings }) {
  const [span, setSpan] = useState(SPANS[0]);
  const now = Date.now();
  const shown = readings.filter((r) => now - r.at <= span[1]);
  const start = now - span[1];
  const week = span[1] > 24 * HOUR;
  const humidity = shown.filter((r) => r.humidity != null).map((r) => [r.at, r.humidity]);
  return html`
    <div style="margin-top:20px">
      <div class="chips">${SPANS.map((choice) => html`
        <button class=${`chip ${choice === span ? 'selected' : ''}`} onClick=${() => setSpan(choice)}>
          ${choice === span && html`<${Icon} name="check" />`}${str(choice[0])}</button>`)}</div>
      ${shown.length < 2
        ? html`<div class="body-medium faint" style="margin-top:12px">${str('history_empty')}</div>`
        : html`
          <${Chart} title=${str('temperature')} points=${shown.map((r) => [r.at, r.temperature])} start=${start} end=${now}
            week=${week} color="var(--primary)" unit="°" decimals=${1} />
          ${humidity.length >= 2 && html`<${Chart} title=${str('humidity')} points=${humidity} start=${start} end=${now}
            week=${week} color="var(--tertiary)" unit="%" decimals=${0} />`}
          <div class="label-small faint" style="margin-top:8px">${str('chart_hint')}</div>`}
    </div>`;
}

/** Where the vertical lines go: every six hours of the clock, or at the midnights of a week. */
function ticks(start, end, week) {
  const date = new Date(start);
  date.setMinutes(0, 0, 0);
  date.setHours(week ? 0 : Math.floor(date.getHours() / 6) * 6);
  const list = [];
  while (date.getTime() <= end) {
    if (date.getTime() >= start) list.push(date.getTime());
    if (week) date.setDate(date.getDate() + 1);
    else date.setHours(date.getHours() + 6);
  }
  return list;
}

const weekday = (at) => new Date(at).toLocaleDateString(locale(), { weekday: 'short' });
const tickLabel = (at, week) => (week ? weekday(at) : clock(at));
/** When a reading was taken: the time alone within a day, with its day over a week. */
const moment = (at, week) =>
  (week ? `${new Date(at).toLocaleDateString(locale(), { weekday: 'short', day: 'numeric' })}, ${clock(at)}` : clock(at));

/**
 * One series over time, with its scale on the left and the hours, or the days of a [week], at
 * the bottom. A finger held on it reads the value under it.
 */
function Chart({ title, points, start, end, week, color, unit, decimals }) {
  const ref = useRef(null);
  const [width, setWidth] = useState(320);
  const [picked, setPicked] = useState(null);
  useLayoutEffect(() => {
    const measure = () => ref.current && setWidth(ref.current.clientWidth || 320);
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, []);
  const values = points.map((p) => p[1]);
  const lowest = Math.min(...values);
  const highest = Math.max(...values);
  const average = values.reduce((a, b) => a + b, 0) / values.length;
  // The unit is added afterwards: a percent sign would be read as part of the format.
  const value = (v) => number(v, decimals) + unit;
  // Round steps, few enough to leave three or four lines to read the values against.
  const step = SCALE_STEPS.find((it) => (highest - lowest) / it <= 3) ?? SCALE_STEPS[SCALE_STEPS.length - 1];
  const bottom = Math.floor(lowest / step) * step;
  const top = Math.max(Math.ceil(highest / step) * step, bottom + 2 * step);
  const levels = [];
  for (let v = bottom; v <= top + step / 100; v += step) levels.push(v);
  const level = (v) => (step < 1 ? value(v) : number(v, 0) + unit);
  const gutter = Math.max(...levels.map((v) => level(v).length)) * CHAR_WIDTH + 8;
  const plot = Math.max(10, width - gutter);
  const height = HEIGHT - LABEL_HEIGHT;
  const x = (at) => gutter + ((at - start) / (end - start)) * plot;
  const y = (v) => height - ((v - bottom) / (top - bottom)) * height;

  // Readings far apart are not joined: each stretch is a line with its own shade under it.
  const stretches = [[points[0]]];
  for (let i = 1; i < points.length; i++) {
    if (points[i][0] - points[i - 1][0] > MAX_JOIN) stretches.push([]);
    stretches[stretches.length - 1].push(points[i]);
  }
  const id = `fade${title.replace(/\W/g, '')}`;
  const pick = (e) => {
    const box = ref.current.getBoundingClientRect();
    const at = start + ((e.clientX - box.left - gutter) / plot) * (end - start);
    setPicked(points.reduce((best, p) => (Math.abs(p[0] - at) < Math.abs(best[0] - at) ? p : best)));
  };
  // The latest reading stands out, unless the finger is pointing at another one.
  const center = picked ?? points[points.length - 1];
  return html`
    <div class="chart">
      <div class="chart-head">
        <span class="label-large">${title}</span>
        ${picked
          ? html`<span class="label-large" style=${`color:${color}`}>${str('chart_reading', value(picked[1]), moment(picked[0], week))}</span>`
          : html`<span class="label-medium faint">${str('min_avg_max', value(lowest), value(average), value(highest))}</span>`}
      </div>
      <div ref=${ref} style="margin-top:8px">
        <svg height=${HEIGHT} viewBox=${`0 0 ${width} ${HEIGHT}`} role="img" aria-label=${title}
          onPointerDown=${pick} onPointerMove=${(e) => (e.buttons || e.pointerType === 'mouse') && pick(e)}
          onPointerUp=${() => setPicked(null)} onPointerLeave=${() => setPicked(null)} onPointerCancel=${() => setPicked(null)}>
          <defs><linearGradient id=${id} x1="0" y1="0" x2="0" y2=${height} gradientUnits="userSpaceOnUse">
            <stop offset="0" style=${`stop-color:${color};stop-opacity:.28`} />
            <stop offset="1" style=${`stop-color:${color};stop-opacity:0`} /></linearGradient></defs>
          ${levels.map((v) => html`
            <line x1=${gutter} x2=${width} y1=${y(v)} y2=${y(v)} stroke="var(--outline-variant)" stroke-width="1" />
            <text x=${gutter - 8} y=${Math.min(height - 2, Math.max(9, y(v) + 4))} text-anchor="end" font-size="11"
              fill="var(--on-surface-variant)">${level(v)}</text>`)}
          ${ticks(start, end, week).map((tick) => html`
            <line x1=${x(tick)} x2=${x(tick)} y1="0" y2=${height} stroke="var(--outline-variant)" stroke-opacity=".5" />
            <text x=${Math.min(width - 16, Math.max(gutter + 16, x(tick)))} y=${HEIGHT - 3} text-anchor="middle" font-size="11"
              fill="var(--on-surface-variant)">${tickLabel(tick, week)}</text>`)}
          ${stretches.map((stretch) => {
            if (stretch.length === 1) return html`<circle cx=${x(stretch[0][0])} cy=${y(stretch[0][1])} r="2" style=${`fill:${color}`} />`;
            const line = stretch.map((p, i) => `${i ? 'L' : 'M'}${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`).join('');
            const shade = `${line}L${x(stretch[stretch.length - 1][0]).toFixed(1)},${height}L${x(stretch[0][0]).toFixed(1)},${height}Z`;
            return html`<path d=${shade} fill=${`url(#${id})`} />
              <path d=${line} fill="none" style=${`stroke:${color}`} stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />`;
          })}
          ${picked && html`<line x1=${x(center[0])} x2=${x(center[0])} y1="0" y2=${height} style=${`stroke:${color}`} stroke-opacity=".6" />`}
          <circle cx=${x(center[0])} cy=${y(center[1])} r="5" style=${`fill:${color}`} />
          <circle cx=${x(center[0])} cy=${y(center[1])} r="2.5" fill="var(--surface-container-low)" />
        </svg>
      </div>
    </div>`;
}
