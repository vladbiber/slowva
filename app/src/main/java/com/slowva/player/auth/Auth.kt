package com.slowva.player.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.slowva.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Authorization Code + PKCE against accounts.spotify.com, the same flow spotc
 * uses on the desktop.
 *
 * There are two ways home from the redirect, because neither is reliable alone:
 *
 *  - [loginInBrowser] does what spotc does — a real browser tab, so Google or
 *    Facebook sign-in and any session already logged in still work — and
 *    catches the redirect on a loopback socket. The catch is that Android
 *    freezes cached processes: with the browser in front, this app stops
 *    accept()ing and the redirect page just hangs. The caller must keep a
 *    foreground service running for the whole flow.
 *  - [readRedirect] serves the in-app WebView, which intercepts the navigation
 *    before any request leaves, so no socket and no process state matter.
 *
 * The resulting access token is used twice: for the Web API, and for
 * `librespot --access-token`, which is how the embedded player signs in.
 */
object Auth {
    /** Spotify's own desktop client id — the only one librespot can sign in with. */
    const val DESKTOP_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    const val REDIRECT_URI = "http://127.0.0.1:5588/login"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    private val SCOPES = listOf(
        "streaming",
        "user-read-email",
        "user-read-private",
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
    ).joinToString(" ")

    private val http = OkHttpClient()

    /** One login attempt: the URL to show, and the verifier to redeem it with. */
    data class Request(val url: String, val verifier: String, val clientId: String)

    /**
     * A personal client id for Web API calls, from developer.spotify.com with
     * `http://127.0.0.1:5588/login` as a redirect URI. Optional: it only
     * matters when session tokens are unavailable, because the desktop id
     * everyone shares is permanently rate limited.
     */
    fun apiClientId(ctx: Context): String? =
        prefs(ctx).getString("api_client_id", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.SPOTIFY_CLIENT_ID.takeIf { it.isNotBlank() }

    /** Personal Web API app when bundled/configured; desktop id only as legacy fallback. */
    fun loginClientId(ctx: Context): String = apiClientId(ctx) ?: DESKTOP_CLIENT_ID

    fun setApiClientId(ctx: Context, id: String?) {
        prefs(ctx).edit()
            .putString("api_client_id", id?.trim())
            .remove("api_access").remove("api_refresh").remove("api_expires_at")
            .apply()
    }

    fun apiSignedIn(ctx: Context) = prefs(ctx).getString("api_refresh", null) != null

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("slowva-auth", Context.MODE_PRIVATE)

    fun isLoggedIn(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getString("refresh", null) != null ||
            (apiClientId(ctx) != null && p.getString("api_refresh", null) != null)
    }

    fun logout(ctx: Context) {
        val keepClientId = apiClientId(ctx)
        prefs(ctx).edit().clear().apply()
        if (keepClientId != null) prefs(ctx).edit().putString("api_client_id", keepClientId).apply()
        SessionToken.credentialsFile(ctx).delete()
    }

    fun startLogin(clientId: String = DESKTOP_CLIENT_ID): Request {
        val verifier = randomUrlSafe(32)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        )
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
            .toString()
        return Request(url, verifier, clientId)
    }

    /** null when [url] is not the redirect; otherwise the code, or an error. */
    fun readRedirect(url: String): Result<String>? {
        if (!url.startsWith(REDIRECT_URI)) return null
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        if (code != null) return Result.success(code)
        val error = uri.getQueryParameter("error") ?: "login refused"
        return Result.failure(IllegalStateException(error))
    }

    /**
     * Opens [request] in a browser tab and waits for the redirect to arrive on
     * the loopback socket. The caller MUST hold a foreground service up, or
     * Android will freeze this process while the browser is in front and the
     * redirect will hang until it times out.
     */
    suspend fun loginInBrowser(
        ctx: Context,
        request: Request,
        timeoutSec: Int = 180,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val server = try {
            ServerSocket(REDIRECT_PORT, 16, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("port $REDIRECT_PORT is busy: ${e.message}")
            )
        }
        server.use {
            withContext(Dispatchers.Main) { openBrowser(ctx, Uri.parse(request.url)) }
            val code = awaitCode(server, timeoutSec)
                ?: return@withContext Result.failure(
                    IllegalStateException("the browser never came back — try signing in inside the app")
                )
            redeem(ctx, code, request)
        }
    }

