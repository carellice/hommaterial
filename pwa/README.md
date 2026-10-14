# Hommaterial per il web

La versione PWA di Hommaterial: la stessa app, da aprire nel browser o da
installare sulla schermata Home di qualsiasi telefono, tablet o computer,
iPhone compresi. Le avvertenze dell'app Android valgono anche qui: **app non
ufficiale, non affiliata ad Amazon, uso a proprio rischio**.

Le parole sono quelle dell'app Android (`tools/strings.py` le prende da
`app/src/main/res`), e il backup è lo stesso file: si esporta da una e si
importa nell'altra.

## Perché serve un proxy

Un sito non può parlare direttamente con Alexa né con SwitchBot: i loro server
rifiutano le richieste che arrivano da altre pagine (CORS), e il browser non
lascia inviare i cookie di Amazon. Per questo le richieste passano da un
piccolo proxy su Cloudflare Workers, gratuito, che crei tu:

- inoltra solo verso i domini di Amazon e di SwitchBot;
- non salva nulla: token e chiavi restano nel browser e viaggiano nella
  singola richiesta;
- risponde solo alla pagina della PWA (`ALLOWED_ORIGINS`) e, se vuoi, solo a
  chi conosce una chiave (`PROXY_KEY`).

Usa sempre un proxy tuo: vede passare il token del tuo account.

### Creare il proxy

Con l'account Cloudflare (il piano gratuito basta):

1. Dashboard → **Workers & Pages** → **Create** → **Hello World** → dai il nome
   `hommaterial-proxy` → **Deploy**.
2. **Edit code**: sostituisci tutto con il contenuto di `worker/worker.js` →
   **Deploy**.
3. **Settings → Variables and Secrets**: aggiungi `ALLOWED_ORIGINS` con
   `https://carellice.github.io` e, se vuoi, il segreto `PROXY_KEY`.
4. L'indirizzo è del tipo `https://hommaterial-proxy.<account>.workers.dev`.

In alternativa, da terminale, nella cartella `pwa/worker`:

```bash
npx wrangler deploy
```

Per non scriverlo su ogni dispositivo, l'indirizzo si può mettere in
`config.js`.

## Pubblicarla su GitHub Pages

Il workflow `.github/workflows/pages.yml` pubblica la cartella `pwa` a ogni
push che la cambia. Una sola volta: nel repository, **Settings → Pages →
Source: GitHub Actions**. L'app sarà su
`https://carellice.github.io/hommaterial/`.

## Accedere ad Amazon

La pagina di Amazon non si può mostrare dentro una pagina web come fa l'app
Android, quindi l'accesso ha un passo in più:

1. **Apri la pagina di accesso**: si apre Amazon in una nuova scheda; entra,
   verifica in due passaggi compresa.
2. Amazon finisce su una pagina vuota o di errore il cui indirizzo contiene
   `maplanding`: copialo per intero dalla barra del browser.
3. Incollalo nell'app e tocca **Completa l'accesso**.

## Cosa cambia rispetto all'app Android

C'è tutto quello che un browser permette: stanze, gruppi, preferiti,
riordino, vista a griglia o elenco, ricerca, comandi vocali (dove il browser
ha il riconoscimento vocale, come Chrome), luminosità e colori, timer,
grafici dei sensori, avvisi, dispositivi personalizzati, telecomandi
SwitchBot, azioni programmate, automazioni, modalità monitor con PIN e
schermata di riposo, tema, lingua, backup.

Quello che un browser non permette:

- **Niente in sottofondo.** Timer, azioni programmate, automazioni e avvisi
  funzionano solo mentre l'app è aperta. Se a quell'ora era chiusa, alla
  riapertura l'app avvisa di quello che ha saltato invece di farlo in
  ritardo. Per una casa che deve funzionare da sola resta l'app Android, o un
  tablet in modalità monitor sempre acceso.
- **Niente widget, riquadri rapidi né scorciatoie dell'icona.** Le scelte
  fatte per questi nell'app Android restano nel backup.
- **Luminosità dello schermo.** La schermata di riposo attenua il testo, non
  lo schermo. Lo schermo sempre acceso usa il Wake Lock, dove il browser lo
  supporta.
- **Notifiche.** Vanno consentite al browser; su iPhone solo dopo aver
  aggiunto l'app alla schermata Home.
- **Aggiornamenti.** Arrivano da soli a ogni apertura: niente APK da scaricare.

## Sviluppo

```bash
python3 tools/strings.py
node tools/test.mjs
```

Dalla cartella `pwa`: il primo riscrive `js/strings.js` e `js/version.js` dall'app Android, il
secondo esegue i test della logica. Per provarla in locale:

```bash
python3 -m http.server 8000 --directory pwa
```

e, in un altro terminale, il proxy su `http://localhost:8787`:

```bash
node pwa/worker/dev.mjs
```
