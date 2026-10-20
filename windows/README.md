# Hommaterial per Windows 11

Prima versione nativa **C# / WinUI 3**, con Windows App SDK 2.5.1 e .NET 8.
L'interfaccia usa controlli Windows, Mica, navigazione laterale adattiva e tema di sistema.
Non incorpora la PWA: il client C# comunica direttamente con Amazon, senza proxy.
Le avvertenze del [progetto](../README.md) sulle API Alexa non ufficiali valgono anche qui.

## Provarla

Su Windows 11 x64, con [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0):

```powershell
.\windows\build.ps1 -Run
```

Se la build è già pronta, apri direttamente
`windows\artifacts\x64\app\Hommaterial.Windows.exe` con un doppio clic.
Per avviarla subito con la casa dimostrativa, dalla cartella del repository:

```powershell
.\windows\artifacts\x64\app\Hommaterial.Windows.exe --demo
```

Al primo avvio scegli **Prova la demo**: luci, sensori e stanze sono simulati e
non inviano richieste ad Amazon. Puoi accendere, spegnere, cambiare luminosità,
aggiungere preferiti, cercare e provare i timer.

Per i dispositivi reali scegli **Collega Amazon**, accetta le avvertenze e scegli il
marketplace. L'accesso e la verifica in due passaggi avvengono nel browser di sistema.
Alla fine copia l'indirizzo completo della pagina `https://www.amazon.com/ap/maplanding?…`
e incollalo nell'app. Il codice è usato con PKCE per registrare Hommaterial.
Non condividere questo indirizzo: contiene un codice di accesso temporaneo.

L'app pubblicata in `windows/artifacts/x64/app` è autonoma: **conserva tutta la cartella**,
non soltanto l'EXE. Non richiede Visual Studio né installazioni separate dei runtime .NET
e Windows App SDK. La prima compilazione richiede Internet per NuGet.

## Funzioni presenti

- Stanze Alexa e personalizzate, preferiti, dispositivi nascosti, gruppi e ordinamento.
- Ctrl+K cerca nella casa; Invio interagisce con il primo risultato. Clic sulla card per
  l'azione principale, tasto destro o pressione prolungata per i dettagli.
- Accensione/spegnimento, luminosità, colori e bianchi delle luci compatibili.
- Temperatura e umidità, grafici dell'ultimo giorno/settimana e avvisi con isteresi.
- Cache e aggiornamento degli stati ogni 45 secondi durante l'esecuzione.
- Timer, azioni programmate singole/settimanali e automazioni su stati e soglie.
- Dispositivi personalizzati con condizioni, sequenze di comandi e attese.
- Telecomandi SwitchBot con tasti personalizzati e controlli del condizionatore.
- Backup JSON compatibile con Android/web, esteso ad automazioni e programmi.
- Tema chiaro/scuro/di sistema, colori del logo, animazioni rispettose delle preferenze Windows.
- Notifiche Windows e area di notifica per continuare con la finestra chiusa.
- Monitor con pagine selezionabili, orologio, riposo e PIN locale facoltativo.
- Comandi vocali in italiano/inglese, riconoscimento Windows e alternativa scritta.
- Token Amazon e credenziali SwitchBot cifrati con DPAPI per l'utente Windows corrente.

**Limiti dell'anteprima:** l'interfaccia è italiana. Widget Android, NFC e riquadri
rapidi non sono funzioni Windows di questa build: le loro impostazioni vengono
conservate nel backup. Non è ancora una build certificata per il Microsoft Store.

Timer, programmi e automazioni lavorano finché il processo è in esecuzione, anche
con la finestra chiusa se l'app rimane nell'area di notifica. Non installano un
servizio e non si avviano automaticamente all'accesso a Windows. Uscendo completamente,
i programmi restano salvati ma riprendono solo al successivo avvio. Con PC spento o
in sospensione non vengono inviati comandi; un'azione scaduta da oltre 10 minuti
viene saltata. La logica dei programmi gestisce ritentativi limitati degli errori
di trasporto; i comandi rifiutati vengono notificati. Non è garantita l'esecuzione puntuale.