    private fun openBrowser(ctx: Context, url: Uri) {
        try {
            CustomTabsIntent.Builder().build().also {
                it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(ctx, url)
        } catch (e: Exception) {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
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
                val line = BufferedReader(InputStreamReader(it.getInputStream()))
                    .readLine() ?: return@use
                val code = Regex("[?&]code=([^ &]+)").find(line)?.groupValues?.get(1)
                val page = if (code != null) PAGE_OK else PAGE_FAIL
                it.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${page.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n$page").toByteArray()
                    )
                    flush()
                }
                if (code != null) return code
            }
        }
        return null
    }

    suspend fun redeem(ctx: Context, code: String, request: Request): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", request.clientId)
                .add("code_verifier", request.verifier)
                .build()
            runCatching { tokenRequest(ctx, body, prefixFor(request.clientId)); Unit }
        }

    private fun prefixFor(clientId: String) = if (clientId == DESKTOP_CLIENT_ID) "" else "api_"

    /**
     * Token for Web API calls, in order of preference:
     *
     *  1. the personal client id, when one is configured — its own quota;
     *  2. a token minted through the librespot session ([SessionToken]) — tied
     *     to the account, the way the official client works;
     *  3. the desktop client id from sign-in, which is shared with every other
     *     librespot tool and answers 429 most of the time.
     */
    suspend fun token(ctx: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        apiClientId(ctx)?.let { clientId ->
            cached(ctx, "api_")?.let { return@withContext it }
            val refresh = p.getString("api_refresh", null) ?: return@withContext null
            return@withContext runCatching {
                tokenRequest(ctx, refreshBody(refresh, clientId), "api_")
            }.getOrNull()
        }

        val fresh = cached(ctx, "")
        val alreadySession = p.getBoolean("from_session", false)
        if (fresh != null && (alreadySession || !SessionToken.available(ctx))) return@withContext fresh

        SessionToken.mint(ctx)?.let { (token, expiresIn) ->
            p.edit()
                .putString("access", token)
                .putLong("expires_at", System.currentTimeMillis() + expiresIn * 1000L)
                .putBoolean("from_session", true)
                .apply()
            return@withContext token
        }
        if (fresh != null) return@withContext fresh

        val refresh = p.getString("refresh", null) ?: return@withContext null
        runCatching { tokenRequest(ctx, refreshBody(refresh, DESKTOP_CLIENT_ID), "") }.getOrNull()
    }

    /**
     * Token librespot signs in with. Always the desktop client id: Spotify only
     * accepts that one for a streaming session.
     */
    suspend fun streamingToken(
        ctx: Context,
        forceRefresh: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        if (!forceRefresh && !p.getBoolean("from_session", false)) {
            cached(ctx, "")?.let { return@withContext it }
        }
        val refresh = p.getString("refresh", null) ?: return@withContext null
        runCatching { tokenRequest(ctx, refreshBody(refresh, DESKTOP_CLIENT_ID), "") }.getOrNull()
    }

    /**
     * Drops only the reusable librespot session. The OAuth refresh token is
     * deliberately kept so [streamingToken] can bootstrap a clean session.
     */
    fun clearPlayerCredentials(ctx: Context) {
        SessionToken.credentialsFile(ctx).delete()
        prefs(ctx).edit().putBoolean("from_session", false).apply()
    }

    /** One line for the diagnostics card: which token the Web API is using. */
    fun tokenSummary(ctx: Context): String {
        val p = prefs(ctx)
        val personal = apiClientId(ctx)
        val prefix = if (personal != null) "api_" else ""
        val expires = p.getLong("${prefix}expires_at", 0L)
        val minutes = ((expires - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val source = when {
            personal != null -> "personal client id ${personal.take(6)}…"
            p.getBoolean("from_session", false) -> "librespot session (not rate limited)"
            else -> "shared desktop client id (rate limited)"
        }
        val have = p.getString("${prefix}access", null) != null
        return if (have) "$source, ${minutes}m left" else "$source, no token yet"
    }

    private fun cached(ctx: Context, prefix: String): String? {
        val p = prefs(ctx)
        val access = p.getString("${prefix}access", null) ?: return null
        val expires = p.getLong("${prefix}expires_at", 0L)
        return access.takeIf { System.currentTimeMillis() < expires - 60_000 }
    }

    private fun refreshBody(refresh: String, clientId: String) = FormBody.Builder()
        .add("grant_type", "refresh_token")
        .add("refresh_token", refresh)
        .add("client_id", clientId)
        .build()

    private fun tokenRequest(ctx: Context, body: FormBody, prefix: String): String {
        val req = HttpRequest.Builder().url(TOKEN_URL).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Spotify replied ${resp.code}: $text")
            val json = JSONObject(text)
            val access = json.getString("access_token")
            val editor = prefs(ctx).edit()
            editor.putString("${prefix}access", access)
            editor.putLong(
                "${prefix}expires_at",
                System.currentTimeMillis() + json.optInt("expires_in", 3600) * 1000L
            )
            if (prefix.isEmpty()) editor.putBoolean("from_session", false)
            if (json.has("refresh_token")) {
                editor.putString("${prefix}refresh", json.getString("refresh_token"))
            }
            editor.apply()
            return access
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    private fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private const val REDIRECT_PORT = 5588
    private const val PAGE_OK =
        "<html><body style=\"background:#0B0B0F;color:#ddd;font-family:sans-serif;display:flex;" +
            "align-items:center;justify-content:center;height:100vh\"><div style=\"text-align:center\">" +
            "<h2 style=\"color:#B48EAD\">slowva</h2><p>Signed in. You can close this tab.</p>" +
            "</div></body></html>"
    private const val PAGE_FAIL =
        "<html><body style=\"background:#0B0B0F;color:#ddd;font-family:sans-serif\">" +
            "slowva: sign-in was refused.</body></html>"
}
