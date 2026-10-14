import { Prefs } from './prefs.js';
import { str } from './i18n.js';

/** No network, or the proxy cannot be reached: what the app calls having no connection. */
export class OfflineError extends Error {
  constructor(message = str('no_connection')) {
    super(message);
  }
}

/** Thrown when the stored login is missing or no longer accepted by Amazon. */
export class NotLoggedInError extends Error {
  constructor(message = str('login_required')) {
    super(message);
  }
}

const settings = new Prefs('settings');

/** The address of the proxy the requests go through, and the word it may want along with them. */
export const proxy = {
  get url() {
    return settings.get('proxyUrl', window.HOMMATERIAL_PROXY || '');
  },
  get key() {
    return settings.get('proxyKey', '');
  },
  set(url, key) {
    settings.set('proxyUrl', url.trim().replace(/\/+$/, ''));
    settings.set('proxyKey', key.trim());
  },
};

/**
 * Sends a request through the proxy and returns what the server answered: its status, its
 * headers, the cookies it set and its body as text.
 */
export async function relay({ url, method = 'GET', headers = {}, body = null }) {
  if (!proxy.url) throw new Error(str('pwa_proxy_missing'));
  let response;
  try {
    response = await fetch(proxy.url, {
      method: 'POST',
      // Plain text keeps the request simple: the browser sends it without asking first.
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: JSON.stringify({ url, method, headers, body, key: proxy.key || undefined }),
    });
  } catch (e) {
    throw new OfflineError();
  }
  const data = await response.json().catch(() => null);
  if (!response.ok || !data || typeof data.status !== 'number') {
    if (data?.error === 'unreachable' || response.status >= 500) throw new OfflineError();
    throw new Error(str('pwa_proxy_refused', data?.error || response.status));
  }
  return data;
}

/** Checks that [url] is a Hommaterial proxy, before it is saved. */
export async function probe(url) {
  try {
    const response = await fetch(url.trim().replace(/\/+$/, ''));
    const data = await response.json();
    return data?.hommaterial === true;
  } catch {
    return false;
  }
}
