package com.slowva.player.spotify

import android.content.Context
import com.slowva.player.player.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The sign-in flows, kept outside any screen so a browser round trip survives leaving Settings.
 * Two approvals: the desktop client id for the librespot session (playback), then the personal Client ID
 * (search and library). Each call does whichever is still missing and chains into the next.
 */
object SpotifyLogin {
    val busy = MutableStateFlow(false)
    /** set while the in-app sign-in page is on screen */
    val webRequest = MutableStateFlow<SpotifyAuth.Request?>(null)
    /** the last result, shown once by the settings page */
    val message = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The client id still waiting for approval, null when nothing is. */
    private fun pending(): String? = when {
        !Spotify.signedIn.value -> SpotifyAuth.DESKTOP_CLIENT_ID
        Spotify.clientId.value.isNotEmpty() && !Spotify.personalSignedIn.value -> Spotify.clientId.value
        else -> null
    }

    /** [personal] re-approves the personal Client ID even when it is already signed in. */
    fun inBrowser(ctx: Context, personal: Boolean = false) {
        if (busy.value) return
        val app = ctx.applicationContext
        val first = (if (personal) Spotify.clientId.value.ifEmpty { null } else pending()) ?: return
        busy.value = true
        scope.launch {
            // Android freezes a backgrounded app; the loopback socket must keep answering while the browser is in front
            runCatching { PlaybackService.hold(app) }
            try {
                var id: String? = first
                while (id != null) {
                    val request = SpotifyAuth.start(id)
                    val code = SpotifyAuth.codeFromBrowser(app, request).getOrElse { message.value = it.message; break }
                    if (!finish(code, request)) break
                    id = pending()?.takeIf { it != request.clientId }
                }
            } finally {
                PlaybackService.release(app)
                busy.value = false
            }
        }
    }

    fun inApp(personal: Boolean = false) {
        if (busy.value) return
        val id = (if (personal) Spotify.clientId.value.ifEmpty { null } else pending()) ?: return
        webRequest.value = SpotifyAuth.start(id)
    }

    fun onCode(code: String) {
        val request = webRequest.value ?: return
        webRequest.value = null
        busy.value = true
        scope.launch {
            try {
                if (finish(code, request)) pending()?.takeIf { it != request.clientId }?.let { webRequest.value = SpotifyAuth.start(it) }
            } finally { busy.value = false }
        }
    }

    fun onError(text: String) {
        webRequest.value = null
        message.value = text
    }

    fun cancel() { webRequest.value = null }

    private suspend fun finish(code: String, request: SpotifyAuth.Request): Boolean {
        return try {
            val tokens = SpotifyAuth.redeem(code, request)
            if (request.clientId == SpotifyAuth.DESKTOP_CLIENT_ID) {
                Spotify.saveSession(tokens)
                message.value = if (pending() != null) "Playback is signed in. Now approve your own app for search and your library." else "Signed in to Spotify"
            } else {
                Spotify.savePersonal(tokens)
                message.value = "Signed in to Spotify with your Client ID"
            }
            if (pending() == null) SpotifyLibrary.load(force = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotifyNative.note(Spotify.app, "sign-in failed: ${e.message}")
            message.value = "Sign-in failed: ${e.message}"
            false
        }
    }
}
