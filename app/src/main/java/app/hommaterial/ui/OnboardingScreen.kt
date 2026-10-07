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
import app.hommaterial.R
import app.hommaterial.str

private val FEATURES = listOf(
    Icons.Outlined.MeetingRoom to R.string.feature_1,
    Icons.Outlined.TouchApp to R.string.feature_2,
    Icons.Outlined.Lightbulb to R.string.feature_3,
    Icons.Outlined.Thermostat to R.string.feature_4,
    Icons.Outlined.Bolt to R.string.feature_5,
)

private val WARNINGS = listOf(
    R.string.warning_1_title to R.string.warning_1_text,
    R.string.warning_2_title to R.string.warning_2_text,
    R.string.warning_3_title to R.string.warning_3_text,
    R.string.warning_4_title to R.string.warning_4_text,
    R.string.warning_5_title to R.string.warning_5_text,
    R.string.warning_6_title to R.string.warning_6_text,
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
                            str(R.string.onboarding_accept),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                    Button(onClick = onAccept, enabled = accepted, modifier = Modifier.fillMaxWidth()) {
                        Text(str(R.string.continue_))
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
                str(R.string.welcome),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                str(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            SectionTitle(str(R.string.onboarding_purpose))
            for ((icon, text) in FEATURES) Feature(icon, str(text))

            SectionTitle(str(R.string.onboarding_before))
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
                            str(R.string.onboarding_unofficial),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    for ((title, text) in WARNINGS) {
                        Text(
                            str(title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(str(text), style = MaterialTheme.typography.bodyMedium)
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
