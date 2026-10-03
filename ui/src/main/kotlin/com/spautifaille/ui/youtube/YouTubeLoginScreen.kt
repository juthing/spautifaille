package com.spautifaille.ui.youtube

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spautifaille.domain.youtube.YouTubeCredentials
import com.spautifaille.ui.R
import com.spautifaille.ui.components.ErrorState
import com.spautifaille.ui.components.imeAwareContentWindowInsets
import com.spautifaille.ui.theme.Spacing
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Nombre et cadence des lectures de la configuration de la page (comme Metrolist : 20 × 500 ms). */
private const val CONFIG_ATTEMPTS = 20
private const val CONFIG_RETRY_DELAY_MS = 500L

@Composable
fun YouTubeLoginScreenRoot(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: YouTubeLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnSignedIn by rememberUpdatedState(onSignedIn)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                YouTubeLoginEvent.SignedIn -> currentOnSignedIn()
            }
        }
    }
    YouTubeLoginScreen(
        state = state,
        onBack = onBack,
        onCredentials = viewModel::onCredentials,
        onCaptureFailed = viewModel::onCaptureFailed,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * Connexion Google en plein écran : la page de connexion s'affiche dans une WebView ; dès que la session
 * `music.youtube.com` est établie (cookie `SAPISID`), les identifiants sont capturés et validés.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginScreen(
    state: YouTubeLoginUiState,
    onBack: () -> Unit,
    onCredentials: (YouTubeCredentials) -> Unit,
    onCaptureFailed: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = imeAwareContentWindowInsets(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.yt_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Nouvelle WebView à chaque nouvelle tentative : page de connexion rechargée, capture réarmée.
            key(state.attempt) {
                LoginWebView(
                    onCredentials = onCredentials,
                    onCaptureFailed = onCaptureFailed,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.isChecking) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterVertically),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.yt_login_checking), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            state.errorMessage?.let { message ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    ErrorState(message = message, onRetry = onRetry)
                }
            }
        }
    }
}

/**
 * WebView sur la page de connexion Google. Lorsqu'une page `music.youtube.com` est chargée avec le cookie de
 * session, [LoginSession.CONFIG_SCRIPT] lit `VISITOR_DATA` / `DATASYNC_ID` (jusqu'à 20 essais, la page pouvant
 * ne pas être prête), puis les identifiants sont remis à [onCredentials]. Une seule capture par WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(
    onCredentials: (YouTubeCredentials) -> Unit,
    onCaptureFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentOnCredentials by rememberUpdatedState(onCredentials)
    val currentOnCaptureFailed by rememberUpdatedState(onCaptureFailed)
    var capturing by remember { mutableStateOf(false) }

    fun captureIfSignedIn(view: WebView, url: String?) {
        if (capturing || !LoginSession.isMusicPage(url)) return
        if (!LoginSession.hasSessionCookie(CookieManager.getInstance().getCookie(LoginSession.COOKIE_URL))) return
        capturing = true
        scope.launch {
            var credentials: YouTubeCredentials? = null
            repeat(CONFIG_ATTEMPTS) {
                if (credentials == null) {
                    // Le cookie est relu à chaque essai : il peut s'enrichir pendant le chargement de la page.
                    val cookie = CookieManager.getInstance().getCookie(LoginSession.COOKIE_URL).orEmpty()
                    credentials = LoginSession.credentials(cookie, view.readPageConfig())
                    if (credentials == null) delay(CONFIG_RETRY_DELAY_MS)
                }
            }
            val captured = credentials
            if (captured != null) {
                currentOnCredentials(captured)
            } else {
                capturing = false
                currentOnCaptureFailed()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                val webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Google refuse les WebView reconnaissables : User-Agent proche de celui de Chrome mobile.
                settings.userAgentString = LoginSession.browserUserAgent(settings.userAgentString)
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }
                webViewClient = object : WebViewClient() {
                    // Seuls http(s) sont suivis : aucun schéma `intent:` ou autre n'est lancé depuis la page.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https" && request.url.scheme != "http"

                    override fun onPageFinished(view: WebView, url: String?) {
                        captureIfSignedIn(view, url)
                    }
                }
                loadUrl(LoginSession.LOGIN_URL)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

/** Lit la configuration YouTube de la page (`null` si la page n'est pas prête ou si le script échoue). */
private suspend fun WebView.readPageConfig(): LoginSession.PageConfig? =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(LoginSession.CONFIG_SCRIPT) { raw ->
            if (continuation.isActive) continuation.resume(LoginSession.parseConfig(raw))
        }
    }
