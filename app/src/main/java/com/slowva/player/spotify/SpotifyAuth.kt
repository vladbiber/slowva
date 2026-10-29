package com.slowva.player.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Authorization Code + PKCE against accounts.spotify.com.
 * The browser flow catches the redirect on a loopback socket, so the caller must keep a foreground
 * service up or Android freezes the app while the browser is in front. The WebView flow reads the
 * redirect from the navigation and needs neither.
 */
object SpotifyAuth {
    /** Spotify's desktop client id, the only one a librespot session accepts. */
    const val DESKTOP_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    const val REDIRECT_URI = "http://127.0.0.1:5588/login"
    private const val REDIRECT_PORT = 5588
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    private val SCOPES = listOf(
        "streaming", "user-read-email", "user-read-private",
        "playlist-read-private", "playlist-read-collaborative", "user-library-read", "user-top-read",
    ).joinToString(" ")

    /** One sign-in attempt: the page to open and the verifier that redeems its code. */
    data class Request(val url: String, val verifier: String, val clientId: String)

    data class Tokens(val access: String, val refresh: String?, val expiresIn: Long)

    fun start(clientId: String): Request {
        val verifier = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build().toString()
        return Request(url, verifier, clientId)
    }

    /** null when [url] isn't the redirect; otherwise the code, or why sign-in was refused. */
    fun readRedirect(url: String): Result<String>? {
        if (!url.startsWith(REDIRECT_URI)) return null
        val uri = Uri.parse(url)
        uri.getQueryParameter("code")?.let { return Result.success(it) }
        return Result.failure(IOException(uri.getQueryParameter("error") ?: "Sign-in was refused"))
    }

    /** Opens [request] in a browser tab and waits for the code on the loopback socket. */
    suspend fun codeFromBrowser(ctx: Context, request: Request, timeoutSec: Int = 180): Result<String> = withContext(Dispatchers.IO) {
        val server = try {
            ServerSocket(REDIRECT_PORT, 16, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            return@withContext Result.failure(IOException("Port $REDIRECT_PORT is busy: ${e.message}"))
        }
        server.use {
            withContext(Dispatchers.Main) { openBrowser(ctx, Uri.parse(request.url)) }
            awaitCode(it, timeoutSec)?.let { code -> Result.success(code) }
                ?: Result.failure(IOException("The browser never came back. Try signing in inside the app."))
        }
    }

    suspend fun redeem(code: String, request: Request): Tokens = post(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", request.clientId)
            .add("code_verifier", request.verifier)
            .build(),
    )

    suspend fun refresh(refreshToken: String, clientId: String): Tokens = post(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build(),
    )

    private suspend fun post(body: FormBody): Tokens = withContext(Dispatchers.IO) {
        val req = NetworkGate.request(TOKEN_URL, Feature.STREAMING).post(body).build()
        NetworkGate.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val why = runCatching { JSONObject(text).optString("error_description") }.getOrNull()?.ifBlank { null }
                throw IOException("Spotify replied ${resp.code}" + (why?.let { ": $it" } ?: ""))
            }
            val json = JSONObject(text)
            Tokens(json.getString("access_token"), json.optString("refresh_token").ifBlank { null }, json.optLong("expires_in", 3600))
        }
    }

    private fun openBrowser(ctx: Context, url: Uri) {
        try {
            CustomTabsIntent.Builder().build().also { it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(ctx, url)
        } catch (e: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun awaitCode(server: ServerSocket, timeoutSec: Int): String? {
        server.soTimeout = 1000
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val client = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                return null
            }
            client.use {
                val line = it.getInputStream().bufferedReader().readLine() ?: return@use
                val code = Regex("[?&]code=([^ &]+)").find(line)?.groupValues?.get(1)
                val page = if (code != null) PAGE_OK else PAGE_FAIL
                val bytes = page.toByteArray()
                it.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
                if (code != null) return code
            }
        }
        return null
    }

    private fun base64Url(data: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private const val PAGE_OK = "<html><body style=\"background:#0B0B0F;color:#ddd;font-family:sans-serif;display:flex;" +
        "align-items:center;justify-content:center;height:100vh\"><div style=\"text-align:center\">" +
        "<h2>Slowva</h2><p>Signed in. You can close this tab.</p></div></body></html>"
    private const val PAGE_FAIL = "<html><body style=\"background:#0B0B0F;color:#ddd;font-family:sans-serif\">Slowva: sign-in was refused.</body></html>"
}
