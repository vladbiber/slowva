package com.slowva.player.spotify

import android.content.Context
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkBlockedException
import com.slowva.player.net.NetworkGate
import com.slowva.player.recommend.SongRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Spotify sign-in state and Web API tokens.
 * Signed in = librespot credentials saved in cacheDir/librespot. Web API tokens come from the personal
 * Client ID when it is signed in, else from that session, whose quota is shared and often spent.
 */
object Spotify {
    lateinit var app: Context
        private set

    val signedIn = MutableStateFlow(false)
    val bitrate = MutableStateFlow(320)
    /** personal Client ID, "" when none */
    val clientId = MutableStateFlow("")
    val personalSignedIn = MutableStateFlow(false)
    /** why the last token attempt failed, for diagnostics */
    val lastError = MutableStateFlow<String?>(null)

    private val mint = Mutex()
    private var repaired = false

    private const val SCOPES = "user-read-private,user-read-email,playlist-read-private,playlist-read-collaborative,user-library-read,user-top-read"
    val BITRATES = listOf(96, 160, 320)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = prefs()
        SpotifyMigration.run(app)
        bitrate.value = p.getInt("bitrate", 320).takeIf { it in BITRATES } ?: 320
        clientId.value = p.getString("client_id", null) ?: com.slowva.player.BuildConfig.SPOTIFY_CLIENT_ID
        personalSignedIn.value = p.getString("api_refresh", null) != null
        signedIn.value = SpotifyNative.hasCredentials(app)
        publish()
        if (!signedIn.value) p.getString("desktop_refresh", null)?.let(::restore)
    }

    /** A 2.x install that lost credentials.json signs the session in again from its desktop refresh token. */
    private fun restore(refresh: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val t = SpotifyAuth.refresh(refresh, SpotifyAuth.DESKTOP_CLIENT_ID)
                saveSession(t)
            }.onFailure { SpotifyNative.note(app, "couldn't restore the session: ${it.message}") }
        }
    }

    fun setBitrate(v: Int) { bitrate.value = v; prefs().edit().putInt("bitrate", v).apply() }

    /** Saves a personal Client ID (32 letters and digits) or clears it with ""; false when it isn't valid. */
    fun setClientId(id: String): Boolean {
        val v = id.trim()
        if (v.isNotEmpty() && !v.matches(Regex("[A-Za-z0-9]{32}"))) return false
        clientId.value = v
        personalSignedIn.value = false
        prefs().edit().putString("client_id", v).remove("api_access").remove("api_expires").remove("api_refresh").apply()
        return true
    }

    /** After a desktop-id sign-in: trades the OAuth token for reusable librespot credentials. */
    suspend fun saveSession(tokens: SpotifyAuth.Tokens) {
        mint.withLock { runHelper(tokens.access) }
        prefs().edit().apply { tokens.refresh?.let { putString("desktop_refresh", it) } }.apply()
        signedIn.value = true
        publish()
    }

    fun savePersonal(tokens: SpotifyAuth.Tokens) {
        prefs().edit()
            .putString("api_access", tokens.access)
            .putLong("api_expires", System.currentTimeMillis() + tokens.expiresIn * 1000)
            .apply { tokens.refresh?.let { putString("api_refresh", it) } }
            .apply()
        personalSignedIn.value = true
    }

    /** Forgets the session and tokens; the personal Client ID stays for the next sign-in. */
    fun signOut() {
        SpotifyNative.credentials(app).delete()
        val keep = clientId.value
        prefs().edit().clear().putString("client_id", keep).putInt("bitrate", bitrate.value).putBoolean(SpotifyMigration.DONE, true).apply()
        personalSignedIn.value = false
        signedIn.value = false
        SpotifyLibrary.clear()
        publish()
    }

    /** A Web API token, the [personal] Client ID's or the session's first, then the other; null when neither works. */
    suspend fun webToken(personal: Boolean = false): String? =
        if (personal) personalToken() ?: sessionToken() else sessionToken() ?: personalToken()

    /** Drops a cached token Spotify refused, so the next call mints a new one. */
    fun forget(token: String) {
        val p = prefs()
        val e = p.edit()
        if (p.getString("session_access", null) == token) e.remove("session_access")
        if (p.getString("api_access", null) == token) e.remove("api_access")
        e.apply()
    }

    /** Where Web API tokens come from right now, for diagnostics. */
    fun tokenSummary(): String {
        val p = prefs()
        val left = (p.getLong("session_expires", 0) - System.currentTimeMillis()) / 60_000
        val session = if (p.getString("session_access", null) != null && left > 0) "session token, ${left} min left" else "no session token yet"
        val personal = when {
            clientId.value.isEmpty() -> "no personal Client ID"
            personalSignedIn.value -> "personal Client ID ${clientId.value.take(6)}… signed in"
            else -> "personal Client ID ${clientId.value.take(6)}… not signed in"
        }
        return "$session · $personal"
    }

    private suspend fun sessionToken(): String? {
        if (!SpotifyNative.hasCredentials(app)) return null
        cached("session_access", "session_expires")?.let { return it }
        return mint.withLock {
            cached("session_access", "session_expires") ?: runCatching { runHelper(null) }.recoverCatching { e ->
                // the saved session can be revoked; the desktop refresh token signs in afresh, once per run
                val refresh = prefs().getString("desktop_refresh", null)
                if (e is NetworkBlockedException || repaired || refresh == null) throw e
                repaired = true
                SpotifyNative.note(app, "session refused, signing in again with the saved refresh token")
                val t = SpotifyAuth.refresh(refresh, SpotifyAuth.DESKTOP_CLIENT_ID)
                t.refresh?.let { prefs().edit().putString("desktop_refresh", it).apply() }
                runHelper(t.access)
            }.onFailure { lastError.value = it.message }.getOrNull()
        }
    }

    private suspend fun personalToken(): String? {
        val id = clientId.value.ifEmpty { return null }
        cached("api_access", "api_expires")?.let { return it }
        val refresh = prefs().getString("api_refresh", null) ?: return null
        return runCatching { SpotifyAuth.refresh(refresh, id) }.onSuccess { savePersonal(it) }
            .onFailure { lastError.value = "Personal Client ID: ${it.message}" }.getOrNull()?.access
    }

    /** Runs libslowvatoken; with [oauth] it signs in with that token and saves credentials first. */
    private suspend fun runHelper(oauth: String?): String = withContext(Dispatchers.IO) {
        NetworkGate.blockReason(Feature.STREAMING)?.let { throw NetworkBlockedException(Feature.STREAMING, it) }
        val env = if (oauth != null) mapOf("SLOWVA_ACCESS_TOKEN" to oauth) else emptyMap()
        SpotifyNative.note(app, if (oauth != null) "saving the Spotify session" else "minting a Web API token")
        val p = SpotifyNative.start(app, SpotifyNative.TOKEN, listOf(SpotifyNative.cacheDir(app).absolutePath, SCOPES), env)
        try {
            val out = p.inputStream.bufferedReader().readText().trim()
            if (!p.waitFor(30, TimeUnit.SECONDS)) throw IOException("Spotify token helper timed out")
            val code = p.exitValue()
            if (code != 0) {
                SpotifyNative.note(app, "token helper exit $code")
                throw IOException(SpotifyNative.tokenError(code))
            }
            val parts = out.split(' ')
            val token = parts.firstOrNull()?.takeIf { it.length > 20 } ?: throw IOException("Spotify token helper printed nothing usable")
            val expires = parts.getOrNull(1)?.toLongOrNull() ?: 3600
            prefs().edit().putString("session_access", token).putLong("session_expires", System.currentTimeMillis() + expires * 1000).apply()
            lastError.value = null
            token
        } finally {
            p.destroy()
        }
    }

    private fun cached(key: String, expiresKey: String): String? {
        val p = prefs()
        return p.getString(key, null)?.takeIf { System.currentTimeMillis() < p.getLong(expiresKey, 0) - 60_000 }
    }

    private fun publish() {
        val provider = SongRadio.provider
        if (signedIn.value) { if (provider.value !is SpotifyProvider) provider.value = SpotifyProvider }
        else if (provider.value is SpotifyProvider) provider.value = null
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val PREFS = "slowva-spotify"
}
