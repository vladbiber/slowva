package com.slowva.player.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.slowva.player.auth.Auth

/**
 * Spotify's authorize page, hosted in-app.
 *
 * The redirect goes to a loopback URL nobody serves; we catch the navigation
 * here and pull the code straight out of it, so no socket, no browser and no
 * backgrounded process is involved in the handover.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(
    request: Auth.Request,
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "cancel")
                    }
                    Text("Sign in to Spotify", fontSize = 16.sp)
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            CookieManager.getInstance().setAcceptCookie(true)
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        req: WebResourceRequest?,
                                    ): Boolean = handle(req?.url?.toString())

                                    @Deprecated("kept for API < 24 callers")
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        url: String?,
                                    ): Boolean = handle(url)

                                    override fun onPageStarted(
                                        view: WebView?, url: String?, favicon: Bitmap?,
                                    ) {
                                        loading = true
                                        // some flows land on the redirect without
                                        // going through shouldOverrideUrlLoading
                                        if (handle(url)) view?.stopLoading()
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        req: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        val failing = req?.url?.toString()
                                        if (failing != null && handle(failing)) return
                                        if (req?.isForMainFrame == true && !done) {
                                            onError("page failed to load: ${error?.description}")
                                        }
                                    }

                                    private fun handle(url: String?): Boolean {
                                        if (done || url == null) return false
                                        val result = Auth.readRedirect(url) ?: return false
                                        done = true
                                        result
                                            .onSuccess { onCode(it) }
                                            .onFailure { onError(it.message ?: "login failed") }
                                        return true
                                    }
                                }
                                loadUrl(request.url)
                            }
                        }
                    )
                }
            }
        }
    }

    BackHandler { onCancel() }
}
