package com.slowva.player.player.sound

import kotlinx.coroutines.flow.MutableStateFlow

/** PCM as the decoder hands it to the effects; [processed] is false when it plays untouched. */
data class PcmFormat(val sampleRate: Int, val channels: Int, val encoding: Int, val processed: Boolean)

/** What the audio processor last saw, for the signal path screen. */
object SignalPath {
    val decoderOut = MutableStateFlow<PcmFormat?>(null)
}
