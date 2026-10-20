# Release da Windows

Le distribuzioni sono su **GitHub Releases**, senza pubblicazione sul Microsoft Store.
`app/build.gradle.kts` è la fonte della versione: Windows la legge durante la build;
lo script aggiorna anche `pwa/js/version.js`. Per esempio, `1.33` diventa `1.34` e
Android passa da `versionCode 34` a `35`.

## Preparazione, una sola volta

1. Installa Git, [GitHub CLI](https://cli.github.com/) e [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0).
2. Esegui `gh auth login` con un account che possa pubblicare sul repository.
3. Per Android installa Android Studio, SDK Platform 36 e i relativi Build-Tools.
   Il percorso predefinito è `%LOCALAPPDATA%\Android\Sdk`; per altri percorsi usa
   `ANDROID_HOME`. Serve anche Java 17 o il JDK incluso in Android Studio.
4. La nuova chiave dedicata è in `%USERPROFILE%\.android\hommaterial-signing\`:
   `hommaterial.keystore` contiene la chiave privata e `signing.properties` le password
   e la configurazione. Gradle e `release.bat` la leggono automaticamente.
   Conserva un backup privato **di entrambi i file** e non rigenerarli per ogni release.
   Su un altro PC ripristina la cartella oppure imposta `HOMMATERIAL_ANDROID_SIGNING_PROPERTIES`
   sul percorso assoluto del file `signing.properties`, aggiornando il suo `storeFile`.
   Per una prima configurazione su un progetto senza alcuna chiave esistente,
   `tools/New-AndroidSigningKey.ps1` ne crea una e si rifiuta di sovrascrivere file esistenti.

Non caricare mai chiavi o password su GitHub. Il rilascio congiunto confronta la firma
del nuovo APK con l'APK della release precedente e si ferma se sono diverse.
È stata autorizzata una nuova firma dopo la v1.33, perché la chiave del Mac non è
disponibile. `tools/android-signing-migration.json` contiene solo l'impronta pubblica
del nuovo certificato e la release precedente: permette questa precisa transizione,
senza disabilitare il controllo sulle release successive. Lo script aggiunge alle
note della prima release Android con nuova firma le istruzioni di migrazione.
Gli utenti devono esportare il backup, disinstallare il vecchio APK, installare il
nuovo, collegare nuovamente Amazon e importare il backup. La vecchia app non può
installare automaticamente un APK firmato con la nuova chiave.

Il percorso legacy `HOMMATERIAL_ANDROID_KEYSTORE`/`~/.android/debug.keystore` resta
disponibile per vecchi ambienti, ma dopo la migrazione anche sul Mac va usata la
nuova chiave dedicata per pubblicare APK compatibili.

## Un doppio clic per pubblicare

Prima salva le modifiche in un commit, aggiorna `main` e chiudi Hommaterial anche
dall'area di notifica. Poi fai doppio clic su **`release.bat`**.

Lo script:

- verifica strumenti, autenticazione, repository e assenza di modifiche non committate;
- incrementa l'ultimo numero della versione comune e il `versionCode` Android;
- esegue i test Android, compila l'APK e ne confronta la firma con quello già distribuito;
- esegue i test Windows e compila l'app WinUI x64 autonoma;
- prepara APK, ZIP Windows e file SHA-256 in `windows/artifacts/release-vVERSIONE/`;
- committa le versioni, pusha `main` e crea una release in bozza sul commit compilato;
- pubblica la bozza solo dopo il caricamento degli allegati.

Le release precedenti **non vengono eliminate**. Nessun passaggio apre Partner Center.
La build Windows ARM64 resta disponibile per sviluppo/CI: questo script distribuisce
solo x64, finché ARM64 non viene collaudato.

## Opzioni

Da PowerShell, nella cartella del repository:

```powershell
# Mostra il funzionamento senza modifiche, rete o pubblicazione.
.\release.bat -Plan

# Solo Windows: incrementa comunque la versione comune nei sorgenti.
.\release.bat -WindowsOnly

# Compila entrambi e prepara gli allegati senza commit/push/release.
# Al termine ripristina i numeri di versione nei sorgenti.
.\release.bat -PrepareOnly

# Stessa prova, senza SDK o chiave Android.
.\release.bat -WindowsOnly -PrepareOnly

# Solo prima distribuzione Windows: aggiunge lo ZIP alla release Android attuale.
# Non incrementa versioni, non ricompila né sostituisce l'APK.
.\release.bat -AttachWindowsToCurrent
```

`-WindowsOnly` non pubblica un APK con un numero fittizio: Android continua a ricevere
la propria ultima versione disponibile, perché la release Windows non prende il posto
della release contrassegnata come "Latest". Al prossimo rilascio congiunto i numeri tornano
uguali. Se una compilazione fallisce prima del commit, i file delle versioni vengono
ripristinati. Se fallisce un push o un caricamento dopo il commit, il commit resta:
leggi l'errore e verifica eventuali bozze su GitHub prima di riprovare. Non eliminare
release già installate per correggere una compilazione.

`release.command` resta il percorso legacy per compilare **solo Android** su Mac.
Per pubblicare contemporaneamente Android e Windows usa `release.bat`.

## Installazione e aggiornamenti Windows

Scarica `Hommaterial-vVERSIONE-windows-x64.zip` dalla release. Estrai **tutta** la
cartella in un percorso personale scrivibile, per esempio
`%LOCALAPPDATA%\Programs\Hommaterial`, e avvia `Hommaterial.Windows.exe`.
Non avviare l'EXE dentro lo ZIP e non spostare soltanto l'EXE.

L'app controlla le release stabili all'avvio e ogni sei ore. Puoi usare anche
**Impostazioni → Controlla aggiornamenti**. Quando confermi, scarica il pacchetto,
verifica dimensione e SHA-256 forniti dall'API GitHub, estrae e controlla i file,
chiude l'app, sostituisce i file e la riapre. Il programma di aggiornamento
conserva i file precedenti e tenta il ripristino se la copia fallisce.
Account e scelte restano in `%LOCALAPPDATA%\Hommaterial\Windows`, fuori dalla
cartella del programma. Durante il riavvio timer e automazioni sono sospesi.

Gli aggiornamenti non richiedono un token GitHub. Gli indirizzi accettati sono
solo quelli delle release di `carellice/hommaterial`; prerelease, downgrade,
pacchetti di altre architetture e release prive di digest valido vengono ignorati.
Il digest verifica l'integrità rispetto all'asset GitHub, non sostituisce una firma
Authenticode. I binari attuali non sono firmati: Windows può mostrare avvisi di
reputazione. Lo script PowerShell locale non richiede privilegi amministrativi;
policy aziendali possono impedirne l'esecuzione.

I file diagnostici degli aggiornamenti sono in
`%LOCALAPPDATA%\Hommaterial\Windows\updates\IDENTIFICATIVO\` (`result.txt` o `error.txt`).
Qui rimangono anche i file precedenti per il recupero manuale. Possono essere rimossi
quando l'app è chiusa e hai verificato che la nuova versione funziona.
Il riavvio riuscito va comunque collaudato su un aggiornamento tra due release reali:
una verifica dei soli download non dimostra il funzionamento dell'intero passaggio.