La demo conserva i dati in memoria. Per uscire usa **Collega Amazon**. I programmi
dell'account non vengono eseguiti mentre la demo è attiva.

Il backup non contiene sessione Amazon né PIN. Le credenziali SwitchBot sono incluse
solo scegliendo l'opzione di esportazione: in quel caso sono leggibili nel JSON.
Windows mostra un riepilogo prima del ripristino e chiede se importare il monitor
e attivare automazioni/programmi. Le vecchie versioni Android/web leggono i campi che
conoscono e possono perdere quelli aggiunti quando riesportano il file.

## Build, verifiche e architetture

```powershell
dotnet run --project windows/Hommaterial.Tests
.\windows\build.ps1 -Architecture x64 -Package
.\windows\build.ps1 -Architecture arm64 -Package
```

Le verifiche esercitano parsing delle risposte Alexa, PKCE, validazione dei redirect,
sessione con rinnovo dopo 401, identificativi di lettura/comando, rifiuti dei dispositivi,
backup, DPAPI, isteresi, scadenze, giorni della settimana, voce e firma SwitchBot.
Usano un trasporto HTTP simulato: **non comandano dispositivi reali**.

`Hommaterial.Core` contiene modelli e client HTTP, indipendenti dall'interfaccia;
`Hommaterial.Windows` contiene UI WinUI, persistenza DPAPI e integrazione con Windows.
`New-Assets.ps1` deriva le icone dalla stessa icona del progetto. `build.ps1` compila,
esegue le verifiche e usa MakeAppx, fornito dal pacchetto Microsoft SDK BuildTools,
per creare e validare un MSIX non firmato.

## Preparazione al Microsoft Store

Il manifest MSIX è predisposto per Windows 11 e include `internetClient` e `runFullTrust`
(app desktop WinUI). I valori di identità predefiniti sono **di sviluppo**.
Quando avrai riservato il nome in Partner Center, usa i valori esatti assegnati:

```powershell
.\windows\build.ps1 -Package `
  -IdentityName 'IDENTITA_DA_PARTNER_CENTER' `
  -Publisher 'CN=IDENTIFICATIVO_DA_PARTNER_CENTER' `
  -PublisherDisplayName 'NOME_EDITORE' `
  -Version '1.0.0.0'
```

Il pacchetto creato non è firmato: per l'installazione MSIX locale occorre firmarlo con
un certificato attendibile; per la prova immediata usa la cartella EXE autonoma.
Per lo Store Microsoft applica la propria firma dopo la certificazione.
Riferimento: [pubblicare un'app Windows](https://learn.microsoft.com/windows/apps/package-and-deploy/publish-first-app).

Prima della pubblicazione restano: identità effettiva, descrizione, screenshot,
URL pubblico dell'informativa privacy, classificazione, test su macchina pulita
e Windows App Certification Kit, verifica delle dipendenze e revisione delle condizioni
applicabili all'integrazione Amazon. La creazione del MSIX non garantisce l'accettazione
da parte dello Store. Questa attività non pubblica né carica nulla su Partner Center.

## Dati locali

I dati dell'app sono in `%LOCALAPPDATA%\Hommaterial\Windows`, con l'eventuale
virtualizzazione quando installata come MSIX. `home.json` contiene scelte, cache,
programmi, automazioni e cronologia, `session.bin` il token Amazon cifrato e
`switchbot.bin` le credenziali SwitchBot cifrate. Il registro `errors.log` contiene
messaggi e stack degli errori, senza corpi HTTP o cookie. Leggi [PRIVACY.md](PRIVACY.md).
La disconnessione Amazon cancella token e dati della casa, non le credenziali SwitchBot:
quest'ultimo è un account separato e si rimuove con **Scollega SwitchBot**. Per revocare
anche la registrazione sui server Amazon rimuovi Hommaterial dai dispositivi dell'account.
