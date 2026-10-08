<p align="center">
  <img src="docs/icon.png" alt="Icona di Hommaterial" width="128" height="128">
</p>

<h1 align="center">Hommaterial</h1>

<p align="center"><b>Comanda la tua smart home Alexa, senza fronzoli.</b></p>

<p align="center">App Android · Gratuita · Open source · Italiano e inglese</p>

Un'app Android nativa, veloce e minimale per comandare i dispositivi smart home
collegati al proprio account Alexa.

---

# ⚠️ ATTENZIONE ⚠️

# APP NON UFFICIALE

# NON AFFILIATA AD AMAZON

# USO A PROPRIO RISCHIO

> ## Leggi prima di installare
>
> - **Hommaterial non è un prodotto Amazon.** Non è affiliata, sponsorizzata,
>   approvata né supportata in alcun modo da Amazon.com, Inc. o dalle sue
>   società collegate. "Amazon", "Alexa" ed "Echo" sono marchi di Amazon.com,
>   Inc. o delle sue affiliate, citati qui solo per descrivere con cosa l'app
>   è compatibile.
> - **Usa API non ufficiali e non documentate.** L'app comunica con i server
>   di Amazon presentandosi come l'app Alexa ufficiale. Questo con ogni
>   probabilità **viola le condizioni d'uso di Amazon**.
> - **Il tuo account è a rischio.** Amazon può limitare, sospendere o chiudere
>   gli account che usano client non autorizzati. Può anche cambiare le sue API
>   in qualsiasi momento e l'app può **smettere di funzionare senza preavviso**.
> - **L'app gestisce l'accesso al tuo account Amazon.** Il login avviene sulla
>   pagina di Amazon mostrata dentro l'app; sul telefono resta salvato un token
>   che dà accesso al tuo account Alexa. Installa solo build che hai compilato
>   tu o di cui ti fidi, e **non installare mai versioni modificate da
>   sconosciuti**.
> - **Comanda dispositivi reali.** Prese, stufe, climatizzatori: un errore
>   dell'app o un tocco sbagliato accende o spegne cose vere in casa tua.
> - **Nessuna garanzia.** Il software è fornito "così com'è", senza garanzie
>   di alcun tipo. Gli autori non rispondono di danni, perdite di dati, blocchi
>   dell'account o qualsiasi altra conseguenza derivante dal suo uso.
>
> ## Se non accetti tutto questo, non usare l'app.

---

## Cosa fa

- Mostra i dispositivi raggruppati per stanza, come configurati in Alexa.
- Un tocco accende o spegne.
- Tenendo premuto si apre il pannello con accensione, spegnimento esplicito,
  timer di spegnimento, luminosità e colore per le luci che li supportano.
- I timer in corso stanno in una sezione in cima alla lista, con il conto
  alla rovescia, ordinati dal più vicino allo spegnimento.
- I preferiti stanno in cima alla lista.
- Il widget per la schermata Home mostra i dispositivi e i gruppi scelti
  nelle Impostazioni, dove si regolano anche ordine, colonne, dimensione dei
  riquadri, intestazione e sfondo.
- Fino a cinque dispositivi a scelta si comandano dalle Impostazioni rapide di
  Android, ognuno con il suo riquadro.
- Dall'intestazione di una stanza si accende o spegne tutto in una volta.
- Col microfono in alto si dice "accendi la lampada" o "spegni tutto in
  salotto". Capisce solo accensione e spegnimento, e chiede conferma quando il
  nome non è esatto o riguarda una stanza intera.
- Mostra temperatura e umidità dei sensori, con il grafico dell'ultimo giorno
  o dell'ultima settimana. I dati li raccoglie il telefono quando l'app o il
  widget si aggiornano, quindi il grafico parte vuoto e può avere dei buchi.
- Avvisa con una notifica quando un sensore supera o scende sotto una
  temperatura scelta.
- Permette di nascondere i dispositivi che non interessano.
- All'apertura mostra subito l'ultimo stato noto e lo aggiorna in sottofondo.
- Controlla una volta al giorno se su GitHub c'è una versione più recente e,
  se confermi, la scarica e la installa. Il controllo si può anche avviare a
  mano da "Controlla aggiornamenti" nel menu.
- Dal menu si aprono le Impostazioni: tema chiaro, scuro o automatico,
  lingua, notifiche, aggiornamenti, stanze, preferiti e dispositivi nascosti,
  riquadri, timer e avvisi attivi, e il backup su file delle proprie scelte.
- Le stanze si possono riorganizzare dentro l'app, creandone quante se ne
  vuole e scegliendo i dispositivi di ognuna, senza cambiare niente in Alexa.
- I gruppi accendono o spengono insieme più dispositivi di stanze diverse.
- Sotto l'icona dell'app, tenendola premuta, compaiono i dispositivi e i
  gruppi scelti nelle Impostazioni, oppure i preferiti.
- Sugli schermi larghi, come i tablet, una barra laterale divide la casa in
  pagine (tutto, preferiti, gruppi e una per stanza) e il pannello di un
  dispositivo si apre di lato.

Non fa altro, di proposito: niente routine, niente assistente sempre in
ascolto, niente musica. La voce la trascrive il riconoscimento vocale del
telefono, non l'app.

Il timer di spegnimento lo fa scattare il telefono: all'ora stabilita deve
essere acceso e connesso. Una notifica dice com'è andata: dispositivo spento,
oppure timer non riuscito. Se non c'è rete riprova per dieci minuti prima di
rinunciare.

Gli avvisi di temperatura li controlla il telefono, circa ogni 15 minuti e
più di rado quando è fermo da un po': non sono un allarme in tempo reale.

## Limiti noti

- L'interfaccia è in italiano e in inglese e segue la lingua del telefono; con
  le altre lingue è in inglese.
- È stata provata solo con un account **amazon.it**. Alla schermata di accesso
  si possono scegliere altri marketplace (amazon.de, .fr, .es, .co.uk, .com),
  ma **non sono stati verificati**.
- Sono gestiti accensione/spegnimento, luminosità, colore delle luci (da un
  elenco fisso di colori e bianchi) e sensori di temperatura. Termostati,
  tapparelle e scene non sono ancora supportati.
- Richiede Android 8.0 o successivo.

## Dati e privacy

- L'app parla con i server di Amazon e, solo per cercare e scaricare gli
  aggiornamenti, con GitHub, a cui non invia alcun dato dell'account. Non ci
  sono server intermedi, statistiche né pubblicità.
- La password viene inserita nella pagina di Amazon e non viene letta né
  salvata dall'app.
- Sul telefono, nell'area privata dell'app, restano il token di accesso,
  l'elenco dei dispositivi e il loro ultimo stato. "Esci" dal menu cancella
  tutto.
- Per revocare l'accesso anche lato Amazon, rimuovi "Hommaterial" dai
  dispositivi registrati nel tuo account Amazon.

## Compilazione

Serve [Android Studio](https://developer.android.com/studio) (o l'SDK Android
con un JDK 17 o successivo).

```bash
./gradlew assembleDebug
```

L'APK si trova in `app/build/outputs/apk/debug/app-debug.apk`.

## Crediti

Il funzionamento dell'accesso e delle chiamate ad Alexa è stato ricavato
studiando [alexa-cookie](https://github.com/Apollon77/alexa-cookie) e
[alexa-remote](https://github.com/Apollon77/alexa-remote) di Ingo Fischer
(Apollon77), entrambi con licenza MIT. L'elenco completo è in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Licenza

[MIT](LICENSE). L'icona dell'app è stata generata con un sistema di
intelligenza artificiale.
