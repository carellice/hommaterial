// Keeps the app usable without a network and shows the notifications of the server. The files
// of the app come fresh from the server when it answers, from the copy kept here when it does not;
// the libraries and fonts, which never change, from the copy.
const CACHE = 'hommaterial-v2';
const SHELL = [
  '/', '/index.html', '/manifest.webmanifest', '/css/app.css', '/css/skeuo.css',
  '/icons/icon.svg', '/icons/icon-192.png', '/icons/apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith('hommaterial') && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('message', (event) => {
  if (event.data === 'skipWaiting') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin === location.origin) {
    // What the server knows is never kept here, and the check for a newer version must see it too.
    if (url.pathname.startsWith('/api/') || url.searchParams.has('at')) return;
    event.respondWith(fresh(request));
  } else if (/^(unpkg\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)$/.test(url.hostname)) {
    event.respondWith(kept(request));
  }
});

async function fresh(request) {
  const cache = await caches.open(CACHE);
  try {
    // Asked to the server every time, past the cache of the browser: a new version shows up at once.
    // A navigation cannot be given options, and the browser checks it with the server by itself.
    const response = await (request.mode === 'navigate' ? fetch(request) : fetch(request, { cache: 'no-cache' }));
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch (e) {
    const copy = await cache.match(request, { ignoreSearch: request.mode === 'navigate' });
    if (copy) return copy;
    if (request.mode === 'navigate') return (await cache.match('/')) || Response.error();
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

// A timer, a schedule, an automation or an alert, told by the server.
self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { title: 'Hommaterial', body: event.data ? event.data.text() : '' };
  }
  event.waitUntil((async () => {
    await self.registration.showNotification(data.title || 'Hommaterial', {
      body: data.body || '',
      icon: '/icons/icon-192.png',
      badge: '/icons/badge.png',
      tag: data.tag,
      data: { url: data.url || '/' },
    });
    // An open app shows at once what changed.
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of windows) client.postMessage({ type: 'pushed' });
  })());
});

// A touch on a notification brings the app forward, or opens it.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (windows.length) return windows[0].focus();
    return self.clients.openWindow(event.notification.data?.url || '/');
  })());
});
