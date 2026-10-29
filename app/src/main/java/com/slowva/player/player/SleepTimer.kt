package com.slowva.player.player

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Pauses songs or radio after a while or at the end of the track, fading out first. Lives outside the UI. */
object SleepTimer {
    const val END_OF_TRACK = -1L
    private const val FADE_MS = 10_000L
    private const val FADE_STEPS = 40

    /** wall-clock time it fires, [END_OF_TRACK], or null when off */
    val endsAt = MutableStateFlow<Long?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun start(minutes: Int) = set(System.currentTimeMillis() + minutes * 60_000L)
    fun endOfTrack() = set(END_OF_TRACK)
    fun cancel() { job?.cancel(); job = null; endsAt.value = null }

    private fun set(at: Long) {
        job?.cancel()
        endsAt.value = at
        job = scope.launch {
            if (at == END_OF_TRACK) {
                val id = PlayerHub.playback.value?.track?.id
                val s = PlayerHub.playback.first { it == null || it.track?.id != id || fadeDue(it.progressMs.toLong(), it.durationMs, PlayerHub.rate.value) }
                // the next song already started: stop it now rather than fade over it
                fadeAndPause(fade = s?.track?.id == id)
            } else {
                delay(at - System.currentTimeMillis() - FADE_MS)
                fadeAndPause(fade = true)
            }
            endsAt.value = null
        }
    }

    private suspend fun fadeAndPause(fade: Boolean) = withPlayer { c ->
        val start = c.volume
        try {
            if (fade) for (i in 1..FADE_STEPS) {
                c.volume = start * fadeLevel(i.toFloat() / FADE_STEPS)
                delay(FADE_MS / FADE_STEPS)
            }
            c.pause()
        } finally {
            c.volume = start
        }
    }

    /** The app's session handle, or a short-lived one when the app was swiped away but the service still plays. */
    private suspend fun withPlayer(block: suspend (MediaController) -> Unit) {
        PlayerHub.remote?.let { return block(it) }
        val app = PlayerHub.app
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        val c = runCatching { withContext(Dispatchers.IO) { future.get(5, TimeUnit.SECONDS) } }.getOrNull() ?: return
        try { block(c) } finally { c.release() }
    }

    /** Volume factor at fade progress [t] 0..1; squared so the end sounds as gradual as the start. */
    internal fun fadeLevel(t: Float) = (1f - t.coerceIn(0f, 1f)).let { it * it }

    /** True once what is left of the track, at the current speed, fits in the fade. */
    internal fun fadeDue(positionMs: Long, durationMs: Long, rate: Float) =
        durationMs > 0 && (durationMs - positionMs) / rate.coerceAtLeast(0.05f) <= FADE_MS

    /** Whole minutes left, rounded up so "1 minute" shows until it fires. */
    fun minutesLeft(endsAt: Long, now: Long) = ((endsAt - now).coerceAtLeast(0) + 59_999) / 60_000
}
