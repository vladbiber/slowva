package com.slowva.player.listenbrainz

import android.content.Context
import com.slowva.player.BuildConfig
import com.slowva.player.data.RatingKeys
import com.slowva.player.data.Track
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Opt-in scrobbling to ListenBrainz; listens that can't be sent wait in a small file and go later as an import. */
object ListenBrainz {
    private const val API = "https://api.listenbrainz.org/1/"
    private const val PREFS = "slowva-listenbrainz"
    private const val QUEUE_FILE = "listenbrainz-queue.json"
    private const val MAX_QUEUE = 1000
    private const val BATCH = 100
    private const val MAX_LISTEN_MS = 4 * 60_000L
    private val JSON = "application/json".toMediaType()

    /** [user] is set once the token was validated; [sending] is the user's switch. */
    data class State(val token: String = "", val user: String? = null, val sending: Boolean = false, val queued: Int = 0)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = State(p.getString("token", "").orEmpty(), p.getString("user", null), p.getBoolean("sending", false), readQueue().length())
        if (active()) scope.launch { flush() }
    }

    /** Half the song or four minutes, whichever comes first. */
    fun thresholdMs(durationMs: Long): Long = if (durationMs > 0) minOf(MAX_LISTEN_MS, durationMs / 2) else MAX_LISTEN_MS

    /** Saves [token] and asks ListenBrainz whose it is; the result is the user name or why not. */
    suspend fun validate(token: String): Result<String> = withContext(Dispatchers.IO) {
        val t = token.trim()
        runCatching {
            require(t.isNotEmpty()) { "Paste your token first" }
            val req = NetworkGate.request(API + "validate-token", Feature.LISTENBRAINZ).header("Authorization", "Token $t").build()
            val o = NetworkGate.client.newCall(req).execute().use { r -> runCatching { JSONObject(r.body.string()) }.getOrElse { throw IOException("ListenBrainz answered ${r.code}") } }
            if (!o.optBoolean("valid")) error("ListenBrainz doesn't know this token")
            o.optString("user_name").ifEmpty { "you" }
        }.also { r -> save(_state.value.copy(token = t, user = r.getOrNull(), sending = r.isSuccess && _state.value.sending)) }
    }

    fun setSending(on: Boolean) {
        val s = _state.value
        save(s.copy(sending = on && s.user != null))
        if (active()) scope.launch { flush() }
    }

    /** Forgets the token; listens still waiting are dropped too. */
    fun disconnect() {
        scope.launch { lock.withLock { writeQueue(JSONArray()) } }
        save(State())
    }

    fun nowPlaying(t: Track) {
        if (!active() || NetworkGate.blockReason(Feature.LISTENBRAINZ) != null) return
        scope.launch { runCatching { submit(body("playing_now", listOf(listen(t, null)))) } }
    }

    /** A listen that crossed [thresholdMs]; [startedAt] in ms. */
    fun listened(t: Track, startedAt: Long) {
        if (!active()) return
        val item = listen(t, startedAt / 1000)
        scope.launch {
            val sent = NetworkGate.blockReason(Feature.LISTENBRAINZ) == null && runCatching { submit(body("single", listOf(item))) }.getOrDefault(false)
            if (sent) flush() else lock.withLock { writeQueue(readQueue().apply { put(item) }) }
        }
    }

    /** Sends waiting listens in import batches; stops at the first failure. */
    suspend fun flush() = lock.withLock {
        if (!active() || NetworkGate.blockReason(Feature.LISTENBRAINZ) != null) return@withLock
        var q = readQueue()
        while (q.length() > 0) {
            val chunk = (0 until minOf(BATCH, q.length())).map { q.getJSONObject(it) }
            val ok = runCatching { submit(body("import", chunk)) }.getOrElse { return@withLock }
            if (!ok) return@withLock
            q = JSONArray((chunk.size until q.length()).map { q.get(it) })
            writeQueue(q)
        }
    }

    /** One listen; [listenedAt] in seconds, null for playing_now. */
    fun listen(t: Track, listenedAt: Long?): JSONObject {
        val info = JSONObject()
            .put("submission_client", "slowva")
            .put("submission_client_version", BuildConfig.VERSION_NAME)
        if (t.durationMs > 0) info.put("duration_ms", t.durationMs)
        val meta = JSONObject()
            .put("artist_name", t.artist.removeSuffix(" - Topic").trim())
            .put("track_name", t.title)
        releaseName(t)?.let { meta.put("release_name", it) }
        meta.put("additional_info", info)
        return JSONObject().apply { listenedAt?.let { put("listened_at", it) } }.put("track_metadata", meta)
    }

    fun body(type: String, listens: List<JSONObject>): JSONObject = JSONObject().put("listen_type", type).put("payload", JSONArray(listens))

    /** The album, unless it's a placeholder like "Imported". */
    fun releaseName(t: Track): String? {
        val a = t.album.trim()
        if (a.equals("Imported", true) || RatingKeys.album(t.artist, a) == null) return null
        return a
    }

    private fun active(): Boolean = ::app.isInitialized && _state.value.let { it.sending && it.user != null && it.token.isNotEmpty() }

    /** true when accepted; false for a refusal that retrying won't fix (the listens are dropped), IOException to retry. */
    private fun submit(body: JSONObject): Boolean {
        val req = NetworkGate.request(API + "submit-listens", Feature.LISTENBRAINZ)
            .header("Authorization", "Token ${_state.value.token}")
            .post(body.toString().toRequestBody(JSON)).build()
        NetworkGate.client.newCall(req).execute().use { r ->
            return when {
                r.isSuccessful -> true
                r.code == 401 -> throw IOException("Token refused")
                r.code == 400 -> true
                else -> throw IOException("ListenBrainz answered ${r.code}")
            }
        }
    }

    private fun save(s: State) {
        _state.value = s.copy(queued = _state.value.queued)
        if (!::app.isInitialized) return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", s.token).putString("user", s.user).putBoolean("sending", s.sending).apply()
    }

    private fun queueFile() = File(app.filesDir, QUEUE_FILE)

    private fun readQueue(): JSONArray = runCatching { JSONArray(queueFile().readText()) }.getOrElse { JSONArray() }

    private fun writeQueue(q: JSONArray) {
        val trimmed = if (q.length() > MAX_QUEUE) JSONArray((q.length() - MAX_QUEUE until q.length()).map { q.get(it) }) else q
        runCatching { queueFile().writeText(trimmed.toString()) }
        _state.value = _state.value.copy(queued = trimmed.length())
    }
}
