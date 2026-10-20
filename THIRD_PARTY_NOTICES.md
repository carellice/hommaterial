# Crediti e software di terze parti

## Progetti da cui deriva il funzionamento

Hommaterial non include codice di questi progetti, ma il modo in cui accede ad
Amazon e parla con Alexa è stato ricavato studiandoli. Senza il loro lavoro
questa app non esisterebbe.

| Progetto | Autore | Licenza | Cosa ne deriva |
|---|---|---|---|
| [alexa-cookie](https://github.com/Apollon77/alexa-cookie) (`alexa-cookie2`) | Ingo Fischer (Apollon77) | MIT | Accesso all'account, registrazione del dispositivo, rinnovo della sessione |
| [alexa-remote](https://github.com/Apollon77/alexa-remote) (`alexa-remote2`) | Ingo Fischer (Apollon77) | MIT | Chiamate per elenco dispositivi, stati e comandi |

## Librerie incluse nell'app

| Libreria | Licenza |
|---|---|
| [Kotlin](https://kotlinlang.org) e [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 |
| [AndroidX e Jetpack Compose](https://developer.android.com/jetpack/androidx) (incluse Material 3 e Material Icons) | Apache 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 |

Il testo della licenza Apache 2.0 è disponibile su
<https://www.apache.org/licenses/LICENSE-2.0>.

## Dipendenze della versione Windows

La versione Windows usa .NET 8, Windows Forms per l'area di notifica e WinUI 3 tramite
[Microsoft.WindowsAppSDK 2.5.1](https://www.nuget.org/packages/Microsoft.WindowsAppSDK/2.5.1).
La compilazione e la creazione MSIX usano
[Microsoft.Windows.SDK.BuildTools](https://www.nuget.org/packages/Microsoft.Windows.SDK.BuildTools/10.0.26100.4654).
Il ripristino NuGet risolve anche le rispettive dipendenze transitive.

La build autonoma include runtime e componenti Microsoft: la licenza MIT di Hommaterial
non sostituisce le licenze di tali componenti. I testi e gli avvisi applicabili sono
nei pacchetti NuGet e nei relativi progetti; prima della distribuzione pubblica occorre
verificare gli avvisi da includere per la build effettivamente pubblicata.
