package app.hommaterial.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val FEATURES = listOf(
    Icons.Outlined.MeetingRoom to "Mostra i dispositivi collegati al tuo account Alexa, divisi per stanza.",
    Icons.Outlined.TouchApp to "Un tocco accende o spegne luci, prese, TV e interruttori.",
    Icons.Outlined.Lightbulb to "Tieni premuto su un dispositivo per regolare la luminosità o nasconderlo.",
    Icons.Outlined.Thermostat to "Legge temperatura e umidità dei sensori.",
    Icons.Outlined.Bolt to "Si apre subito con l'ultimo stato noto e si aggiorna in sottofondo.",
)

private val WARNINGS = listOf(
    "Non è un'app ufficiale" to
        "Hommaterial non è affiliata, approvata né supportata da Amazon. Amazon, Alexa ed Echo sono marchi di " +
        "Amazon.com, Inc. o delle sue affiliate.",
    "Usa API non ufficiali" to
        "L'app comunica con i server di Amazon presentandosi come l'app Alexa ufficiale. Questo con ogni " +
        "probabilità viola le condizioni d'uso di Amazon.",
    "Il tuo account è a rischio" to
        "Amazon può limitare, sospendere o chiudere gli account che usano app non autorizzate, e l'app può " +
        "smettere di funzionare in qualsiasi momento.",
    "Gestisce l'accesso al tuo account" to
        "Accedi sulla pagina di Amazon mostrata dentro l'app. La password non viene letta né salvata, ma sul " +
        "telefono resta un token che dà accesso al tuo account Alexa. Usa solo versioni di cui ti fidi.",
    "Comanda dispositivi reali" to
        "Un errore dell'app o un tocco sbagliato accende o spegne cose vere in casa tua, come stufe e " +
        "climatizzatori.",
    "Nessuna garanzia" to
        "Il software è fornito così com'è. Gli autori non rispondono di danni, blocchi dell'account o altre " +
        "conseguenze del suo uso.",
)

/** Shown once, before the sign-in: what the app is for and what using it implies. */
@Composable
fun OnboardingScreen(onAccept: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(accepted, role = Role.Checkbox, onValueChange = { accepted = it }),
                    ) {
                        Checkbox(checked = accepted, onCheckedChange = null)
                        Text(
                            "Ho letto le avvertenze e uso l'app a mio rischio",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                    Button(onClick = onAccept, enabled = accepted, modifier = Modifier.fillMaxWidth()) {
                        Text("Continua")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                "Benvenuto in Hommaterial",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                "Un modo veloce ed essenziale per comandare la casa collegata ad Alexa.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            SectionTitle("A cosa serve")
            for ((icon, text) in FEATURES) Feature(icon, text)

            SectionTitle("Prima di iniziare")
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(28.dp))
                        Text(
                            "App non ufficiale, uso a proprio rischio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    for ((title, text) in WARNINGS) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
    )
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}
