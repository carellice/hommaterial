# Hommaterial sul server di casa

La versione web di Hommaterial, da installare su un server di casa con
[CasaOS](https://casaos.zimaspace.com/) (o qualsiasi computer Linux con Docker): la stessa app,
da aprire nel browser o da installare sulla schermata Home di qualsiasi telefono, tablet o
computer, iPhone compresi. Le avvertenze dell'app Android valgono anche qui: **app non
ufficiale, non affiliata ad Amazon, uso a proprio rischio**.

Le parole sono quelle dell'app Android (`tools/strings.py` le prende da `app/src/main/res`), e il
backup è lo stesso file: si esporta da una e si importa nell'altra.

<p align="center">
  <a href="../docs/video/hommaterial-promo.mp4"><img src="../docs/video/anteprima.gif" alt="Presentazione animata di Hommaterial" width="640"></a>
</p>

## Perché un server

Un sito da solo non può parlare con Alexa, SwitchBot o i TV LG, e funziona solo finché è aperto.
Il server di casa fa quello che sul telefono fa l'app Android in sottofondo, e qualcosa in più:

- **parla con Amazon, SwitchBot e Cloudflare** al posto del browser: token e chiavi restano sul
  server, il browser non li vede mai;
- **esegue timer, azioni programmate, automazioni e avvisi di temperatura 24 ore su 24**, anche
  con l'app chiusa e il telefono spento, e lo dice con una **notifica push** a ogni dispositivo
  che le ha attivate;
- **raccoglie lo storico dei sensori** da solo, senza buchi;
- **comanda i TV LG** sulla rete di casa: li cerca, li abbina, preme i tasti e li accende con
  Wake-on-LAN, anche quando sei fuori casa;
- **tiene l'assistente AI**: le chiavi di Cloudflare restano sul server e la correzione dei
  comandi vocali vale anche nel browser;
- **un account per persona**: ognuno ha il suo accesso ad Amazon, le sue stanze, i suoi timer e
  le sue automazioni, separati da quelli degli altri.

## Installazione su CasaOS

Serve un server con CasaOS (o Linux con Docker) acceso sempre, nella rete di casa: un mini PC,
un Raspberry Pi 4 o 5, uno ZimaBoard. Dal terminale di CasaOS, o in SSH:

```bash
curl -fsSL https://raw.githubusercontent.com/carellice/hommaterial/main/pwa/casaos/installa.sh | sudo bash
```

L'installazione guidata chiede, con un valore già pronto da confermare con Invio:

1. **la cartella dei dati** (`/DATA/AppData/hommaterial` su CasaOS);
2. **la porta** (8096);
3. **il fuso orario**, per timer e azioni programmate;
4. **il codice famiglia**: serve per creare un account, così chi raggiunge il server per caso
   non può entrare. Lo mostra anche alla fine;
5. **come aprirla dal telefono in HTTPS**, che serve per installarla come app e ricevere le
   notifiche:
   - **Tailscale** (consigliato): HTTPS vero, funziona anche fuori casa, solo per i tuoi
     dispositivi. Installa prima Tailscale dall'App Store di CasaOS e attiva in Tailscale
     *DNS → MagicDNS* e *HTTPS Certificates*;
   - **HTTPS in casa**: il server crea un suo certificato, da installare una volta su ogni
     telefono dalla pagina `http://<server>:8096/certificato`;
   - **solo HTTP**: se hai già un reverse proxy o un Cloudflare Tunnel con HTTPS davanti.

Poi costruisce l'app (la prima volta qualche minuto), la avvia, la aggiunge alla dashboard di
CasaOS e offre l'aggiornamento automatico notturno. Alla fine stampa gli indirizzi da aprire.

Il container usa la **rete dell'host**: è il modo per cercare i TV LG nella rete di casa e
accenderli con Wake-on-LAN. Per lo stesso motivo il server deve stare nella stessa rete dei TV.

## Primo accesso

1. Apri l'indirizzo che l'installazione ha stampato e tocca **Creane uno**: nome utente,
   password e codice famiglia.
2. Accetta le avvertenze.
3. **Accedi ad Amazon**: tocca **Apri la pagina di accesso**, entra con il tuo account
   (verifica in due passaggi compresa). Alla fine Amazon mostra una pagina vuota o di errore: è
   normale. Copia l'indirizzo completo dalla barra del browser (contiene `maplanding`), torna in
   Hommaterial, incollalo e tocca **Completa l'accesso**. La password resta tra te e Amazon.
4. **Installala come app**: su Android con Chrome, menu ⋮ → **Installa app**; su iPhone con
   Safari, **Condividi → Aggiungi alla schermata Home** (le notifiche su iPhone arrivano solo
   così, da iOS 16.4).
5. **Notifiche**: in **Impostazioni → Notifiche** attivale su ogni dispositivo che deve
   riceverle; **Manda una notifica di prova** controlla che arrivino.
6. **Le scelte dell'app Android**: esporta il backup da Android (**Impostazioni → Backup →
   Esporta**) e importalo da **Impostazioni → Backup → Importa**, scegliendo le categorie.

Ogni persona della famiglia crea il suo account e accede al suo Amazon: i dati restano separati.

## L'app Android sullo stesso server

