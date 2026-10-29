package com.slowva.player.player.fx

/**
 * The EQ layers in play order: device correction, genre tone, the user's EQ, loudness
 * compensation. Built off the audio thread; the chain compares instances, so an unchanged
 * layer must keep its object. [headroomDb] is the combined peak boost the preamp takes back.
 */
class Layers(
    val device: EqSpec? = null,
    val crossfeed: CrossfeedPreset? = null,
    val genre: EqSpec? = null,
    val user: EqSpec? = null,
    val loudness: EqSpec? = null,
    val headroomDb: Float = 0f,
) {
    companion object {
        val FLAT = Layers()
    }
}

/** Hi-fi settings the audio thread reads, next to the creative ones in [Fx]. */
class HifiFx {
    @Volatile var layers = Layers.FLAT
    @Volatile var tape = 0f              // 0 .. 1
    @Volatile var tube = 0f              // 0 .. 1
    @Volatile var night = false
    @Volatile var nightThresholdDb = -30f
    @Volatile var nightRatio = 3f
    @Volatile var nightMakeupDb = 6f
    @Volatile var gainDb = 0f            // ReplayGain, clipping prevention already applied
    @Volatile var balance = 0f           // -1 left .. 1 right
    @Volatile var mono = false
    @Volatile var swap = false
}

/** The stages around the creative effects: [pre] runs before them, [post] after. */
internal class HifiChain(private val h: HifiFx, sr: Float) {
    private val device = EqStage(sr)
    private val crossfeed = Crossfeed(sr)
    private val genre = EqStage(sr)
    private val user = EqStage(sr)
    private val loud = EqStage(sr)
    private val tape = Tape(sr)
    private val tube = Tube(sr)
    private val night = NightComp(sr)
    private val output = OutputStage()

    val idle: Boolean
        get() {
            val l = h.layers
            return device.idle(l.device) && crossfeed.idle(l.crossfeed) && genre.idle(l.genre) &&
                user.idle(l.user) && loud.idle(l.loudness) && tape.idle(h.tape) && tube.idle(h.tube) &&
                night.idle(h.night) && output.idle(h.gainDb, h.balance, h.mono, h.swap)
        }

    fun reset() {
        device.reset(); crossfeed.reset(); genre.reset(); user.reset(); loud.reset()
        tape.reset(); tube.reset(); night.reset()
    }

    fun pre(buf: FloatArray, frames: Int) {
        val l = h.layers
        device.process(buf, frames, l.device)
        crossfeed.process(buf, frames, l.crossfeed)
        genre.process(buf, frames, l.genre)
        user.process(buf, frames, l.user)
        loud.process(buf, frames, l.loudness)
    }

    fun post(buf: FloatArray, frames: Int) {
        tape.process(buf, frames, h.tape)
        tube.process(buf, frames, h.tube)
        night.process(buf, frames, h.night, h.nightThresholdDb, h.nightRatio, h.nightMakeupDb)
        output.process(buf, frames, h.gainDb, h.balance, h.mono, h.swap)
    }
}
