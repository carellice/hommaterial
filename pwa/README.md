# Hommaterial per il web

La versione PWA di Hommaterial: la stessa app, da aprire nel browser o da
installare sulla schermata Home di qualsiasi telefono, tablet o computer,
iPhone compresi: **[carellice.github.io/hommaterial](https://carellice.github.io/hommaterial/)**. Le avvertenze dell'app Android valgono anche qui: **app non
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

## Configurazione passo per passo

### 1. Crea il proxy su Cloudflare (gratis, una volta sola)

1. Vai su [dash.cloudflare.com](https://dash.cloudflare.com) e registrati se
   non hai un account: il piano gratuito basta.
2. **Workers & Pages → Create → Hello World**, dai il nome
   `hommaterial-proxy` e premi **Deploy**.
3. **Edit code**: sostituisci tutto con il contenuto di
   [`worker/worker.js`](worker/worker.js) e premi **Deploy**.
4. **Settings → Variables and Secrets**: aggiungi la variabile
   `ALLOWED_ORIGINS` con valore `https://carellice.github.io`. Se vuoi, aggiungi
   anche il segreto `PROXY_KEY` con una parola a tua scelta: senza quella il
   proxy non risponde a nessuno.
5. Annota l'indirizzo che Cloudflare ti dà, del tipo
   `https://hommaterial-proxy.<tuo-account>.workers.dev`.

In alternativa, da terminale, nella cartella `pwa/worker`:

```bash
npx wrangler deploy
```

### 2. Primo accesso

1. Apri [carellice.github.io/hommaterial](https://carellice.github.io/hommaterial/)
   e accetta le avvertenze.
2. Alla voce **Proxy** tocca **Imposta il proxy** e incolla l'indirizzo del
   Worker (e la chiave, se l'hai messa). L'app controlla che risponda prima di
   salvarlo.
3. Tocca **Apri la pagina di accesso**: Amazon si apre in una nuova scheda.
   Entra con il tuo account, verifica in due passaggi compresa.
4. Alla fine Amazon mostra una pagina vuota o di errore: è normale. Copia
   l'indirizzo completo dalla barra del browser (contiene `maplanding`).
5. Torna nell'app, incollalo e tocca **Completa l'accesso**.

La password resta tra te e Amazon: l'app riceve solo il codice che sta
nell'indirizzo.

### 3. Installala (facoltativo, consigliato)

- **Android, con Chrome:** menu ⋮ → **Installa app** (o **Aggiungi a
  schermata Home**).
- **iPhone e iPad, con Safari:** **Condividi → Aggiungi alla schermata Home**.
  Su iPhone le notifiche funzionano solo così.
- **Computer, con Chrome o Edge:** l'icona di installazione nella barra degli
  indirizzi.

### 4. Ultimi ritocchi (facoltativi)

- **Notifiche:** in **Impostazioni → Notifiche** tocca per consentirle.
  Servono a timer, azioni programmate, automazioni e avvisi.
- **Le scelte dell'app Android:** esporta il backup da Android
  (**Impostazioni → Backup → Esporta**) e importalo nella PWA dallo stesso
  menu. Stanze, gruppi, preferiti, dispositivi personalizzati e il resto
  arrivano uguali.
- **SwitchBot:** in **Impostazioni → Telecomandi SwitchBot** inserisci token e
  chiave segreta, come nell'app Android.
- **Più dispositivi:** se scrivi l'indirizzo del Worker in
  [`config.js`](config.js), ogni dispositivo lo trova già impostato e salta il
  passo 2.2.

Se il primo accesso non riesce, il messaggio in rosso nella schermata di
accesso dice perché.

## Pubblicarla su GitHub Pages

Serve solo a chi pubblica la propria copia del progetto. Il workflow
`.github/workflows/pages.yml` pubblica la cartella `pwa` a ogni push che la
cambia. Una sola volta, nel repository: **Settings → Pages → Source: GitHub
Actions**. Chi la pubblica altrove mette il proprio indirizzo in
`ALLOWED_ORIGINS` del Worker.

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
