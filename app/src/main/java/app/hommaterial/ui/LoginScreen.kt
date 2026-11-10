package app.hommaterial.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.LOGIN_DOMAIN
import app.hommaterial.data.MARKETPLACES
import app.hommaterial.data.ServerSync
import app.hommaterial.str

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(state: UiState, vm: HomeViewModel) {
    var linking by rememberSaveable { mutableStateOf(false) }
    val config by ServerSync.store.config.collectAsStateWithLifecycle()
    LaunchedEffect(config) { if (config != null) linking = false }
    if (linking || state.serverBusy && config == null) ServerLinkDialog(state, vm, onDismiss = { linking = false })
    if (state.serverChoice) ServerChoiceDialog(vm)
    Scaffold(
        modifier = Modifier.screenTexture(),
        containerColor = screenColor(),
        topBar = {
            AppTopBar(
                title = { Text(str(R.string.login_title)) },
                actions = {
                    if (!state.signingIn) {
                        // A phone that will use the home server can link it before, or instead of, signing in here.
                        if (!ServerSync.active) {
                            IconButton(onClick = { linking = true }) {
                                Icon(Icons.Outlined.Dns, contentDescription = str(R.string.server_link))
                            }
                        }
                        MarketplacePicker(state, vm)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.loginMessage?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (state.signingIn) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(str(R.string.login_connecting), Modifier.padding(top = 16.dp))
                }
            } else {
                // A failed attempt cannot be reused: bumping the key starts over with a new one.
                var round by remember { mutableIntStateOf(0) }
                key(round, state.loginMessage, state.marketplace) {
                    Box(Modifier.fillMaxSize()) { AmazonSignIn(vm, onRestart = { round++ }) }
                }
            }
        }
    }
}

/** The account's national Amazon site; it decides which Alexa servers the app talks to. */
@Composable
private fun MarketplacePicker(state: UiState, vm: HomeViewModel) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(state.marketplace.domain) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (marketplace in MARKETPLACES) {
            DropdownMenuItem(
                text = { Text(marketplace.domain) },
                onClick = { open = false; vm.setMarketplace(marketplace) },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AmazonSignIn(vm: HomeViewModel, onRestart: () -> Unit) {
    val attempt = remember { vm.newLoginAttempt() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val site = "https://www.$LOGIN_DOMAIN"
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.setAcceptCookie(true)
            for ((name, value) in listOf("frc" to attempt.frc, "map-md" to attempt.mapMd)) {
                cookies.setCookie(site, "$name=$value; Domain=.$LOGIN_DOMAIN; Path=/; Secure")
            }

            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.path != "/ap/maplanding") return false
                        // Amazon redirects here once the sign-in, including any 2-step check, is done.
                        val code = url.getQueryParameter("openid.oa2.authorization_code")
                        if (code.isNullOrEmpty()) {
                            onRestart()
                        } else {
                            vm.completeLogin(attempt, code, cookies.getCookie(site).orEmpty())
                        }
                        return true
                    }
                }
                loadUrl(attempt.url)
            }
        },
        onRelease = { it.destroy() },
    )
}
