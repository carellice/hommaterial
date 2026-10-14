// Keeps the app usable without a network: its own files come fresh from the site when it answers,
// from the copy kept here when it does not; the libraries and fonts, which never change, from the copy.
const CACHE = 'hommaterial';
const SHELL = [
  './', 'index.html', 'manifest.webmanifest', 'css/app.css',
  'icons/icon.svg', 'icons/icon-192.png', 'icons/apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('message', (event) => {
  if (event.data === 'skipWaiting') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin === location.origin) {
    // The check for a newer version must see the site, never the copy.
    if (url.searchParams.has('at')) return;
    event.respondWith(fresh(request));
  } else if (/^(unpkg\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)$/.test(url.hostname)) {
    event.respondWith(kept(request));
  }
});

async function fresh(request) {
  const cache = await caches.open(CACHE);
  try {
    // Asked to the site every time, past the cache of the browser: a new version shows up at once.
    // A navigation cannot be given options, and the browser checks it with the site by itself.
    const response = await (request.mode === 'navigate' ? fetch(request) : fetch(request, { cache: 'no-cache' }));
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch (e) {
    const copy = await cache.match(request, { ignoreSearch: request.mode === 'navigate' });
    if (copy) return copy;
    if (request.mode === 'navigate') return (await cache.match('index.html')) || Response.error();
    throw e;
  }
}

async function kept(request) {
  const cache = await caches.open(CACHE);
  const copy = await cache.match(request);
  if (copy) return copy;
  const response = await fetch(request);
  if (response.ok || response.type === 'opaque') cache.put(request, response.clone());
  return response;
}

// A touch on a notification brings the app forward, or opens it.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (windows.length) return windows[0].focus();
    return self.clients.openWindow('./');
  })());
});
