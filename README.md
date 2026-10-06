<p align="center">
  <img src="docs/icon.png" alt="Icona di Hommaterial" width="128" height="128">
</p>

<h1 align="center">Hommaterial</h1>

<p align="center"><b>Comanda la tua smart home Alexa, senza fronzoli.</b></p>

<p align="center">App Android · Gratuita · Open source · Solo italiano</p>

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
- Tenendo premuto si apre il pannello con accensione, spegnimento esplicito e
  luminosità per le luci dimmerabili.
- Mostra temperatura e umidità dei sensori.
- Permette di nascondere i dispositivi che non interessano.
- All'apertura mostra subito l'ultimo stato noto e lo aggiorna in sottofondo.

Non fa altro, di proposito: niente routine, niente comandi vocali, niente
musica.

## Limiti noti

- L'interfaccia è solo in italiano.
- È stata provata solo con un account **amazon.it**. Alla schermata di accesso
  si possono scegliere altri marketplace (amazon.de, .fr, .es, .co.uk, .com),
  ma **non sono stati verificati**.
- Sono gestiti solo accensione/spegnimento, luminosità e sensori di
  temperatura. Colore delle luci, termostati, tapparelle e scene non sono
  ancora supportati.
- Richiede Android 8.0 o successivo.

## Dati e privacy

- L'app parla solo con i server di Amazon. Non ci sono server intermedi,
  statistiche né pubblicità.
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