Nell'app Android, **Impostazioni → Server di casa** collega il telefono allo stesso account del
server: stanze, gruppi, preferiti, dispositivi personalizzati, telecomandi, azioni programmate,
automazioni e avvisi sono gli stessi nell'app e nel browser, e i timer, le azioni e le
automazioni li esegue il server (il telefono smette di farlo da sé, per non farli due volte).
È spento finché non lo accendi: senza, l'app Android resta com'era.

- Senza rete le modifiche restano sul telefono e partono appena il server torna raggiungibile;
  se nel frattempo la stessa cosa è stata cambiata altrove, vale l'ultima modifica arrivata.
- In modalità server l'app comanda i dispositivi Alexa e i TV LG attraverso il server: l'accesso
  ad Amazon è quello dell'account del server.
- Le notifiche push arrivano ai browser; l'app Android mostra le sue da sé.
- Per l'HTTPS in casa installa sul telefono il certificato del server, come per il browser.

## Musica

La pagina **Musica** suona i brani della cartella della musica scelta all'installazione
(di solito `/DATA/Media/Music`): ogni account ha la sua sottocartella, con il suo nome.
Si caricano dalla pagina, brani o cartelle intere, oppure si copiano lì direttamente.
Sui server installati prima della musica, l'aggiornamento notturno collega da solo
`/DATA/Media/Music` (o `/opt/hommaterial/music`) e ci sposta quello già caricato:
`hommaterial-aggiorna` si aggiorna dopo ogni passaggio, quindi serve fino alla seconda notte.
L'app Android la ascolta in streaming e la scarica per quando non c'è rete.

## Aggiornamenti

Se l'hai scelto all'installazione, ogni notte verso le 4 il server controlla su GitHub se c'è
una versione nuova: se c'è, salva una copia dei database, costruisce la nuova versione e la avvia;
se non parte, torna da sola a quella di prima. A mano:

```bash
sudo hommaterial-aggiorna
```

`--controlla` dice solo se c'è una versione nuova, `--forza` ricostruisce anche se è la stessa.
Le copie dei database stanno in `<cartella dei dati>/backup` (le ultime cinque); il registro in
`journalctl -u hommaterial-aggiorna`. L'app nel browser prende la nuova versione alla prima
apertura, o da **Controlla aggiornamenti** nel menu.

Chi pubblica: una nuova versione è il campo `version` di `pwa/server/package.json` cambiato e
pubblicato su `main`. Senza quel cambio i server non si aggiornano.

## Cosa cambia rispetto all'app Android

| App Android | Sul server di casa |
|---|---|
| Timer, azioni programmate, automazioni, avvisi fatti dal telefono | Li fa il server, anche con l'app chiusa; notifiche push |
| Storico dei sensori raccolto quando il telefono legge gli stati | Il server legge gli stati ogni 2–10 minuti da solo |
| TV LG solo con il telefono nella rete di casa | Il server li comanda sempre; il telefono può essere fuori |
| Token di Amazon, chiavi di SwitchBot e Cloudflare sul telefono | Sul server, per account; il browser non li vede |
| Accesso ad Amazon nella pagina dentro l'app | La pagina si apre in una scheda e l'indirizzo finale si incolla |
| Riconoscimento vocale del telefono | Quello del browser, dove c'è (Chrome, Edge, Safari) |
| Widget, riquadri delle Impostazioni rapide, scorciatoie dell'icona, tag NFC | Non esistono sul web; le scelte restano nel backup |
| Luminosità dello schermo nella schermata di riposo | Si attenua il testo; lo schermo resta acceso col Wake Lock |
| Vibrazione | Solo su Android, con Chrome |
| Aggiornamenti da GitHub con l'APK | Il server si aggiorna da solo, l'app nel browser alla prima apertura |

## Dati e privacy

- I dati stanno solo sul server, nella cartella scelta all'installazione: `accounts.db` con
  gli account e le sessioni, e `users/<numero>/hommaterial.db` per ogni account, con l'accesso ad
  Amazon, le scelte, le chiavi di SwitchBot, Cloudflare e dei TV LG, lo storico dei sensori.
- Le password degli account sono salvate come hash (scrypt), mai in chiaro.
- Il server parla con Amazon e, solo se li colleghi, con SwitchBot e Cloudflare; con GitHub per
  gli aggiornamenti. Non ci sono statistiche né pubblicità.
- Il backup in JSON contiene le chiavi di SwitchBot e Cloudflare e dei TV LG: custodiscilo.
- **Non esporre il server su Internet** senza protezione: con Tailscale lo raggiungono solo i
  tuoi dispositivi.

## Sviluppo

Serve Node 24. Il server esegue direttamente i file TypeScript e serve l'app web così com'è,
senza passaggi di build:

```bash
cd pwa/server && npm install
DATA_DIR=./data PORT=8096 INVITE_CODE=prova npm run dev
```

e si apre `http://localhost:8096`. Le cartelle:

- `web/`: l'app (Preact con htm, senza build), il service worker e il manifest;
- `shared/`: il modello, i testi e il backup, usati sia dall'app sia dal server;
- `server/`: account, database per account, Alexa, SwitchBot, TV LG, assistente, lavori in
  sottofondo e notifiche push;
- `casaos/`: installazione guidata, aggiornamento con ripristino e certificati HTTPS;
- `PORTING.md`: cosa dell'app Android è dove nella versione web.

```bash
python3 pwa/tools/strings.py
node pwa/tools/test.mjs
```

Il primo riscrive `shared/strings.js` e `web/js/version.js` dall'app Android, il secondo esegue i
test della logica.
