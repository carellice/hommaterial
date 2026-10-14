import { html, render } from './lib.js';
import { App } from './ui/app.js';

render(html`<${App} />`, document.getElementById('app'));

// The service worker keeps the app usable without a network and shows its notifications.
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
