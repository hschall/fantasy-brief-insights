package com.aviato.fantasybrief.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aviato.fantasybrief.data.EspnCookies
import kotlinx.coroutines.delay

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15; SM-S928B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"

private const val START_URL = "https://www.espn.com/fantasy/football/"

@Composable
fun ColumnScope.LoginScreen(onCookiesFound: (String, String) -> Unit) {
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Waiting for you to log in...") }

    // Escape hatch: the system back gesture walks the WebView's history
    // instead of closing the app. Without this a bad page traps you.
    BackHandler(enabled = !manualMode && canGoBack) { webView?.goBack() }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row {
            if (!manualMode) {
                TextButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                    Text("Back")
                }
                TextButton(onClick = { webView?.loadUrl(START_URL) }) { Text("Restart") }
            }
        }
        TextButton(onClick = { manualMode = !manualMode }) {
            Text(if (manualMode) "Use browser" else "Paste manually")
        }
    }
    HorizontalDivider()

    if (manualMode) {
        ManualCookieEntry(onCookiesFound)
        return
    }

    // Start from a clean jar so a stale espn_s2 cannot satisfy the poll.
    LaunchedEffect(Unit) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    // Poll for both cookies. More reliable than page callbacks, because the
    // Disney login sets cookies from JS well after onPageFinished fires.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val found = EspnCookies.parse(
                CookieManager.getInstance().getCookie("https://fantasy.espn.com")
            )
            if (found != null) {
                CookieManager.getInstance().flush()
                status = "Got them."
                onCookiesFound(found.espnS2, found.swid)
                break
            }
        }
    }

    Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(factory = { ctx -> buildWebView(ctx) { canGoBack = it }
            .also { webView = it } })
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(
    ctx: android.content.Context,
    onHistoryChanged: (Boolean) -> Unit
): WebView = WebView(ctx).apply {
    // Lets you inspect this WebView from desktop Chrome at chrome://inspect
    WebView.setWebContentsDebuggingEnabled(true)

    CookieManager.getInstance().setAcceptCookie(true)
    // Login runs on a Disney domain; the cookies land on .espn.com
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.mediaPlaybackRequiresUserGesture = true   // no surprise autoplay
    settings.userAgentString = MOBILE_UA

    // Keep popups in this same WebView rather than opening a window we
    // never created, which is what produced the black overlay.
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = true

    // The missing piece. A WebView with no WebChromeClient hands fullscreen
    // requests a null surface and paints black over the page.
    webChromeClient = WebChromeClient()

    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            onHistoryChanged(view?.canGoBack() == true)
        }
    }

    loadUrl(START_URL)
}

@Composable
private fun ManualCookieEntry(onCookiesFound: (String, String) -> Unit) {
    var s2 by rememberSaveable { mutableStateOf("") }
    var swid by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Fallback. In Chrome on the Mac: log in to ESPN, open DevTools, " +
                "Application, Cookies, https://www.espn.com, copy these two.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = s2,
            onValueChange = { s2 = it.trim() },
            label = { Text("espn_s2  (~350 chars)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )
        OutlinedTextField(
            value = swid,
            onValueChange = { swid = it.trim() },
            label = { Text("SWID  {WITH-BRACES}") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )
        Button(
            onClick = { onCookiesFound(s2, EspnCookies.normalizeSwid(swid)) },
            enabled = s2.length > 50 && swid.length > 10,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }
    }
}
