// The notifications of the Android app arrive as Web Push, sent by the server of the home.
import { get, post } from './net.js';
import { str } from '/shared/i18n.js';

const supported = () =>
  'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window && window.isSecureContext;

const KEY = 'hommaterial.push';

/** Whether this device can get notifications, and whether it asked the server for them. */
export function pushState() {
  let enabled = false;
  try {
    enabled = localStorage.getItem(KEY) === '1';
  } catch {
    // Without storage the switch shows off.
  }
  const can = supported();
  return {
    supported: can,
    denied: can && Notification.permission === 'denied',
    enabled: can && enabled && Notification.permission === 'granted',
  };
}

function remember(on) {
  try {
    if (on) localStorage.setItem(KEY, '1');
    else localStorage.removeItem(KEY);
  } catch {
    // Kept in memory only.
  }
}

function keyBytes(base64) {
  const padded = (base64 + '='.repeat((4 - (base64.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

/** Asks for the permission and subscribes this device; an error message, or null when it went well. */
export async function enablePush() {
  if (!supported()) return str('srv_push_unsupported');
  let permission = Notification.permission;
  if (permission === 'default') {
    try {
      permission = await Notification.requestPermission();
    } catch {
      permission = Notification.permission;
    }
  }
  if (permission !== 'granted') {
    remember(false);
    return permission === 'denied' ? str('pwa_notifications_denied') : null;
  }
  try {
    const registration = await navigator.serviceWorker.ready;
    const { key } = await get('/api/push/key');
    const subscription = (await registration.pushManager.getSubscription())
      ?? await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) });
    await post('/api/push/subscribe', { subscription: subscription.toJSON() });
    remember(true);
    return null;
  } catch (e) {
    remember(false);
    return e.message || str('something_wrong');
  }
}

export async function disablePush() {
  remember(false);
  if (!supported()) return;
  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  if (subscription) {
    await post('/api/push/unsubscribe', { endpoint: subscription.endpoint }).catch(() => {});
    await subscription.unsubscribe();
  }
}
