# Dati della versione Windows

Hommaterial comunica direttamente via HTTPS con Amazon per accesso, dispositivi e
comandi Alexa. Se colleghi SwitchBot, comunica con la sua API ufficiale per elencare
e comandare i telecomandi. Non usa server intermedi, pubblicità, statistiche o
telemetria propria. La demo non invia comandi ad Amazon o SwitchBot.

Per gli aggiornamenti l'app contatta l'API pubblica GitHub all'avvio, ogni sei ore e
su richiesta, e scarica il pacchetto solo dopo conferma. GitHub riceve l'indirizzo IP
e l'intestazione del client con la versione; non vengono inviati credenziali Amazon,
nomi dei dispositivi o dati della casa. Download, log e copia dei file precedenti
restano nella sottocartella locale `updates` finché non vengono eliminati.

Password e verifica in due passaggi si inseriscono nel browser sul sito Amazon.
L'app riceve un codice temporaneo dall'indirizzo finale, non lo salva, e lo scambia
per un token. Il token Amazon e le credenziali SwitchBot sono cifrati su disco con
Windows DPAPI per l'utente corrente. I cookie Alexa restano in memoria durante
l'esecuzione. L'eventuale PIN del monitor è conservato come derivato PBKDF2 con salt
casuale e non è incluso nel backup.

Nei dati locali restano nomi e identificativi dei dispositivi, stanze, ultimi stati,
preferiti, elementi nascosti, macro, programmi, automazioni, avvisi, cronologia dei
sensori e impostazioni. Non vengono sincronizzati con server gestiti da Hommaterial.
Il registro locale degli errori contiene data, tipo, messaggio e stack, senza corpi
HTTP completi, cookie o codici di accesso. La demo conserva le modifiche in memoria.

I backup sono file JSON scelti dall'utente. Non contengono la sessione Amazon né il
PIN; possono contenere token e chiave SwitchBot in chiaro se selezioni l'opzione
di esportazione. Conserva e condividi questi file tenendo conto del loro contenuto.

Le notifiche vengono consegnate tramite Windows e possono mostrare nomi e stati dei
dispositivi nelle superfici di notifica del sistema. Si possono disattivare nelle
impostazioni dell'app. Il riconoscimento vocale usa le funzioni di Windows e richiede
microfono e autorizzazioni del sistema; l'app non salva registrazioni audio. Le
impostazioni e le informative di Microsoft si applicano ai servizi forniti da Windows.

**Esci e cancella i dati Amazon locali** rimuove token Amazon, cache, preferenze della
casa, programmi e cronologia; SwitchBot è separato e si rimuove con **Scollega SwitchBot**.
Il registro degli errori può essere eliminato dalla cartella dati. Per revocare anche
l'accesso sui server Amazon rimuovi Hommaterial dai dispositivi registrati nell'account.
Le pagine Amazon e GitHub aperte nel browser applicano le informative dei rispettivi servizi.

Questa pagina descrive l'anteprima Windows. Prima della distribuzione pubblica occorre
pubblicare un'informativa completa con identità e contatto del responsabile del progetto,
verificata rispetto alla build effettivamente distribuita.
