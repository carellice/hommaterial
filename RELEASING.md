# Release da Windows

Le distribuzioni sono su **GitHub Releases**.
`app/build.gradle.kts` è la fonte della versione; lo script aggiorna anche `pwa/web/js/version.js`. Per esempio, `1.33` diventa `1.34` e
Android passa da `versionCode 34` a `35`.

## Preparazione, una sola volta

1. Installa Git e [GitHub CLI](https://cli.github.com/).
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

Prima salva le modifiche in un commit e aggiorna `main`. Poi fai doppio clic su **`release.bat`**.

Lo script:

- verifica strumenti, autenticazione, repository e assenza di modifiche non committate;
- incrementa l'ultimo numero della versione e il `versionCode` Android;
- esegue i test Android, compila l'APK e ne confronta la firma con quello già distribuito;
- prepara APK e file SHA-256 in `app/build/release-vVERSIONE/`;
- committa le versioni, pusha `main` e crea una release in bozza sul commit compilato;
- pubblica la bozza solo dopo il caricamento degli allegati.

Le release precedenti **non vengono eliminate**.

## Opzioni

Da PowerShell, nella cartella del repository:

```powershell
# Mostra il funzionamento senza modifiche, rete o pubblicazione.
.\release.bat -Plan

# Compila e prepara gli allegati senza commit/push/release.
# Al termine ripristina i numeri di versione nei sorgenti.
.\release.bat -PrepareOnly
```

Se una compilazione fallisce prima del commit, i file delle versioni vengono
ripristinati. Se fallisce un push o un caricamento dopo il commit, il commit resta:
leggi l'errore e verifica eventuali bozze su GitHub prima di riprovare. Non eliminare
release già installate per correggere una compilazione.

`release.command` resta il percorso legacy per compilare Android su Mac.
