import webpush from 'web-push'
import { getServerMeta, setServerMeta, userIds } from './accounts.ts'
import { db, withUser } from './db.ts'

// Web Push replaces the notifications of the Android app: timers, schedules, automations and
// alerts now run on the server, which tells every device of the account how they went.

// One pair of keys for the whole server; each account keeps the subscriptions of its own devices.
let keys = { publicKey: getServerMeta('vapid_public') ?? '', privateKey: getServerMeta('vapid_private') ?? '' }
if (!keys.publicKey || !keys.privateKey) {
  keys = webpush.generateVAPIDKeys()
  setServerMeta('vapid_public', keys.publicKey)
  setServerMeta('vapid_private', keys.privateKey)
}
webpush.setVapidDetails('https://github.com/carellice/hommaterial', keys.publicKey, keys.privateKey)

export const vapidPublicKey = keys.publicKey

type Subscription = { endpoint: string; keys: { p256dh: string; auth: string } }

/** Subscribes a device of the current account. A phone moved to another account stops getting the old one's notifications. */
export function subscribe(subscription: Subscription, reminders: boolean, userId: number) {
  for (const other of userIds()) {
    if (other !== userId) withUser(other, () => unsubscribe(subscription.endpoint))
  }
  db.prepare('INSERT INTO push_subs (endpoint, json, reminders) VALUES (?, ?, ?) ON CONFLICT(endpoint) DO UPDATE SET json = excluded.json, reminders = excluded.reminders')
    .run(subscription.endpoint, JSON.stringify(subscription), reminders ? 1 : 0)
}

export function unsubscribe(endpoint: string) {
  db.prepare('DELETE FROM push_subs WHERE endpoint = ?').run(endpoint)
}

/** url: where a tap on the notification opens the app. tag: replaces older ones with the same tag. */
export type Payload = { title: string; body: string; url: string; tag: string }

/** Sends to every device of the current account. */
export async function send(payload: Payload) {
  const rows = db.prepare('SELECT endpoint, json FROM push_subs WHERE reminders = 1').all() as { endpoint: string; json: string }[]
  await Promise.all(rows.map(async (row) => {
    try {
      await webpush.sendNotification(JSON.parse(row.json), JSON.stringify(payload), { TTL: 60 * 60 * 24 })
    } catch (e) {
      const status = (e as { statusCode?: number }).statusCode
      // The browser dropped the subscription: forget it.
      if (status === 404 || status === 410) unsubscribe(row.endpoint)
      else console.warn('Push non inviata:', (e as Error).message)
    }
  }))
}
