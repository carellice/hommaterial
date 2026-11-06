// The weather at the top of the home, above the favorite team (ui/Weather.kt): Open-Meteo, chosen
// and switched on in the settings, with the place searched by name.
import { html, useState, useEffect } from '../lib.js';
import { Icon, Dialog, Setting, Toggle, Field, Spinner } from './common.js';
import * as store from '../store.js';
import { str, locale } from '/shared/i18n.js';

/** What a WMO weather code is called, its icon by day and by night, and the look of the card. */
export function condition(code, day = true) {
  if (code === 0) return ['weather_clear', day ? 'sunny' : 'clear_night', day ? 'sun' : 'night'];
  if (code === 1) return ['weather_mostly_clear', day ? 'partly_cloudy_day' : 'partly_cloudy_night', day ? 'sun' : 'night'];
  if (code === 2) return ['weather_partly', day ? 'partly_cloudy_day' : 'partly_cloudy_night', day ? 'cloud' : 'night'];
  if (code === 3) return ['weather_cloudy', 'cloud', 'cloud'];
  if (code === 45 || code === 48) return ['weather_fog', 'foggy', 'cloud'];
  if (code >= 51 && code <= 57) return ['weather_drizzle', 'rainy_light', 'rain'];
  if (code >= 61 && code <= 67) return ['weather_rain', 'rainy', 'rain'];
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return ['weather_snow', 'weather_snowy', 'snow'];
  if (code >= 80 && code <= 82) return ['weather_showers', 'rainy_heavy', 'rain'];
  if (code >= 95) return ['weather_storm', 'thunderstorm', 'storm'];
  return ['weather_cloudy', 'cloud', 'cloud'];
}

const degrees = (t) => `${Math.round(t)}°`;

/** The weather now and of the next days, in the colors of the sky. */
export function WeatherHomeCard({ s }) {
  useEffect(() => {
    store.loadWeather();
  }, [s.weather?.lat, s.weather?.lon]);
  const w = s.weatherInfo;
  const [name, icon, look] = w ? condition(w.code, w.day) : ['', 'cloud', 'cloud'];
  const today = w?.days[0];
  return html`
    <div class=${`span weather-card sky-${look}`}>
      <${Icon} name=${icon} cls="weather-watermark" />
      <div class="weather-head">
        <${Icon} name="location_on" />
        <span class="title-small grow ellipsis">${s.weather.name}</span>
        ${w && html`<span class="label-large dim">${str(name)}</span>`}
      </div>
      ${!w ? (s.weatherFailed ? html`<div class="body-large dim">${str('weather_unavailable')}</div>` : html`<span class="team-loading"><span></span></span>`) : html`
        <div class="weather-now">
          <${Icon} name=${icon} cls="weather-icon" />
          <span class="weather-temp tnum">${degrees(w.temperature)}</span>
          <div class="weather-facts label-large">
            ${today && html`<span>${degrees(today.max)} / ${degrees(today.min)}</span>`}
            <span class="dim">${str('weather_feels', degrees(w.feels))}</span>
            <span class="dim">${today?.rain != null ? str('weather_rain_chance', today.rain) : str('weather_humidity', w.humidity)} · ${str('weather_wind', Math.round(w.wind))}</span>
          </div>
        </div>
        <div class="weather-days">${w.days.slice(1, 5).map((d, i) => html`
          <div key=${d.date} class="weather-day" style=${`--i:${i}`}>
            <span class="label-medium dim">${new Date(`${d.date}T12:00`).toLocaleDateString(locale(), { weekday: 'short' })}</span>
            <${Icon} name=${condition(d.code)[1]} />
            <span class="label-large tnum">${degrees(d.max)}<span class="dim"> ${degrees(d.min)}</span></span>
          </div>`)}
        </div>`}
    </div>`;
}

/** The section of the settings: whether the home shows the weather, and of which place. */
export function WeatherSettings({ s }) {
  const [picking, setPicking] = useState(false);
  return html`
    <${Toggle} title=${str('weather_home')} checked=${s.weather != null}
      onChange=${(on) => (on ? setPicking(true) : store.setWeather(null))} />
    ${s.weather && html`<${Setting} title=${str('weather_place')} text=${s.weather.name} onClick=${() => setPicking(true)} />`}
    ${picking && html`<${PlacePicker} onDismiss=${() => setPicking(false)} onPick=${(place) => {
      store.setWeather(place);
      setPicking(false);
    }} />`}`;
}

/** A town searched by name, among those of Open-Meteo. */
function PlacePicker({ onPick, onDismiss }) {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState(null);
  const [searching, setSearching] = useState(false);
  useEffect(() => {
    if (query.trim().length < 2) return setFound(null);
    setSearching(true);
    let current = true;
    const wait = setTimeout(async () => {
      const places = await store.searchPlaces(query.trim());
      if (!current) return;
      setFound(places ?? []);
      setSearching(false);
    }, 350);
    return () => {
      current = false;
      clearTimeout(wait);
    };
  }, [query]);
  return html`<${Dialog} title=${str('weather_place_none')} onDismiss=${onDismiss}
    actions=${html`<button class="text-btn" onClick=${onDismiss}>${str('cancel')}</button>`}>
    <${Field} label=${str('weather_search')} value=${query} onInput=${setQuery} autofocus />
    <div class="place-list">
      ${searching ? html`<div class="team-center"><${Spinner} /></div>`
        : found && !found.length ? html`<div class="body-medium faint" style="padding:16px 0">${str('weather_no_results')}</div>`
        : (found ?? []).map((p) => html`<${Setting} key=${`${p.lat},${p.lon}`} title=${p.name} text=${p.detail}
            onClick=${() => onPick({ name: p.name, lat: p.lat, lon: p.lon })} />`)}
    </div>
  <//>`;
}
