// The server of the home, which speaks to Alexa, SwitchBot and the TVs in place of the browser.
import { str } from '/shared/i18n.js';

/** No network, or the server cannot reach Amazon, SwitchBot or the TV: what the app calls having no connection. */
export class OfflineError extends Error {
  constructor(message = str('no_connection')) {
    super(message);
  }
}

/** Thrown when the Amazon sign-in is missing or no longer accepted. */
export class NotLoggedInError extends Error {
  constructor(message = str('login_required')) {
    super(message);
  }
}

/** The session with the server is over: the account has to sign in again. */
export class SessionError extends Error {}

const sessionListeners = new Set();

/** Called when a request finds the session with the server gone. */
export function onSessionLost(listener) {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

/** Sends a request to the server and returns its answer; failures come back as the errors above. */
export async function api(method, path, body) {
  let response;
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new OfflineError();
  }
  const data = await response.json().catch(() => null);
  if (response.ok) return data;
  if (response.status === 401 && !['/api/login', '/api/register'].includes(path)) {
    for (const listener of sessionListeners) listener();
    throw new SessionError(data?.error || str('srv_session_lost'));
  }
  if (data?.code === 'alexa_login') throw new NotLoggedInError(data.error);
  if (data?.code === 'offline' || response.status === 502 && !data?.error || response.status >= 503) {
    throw new OfflineError(data?.error || undefined);
  }
  throw new Error(data?.error || str('something_wrong'));
}

export const get = (path) => api('GET', path);
export const post = (path, body = {}) => api('POST', path, body);
export const del = (path) => api('DELETE', path);
