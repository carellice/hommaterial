package app.hommaterial.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hommaterial.R
import app.hommaterial.data.Offline
import app.hommaterial.str

/**
 * Says, over the lists and the calendar, that there is no internet and the copy kept on the phone
 * is shown, and how many changes wait to be sent; it goes once they are.
 */
@Composable
internal fun OfflineBanner() {
    val online by Offline.online.collectAsState()
    val pending by Offline.pending.collectAsState()
    val waiting = when (pending.size) {
        0 -> null
        1 -> str(R.string.offline_pending_one)
        else -> str(R.string.offline_pending_many, pending.size)
    }
    AnimatedVisibility(
        visible = !online || waiting != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = corners(16.dp),
            modifier = Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (online) Icons.Outlined.CloudUpload else Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    when {
                        !online && waiting != null -> str(R.string.offline_banner) + " · " + waiting
                        !online -> str(R.string.offline_banner)
                        else -> str(R.string.offline_sending, waiting.orEmpty())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
