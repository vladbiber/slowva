package com.slowva.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slowva.player.api.Album
import com.slowva.player.api.Playlist
import com.slowva.player.api.Source
import com.slowva.player.api.Track
import com.slowva.player.auth.Auth
import com.slowva.player.player.PlayerHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext

    val loggedIn = MutableStateFlow(
        Auth.isLoggedIn(ctx) &&
            (Auth.apiClientId(ctx) == null || Auth.apiSignedIn(ctx))
    )
    val busy = MutableStateFlow(false)
    /** non-null while the in-app Spotify login is on screen */
    val authRequest = MutableStateFlow<Auth.Request?>(null)

    val liked = MutableStateFlow<List<Track>>(emptyList())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val albums = MutableStateFlow<List<Album>>(emptyList())

    /** the list currently open in the detail pane */
    val openSource = MutableStateFlow<Source?>(null)
    val openTracks = MutableStateFlow<List<Track>>(emptyList())
    val openError = MutableStateFlow<String?>(null)

    val searchTracks = MutableStateFlow<List<Track>>(emptyList())
    val searchAlbums = MutableStateFlow<List<Album>>(emptyList())
    val searchPlaylists = MutableStateFlow<List<Playlist>>(emptyList())

    init {
        PlayerHub.init(ctx)
        // Spotify Connect pairing is independent of the Web API login. Publish
        // the receiver immediately, just like spotc does when it is opened.
        PlayerHub.startDiscoverable()
        if (loggedIn.value) {
            loadLibrary()
        }
    }

    /** Browser sign-in: keeps Google/Facebook logins and existing sessions working. */
    fun login() = viewModelScope.launch {
        busy.value = true
        PlayerHub.holdForeground()
        Auth.loginInBrowser(ctx, Auth.startLogin(Auth.loginClientId(ctx)))
            .onSuccess { onSignedIn() }
            .onFailure { PlayerHub.say(it.message?.take(160)) }
        PlayerHub.releaseForeground()
        busy.value = false
    }

    /** Fallback when the browser cannot hand the redirect back. */
    fun loginInApp() {
        authRequest.value = Auth.startLogin(Auth.loginClientId(ctx))
    }

    /** Personal client id: its own Web API quota, so no shared 429s. */
    val apiClientId = MutableStateFlow(Auth.apiClientId(ctx).orEmpty())
    val apiSignedIn = MutableStateFlow(Auth.apiSignedIn(ctx))

    fun saveApiClientId(id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isNotBlank() && !trimmed.matches(Regex("[A-Za-z0-9]{32}"))) {
            PlayerHub.say("Client ID must contain exactly 32 letters or numbers")
            return false
        }
        Auth.setApiClientId(ctx, trimmed.ifBlank { null })
        apiClientId.value = trimmed
        apiSignedIn.value = Auth.apiSignedIn(ctx)
        PlayerHub.say(
            if (trimmed.isBlank()) "back to session tokens"
            else "client id saved — sign in with it next"
        )
        return true
    }

    fun setupAndLogin(id: String, inApp: Boolean = false) {
        if (!saveApiClientId(id)) return
        if (inApp) loginInApp() else login()
    }

    fun loginApi(inApp: Boolean) = viewModelScope.launch {
        val clientId = Auth.apiClientId(ctx)
        if (clientId == null) {
            PlayerHub.say("enter a client id first")
            return@launch
        }
        if (inApp) {
            authRequest.value = Auth.startLogin(clientId)
            return@launch
        }
        busy.value = true
        PlayerHub.holdForeground()
        Auth.loginInBrowser(ctx, Auth.startLogin(clientId))
            .onSuccess {
                apiSignedIn.value = true
                PlayerHub.say("signed in with your client id")
                loadLibrary()
            }
            .onFailure { PlayerHub.say(it.message?.take(160)) }
        PlayerHub.releaseForeground()
        busy.value = false
    }

    private fun onSignedIn() = viewModelScope.launch {
        loggedIn.value = true
        busy.value = true
        busy.value = false
        PlayerHub.startDiscoverable()
        loadLibrary()
        PlayerHub.say("Signed in — slowva is now visible in Spotify Devices")
    }

    fun cancelLogin() {
        authRequest.value = null
    }

    fun onLoginCode(code: String) = viewModelScope.launch {
        val request = authRequest.value ?: return@launch
        authRequest.value = null
        busy.value = true
        Auth.redeem(ctx, code, request)
            .onSuccess {
                if (request.clientId == Auth.DESKTOP_CLIENT_ID) {
                    onSignedIn()
                } else {
                    apiSignedIn.value = true
                    onSignedIn()
                }
            }
            .onFailure { PlayerHub.say("sign-in failed: ${it.message?.take(140)}") }
        busy.value = false
    }

    fun onLoginError(message: String) {
        authRequest.value = null
        PlayerHub.say(message)
    }

    fun logout() {
        Auth.logout(ctx)
        PlayerHub.disconnectLocal()
        loggedIn.value = false
        liked.value = emptyList()
        playlists.value = emptyList()
        albums.value = emptyList()
    }

    /**
     * Two requests, not sixteen: liked songs are fetched when that row is
     * opened. The Web API rate limit is shared with every other librespot
     * client on the planet, so every avoidable call is worth avoiding.
     */
    fun loadLibrary() = viewModelScope.launch {
        busy.value = true
        runCatching {
            playlists.value = PlayerHub.api.playlists()
            albums.value = PlayerHub.api.albums()
        }.onFailure { PlayerHub.say("library: ${it.message?.take(120)}") }
        busy.value = false
    }

    fun open(source: Source) = viewModelScope.launch {
        openSource.value = source
        openTracks.value = emptyList()
        openError.value = null
        busy.value = true
        runCatching {
            openTracks.value = when (source) {
                is Source.Liked -> liked.value.ifEmpty {
                    PlayerHub.api.likedTracks().also { liked.value = it }
                }
                is Source.OfPlaylist -> PlayerHub.api.playlistTracks(source.playlist.id)
                is Source.OfAlbum -> PlayerHub.api.albumTracks(source.album)
                is Source.SearchResults -> searchTracks.value
            }
        }.onFailure {
            val detail = it.message?.take(160) ?: "unknown Spotify error"
            openError.value = detail
            PlayerHub.say("could not load: $detail")
        }
        busy.value = false
    }

    fun closeSource() {
        openSource.value = null
        openTracks.value = emptyList()
        openError.value = null
    }

    fun search(query: String) = viewModelScope.launch {
        if (query.isBlank()) return@launch
        busy.value = true
        runCatching { PlayerHub.api.search(query) }
            .onSuccess { (t, a, p) ->
                searchTracks.value = t
                searchAlbums.value = a
                searchPlaylists.value = p
            }
            .onFailure { PlayerHub.say("search: ${it.message?.take(120)}") }
        busy.value = false
    }

    /** Plays a list, using the playlist/album context when we have one. */
    fun play(tracks: List<Track>, index: Int) {
        val contextUri = when (val s = openSource.value) {
            is Source.OfPlaylist -> s.playlist.uri
            is Source.OfAlbum -> s.album.uri
            else -> null
        }
        PlayerHub.playTracks(tracks, index, contextUri)
    }
}
