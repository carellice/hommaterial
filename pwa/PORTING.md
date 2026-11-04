# Porting: dall'app Android al server di casa

Dove sta nella versione web ogni parte dell'app Android, e a che punto è. Stato: **fatto**
(scritto e provato in locale), **verificato** (provato anche col sistema vero), **non portabile**.

L'interfaccia web è quella della PWA che esisteva già (Preact con htm, senza build), fatta sui
valori di Compose: colori Material 3 chiaro e scuro in `web/css/app.css`, Roboto Flex, simboli
Material. Il server porta tutto quello che il telefono faceva fuori dalle schermate.

## Schermate

| Android | Web | Stato |
|---|---|---|
| `ui/OnboardingScreen.kt` | `web/js/ui/screens.js` `OnboardingScreen` | fatto |
| — (nuovo) account del server | `web/js/ui/account.js` `AccountScreen` | fatto |
| `ui/LoginScreen.kt` (WebView Amazon) | `screens.js` `LoginScreen`: scheda + indirizzo incollato | fatto |
| `ui/HomeScreen.kt` | `web/js/ui/home.js` | fatto |
| `ui/Pages.kt`, barra laterale | `home.js` `SideBar`, `shared/model.js` `availablePages` | fatto |
| `ui/RestScreen.kt`, `ui/Clock.kt` | `screens.js` `RestScreen` | fatto |
| `ui/SettingsScreen.kt` | `web/js/ui/settings.js` | fatto |
| `ui/MonitorSettings.kt`, `ui/PinDialog.kt` | `settings.js` `MonitorSettings`, `common.js` `PinDialog` | fatto |
| `ui/Remotes.kt` | `web/js/ui/remotes.js` | fatto |
| `ui/LgTvs.kt` | `web/js/ui/lgtv.js` | fatto (provato con un TV finto) |
| `ui/Assistant.kt` | `web/js/ui/assistant.js` | fatto |
| `ui/Macros.kt` | `web/js/ui/macros.js` | fatto |
| `ui/Schedules.kt` | `web/js/ui/schedules.js` | fatto |
| `ui/Automations.kt` | `web/js/ui/automations.js` | fatto |
| `ui/RoomDialogs.kt` | `web/js/ui/rooms.js` | fatto |
| `ui/HistoryChart.kt` | `web/js/ui/chart.js` (dati da `/api/history`) | fatto |
| `ui/UpdateDialog.kt` | `web/js/ui/app.js` `UpdateDialog` | fatto |
| `ui/BottomNav.kt` (barra in basso: Casa e Liste) | `home.js` `BottomBar` | fatto |
| `ui/Lists.kt` (pagina delle liste di Alexa) | `web/js/ui/lists.js` `ListsPage`, `/api/lists` | verificato con Amazon |
| `ui/NfcTag.kt` | — | non portabile |

## Dati

| Android (SharedPreferences) | Server | Stato |
|---|---|---|
| `cache`: devices, states, hidden, favorites, rooms, roomList, groups, order, tileSpans, macros, shortcuts, widget, tileDevice* | `kv` per account, stessi nomi e stesso JSON (`server/src/db.ts`) | fatto |
| `Timers`, `Schedules`, `Automations`, `Alerts` | `kv` timers, schedules, automations, alerts; "tripped" in `meta` | fatto |
| `history` (storico sensori) | tabella `history` per account, una settimana, una lettura ogni 10 minuti al massimo | fatto |
| `switchbot` (token, secret, remotes, buttons, hidden, ac) | chiavi in `meta`, il resto in `kv` | fatto |
| `lgtv` (TV con chiave e impronta) | `meta` `lgtv`; in `kv` `lgTvs` senza chiave né impronta | fatto |
| `assistant` (account, modello, notes, house, taught) | chiavi e modello in `meta`, il resto in `kv` | fatto |
| `auth` (refresh token, cookie, csrf, marketplace) | `meta` per account | fatto |
| `settings` (tema, layout, lingua, monitor, PIN) | `localStorage` per dispositivo, come per tablet sull'Android | fatto |
| Backup JSON v1 (`BackupSelection.kt`) | `shared/backup.js` + `/api/backup/keys` e `/api/backup/restore` | fatto |

## Lavori in sottofondo e notifiche

| Android | Server (`server/src/engine.ts`) | Stato |
|---|---|---|
| `quick/Timers.kt` (AlarmManager) | controllo ogni 5 s, riprova per 10 minuti senza rete | fatto |
| `quick/Schedules.kt` | come sopra; le ripetute passano al giorno dopo | fatto (provato) |
| `quick/Automations.kt` (allo scatto della lettura) | letture ogni 2 minuti con automazioni attive | fatto |
| `quick/Alerts.kt` (WorkManager ~15 min) | letture ogni 10 minuti, isteresi 0,3° | fatto |
| Notifiche locali | Web Push (`server/src/push.ts`, `web/sw.js`) con gli stessi testi | fatto; consegna sul telefono da verificare |
| `data/Updater.kt` (APK da GitHub) | `casaos/aggiorna.sh` notturno + service worker | fatto |

## Integrazioni

| Android | Server | Stato |
|---|---|---|
| `data/AlexaAuth.kt`, `data/AlexaApi.kt` | `server/src/alexa.ts` | fatto; con Amazon vero da verificare |
| `data/SwitchBot.kt` | `server/src/switchbot.ts` (firma provata nei test) | fatto |
| `data/LgTv.kt` (OkHttp WebSocket, pin del certificato) | `server/src/lgtv.ts` + `websocket.ts` scritto a mano | fatto (TV finto); TV vero da verificare |
| `data/Assistant.kt` (Workers AI) | `server/src/assistant.ts` | fatto; con Cloudflare vero da verificare |
| `voice/VoiceParser.kt` | `web/js/voice.js`, correzione con `/api/assistant/correct` | fatto |

## Non portabili

| Android | Alternativa |
|---|---|
| Widget della schermata Home e della schermata di blocco | nessuna; le scelte restano nel backup |
| Riquadri delle Impostazioni rapide (`quick/PowerTile.kt`) | nessuna; le scelte restano nel backup |
| Scorciatoie dell'icona (`quick/Shortcuts.kt`) | nessuna |
| Tag NFC (`quick/Nfc.kt`) | nessuna |
| Luminosità dello schermo a riposo | si attenua il testo |
| Vibrazione con intensità | `navigator.vibrate`, solo Android |

## App Android in modalità server

| Parte | File | Stato |
|---|---|---|
| Client HTTP e sessione cifrata | `app/.../data/ServerClient.kt` | fatto |
| Sincronizzazione delle scelte, coda offline | `app/.../data/ServerSync.kt` | fatto |
| Comandi via server (Alexa, telecomandi, TV LG, dispositivi personalizzati) | `HomeViewModel.kt` | fatto |
| Timer, azioni, automazioni, avvisi lasciati al server | `quick/*` saltati in modalità server | fatto |
| Schermata di collegamento | `ui/ServerSettings.kt` | fatto |
